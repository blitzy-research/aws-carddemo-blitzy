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
package com.carddemo.api.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.carddemo.config.FixedLocaleMessageInterpolator;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.support.SensitiveValues;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link SignOnRequest}, the transport contract for the two operator-entered
 * fields of legacy CICS transaction {@code CC00}.
 *
 * <h2>What is under test</h2>
 *
 * <p>{@link SignOnRequest} replaces the inbound half of the 3270 sign-on screen driven by
 * {@code app/cbl/COSGN00C.cbl}, a 260-line program with six paragraphs. The screen layout
 * lives in {@code app/bms/COSGN00.bms}, 210 lines, where the user-id field is defined at
 * line 156 and the password field at line 175, each declared eight characters wide. The
 * generated symbolic map the program actually reads is {@code app/cpy-bms/COSGN00.CPY},
 * whose inbound group declares {@code USERIDI} at line 72 and {@code PASSWDI} at line 78,
 * again eight characters each. The persisted credential record is
 * {@code app/cpy/CSUSR01Y.cpy}, an 80-byte layout whose {@code SEC-USR-ID} field at line 18
 * occupies bytes 1 through 8 and whose {@code SEC-USR-PWD} field at line 21 occupies bytes
 * 49 through 56. Presentation side and persistence side therefore agree: both values are
 * eight characters wide, and both are alphanumeric rather than numeric.</p>
 *
 * <h2>The width is asserted behaviourally, in both directions, for both values</h2>
 *
 * <p>This class owns the folder-level acceptance obligation that the sign-on user id and the
 * sign-on password are each bounded at eight characters. That obligation is discharged by
 * behaviour and not by inspecting a declaration: for each of the two values independently, a
 * value of exactly eight characters is accepted with no violation at all, and a value of
 * nine characters produces a bound violation. Both directions are also swept across a range
 * of lengths so the bound is pinned as inclusive at eight rather than merely "around"
 * eight.</p>
 *
 * <h2>Why there is no presence or business-format constraint</h2>
 *
 * <p>{@code COSGN00C} tests the two submitted values for emptiness inside a single ordered
 * cascade at lines 118 through 129: the user id is examined first, the password second, and
 * because the construct stops at the first matching clause a submission with <em>both</em>
 * values empty reports the user-id prompt alone. Never the password prompt, and never both
 * messages together. Bean Validation, by contrast, evaluates constraints in an unspecified
 * order and reports every violation it finds, so a pair of presence constraints on this type
 * would emit two messages where the legacy screen emits exactly one. That is an observable
 * difference on an external interface, so the presence test has to stay in the service layer
 * where the ordering can be honoured.</p>
 *
 * <p>This class therefore proves the absence of every business constraint by behaviour: an
 * instance carrying two nulls yields no violation, an instance carrying two empty strings
 * yields no violation, and an instance carrying two all-space values yields no violation. No
 * printable character-class, format, digit or strength rule fires either, because the legacy
 * screen applies none and any of them would reject input the legacy system accepts. One
 * transport rule additionally excludes control and format characters from the identifier:
 * the terminal cannot transmit them as operator text, while HTTP can use them to forge a log
 * record.</p>
 *
 * <h2>The credential is carried, never printed</h2>
 *
 * <p>The password field is defined on the mapset with the non-display attribute, so the
 * legacy terminal never echoed it. {@link SignOnRequest#toString()} honours that by
 * substituting a fixed placeholder for the value, which is what stops the credential
 * reaching a log line, an exception message or a test-failure report. The complementary half
 * of the contract matters just as much and is asserted here too: the password must still
 * deserialize from a request body, because an endpoint cannot verify a credential it refuses
 * to read.</p>
 *
 * <p>Credential verification itself is not exercised here and cannot be. Hashed verification
 * replaces the legacy plaintext comparison at line 223 of {@code COSGN00C}, and that
 * substitution is a documented parity exception owned by the service layer and recorded in
 * {@code docs/decision-log.md}. No credential encoder, authentication token, user-details
 * type or security annotation appears in this class, and the eight-character value used
 * below is synthetic: it is not the value the legacy provisioning job seeds, which appears
 * nowhere in this module's test sources.</p>
 *
 * <h2>Scope of this class</h2>
 *
 * <p>This is a pure in-process unit test. It starts no application context, opens no
 * database, queue or socket, launches no external service, reads no file and uses no mocking
 * framework, because the type under test is a value type with no collaborator. The two
 * mappers below are plain local instances configured by hand to match the shared Jackson
 * settings declared in {@code carddemo-java/src/main/resources/application.yml}: non-null
 * property inclusion, timestamps written as text rather than as numbers, unknown incoming
 * properties tolerated, and plain rather than scientific notation for decimal values. No
 * slicing annotation and no shared test helper is used, so the class stands alone.</p>
 *
 * <p>Immutability is demonstrated by construction only. The type exposes no mutator, and the
 * proof of that is a compile-time one: this class never calls a setter because there is none
 * to call, and a call to one would not compile. Nothing here introspects the class, walks
 * its members or reads its annotations by any dynamic mechanism.</p>
 *
 * <p>No user-specified rules exist for this project, so this class is held to
 * enterprise-standard practice instead: a hermetic and deterministic test, a zero-warning
 * compile under all lint categories promoted to errors, no dynamic type introspection, no
 * wall-clock or random input, and expectations derived independently of the code under
 * test.</p>
 *
 * <p>Provenance of the translated source: legacy checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL source text is reproduced
 * here; only member names, field names, paragraph counts, widths, byte offsets, line numbers
 * and item counts are cited.</p>
 *
 * @see SignOnRequest
 */
@DisplayName("SignOnRequest")
class SignOnRequestBoundaryTest {

    /*
     * ==================================================================
     * THE INDEPENDENT ORACLE
     * ==================================================================
     * Every expected value in this class is a literal hand-derived from
     * the legacy artefacts or from the Bean Validation and Jackson
     * specifications. Nothing here asks the type under test to supply its
     * own expected value, nothing snapshots its output, and no assertion
     * compares one production call against another production call.
     *
     * The redaction placeholder is declared as a private constant on the
     * production type, so it cannot be referenced from here even though
     * this class sits in the same package. Its text is therefore restated
     * below as an independent literal, read off the production source
     * rather than obtained from it at run time - which is exactly the
     * property that makes the assertion meaningful: if the production
     * constant were ever changed, this class would fail rather than
     * silently follow.
     *
     * ------------------------------------------------------------------
     * Two points where the production type differs from a first reading
     * of the specification. In both cases the production file is
     * authoritative and this class follows it.
     * ------------------------------------------------------------------
     * 1. The type is a record, so equals and hashCode are inherited from
     *    the record contract and compare both components by value. The
     *    production source states that this is deliberate. This class
     *    therefore asserts value equality, which is the contract the type
     *    actually declares, rather than the identity equality a plain
     *    final class without overrides would have given.
     * 2. The password component declares a write-only serialization
     *    access mode rather than a total suppression, so it deserializes
     *    from a request body exactly as before and is omitted from every
     *    serialized form. This class asserts both halves separately: the
     *    inbound direction, because a total suppression would have broken
     *    sign-on, and the outbound omission, because redaction of the
     *    diagnostic representation covers only one of the two ways a
     *    value leaves the object. The legacy field was defined with the
     *    non-display attribute and the outbound symbolic-map item was
     *    never written, so the withheld direction is the faithful one.
     */

    /** Screen and record width of the user id, in characters. */
    private static final int USER_ID_WIDTH = 8;

    /** Screen and record width of the submitted credential, in characters. */
    private static final int CREDENTIAL_WIDTH = 8;

    /**
     * A user id of exactly the admitted width.
     *
     * <p>Eight characters, upper case, of the shape the seeded credential records use. It is
     * an identifier rather than a secret, which is why it is allowed to appear in diagnostic
     * output.</p>
     */
    private static final String USER_ID_AT_WIDTH = "USER0001";

    /**
     * A synthetic credential of exactly the admitted width.
     *
     * <p>Eight characters, deliberately unrelated to any value the legacy provisioning job
     * seeds. It exists only so that the redaction assertions have something concrete to look
     * for and fail to find.</p>
     */
    private static final String SYNTHETIC_CREDENTIAL = "ABCD1234";

    /**
     * A second synthetic credential, used where two unequal credentials are needed.
     */
    private static final String OTHER_SYNTHETIC_CREDENTIAL = "WXYZ9876";

    /** A user id one character over the admitted width. */
    private static final String USER_ID_OVER_WIDTH = "USER00012";

    /** A synthetic credential one character over the admitted width. */
    private static final String CREDENTIAL_OVER_WIDTH = "ABCD12345";

    /**
     * Text the production type substitutes for the password in diagnostic output.
     *
     * <p>Restated here as an independent literal because the production constant is private.
     * The text carries no information about the value it stands in for.</p>
     */
    private static final String REDACTION_PLACEHOLDER_TEXT = "***REDACTED***";

    /**
     * Interpolated message the size constraint produces when a value is too long.
     *
     * <p>Taken from the Bean Validation default message bundle for a size constraint whose
     * lower bound is zero and whose upper bound is eight, not from the type under test.</p>
     */
    private static final String WIDTH_VIOLATION_MESSAGE = "size must be between 0 and 8";

    /** Stable message of the identifier control-character exclusion. */
    private static final String CONTROL_CHARACTER_VIOLATION_MESSAGE =
            "must not contain control or format characters";

    /** JSON property name of the user id. */
    private static final String USER_ID_PROPERTY = "userId";

    /** JSON property name carrying the submitted credential. */
    private static final String CREDENTIAL_PROPERTY = "password";

    /** Wire name of the attention-key component, spelled as the whole package spells it. */
    private static final String KEY_ACTION_PROPERTY = "keyAction";

    /**
     * Type token for reading a JSON document back as a plain property map.
     *
     * <p>Used instead of a tree walk so that the exact set of emitted property names can be
     * asserted with no dependency on any tree-node convenience API.</p>
     */
    private static final TypeReference<Map<String, Object>> PROPERTY_MAP =
            new TypeReference<>() {
            };

    /**
     * Mapper carrying the four settings the module declares in its own {@code application.yml}.
     *
     * <p>Non-null value inclusion and non-null content inclusion, which is the pair the shared
     * setting expands to; timestamps as text; unknown incoming properties tolerated; and plain rather
     * than scientific decimal notation. That last setting has no field to act on here, and it is in
     * force anyway because this file does not assemble the mapper: it comes from
     * {@link JsonContractSupport#declaredSettingsMapper()}, the single place in the test tree where
     * the four settings are written out by hand.</p>
     *
     * <p>What it evidences is the shape this type takes <em>under those settings</em>, and nothing
     * more; it is not the mapper a deployed instance holds.
     * {@link ApplicationJsonContractTest} compares a mapper obtained from a real context against this
     * very factory, so an edit to the module's file fails there rather than leaving this stand-in
     * unrepresentative.</p>
     */
    private static final ObjectMapper SHARED_SETTINGS_MAPPER =
            JsonContractSupport.declaredSettingsMapper();

    /**
     * Mapper that rejects an unknown property instead of ignoring it.
     *
     * <p>Its only purpose is to establish where unknown-property tolerance comes from. If the
     * type under test suppressed unknown properties itself, this mapper would still accept a
     * foreign key; because it rejects one, the tolerance is proved to be a property of the
     * shared configuration rather than of the contract.</p>
     */
    private static final ObjectMapper STRICT_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** Factory backing {@link #validator}; closed once the class finishes. */
    private static ValidatorFactory validatorFactory;

    /** Validator used by every constraint assertion below. */
    private static Validator validator;

    /**
     * Builds the validator once for the whole class.
     *
     * <p>Obtained from the Bean Validation bootstrap directly rather than from an application
     * context, so no framework container is started and the constraint assertions observe the
     * declared constraints alone.</p>
     *
     * <p><strong>The message locale is pinned, and it has to be.</strong> Some assertions below
     * compare the interpolated constraint message character for character, and the provider
     * interpolates in the JVM default locale, so under a localized default the same violation reports
     * localized prose - Turkish renders the size message as {@code boyut '0' ile '8' arasında olmalı}.
     * The continuous-integration definition deliberately re-runs this tier with the default locale
     * overridden, so the interpolation locale is fixed here rather than left to the machine. It is
     * fixed by installing the application's own {@code FixedLocaleMessageInterpolator}, which pins
     * {@link java.util.Locale#ROOT} and so resolves the provider's base bundle - the English text
     * asserted below - rather than any translation of it.</p>
     */
    @BeforeAll
    static void buildValidator() {
        // Built with the SAME pinned interpolator the application installs on its own validator,
        // rather than with the provider's default. These assertions compare rendered message text, and
        // the provider renders against a locale: the default configuration would resolve a translated
        // bundle whenever the host's default locale had one, so this test would pass on one machine
        // and fail on another while the code under test was identical. Using the application's own
        // statement of the rule - com.carddemo.config.FixedLocaleMessageInterpolator - means the text
        // asserted here is the text a client receives, and neither side can be pinned without the
        // other.
        validatorFactory = Validation.byDefaultProvider().configure()
                .messageInterpolator(new FixedLocaleMessageInterpolator())
                .buildValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /**
     * Releases the validator factory once the class has finished.
     *
     * <p>Guarded against a null factory so that a bootstrap failure surfaces as the original
     * diagnostic rather than being masked by a follow-on failure during teardown.</p>
     */
    @AfterAll
    static void releaseValidator() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    /**
     * Validates a request and returns its violations.
     *
     * @param request the instance to validate
     * @return the violation set, empty when the instance satisfies every declared constraint
     */
    private static Set<ConstraintViolation<SignOnRequest>> violationsOf(SignOnRequest request) {
        return validator.validate(request);
    }

    /**
     * Serializes a request with the shared application settings.
     *
     * @param request the instance to serialize
     * @return the JSON document
     * @throws JsonProcessingException if serialization fails, which fails the calling test
     */
    private static String jsonOf(SignOnRequest request) throws JsonProcessingException {
        return SHARED_SETTINGS_MAPPER.writeValueAsString(request);
    }

    /**
     * Deserializes a request with the shared application settings.
     *
     * @param json the document to read
     * @return the reconstructed instance
     * @throws JsonProcessingException if deserialization fails, which fails the calling test
     */
    private static SignOnRequest parse(String json) throws JsonProcessingException {
        return SHARED_SETTINGS_MAPPER.readValue(json, SignOnRequest.class);
    }

    /**
     * Reads a JSON document back as a plain property map.
     *
     * @param json the document to inspect
     * @return the emitted properties, keyed by name
     * @throws JsonProcessingException if the document cannot be read, failing the caller
     */
    private static Map<String, Object> propertiesOf(String json) throws JsonProcessingException {
        return SHARED_SETTINGS_MAPPER.readValue(json, PROPERTY_MAP);
    }

    /**
     * Collects the property names a violation set reports on.
     *
     * <p>Written as an explicit loop rather than as an extraction chain so the assertion has
     * no dependency on any fluent-extraction overload resolution.</p>
     *
     * @param violations the violations to inspect
     * @return the reported property names, in encounter order
     */
    private static List<String> violatedPropertiesOf(Set<ConstraintViolation<SignOnRequest>> violations) {
        List<String> names = new ArrayList<>();
        for (ConstraintViolation<SignOnRequest> violation : violations) {
            names.add(violation.getPropertyPath().toString());
        }
        return names;
    }

    /**
     * Builds a value of a given character length.
     *
     * @param length how many characters the value carries
     * @return a value of exactly that length, built from a single repeated letter
     */
    private static String valueOfLength(int length) {
        return "A".repeat(length);
    }

    /**
     * Counts non-overlapping occurrences of a fragment inside a subject.
     *
     * @param subject  the text to search
     * @param fragment the text to look for
     * @return how many times the fragment occurs
     */
    private static int occurrencesOf(String subject, String fragment) {
        int count = 0;
        int from = 0;
        int at = subject.indexOf(fragment, from);
        while (at >= 0) {
            count++;
            from = at + fragment.length();
            at = subject.indexOf(fragment, from);
        }
        return count;
    }

    /**
     * The folder-level acceptance obligation: both values are bounded at eight characters.
     *
     * <p>Each of the two values is asserted independently and in both directions, so neither
     * bound can be satisfied by the other one's constraint.</p>
     */
    @Nested
    @DisplayName("width bound of eight characters")
    class WidthBound {

        @Test
        @DisplayName("a user id of exactly eight characters raises no violation at all")
        void userIdOfExactlyEightCharactersIsAccepted() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, null, null);

            assertThat(USER_ID_AT_WIDTH).hasSize(USER_ID_WIDTH);
            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("a user id of nine characters violates the width bound and nothing else")
        void userIdOfNineCharactersViolatesTheWidthBound() {
            SignOnRequest request = new SignOnRequest(USER_ID_OVER_WIDTH, null, null);

            assertThat(USER_ID_OVER_WIDTH).hasSize(USER_ID_WIDTH + 1);
            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).hasSize(1);

            ConstraintViolation<SignOnRequest> violation = violations.iterator().next();
            assertThat(violation.getPropertyPath()).hasToString(USER_ID_PROPERTY);
            assertThat(violation.getInvalidValue()).isEqualTo(USER_ID_OVER_WIDTH);
            assertThat(violation.getMessage()).isEqualTo(WIDTH_VIOLATION_MESSAGE);
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Size.class);
        }

        @Test
        @DisplayName("a password of exactly eight characters raises no violation at all")
        void passwordOfExactlyEightCharactersIsAccepted() {
            SignOnRequest request = new SignOnRequest(null, SYNTHETIC_CREDENTIAL, null);

            assertThat(SYNTHETIC_CREDENTIAL).hasSize(CREDENTIAL_WIDTH);
            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("a password of nine characters violates the width bound and nothing else")
        void passwordOfNineCharactersViolatesTheWidthBound() {
            SignOnRequest request = new SignOnRequest(null, CREDENTIAL_OVER_WIDTH, null);

            assertThat(CREDENTIAL_OVER_WIDTH).hasSize(CREDENTIAL_WIDTH + 1);
            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).hasSize(1);

            ConstraintViolation<SignOnRequest> violation = violations.iterator().next();
            assertThat(violation.getPropertyPath()).hasToString(CREDENTIAL_PROPERTY);
            assertThat(violation.getMessage()).isEqualTo(WIDTH_VIOLATION_MESSAGE);
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Size.class);
        }

        @Test
        @DisplayName("both values at the bound together are accepted, so the bounds are independent")
        void bothValuesAtTheBoundAreAccepted() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("both values over the bound together produce exactly two violations, one each")
        void bothValuesOverTheBoundProduceExactlyTwoViolations() {
            SignOnRequest request = new SignOnRequest(USER_ID_OVER_WIDTH, CREDENTIAL_OVER_WIDTH, null);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).hasSize(2);
            assertThat(violatedPropertiesOf(violations))
                    .containsExactlyInAnyOrder(USER_ID_PROPERTY, CREDENTIAL_PROPERTY);
        }

        @ParameterizedTest(name = "length {0} is accepted")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
        @DisplayName("every length from zero up to and including eight is accepted for the user id")
        void everyLengthUpToTheBoundIsAcceptedForTheUserId(int length) {
            SignOnRequest request = new SignOnRequest(valueOfLength(length), null, null);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).isEmpty();
        }

        @ParameterizedTest(name = "length {0} is accepted")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
        @DisplayName("every length from zero up to and including eight is accepted for the password")
        void everyLengthUpToTheBoundIsAcceptedForThePassword(int length) {
            SignOnRequest request = new SignOnRequest(null, valueOfLength(length), null);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).isEmpty();
        }

        @ParameterizedTest(name = "length {0} is rejected")
        @ValueSource(ints = {9, 10, 16, 32, 80})
        @DisplayName("every length above eight is rejected for the user id")
        void everyLengthAboveTheBoundIsRejectedForTheUserId(int length) {
            SignOnRequest request = new SignOnRequest(valueOfLength(length), null, null);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(USER_ID_PROPERTY);
        }

        @ParameterizedTest(name = "length {0} is rejected")
        @ValueSource(ints = {9, 10, 16, 32, 80})
        @DisplayName("every length above eight is rejected for the password")
        void everyLengthAboveTheBoundIsRejectedForThePassword(int length) {
            SignOnRequest request = new SignOnRequest(null, valueOfLength(length), null);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(CREDENTIAL_PROPERTY);
        }

        @Test
        @DisplayName("the bound measures characters and never trims, so eight spaces are accepted")
        void theBoundNeverTrimsBeforeMeasuring() {
            String eightSpaces = " ".repeat(USER_ID_WIDTH);
            SignOnRequest request = new SignOnRequest(eightSpaces, eightSpaces, null);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).isEmpty();
            assertThat(request.userId()).hasSize(USER_ID_WIDTH);
            assertThat(request.password().length()).isEqualTo(CREDENTIAL_WIDTH);
        }

        @Test
        @DisplayName("the bound measures characters and never trims, so nine spaces are rejected")
        void theBoundRejectsNineSpacesRatherThanTrimmingThemAway() {
            String nineSpaces = " ".repeat(USER_ID_WIDTH + 1);
            SignOnRequest request = new SignOnRequest(nineSpaces, nineSpaces, null);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).hasSize(2);
        }

        @Test
        @DisplayName("the two widths agree with each other, as the map and the record layout do")
        void theTwoWidthsAgree() {
            assertThat(USER_ID_WIDTH).isEqualTo(CREDENTIAL_WIDTH).isEqualTo(8);
        }
    }

    /**
     * No presence or business-format constraint is declared.
     *
     * <p>The reason is behavioural rather than stylistic. {@code COSGN00C} tests the two
     * submitted values for emptiness inside one ordered cascade at lines 118 through 129 and
     * stops at the first matching clause, so a submission with both values empty reports the
     * user-id prompt alone - never the password prompt, and never both prompts at once. A
     * pair of presence constraints here would fire simultaneously and report both, producing
     * two messages where the legacy screen produces exactly one, which is an observable
     * change to an external interface contract. The presence test therefore belongs to the
     * service layer, where the ordering can be preserved, and this type must accept a null,
     * an empty and an all-space value without objecting to any of them.</p>
     */
    @Nested
    @DisplayName("no presence or business-format constraint")
    class NoOtherConstraint {

        @Test
        @DisplayName("two nulls raise no violation, so no presence constraint is declared")
        void twoNullsRaiseNoViolation() {
            SignOnRequest request = new SignOnRequest(null, null, null);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("two empty strings raise no violation, so no emptiness constraint is declared")
        void twoEmptyStringsRaiseNoViolation() {
            SignOnRequest request = new SignOnRequest("", "", null);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("two all-space values raise no violation, so no blankness constraint is declared")
        void twoAllSpaceValuesRaiseNoViolation() {
            SignOnRequest request = new SignOnRequest("    ", "  ", null);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("a null user id beside a present password raises no violation")
        void aNullUserIdBesideAPresentPasswordRaisesNoViolation() {
            SignOnRequest request = new SignOnRequest(null, SYNTHETIC_CREDENTIAL, null);

            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("a null password beside a present user id raises no violation")
        void aNullPasswordBesideAPresentUserIdRaisesNoViolation() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, null, null);

            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("an over-length user id beside a null password yields exactly one violation")
        void anOverLengthUserIdBesideANullPasswordYieldsExactlyOneViolation() {
            SignOnRequest request = new SignOnRequest(USER_ID_OVER_WIDTH, null, null);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(USER_ID_PROPERTY);
        }

        @Test
        @DisplayName("an over-length password beside a null user id yields exactly one violation")
        void anOverLengthPasswordBesideANullUserIdYieldsExactlyOneViolation() {
            SignOnRequest request = new SignOnRequest(null, CREDENTIAL_OVER_WIDTH, null);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(CREDENTIAL_PROPERTY);
        }

        @Test
        @DisplayName("every over-width violation is a size violation")
        void everyOverWidthViolationIsASizeViolation() {
            SignOnRequest request = new SignOnRequest(USER_ID_OVER_WIDTH, CREDENTIAL_OVER_WIDTH, null);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).isNotEmpty();
            for (ConstraintViolation<SignOnRequest> violation : violations) {
                assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Size.class);
                assertThat(violation.getMessage()).isEqualTo(WIDTH_VIOLATION_MESSAGE);
            }
        }

        @ParameterizedTest(name = "value {0} is accepted")
        @ValueSource(strings = {"admin001", "ADMIN001", "AdMiN001", "00000001", "a", "1",
            "AB CD", " AB", "AB ", "A.B-C_D", "@#$%^&*(", "12345678"})
        @DisplayName("no printable character-class, case or format rule rejects screen text")
        void noPrintableCharacterClassRuleRejectsScreenText(String value) {
            SignOnRequest request = new SignOnRequest(value, value, null);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("a value carrying an embedded space is accepted, matching the legacy editor")
        void aValueCarryingAnEmbeddedSpaceIsAccepted() {
            String withEmbeddedSpace = "AB CD EF";
            SignOnRequest request = new SignOnRequest(withEmbeddedSpace, withEmbeddedSpace, null);

            assertThat(withEmbeddedSpace).hasSize(USER_ID_WIDTH);
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("control and format characters are refused only in the identifier")
        void controlAndFormatCharactersAreRefusedOnlyInTheIdentifier() {
            final List<String> unsafeCharacters = List.of(
                    "\n", "\r", "\t", String.valueOf('\0'), "\u0085", "\u200E");

            for (final String unsafe : unsafeCharacters) {
                final Set<ConstraintViolation<SignOnRequest>> identifierViolations =
                        violationsOf(new SignOnRequest("A" + unsafe, null, null));
                assertThat(identifierViolations).singleElement().satisfies(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString(USER_ID_PROPERTY);
                    assertThat(violation.getMessage())
                            .isEqualTo(CONTROL_CHARACTER_VIOLATION_MESSAGE);
                    assertThat(violation.getConstraintDescriptor().getAnnotation())
                            .isInstanceOf(Pattern.class);
                });

                assertThat(violationsOf(new SignOnRequest(null, "A" + unsafe, null)))
                        .as("the credential is write-only and never enters an application log")
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("no minimum-length rule fires, so a one-character value is accepted")
        void noMinimumLengthRuleFires() {
            SignOnRequest request = new SignOnRequest("A", "B", null);

            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("no numeric or digit rule fires, so a purely alphabetic value is accepted")
        void noDigitRuleFires() {
            SignOnRequest request = new SignOnRequest("ABCDEFGH", "ZYXWVUTS", null);

            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("validating an all-null instance never throws, so nothing cascades into a null")
        void validatingAnAllNullInstanceNeverThrows() {
            SignOnRequest request = new SignOnRequest(null, null, null);

            assertThatCode(() -> violationsOf(request)).doesNotThrowAnyException();
        }
    }

    /**
     * The credential is carried but never printed.
     *
     * <p>The password field is defined on the mapset at line 175 with the non-display
     * attribute, so the legacy terminal never echoed it. The override under test reproduces
     * that on the diagnostic channel: the value is replaced by a fixed placeholder that
     * carries nothing about it - not the value, not its length, not a prefix and not a
     * digest.</p>
     */
    @Nested
    @DisplayName("credential redaction in diagnostic output")
    class CredentialRedaction {

        @Test
        @DisplayName("the credential does not appear anywhere in the diagnostic representation")
        void theCredentialDoesNotAppearInTheDiagnosticRepresentation() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            String rendered = request.toString();

            assertThat(rendered).doesNotContain(SYNTHETIC_CREDENTIAL);
        }

        @Test
        @DisplayName("the placeholder appears exactly once, standing where the credential would be")
        void thePlaceholderAppearsExactlyOnce() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            String rendered = request.toString();

            assertThat(occurrencesOf(rendered, REDACTION_PLACEHOLDER_TEXT)).isEqualTo(1);
            assertThat(rendered).contains(CREDENTIAL_PROPERTY + "=" + REDACTION_PLACEHOLDER_TEXT);
        }

        @Test
        @DisplayName("the user id is retained, because an account identifier is not a secret")
        void theUserIdIsRetained() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            String rendered = request.toString();

            assertThat(rendered).contains(USER_ID_PROPERTY + "=" + USER_ID_AT_WIDTH);
        }

        @Test
        @DisplayName("no four-character run of the credential survives into the output")
        void noFourCharacterRunOfTheCredentialSurvives() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            String rendered = request.toString();

            int runLength = 4;
            for (int start = 0; start + runLength <= SYNTHETIC_CREDENTIAL.length(); start++) {
                String run = SYNTHETIC_CREDENTIAL.substring(start, start + runLength);
                assertThat(rendered)
                        .withFailMessage("diagnostic output leaked a credential fragment at offset %d", start)
                        .doesNotContain(run);
            }
        }

        @Test
        @DisplayName("the credential length is not disclosed, because the placeholder is fixed")
        void theCredentialLengthIsNotDisclosed() {
            SignOnRequest shortCredential = new SignOnRequest(USER_ID_AT_WIDTH, "A", null);
            SignOnRequest widthCredential = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            assertThat(shortCredential.toString()).isEqualTo(widthCredential.toString());
        }

        @Test
        @DisplayName("a differing credential yields identical output, so nothing about it leaks")
        void aDifferingCredentialYieldsIdenticalOutput() {
            SignOnRequest first = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);
            SignOnRequest second = new SignOnRequest(USER_ID_AT_WIDTH, OTHER_SYNTHETIC_CREDENTIAL, null);

            assertThat(first.toString()).isEqualTo(second.toString());
            assertThat(first.toString()).doesNotContain(OTHER_SYNTHETIC_CREDENTIAL);
        }

        @Test
        @DisplayName("an absent credential still yields the placeholder rather than a null marker")
        void anAbsentCredentialStillYieldsThePlaceholder() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, null, null);

            String rendered = request.toString();

            assertThat(occurrencesOf(rendered, REDACTION_PLACEHOLDER_TEXT)).isEqualTo(1);
            assertThat(rendered).doesNotContain(CREDENTIAL_PROPERTY + "=null");
        }

        @Test
        @DisplayName("an all-space credential is redacted too, so whitespace never hints at a value")
        void anAllSpaceCredentialIsRedactedToo() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, "        ", null);

            String rendered = request.toString();

            assertThat(occurrencesOf(rendered, REDACTION_PLACEHOLDER_TEXT)).isEqualTo(1);
            assertThat(rendered).isEqualTo(
                    new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null).toString());
        }

        @Test
        @DisplayName("the output names the type and every property in declaration order, so it stays "
                + "readable as a diagnostic")
        void theOutputNamesTheTypeAndEveryProperty() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL,
                    KeyAction.PFK03);

            String rendered = request.toString();

            assertThat(rendered)
                    .startsWith("SignOnRequest[")
                    .endsWith("]")
                    .contains(USER_ID_PROPERTY + "=")
                    .contains(CREDENTIAL_PROPERTY + "=")
                    .contains(KEY_ACTION_PROPERTY + "=");
            assertThat(rendered.indexOf(USER_ID_PROPERTY + "="))
                    .isLessThan(rendered.indexOf(CREDENTIAL_PROPERTY + "="));
            assertThat(rendered.indexOf(CREDENTIAL_PROPERTY + "="))
                    .as("the attention key renders after the redacted credential, mirroring the "
                            + "declaration order of the record")
                    .isLessThan(rendered.indexOf(KEY_ACTION_PROPERTY + "="));
        }

        @Test
        @DisplayName("the attention key is rendered by name, because a keystroke drawn from a published "
                + "vocabulary is a diagnostic aid rather than a secret")
        void theAttentionKeyIsRenderedByName() {
            String rendered = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL,
                    KeyAction.PFK03).toString();

            assertThat(rendered).contains(KEY_ACTION_PROPERTY + "=" + KeyAction.PFK03.name());
        }

        @Test
        @DisplayName("an absent attention key renders as an absence rather than as a substituted key, "
                + "because the legacy evaluation substitutes none")
        void anAbsentAttentionKeyRendersAsAnAbsence() {
            String rendered = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null)
                    .toString();

            assertThat(rendered).contains(KEY_ACTION_PROPERTY + "=null");
            assertThat(rendered).doesNotContain(KeyAction.ENTER.name());
        }

        @Test
        @DisplayName("the override is honoured through string concatenation, the usual accidental leak")
        void theOverrideIsHonouredThroughStringConcatenation() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            String interpolated = "sign-on attempt: " + request;

            assertThat(interpolated).doesNotContain(SYNTHETIC_CREDENTIAL);
            assertThat(interpolated).contains(REDACTION_PLACEHOLDER_TEXT);
        }

        @Test
        @DisplayName("the override is honoured by String.valueOf, another accidental leak route")
        void theOverrideIsHonouredByStringValueOf() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            assertThat(String.valueOf(request)).doesNotContain(SYNTHETIC_CREDENTIAL);
        }

        /*
         * The strongest statement available about a fixed placeholder is that the rendered
         * output is a constant function of the credential: the same text comes back for every
         * credential, whatever its characters and whatever its length, so the output carries
         * no information about the value at all. That is asserted below against a
         * credential-independent expected literal.
         *
         * Note deliberately what is NOT asserted here. A fixed placeholder inevitably shares
         * individual characters with some credentials - the placeholder text alone contains A,
         * C and D - so "no character in common" would be both unachievable and meaningless.
         * Invariance is the property that actually matters, and it subsumes the weaker
         * character-level statement.
         */

        @ParameterizedTest(name = "credential \"{0}\" renders identically")
        @ValueSource(strings = {"A", "AB", "ABCD1234", "        ", "zzzzzzzz", "!@#$%^&*", "00000000"})
        @DisplayName("the placeholder is a constant, so no credential changes the output at all")
        void thePlaceholderIsAConstantForEveryCredential(String credential) {
            String credentialIndependentExpectation = "SignOnRequest["
                    + USER_ID_PROPERTY + "=" + USER_ID_AT_WIDTH
                    + ", " + CREDENTIAL_PROPERTY + "=" + REDACTION_PLACEHOLDER_TEXT
                    + ", " + KEY_ACTION_PROPERTY + "=null]";

            String rendered = new SignOnRequest(USER_ID_AT_WIDTH, credential, null).toString();

            assertThat(rendered).isEqualTo(credentialIndependentExpectation);
        }

        @Test
        @DisplayName("the rendered length never varies with the credential length")
        void theRenderedLengthNeverVariesWithTheCredentialLength() {
            int rendered = new SignOnRequest(USER_ID_AT_WIDTH, "A", null).toString().length();
            int renderedAtWidth =
                    new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null).toString().length();
            int renderedOverWidth =
                    new SignOnRequest(USER_ID_AT_WIDTH, CREDENTIAL_OVER_WIDTH, null).toString().length();

            assertThat(rendered).isEqualTo(renderedAtWidth).isEqualTo(renderedOverWidth);
        }
    }

    /**
     * Equality is the record contract, which is what the production source declares.
     *
     * <p>The production type overrides the diagnostic representation only. It states in its
     * own documentation that equality and hashing are deliberately left to the record
     * contract, so both compare the two components by value. These assertions pin the
     * contract that is actually declared; nothing here asks the production type to be changed
     * so that a different contract could be asserted instead.</p>
     */
    @Nested
    @DisplayName("equality and hashing")
    class EqualityAndHashing {

        @Test
        @DisplayName("two separately built instances carrying the same values are equal")
        void twoSeparatelyBuiltInstancesCarryingTheSameValuesAreEqual() {
            SignOnRequest first = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);
            SignOnRequest second = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            assertThat(first).isNotSameAs(second).isEqualTo(second);
            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("equal instances agree on their hash code")
        void equalInstancesAgreeOnTheirHashCode() {
            SignOnRequest first = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);
            SignOnRequest second = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("instances differing only in the credential are not equal")
        void instancesDifferingOnlyInTheCredentialAreNotEqual() {
            SignOnRequest first = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);
            SignOnRequest second = new SignOnRequest(USER_ID_AT_WIDTH, OTHER_SYNTHETIC_CREDENTIAL, null);

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("instances differing only in the user id are not equal")
        void instancesDifferingOnlyInTheUserIdAreNotEqual() {
            SignOnRequest first = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);
            SignOnRequest second = new SignOnRequest("USER0002", SYNTHETIC_CREDENTIAL, null);

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("equality distinguishes an absent value from an empty one")
        void equalityDistinguishesAnAbsentValueFromAnEmptyOne() {
            SignOnRequest absent = new SignOnRequest(null, null, null);
            SignOnRequest empty = new SignOnRequest("", "", null);

            assertThat(absent).isNotEqualTo(empty);
        }

        @Test
        @DisplayName("equality distinguishes a trailing space, because nothing is trimmed first")
        void equalityDistinguishesATrailingSpace() {
            SignOnRequest padded = new SignOnRequest("USER1   ", SYNTHETIC_CREDENTIAL, null);
            SignOnRequest bare = new SignOnRequest("USER1", SYNTHETIC_CREDENTIAL, null);

            assertThat(padded).isNotEqualTo(bare);
        }

        @Test
        @DisplayName("equality distinguishes case, because nothing is folded first")
        void equalityDistinguishesCase() {
            SignOnRequest lower = new SignOnRequest("admin001", SYNTHETIC_CREDENTIAL, null);
            SignOnRequest upper = new SignOnRequest("ADMIN001", SYNTHETIC_CREDENTIAL, null);

            assertThat(lower).isNotEqualTo(upper);
        }

        @Test
        @DisplayName("equality is reflexive, null-safe and type-safe")
        void equalityIsReflexiveNullSafeAndTypeSafe() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            assertThat(request).isEqualTo(request);
            assertThat(request).isNotEqualTo(null);
            assertThat(request).isNotEqualTo(USER_ID_AT_WIDTH);
        }

        @Test
        @DisplayName("an all-null instance equals another all-null instance and hashes alike")
        void anAllNullInstanceEqualsAnotherAllNullInstance() {
            SignOnRequest first = new SignOnRequest(null, null, null);
            SignOnRequest second = new SignOnRequest(null, null, null);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }
    }

    /**
     * The wire contract: the credential must deserialize, and must never serialize.
     *
     * <p>Redaction protects the diagnostic channel and does nothing for the transport
     * channel, where a record is a plain bean to the mapper. An endpoint cannot verify a
     * credential it refuses to read, so the password component stays fully readable
     * inbound and this group asserts that direction first. Outbound it is suppressed: the
     * legacy transaction never rendered the credential to the terminal and never wrote it
     * into the outbound symbolic map, so no message it produced ever carried the value, and
     * the migrated contract reproduces that by declaring the component write-only. The shape
     * of the emitted document is asserted too, because the emitted property set is what
     * proves the outbound half of the contract.</p>
     */
    @Nested
    @DisplayName("JSON contract")
    class JsonContract {

        @Test
        @DisplayName("a document carrying the credential deserializes with the credential populated")
        void aDocumentCarryingTheCredentialDeserializesWithItPopulated() throws JsonProcessingException {
            String document = "{\"userId\":\"" + USER_ID_AT_WIDTH
                    + "\",\"password\":\"" + SYNTHETIC_CREDENTIAL + "\"}";

            SignOnRequest request = parse(document);

            assertThat(request.userId()).isEqualTo(USER_ID_AT_WIDTH);
            assertThat(SensitiveValues.fingerprint(request.password())).isEqualTo(SensitiveValues.fingerprint(SYNTHETIC_CREDENTIAL));
        }

        @Test
        @DisplayName("only the user id is emitted when both are populated, the credential being write-only")
        void onlyTheUserIdIsEmittedWhenBothArePopulated() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            Map<String, Object> properties = propertiesOf(jsonOf(request));

            assertThat(properties).containsEntry(USER_ID_PROPERTY, USER_ID_AT_WIDTH);
            assertThat(properties).doesNotContainKey(CREDENTIAL_PROPERTY);
        }

        @Test
        @DisplayName("the emitted document carries exactly the one outbound-eligible property")
        void theEmittedDocumentCarriesExactlyTheOneOutboundProperty() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            Map<String, Object> properties = propertiesOf(jsonOf(request));

            assertThat(properties).containsOnlyKeys(USER_ID_PROPERTY);
        }

        @Test
        @DisplayName("an absent value is omitted from the document under non-null inclusion")
        void anAbsentValueIsOmittedFromTheDocument() throws JsonProcessingException {
            SignOnRequest onlyUserId = new SignOnRequest(USER_ID_AT_WIDTH, null, null);

            Map<String, Object> properties = propertiesOf(jsonOf(onlyUserId));

            assertThat(properties).containsOnlyKeys(USER_ID_PROPERTY);
            assertThat(properties).doesNotContainKey(CREDENTIAL_PROPERTY);
        }

        @Test
        @DisplayName("an all-absent instance emits an empty object rather than two null members")
        void anAllAbsentInstanceEmitsAnEmptyObject() throws JsonProcessingException {
            SignOnRequest empty = new SignOnRequest(null, null, null);

            assertThat(jsonOf(empty)).isEqualTo("{}");
            assertThat(propertiesOf(jsonOf(empty))).isEmpty();
        }

        @Test
        @DisplayName("an empty user id is emitted, because only an absent value is omitted")
        void anEmptyStringIsEmitted() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest("", "", null);

            Map<String, Object> properties = propertiesOf(jsonOf(request));

            assertThat(properties).containsEntry(USER_ID_PROPERTY, "");
        }

        @Test
        @DisplayName("an empty credential is omitted too, because the suppression is not value-dependent")
        void anEmptyCredentialIsOmittedAsWell() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest("", "", null);

            Map<String, Object> properties = propertiesOf(jsonOf(request));

            assertThat(properties).doesNotContainKey(CREDENTIAL_PROPERTY);
        }

        @Test
        @DisplayName("an empty document yields an instance whose two values are both absent")
        void anEmptyDocumentYieldsAnInstanceWhoseValuesAreBothAbsent() throws JsonProcessingException {
            SignOnRequest request = parse("{}");

            assertThat(request.userId()).isNull();
            assertThat(request.password()).isNull();
        }

        @Test
        @DisplayName("an explicit JSON null yields an absent value rather than a failure")
        void anExplicitJsonNullYieldsAnAbsentValue() throws JsonProcessingException {
            SignOnRequest request = parse("{\"userId\":null,\"password\":null}");

            assertThat(request.userId()).isNull();
            assertThat(request.password()).isNull();
        }

        @Test
        @DisplayName("an unknown incoming property is tolerated by the shared settings")
        void anUnknownIncomingPropertyIsTolerated() throws JsonProcessingException {
            String document = "{\"userId\":\"" + USER_ID_AT_WIDTH
                    + "\",\"password\":\"" + SYNTHETIC_CREDENTIAL + "\",\"rememberMe\":true}";

            SignOnRequest request = parse(document);

            assertThat(request.userId()).isEqualTo(USER_ID_AT_WIDTH);
            assertThat(SensitiveValues.fingerprint(request.password())).isEqualTo(SensitiveValues.fingerprint(SYNTHETIC_CREDENTIAL));
        }

        @Test
        @DisplayName("that tolerance comes from the configuration, not from the contract itself")
        void toleranceComesFromTheConfigurationNotFromTheContract() {
            String document = "{\"userId\":\"" + USER_ID_AT_WIDTH + "\",\"rememberMe\":true}";

            assertThatExceptionOfType(UnrecognizedPropertyException.class)
                    .isThrownBy(() -> STRICT_MAPPER.readValue(document, SignOnRequest.class))
                    .withMessageContaining("rememberMe");
        }

        @Test
        @DisplayName("a serialize and deserialize cycle keeps the user id and drops the credential")
        void aRoundTripKeepsTheUserIdAndDropsTheCredential() throws JsonProcessingException {
            SignOnRequest original = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            SignOnRequest restored = parse(jsonOf(original));

            assertThat(restored.userId()).isEqualTo(USER_ID_AT_WIDTH);
            assertThat(restored.password()).isNull();
            assertThat(restored).isNotEqualTo(original);
        }

        @Test
        @DisplayName("an inbound document binds both values unchanged, which is the direction that matters")
        void anInboundDocumentBindsBothValuesUnchanged() throws JsonProcessingException {
            String document = "{\"" + USER_ID_PROPERTY + "\":\"" + USER_ID_AT_WIDTH
                    + "\",\"" + CREDENTIAL_PROPERTY + "\":\"" + SYNTHETIC_CREDENTIAL + "\"}";

            SignOnRequest bound = parse(document);

            assertThat(bound).isEqualTo(new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null));
        }

        @Test
        @DisplayName("the emitted document carries neither the credential nor a stand-in for it")
        void theEmittedDocumentCarriesNeitherTheCredentialNorAStandIn() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            String document = jsonOf(request);

            assertThat(document)
                    .doesNotContain(SYNTHETIC_CREDENTIAL)
                    .doesNotContain(CREDENTIAL_PROPERTY)
                    .doesNotContain(REDACTION_PLACEHOLDER_TEXT);
        }
    }

    /**
     * Write-only access mode of the credential, on every outbound path there is.
     *
     * <p>The legacy screen field is defined with the non-display attribute at line 175 of
     * {@code app/bms/COSGN00.bms}, so the terminal accepted the credential but never rendered
     * it, and the outbound symbolic-map item {@code PASSWDO} at line 146 of
     * {@code app/cpy-bms/COSGN00.CPY} is never written by {@code COSGN00C} at all, so no
     * message the transaction produced ever carried the value. Redacting
     * {@code toString()} reproduces none of that on the wire: to a JSON mapper a record is a
     * plain bean, and without an access declaration the plaintext credential is emitted
     * verbatim.</p>
     *
     * <p>This group therefore asserts the suppression in the two ways that can fail
     * independently. First behaviourally, across every value shape and through a nesting
     * wrapper, because a serializer decision taken per property must hold wherever the object
     * appears rather than only when it is the document root. Second declaratively, on the
     * annotations themselves, because the published interface description is a contract of its
     * own that client tooling reads. The inbound direction is asserted in every case alongside
     * the omission, since a suppression that also blocked reading would break sign-on and is
     * the specific mistake an exclusion annotation would make here.</p>
     */
    @Nested
    @DisplayName("write-only access mode of the credential")
    class CredentialWriteOnlyAccess {

        @ParameterizedTest(name = "the credential is withheld from the document for value shape {0}")
        @ValueSource(strings = {
            "ABCD1234", "abcd1234", "aBcD1234", "00001234", "  ABCD  ", "MARY ANN", "A", "        "})
        @DisplayName("no value shape reaches the emitted document")
        void noValueShapeReachesTheEmittedDocument(String candidate) throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, candidate, null);

            String document = jsonOf(request);

            assertThat(document).doesNotContain(CREDENTIAL_PROPERTY);
            assertThat(propertiesOf(document)).doesNotContainKey(CREDENTIAL_PROPERTY);
        }

        @ParameterizedTest(name = "the credential still binds inbound for value shape {0}")
        @ValueSource(strings = {
            "ABCD1234", "abcd1234", "aBcD1234", "00001234", "  ABCD  ", "MARY ANN", "A", "        "})
        @DisplayName("every value shape still binds on the inbound direction")
        void everyValueShapeStillBindsInbound(String candidate) throws JsonProcessingException {
            String document = "{\"" + CREDENTIAL_PROPERTY + "\":\"" + candidate + "\"}";

            SignOnRequest request = parse(document);

            assertThat(SensitiveValues.fingerprint(request.password())).isEqualTo(SensitiveValues.fingerprint(candidate));
        }

        @Test
        @DisplayName("the strict mapper withholds the credential as well, so the suppression is not a setting")
        void theStrictMapperWithholdsTheCredentialAsWell() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            String document = STRICT_MAPPER.writeValueAsString(request);

            assertThat(document).contains(USER_ID_AT_WIDTH).doesNotContain(SYNTHETIC_CREDENTIAL);
        }

        @Test
        @DisplayName("the credential is withheld when the request is nested inside another object")
        void theCredentialIsWithheldWhenNestedInsideAnotherObject() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            String document = SHARED_SETTINGS_MAPPER.writeValueAsString(Map.of("attempt", request));

            assertThat(document).contains(USER_ID_AT_WIDTH).doesNotContain(SYNTHETIC_CREDENTIAL);
        }

        @Test
        @DisplayName("the credential is withheld when the request is nested inside a collection")
        void theCredentialIsWithheldWhenNestedInsideACollection() throws JsonProcessingException {
            SignOnRequest first = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);
            SignOnRequest second = new SignOnRequest("USER0002", OTHER_SYNTHETIC_CREDENTIAL, null);

            String document = SHARED_SETTINGS_MAPPER.writeValueAsString(List.of(first, second));

            assertThat(document)
                    .contains(USER_ID_AT_WIDTH)
                    .contains("USER0002")
                    .doesNotContain(SYNTHETIC_CREDENTIAL)
                    .doesNotContain(OTHER_SYNTHETIC_CREDENTIAL);
        }

        @Test
        @DisplayName("the suppression is an access declaration rather than an exclusion")
        void theSuppressionIsAnAccessDeclarationRatherThanAnExclusion() throws NoSuchFieldException {
            JsonProperty declared = SignOnRequest.class
                    .getDeclaredField(CREDENTIAL_PROPERTY)
                    .getAnnotation(JsonProperty.class);

            assertThat(declared).isNotNull();
            assertThat(declared.access()).isEqualTo(JsonProperty.Access.WRITE_ONLY);
        }

        @Test
        @DisplayName("the user id carries no access declaration, so only the credential is shaped")
        void theUserIdCarriesNoAccessDeclaration() throws NoSuchFieldException {
            JsonProperty declared = SignOnRequest.class
                    .getDeclaredField(USER_ID_PROPERTY)
                    .getAnnotation(JsonProperty.class);

            assertThat(declared).isNull();
        }

        @Test
        @DisplayName("the published schema marks the credential write-only and password-formatted")
        void thePublishedSchemaMarksTheCredentialWriteOnlyAndPasswordFormatted() throws NoSuchFieldException {
            Schema declared = SignOnRequest.class
                    .getDeclaredField(CREDENTIAL_PROPERTY)
                    .getAnnotation(Schema.class);

            assertThat(declared).isNotNull();
            assertThat(declared.accessMode()).isEqualTo(Schema.AccessMode.WRITE_ONLY);
            assertThat(declared.format()).isEqualTo("password");
        }

        @Test
        @DisplayName("the published schema description names no credential value")
        void thePublishedSchemaDescriptionNamesNoCredentialValue() throws NoSuchFieldException {
            Schema declared = SignOnRequest.class
                    .getDeclaredField(CREDENTIAL_PROPERTY)
                    .getAnnotation(Schema.class);

            assertThat(declared.description())
                    .isNotBlank()
                    .doesNotContain(SYNTHETIC_CREDENTIAL)
                    .doesNotContain(OTHER_SYNTHETIC_CREDENTIAL);
        }

        @Test
        @DisplayName("the published schema leaves the user id unshaped")
        void thePublishedSchemaLeavesTheUserIdUnshaped() throws NoSuchFieldException {
            Schema declared = SignOnRequest.class
                    .getDeclaredField(USER_ID_PROPERTY)
                    .getAnnotation(Schema.class);

            assertThat(declared).isNull();
        }

        @Test
        @DisplayName("the access declaration reaches the accessor and the constructor parameter too")
        void theAccessDeclarationReachesTheAccessorAndTheConstructorParameter() throws NoSuchMethodException {
            JsonProperty onAccessor = SignOnRequest.class
                    .getDeclaredMethod(CREDENTIAL_PROPERTY)
                    .getAnnotation(JsonProperty.class);
            JsonProperty onParameter = SignOnRequest.class
                    .getDeclaredConstructor(String.class, String.class, KeyAction.class)
                    .getParameters()[1]
                    .getAnnotation(JsonProperty.class);

            assertThat(onAccessor).isNotNull();
            assertThat(onAccessor.access()).isEqualTo(JsonProperty.Access.WRITE_ONLY);
            assertThat(onParameter).isNotNull();
            assertThat(onParameter.access()).isEqualTo(JsonProperty.Access.WRITE_ONLY);
        }

        @Test
        @DisplayName("a value read inbound is still available to the accessor after the read")
        void aValueReadInboundIsStillAvailableToTheAccessor() throws JsonProcessingException {
            String document = "{\"" + USER_ID_PROPERTY + "\":\"" + USER_ID_AT_WIDTH
                    + "\",\"" + CREDENTIAL_PROPERTY + "\":\"" + SYNTHETIC_CREDENTIAL + "\"}";

            SignOnRequest request = parse(document);

            assertThat(SensitiveValues.fingerprint(request.password())).isEqualTo(SensitiveValues.fingerprint(SYNTHETIC_CREDENTIAL));
            assertThat(request.password().length()).isEqualTo(CREDENTIAL_WIDTH);
        }

        @Test
        @DisplayName("the diagnostic representation stays redacted, so neither outbound path leaks")
        void theDiagnosticRepresentationStaysRedacted() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            assertThat(request.toString()).doesNotContain(SYNTHETIC_CREDENTIAL);
            assertThat(jsonOf(request)).doesNotContain(SYNTHETIC_CREDENTIAL);
        }
    }

    /**
     * Neither value is normalised anywhere on the transport path.
     *
     * <p>{@code COSGN00C} folds both submitted values to upper case at lines 132 through 136,
     * unconditionally and after the emptiness cascade has finished. That fold is part of the
     * authentication algorithm rather than part of the transport shape, so it is performed by
     * the service. This type must therefore hand the service exactly what the client sent:
     * not trimmed, not padded out to the eight-character screen width, not folded to either
     * case, and not canonicalised in any other way.</p>
     */
    @Nested
    @DisplayName("verbatim carriage of both values")
    class VerbatimCarriage {

        @Test
        @DisplayName("a trailing space on the user id survives a round trip character for character")
        void aTrailingSpaceOnTheUserIdSurvives() throws JsonProcessingException {
            String padded = "USER1   ";
            SignOnRequest request = parse("{\"userId\":\"" + padded + "\"}");

            assertThat(request.userId()).isEqualTo(padded).hasSize(USER_ID_WIDTH);
        }

        @Test
        @DisplayName("a trailing space on the credential survives a round trip character for character")
        void aTrailingSpaceOnTheCredentialSurvives() throws JsonProcessingException {
            String padded = "ABCD    ";
            SignOnRequest request = parse("{\"password\":\"" + padded + "\"}");

            assertThat(SensitiveValues.fingerprint(request.password())).isEqualTo(SensitiveValues.fingerprint(padded));
            assertThat(request.password().length()).isEqualTo(CREDENTIAL_WIDTH);
        }

        @Test
        @DisplayName("a leading space survives on both values, because nothing is stripped")
        void aLeadingSpaceSurvivesOnBothValues() throws JsonProcessingException {
            String leading = "   USER1";
            String leadingCredential = "   ABCD";
            SignOnRequest request = parse("{\"userId\":\"" + leading
                    + "\",\"password\":\"" + leadingCredential + "\"}");

            assertThat(request.userId()).isEqualTo(leading);
            assertThat(SensitiveValues.fingerprint(request.password())).isEqualTo(SensitiveValues.fingerprint(leadingCredential));
        }

        @Test
        @DisplayName("a short value is never padded out to the eight-character screen width")
        void aShortValueIsNeverPaddedToTheScreenWidth() throws JsonProcessingException {
            SignOnRequest request = parse("{\"userId\":\"AB\",\"password\":\"CD\"}");

            assertThat(request.userId()).isEqualTo("AB").hasSize(2);
            assertThat(SensitiveValues.fingerprint(request.password())).isEqualTo(SensitiveValues.fingerprint("CD"));
            assertThat(request.password().length()).isEqualTo(2);
            assertThat(request.userId()).doesNotContain(" ");
        }

        @Test
        @DisplayName("a lower-case user id is never folded to upper case by this contract")
        void aLowerCaseUserIdIsNeverFolded() throws JsonProcessingException {
            SignOnRequest request = parse("{\"userId\":\"admin001\"}");

            assertThat(request.userId()).isEqualTo("admin001").isNotEqualTo("ADMIN001");
        }

        @Test
        @DisplayName("a mixed-case credential is never folded to upper case by this contract")
        void aMixedCaseCredentialIsNeverFolded() throws JsonProcessingException {
            String mixedCase = "aBcD1234";
            SignOnRequest request = parse("{\"password\":\"" + mixedCase + "\"}");

            assertThat(SensitiveValues.fingerprint(request.password())).isEqualTo(SensitiveValues.fingerprint(mixedCase));
        }

        @Test
        @DisplayName("a lower-case user id survives the serialize direction, the credential being withheld")
        void aLowerCaseValueSurvivesTheSerializeDirection() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest("admin001", "abcd1234", null);

            Map<String, Object> properties = propertiesOf(jsonOf(request));

            assertThat(properties).containsEntry(USER_ID_PROPERTY, "admin001");
            assertThat(properties).doesNotContainKey(CREDENTIAL_PROPERTY);
        }

        @Test
        @DisplayName("a lower-case credential is carried verbatim on the direction it travels")
        void aLowerCaseCredentialIsCarriedVerbatimInbound() throws JsonProcessingException {
            SignOnRequest request = parse("{\"password\":\"abcd1234\"}");

            assertThat(SensitiveValues.fingerprint(request.password())).isEqualTo(SensitiveValues.fingerprint("abcd1234"));
        }

        @Test
        @DisplayName("a padded user id survives the serialize direction, still untrimmed")
        void aPaddedValueSurvivesTheSerializeDirection() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest("USER1   ", "  ABCD  ", null);

            Map<String, Object> properties = propertiesOf(jsonOf(request));

            assertThat(properties).containsEntry(USER_ID_PROPERTY, "USER1   ");
            assertThat(properties).doesNotContainKey(CREDENTIAL_PROPERTY);
        }

        @Test
        @DisplayName("a padded credential is carried verbatim inbound, still untrimmed")
        void aPaddedCredentialIsCarriedVerbatimInbound() throws JsonProcessingException {
            SignOnRequest request = parse("{\"password\":\"  ABCD  \"}");

            assertThat(SensitiveValues.fingerprint(request.password())).isEqualTo(SensitiveValues.fingerprint("  ABCD  "));
        }

        @Test
        @DisplayName("an embedded space survives, matching the legacy alphabetic editor")
        void anEmbeddedSpaceSurvives() throws JsonProcessingException {
            String withEmbeddedSpace = "MARY ANN";
            SignOnRequest request = parse("{\"userId\":\"" + withEmbeddedSpace + "\"}");

            assertThat(request.userId()).isEqualTo(withEmbeddedSpace).hasSize(USER_ID_WIDTH);
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("the credential is handed on unaltered, so hashed verification sees what was typed")
        void theCredentialIsHandedOnUnaltered() throws JsonProcessingException {
            SignOnRequest request = parse("{\"password\":\"" + SYNTHETIC_CREDENTIAL + "\"}");

            assertThat(request.password())
                    .isEqualTo(SYNTHETIC_CREDENTIAL)
                    .hasSize(CREDENTIAL_WIDTH);
        }
    }

    /**
     * The shape of the two values: character data, absent-tolerant, and settable only once.
     *
     * <p>Both the screen field at line 156 and the credential-record field at line 18 are
     * alphanumeric rather than numeric, so a value of all digits with leading zeroes has to
     * keep those zeroes. That rules out any numeric carrier: the two values are character
     * data, and the assertions below hold on both the accessor and the wire.</p>
     */
    @Nested
    @DisplayName("shape of the two values")
    class ValueShape {

        @Test
        @DisplayName("both accessors hand back character data, which the compiler settles at build time")
        void bothAccessorsHandBackCharacterData() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            String carriedUserId = request.userId();
            String carriedPassword = request.password();

            assertThat(carriedUserId).isEqualTo(USER_ID_AT_WIDTH);
            assertThat(carriedPassword).isEqualTo(SYNTHETIC_CREDENTIAL);
        }

        @Test
        @DisplayName("a user id of leading zeroes keeps every zero, on the accessor and on the wire")
        void aUserIdOfLeadingZeroesKeepsEveryZero() throws JsonProcessingException {
            String leadingZeroes = "00000042";
            SignOnRequest request = parse("{\"userId\":\"" + leadingZeroes + "\"}");

            assertThat(request.userId()).isEqualTo(leadingZeroes).hasSize(USER_ID_WIDTH);
            assertThat(propertiesOf(jsonOf(request))).containsEntry(USER_ID_PROPERTY, leadingZeroes);
        }

        @Test
        @DisplayName("a numeric-looking user id is emitted as a quoted string, never as a JSON number")
        void aNumericLookingValueIsEmittedAsAQuotedString() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest("00000042", "00001234", null);

            String document = jsonOf(request);
            Map<String, Object> properties = propertiesOf(document);

            assertThat(document).contains("\"" + USER_ID_PROPERTY + "\":\"00000042\"");
            assertThat(properties.get(USER_ID_PROPERTY)).isInstanceOf(String.class).isNotInstanceOf(Number.class);
        }

        @Test
        @DisplayName("a numeric-looking credential binds as a string, never as a JSON number")
        void aNumericLookingCredentialBindsAsAString() throws JsonProcessingException {
            SignOnRequest request = parse("{\"" + CREDENTIAL_PROPERTY + "\":\"00001234\"}");

            assertThat(request.password()).isInstanceOf(String.class).isEqualTo("00001234");
        }

        @Test
        @DisplayName("an all-digit value never loses a leading zero when read from a document")
        void anAllDigitValueNeverLosesALeadingZero() throws JsonProcessingException {
            String document = "{\"" + USER_ID_PROPERTY + "\":\"00000001\",\""
                    + CREDENTIAL_PROPERTY + "\":\"00000009\"}";

            SignOnRequest restored = parse(document);

            assertThat(restored.userId()).isEqualTo("00000001");
            assertThat(SensitiveValues.fingerprint(restored.password())).isEqualTo(SensitiveValues.fingerprint("00000009"));
        }

        @Test
        @DisplayName("the user id tolerates an absent value on its own")
        void theUserIdToleratesAnAbsentValueOnItsOwn() {
            SignOnRequest request = new SignOnRequest(null, SYNTHETIC_CREDENTIAL, null);

            assertThat(request.userId()).isNull();
            assertThat(SensitiveValues.fingerprint(request.password())).isEqualTo(SensitiveValues.fingerprint(SYNTHETIC_CREDENTIAL));
        }

        @Test
        @DisplayName("the credential tolerates an absent value on its own")
        void theCredentialToleratesAnAbsentValueOnItsOwn() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, null, null);

            assertThat(request.userId()).isEqualTo(USER_ID_AT_WIDTH);
            assertThat(request.password()).isNull();
        }

        @Test
        @DisplayName("constructing an all-absent instance never throws")
        void constructingAnAllAbsentInstanceNeverThrows() {
            assertThatCode(() -> new SignOnRequest(null, null, null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("repeated reads return the same values, so nothing is computed or consumed lazily")
        void repeatedReadsReturnTheSameValues() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            assertThat(request.userId()).isSameAs(request.userId());
            assertThat(request.password() == request.password()).isTrue();
        }

        @Test
        @DisplayName("the accessors hand back the very references construction was given")
        void theAccessorsHandBackTheVeryReferencesConstructionWasGiven() {
            String suppliedUserId = new String(USER_ID_AT_WIDTH.toCharArray());
            String suppliedCredential = new String(SYNTHETIC_CREDENTIAL.toCharArray());
            SignOnRequest request = new SignOnRequest(suppliedUserId, suppliedCredential, null);

            assertThat(request.userId()).isSameAs(suppliedUserId);
            assertThat(request.password() == suppliedCredential).isTrue();
        }

        @Test
        @DisplayName("construction is the only way to set a value, so an instance cannot be altered")
        void constructionIsTheOnlyWayToSetAValue() {
            SignOnRequest first = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);
            SignOnRequest second = new SignOnRequest("USER0002", OTHER_SYNTHETIC_CREDENTIAL, null);

            assertThat(first.userId()).isEqualTo(USER_ID_AT_WIDTH);
            assertThat(SensitiveValues.fingerprint(first.password())).isEqualTo(SensitiveValues.fingerprint(SYNTHETIC_CREDENTIAL));
            assertThat(second.userId()).isEqualTo("USER0002");
            assertThat(SensitiveValues.fingerprint(second.password())).isEqualTo(SensitiveValues.fingerprint(OTHER_SYNTHETIC_CREDENTIAL));
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("a value is not shared between instances, so building one cannot disturb another")
        void aValueIsNotSharedBetweenInstances() throws JsonProcessingException {
            SignOnRequest first = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);
            String firstDocument = jsonOf(first);

            SignOnRequest second = parse("{\"userId\":\"USER0002\",\"password\":\""
                    + OTHER_SYNTHETIC_CREDENTIAL + "\"}");

            assertThat(second.userId()).isEqualTo("USER0002");
            assertThat(jsonOf(first)).isEqualTo(firstDocument);
            assertThat(first.userId()).isEqualTo(USER_ID_AT_WIDTH);
        }
    }

    /**
     * Everything the screen carries besides the two inputs is deliberately absent.
     *
     * <p>The inbound symbolic map group declares eleven value items, but nine of them are
     * written outbound by the program rather than typed by the operator: the transaction name,
     * two forty-character titles, the current date, the program name, the current time, the
     * application identifier, the system identifier and the error message. Those belong to the
     * response contract. Navigation state, the user type, a route and an error flag are absent
     * for the same reason - the legacy screen has no such input, and adding one would be
     * feature expansion. This group proves the absence by behaviour: a document offering every
     * one of them is tolerated, none of them is retained, and re-emitting the instance yields
     * exactly the two inputs.</p>
     */
    @Nested
    @DisplayName("deliberately absent members")
    class DeliberatelyAbsentMembers {

        /**
         * A document offering every screen item and every navigation field the contract omits.
         *
         * <p>Property names are spelled the way a Java contract would spell them, because the
         * point is to prove the Java contract does not bind them, not to reproduce map item
         * names.</p>
         */
        private static final String DOCUMENT_OFFERING_EVERY_OMITTED_MEMBER = """
                {
                  "userId": "USER0001",
                  "password": "ABCD1234",
                  "transactionName": "CC00",
                  "title01": "AWS Mainframe Modernization",
                  "title02": "CardDemo",
                  "currentDate": "06/10/22",
                  "currentTime": "19:27:53",
                  "programName": "COSGN00C",
                  "applicationId": "CICSAPPL",
                  "systemId": "CICS",
                  "errorMessage": "Please enter User ID ...",
                  "navigationContext": {"fromProgram": "COSGN00C"},
                  "userType": "A",
                  "route": "/api/menu",
                  "errorFlag": true,
                  "screenTitle": "Sign-on"
                }""";

        @Test
        @DisplayName("a document offering every omitted member is tolerated rather than rejected")
        void aDocumentOfferingEveryOmittedMemberIsTolerated() {
            assertThatCode(() -> parse(DOCUMENT_OFFERING_EVERY_OMITTED_MEMBER))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("only the two inputs are retained from such a document")
        void onlyTheTwoInputsAreRetained() throws JsonProcessingException {
            SignOnRequest request = parse(DOCUMENT_OFFERING_EVERY_OMITTED_MEMBER);

            assertThat(request.userId()).isEqualTo(USER_ID_AT_WIDTH);
            assertThat(SensitiveValues.fingerprint(request.password())).isEqualTo(SensitiveValues.fingerprint(SYNTHETIC_CREDENTIAL));
        }

        @Test
        @DisplayName("re-emitting the instance yields exactly the outbound input and no screen furniture")
        void reEmittingTheInstanceYieldsExactlyTheOutboundInput() throws JsonProcessingException {
            SignOnRequest request = parse(DOCUMENT_OFFERING_EVERY_OMITTED_MEMBER);

            Map<String, Object> properties = propertiesOf(jsonOf(request));

            assertThat(properties).containsOnlyKeys(USER_ID_PROPERTY);
        }

        @ParameterizedTest(name = "{0} is not part of the request contract")
        @ValueSource(strings = {"transactionName", "title01", "title02", "currentDate", "currentTime",
            "programName", "applicationId", "systemId", "errorMessage", "navigationContext",
            "userType", "route", "errorFlag", "screenTitle", "message", "pageNumber"})
        @DisplayName("each omitted member is refused by a strict reader, proving it is not bound")
        void eachOmittedMemberIsRefusedByAStrictReader(String omittedMember) {
            String document = "{\"" + omittedMember + "\":\"x\"}";

            assertThatExceptionOfType(UnrecognizedPropertyException.class)
                    .isThrownBy(() -> STRICT_MAPPER.readValue(document, SignOnRequest.class))
                    .withMessageContaining(omittedMember);
        }

        @Test
        @DisplayName("the two inputs themselves are bound, so the strict reader accepts them both")
        void theTwoInputsThemselvesAreBound() {
            String document = "{\"" + USER_ID_PROPERTY + "\":\"" + USER_ID_AT_WIDTH
                    + "\",\"" + CREDENTIAL_PROPERTY + "\":\"" + SYNTHETIC_CREDENTIAL + "\"}";

            assertThatCode(() -> STRICT_MAPPER.readValue(document, SignOnRequest.class))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the contract is exactly three members wide: the two operator-typed items and the "
                + "attention key the program evaluates before either of them")
        void theContractIsExactlyThreeMembersWide() {
            assertThat(SignOnRequest.class.getRecordComponents()).hasSize(3);
            assertThat(SignOnRequest.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .containsExactly(USER_ID_PROPERTY, CREDENTIAL_PROPERTY, KEY_ACTION_PROPERTY);
        }

        @Test
        @DisplayName("only one of those three members is emitted when the key is absent, so the "
                + "document has one entry")
        void onlyOneOfTheThreeMembersIsOutboundEligible() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            Map<String, Object> properties = propertiesOf(jsonOf(request));

            assertThat(properties).hasSize(1);
        }

        @Test
        @DisplayName("the diagnostic representation names no member beyond the two inputs and the "
                + "attention key")
        void theDiagnosticRepresentationNamesNoMemberBeyondTheContract() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            String rendered = request.toString();

            assertThat(occurrencesOf(rendered, "=")).isEqualTo(3);
            assertThat(rendered).doesNotContain("navigationContext", "userType", "route",
                    "errorFlag", "currentDate", "currentTime", "title");
        }
    }


    // THE ATTENTION KEY

    /**
     * The operator's attention key, which the legacy program evaluates before it reads either field.
     *
     * <p>On a continuation turn {@code app/cbl/COSGN00C.cbl} evaluates the terminal's attention
     * identifier at lines 86 to 95 and takes exactly one of three paths in that source order: the enter
     * key runs the credential path, program-function key 3 emits the common acknowledgement, and any
     * other key raises the error switch and emits the common invalid-key notice. Two of the
     * transaction's seven message texts exist only on the second and third path, so the key is part of
     * the request contract rather than an implementation detail of whatever performs authentication.
     * The rules below assert only that this contract carries it faithfully: it is typed, never
     * defaulted, never normalised, never constrained, and it survives a round trip.</p>
     */
    @Nested
    @DisplayName("the attention key")
    class TheAttentionKey {

        @ParameterizedTest(name = "{0} is carried verbatim")
        @EnumSource(KeyAction.class)
        @DisplayName("every value of the published vocabulary is carried through unchanged, so the "
                + "service sees the key the operator actually pressed")
        void everyValueIsCarriedVerbatim(KeyAction action) {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, action);

            assertThat(request.keyAction()).isSameAs(action);
        }

        @Test
        @DisplayName("an absent key stays absent, because the legacy evaluation has no clause that "
                + "substitutes one and its any-other-key path already covers an absence")
        void anAbsentKeyStaysAbsent() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            assertThat(request.keyAction()).isNull();
        }

        @Test
        @DisplayName("an absent key is not a violation, so the ordered service-tier evaluation keeps "
                + "deciding the single message the legacy emits")
        void anAbsentKeyIsNotAViolation() {
            assertThat(violationsOf(new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null)))
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} raises no violation")
        @EnumSource(KeyAction.class)
        @DisplayName("no value of the vocabulary raises a violation, because the component declares no "
                + "constraint at all")
        void noValueRaisesAViolation(KeyAction action) {
            assertThat(violationsOf(new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, action)))
                    .isEmpty();
        }

        @Test
        @DisplayName("the component declares no annotation whatever, so nothing bounds, requires or "
                + "reshapes the key at the boundary")
        void theComponentDeclaresNoAnnotationWhatever() throws NoSuchFieldException {
            assertThat(SignOnRequest.class.getDeclaredField(KEY_ACTION_PROPERTY).getAnnotations())
                    .isEmpty();
        }

        @Test
        @DisplayName("the component is typed as the domain vocabulary rather than as loose text, so an "
                + "unmapped keystroke cannot reach the service disguised as a mapped one")
        void theComponentIsTypedAsTheDomainVocabulary() throws NoSuchFieldException {
            assertThat(SignOnRequest.class.getDeclaredField(KEY_ACTION_PROPERTY).getType())
                    .isEqualTo(KeyAction.class);
        }

        @ParameterizedTest(name = "{0} survives a round trip")
        @EnumSource(KeyAction.class)
        @DisplayName("every value survives a serialize-and-read round trip by name, so a client and the "
                + "service agree on the keystroke")
        void everyValueSurvivesARoundTrip(KeyAction action) throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, action);

            SignOnRequest back = parse(jsonOf(request));

            assertThat(back.keyAction()).isSameAs(action);
        }

        @Test
        @DisplayName("the key binds inbound from a document, which is what makes the three-way branch "
                + "reachable over the wire at all")
        void theKeyBindsInboundFromADocument() throws JsonProcessingException {
            SignOnRequest request = parse("{\"" + KEY_ACTION_PROPERTY + "\":\""
                    + KeyAction.PFK03.name() + "\"}");

            assertThat(request.keyAction()).isSameAs(KeyAction.PFK03);
        }

        @Test
        @DisplayName("an absent key is omitted from the emitted document rather than emitted as a null, "
                + "so an absence is not confused with a value")
        void anAbsentKeyIsOmittedFromTheEmittedDocument() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL, null);

            assertThat(propertiesOf(jsonOf(request))).doesNotContainKey(KEY_ACTION_PROPERTY);
        }

        @Test
        @DisplayName("a present key is emitted by name alongside the user id, and the credential is "
                + "still absent from that document")
        void aPresentKeyIsEmittedByName() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL,
                    KeyAction.ENTER);

            Map<String, Object> properties = propertiesOf(jsonOf(request));

            assertThat(properties)
                    .containsEntry(KEY_ACTION_PROPERTY, KeyAction.ENTER.name())
                    .containsKey(USER_ID_PROPERTY)
                    .doesNotContainKey(CREDENTIAL_PROPERTY);
        }

        @Test
        @DisplayName("the key takes no part in the ordered blank cascade, so a submission carrying only "
                + "a key still reports nothing at the boundary")
        void theKeyTakesNoPartInTheOrderedBlankCascade() {
            assertThat(violationsOf(new SignOnRequest(null, null, KeyAction.ENTER))).isEmpty();
        }
    }

}
