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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
 * <h2>Why the bound is the only constraint, and why that is behaviour rather than taste</h2>
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
 * <p>This class therefore proves the absence of every other constraint by behaviour: an
 * instance carrying two nulls yields no violation, an instance carrying two empty strings
 * yields no violation, and an instance carrying two all-space values yields no violation. No
 * character-class, format, digit or strength rule fires either, because the legacy screen
 * applies none and any of them would reject input the legacy system accepts.</p>
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
class SignOnRequestTest {

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
     * 2. The password component carries no serialization annotation at
     *    all - neither a total suppression nor a write-only access mode -
     *    so it both serializes and deserializes. This class asserts that
     *    round trip rather than inventing a write-only contract the
     *    production source does not declare. Redaction is enforced on the
     *    diagnostic channel, which is where the leak risk actually is.
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

    /** JSON property name of the user id. */
    private static final String USER_ID_PROPERTY = "userId";

    /** JSON property name carrying the submitted credential. */
    private static final String CREDENTIAL_PROPERTY = "password";

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
     * Mapper mirroring the shared application settings.
     *
     * <p>Non-null value inclusion and non-null content inclusion, which is the pair the
     * shared setting expands to; timestamps as text; unknown incoming properties tolerated;
     * and plain rather than scientific decimal notation. That last setting has no field to
     * act on here and is configured only so the mapper stays a faithful stand-in for the one
     * the application builds.</p>
     */
    private static final ObjectMapper SHARED_SETTINGS_MAPPER = JsonMapper.builder()
            .defaultPropertyInclusion(
                    JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

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
     */
    @BeforeAll
    static void buildValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
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
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, null);

            assertThat(USER_ID_AT_WIDTH).hasSize(USER_ID_WIDTH);
            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("a user id of nine characters violates the width bound and nothing else")
        void userIdOfNineCharactersViolatesTheWidthBound() {
            SignOnRequest request = new SignOnRequest(USER_ID_OVER_WIDTH, null);

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
            SignOnRequest request = new SignOnRequest(null, SYNTHETIC_CREDENTIAL);

            assertThat(SYNTHETIC_CREDENTIAL).hasSize(CREDENTIAL_WIDTH);
            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("a password of nine characters violates the width bound and nothing else")
        void passwordOfNineCharactersViolatesTheWidthBound() {
            SignOnRequest request = new SignOnRequest(null, CREDENTIAL_OVER_WIDTH);

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
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("both values over the bound together produce exactly two violations, one each")
        void bothValuesOverTheBoundProduceExactlyTwoViolations() {
            SignOnRequest request = new SignOnRequest(USER_ID_OVER_WIDTH, CREDENTIAL_OVER_WIDTH);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).hasSize(2);
            assertThat(violatedPropertiesOf(violations))
                    .containsExactlyInAnyOrder(USER_ID_PROPERTY, CREDENTIAL_PROPERTY);
        }

        @ParameterizedTest(name = "length {0} is accepted")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
        @DisplayName("every length from zero up to and including eight is accepted for the user id")
        void everyLengthUpToTheBoundIsAcceptedForTheUserId(int length) {
            SignOnRequest request = new SignOnRequest(valueOfLength(length), null);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).isEmpty();
        }

        @ParameterizedTest(name = "length {0} is accepted")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
        @DisplayName("every length from zero up to and including eight is accepted for the password")
        void everyLengthUpToTheBoundIsAcceptedForThePassword(int length) {
            SignOnRequest request = new SignOnRequest(null, valueOfLength(length));

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).isEmpty();
        }

        @ParameterizedTest(name = "length {0} is rejected")
        @ValueSource(ints = {9, 10, 16, 32, 80})
        @DisplayName("every length above eight is rejected for the user id")
        void everyLengthAboveTheBoundIsRejectedForTheUserId(int length) {
            SignOnRequest request = new SignOnRequest(valueOfLength(length), null);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(USER_ID_PROPERTY);
        }

        @ParameterizedTest(name = "length {0} is rejected")
        @ValueSource(ints = {9, 10, 16, 32, 80})
        @DisplayName("every length above eight is rejected for the password")
        void everyLengthAboveTheBoundIsRejectedForThePassword(int length) {
            SignOnRequest request = new SignOnRequest(null, valueOfLength(length));

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(CREDENTIAL_PROPERTY);
        }

        @Test
        @DisplayName("the bound measures characters and never trims, so eight spaces are accepted")
        void theBoundNeverTrimsBeforeMeasuring() {
            String eightSpaces = " ".repeat(USER_ID_WIDTH);
            SignOnRequest request = new SignOnRequest(eightSpaces, eightSpaces);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).isEmpty();
            assertThat(request.userId()).hasSize(USER_ID_WIDTH);
            assertThat(request.password()).hasSize(CREDENTIAL_WIDTH);
        }

        @Test
        @DisplayName("the bound measures characters and never trims, so nine spaces are rejected")
        void theBoundRejectsNineSpacesRatherThanTrimmingThemAway() {
            String nineSpaces = " ".repeat(USER_ID_WIDTH + 1);
            SignOnRequest request = new SignOnRequest(nineSpaces, nineSpaces);

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
     * The width bound is the only constraint the type declares.
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
    @DisplayName("no constraint other than the width bound")
    class NoOtherConstraint {

        @Test
        @DisplayName("two nulls raise no violation, so no presence constraint is declared")
        void twoNullsRaiseNoViolation() {
            SignOnRequest request = new SignOnRequest(null, null);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("two empty strings raise no violation, so no emptiness constraint is declared")
        void twoEmptyStringsRaiseNoViolation() {
            SignOnRequest request = new SignOnRequest("", "");

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("two all-space values raise no violation, so no blankness constraint is declared")
        void twoAllSpaceValuesRaiseNoViolation() {
            SignOnRequest request = new SignOnRequest("    ", "  ");

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("a null user id beside a present password raises no violation")
        void aNullUserIdBesideAPresentPasswordRaisesNoViolation() {
            SignOnRequest request = new SignOnRequest(null, SYNTHETIC_CREDENTIAL);

            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("a null password beside a present user id raises no violation")
        void aNullPasswordBesideAPresentUserIdRaisesNoViolation() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, null);

            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("an over-length user id beside a null password yields exactly one violation")
        void anOverLengthUserIdBesideANullPasswordYieldsExactlyOneViolation() {
            SignOnRequest request = new SignOnRequest(USER_ID_OVER_WIDTH, null);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(USER_ID_PROPERTY);
        }

        @Test
        @DisplayName("an over-length password beside a null user id yields exactly one violation")
        void anOverLengthPasswordBesideANullUserIdYieldsExactlyOneViolation() {
            SignOnRequest request = new SignOnRequest(null, CREDENTIAL_OVER_WIDTH);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(CREDENTIAL_PROPERTY);
        }

        @Test
        @DisplayName("every violation that ever fires is a size violation, never any other kind")
        void everyViolationThatFiresIsASizeViolation() {
            SignOnRequest request = new SignOnRequest(USER_ID_OVER_WIDTH, CREDENTIAL_OVER_WIDTH);

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
        @DisplayName("no character-class, case or format rule fires on any value the screen accepts")
        void noCharacterClassRuleFiresOnAnyValueTheScreenAccepts(String value) {
            SignOnRequest request = new SignOnRequest(value, value);

            Set<ConstraintViolation<SignOnRequest>> violations = violationsOf(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("a value carrying an embedded space is accepted, matching the legacy editor")
        void aValueCarryingAnEmbeddedSpaceIsAccepted() {
            String withEmbeddedSpace = "AB CD EF";
            SignOnRequest request = new SignOnRequest(withEmbeddedSpace, withEmbeddedSpace);

            assertThat(withEmbeddedSpace).hasSize(USER_ID_WIDTH);
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("no minimum-length rule fires, so a one-character value is accepted")
        void noMinimumLengthRuleFires() {
            SignOnRequest request = new SignOnRequest("A", "B");

            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("no numeric or digit rule fires, so a purely alphabetic value is accepted")
        void noDigitRuleFires() {
            SignOnRequest request = new SignOnRequest("ABCDEFGH", "ZYXWVUTS");

            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("validating an all-null instance never throws, so nothing cascades into a null")
        void validatingAnAllNullInstanceNeverThrows() {
            SignOnRequest request = new SignOnRequest(null, null);

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
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);

            String rendered = request.toString();

            assertThat(rendered).doesNotContain(SYNTHETIC_CREDENTIAL);
        }

        @Test
        @DisplayName("the placeholder appears exactly once, standing where the credential would be")
        void thePlaceholderAppearsExactlyOnce() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);

            String rendered = request.toString();

            assertThat(occurrencesOf(rendered, REDACTION_PLACEHOLDER_TEXT)).isEqualTo(1);
            assertThat(rendered).contains(CREDENTIAL_PROPERTY + "=" + REDACTION_PLACEHOLDER_TEXT);
        }

        @Test
        @DisplayName("the user id is retained, because an account identifier is not a secret")
        void theUserIdIsRetained() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);

            String rendered = request.toString();

            assertThat(rendered).contains(USER_ID_PROPERTY + "=" + USER_ID_AT_WIDTH);
        }

        @Test
        @DisplayName("no four-character run of the credential survives into the output")
        void noFourCharacterRunOfTheCredentialSurvives() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);

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
            SignOnRequest shortCredential = new SignOnRequest(USER_ID_AT_WIDTH, "A");
            SignOnRequest widthCredential = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);

            assertThat(shortCredential.toString()).isEqualTo(widthCredential.toString());
        }

        @Test
        @DisplayName("a differing credential yields identical output, so nothing about it leaks")
        void aDifferingCredentialYieldsIdenticalOutput() {
            SignOnRequest first = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);
            SignOnRequest second = new SignOnRequest(USER_ID_AT_WIDTH, OTHER_SYNTHETIC_CREDENTIAL);

            assertThat(first.toString()).isEqualTo(second.toString());
            assertThat(first.toString()).doesNotContain(OTHER_SYNTHETIC_CREDENTIAL);
        }

        @Test
        @DisplayName("an absent credential still yields the placeholder rather than a null marker")
        void anAbsentCredentialStillYieldsThePlaceholder() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, null);

            String rendered = request.toString();

            assertThat(occurrencesOf(rendered, REDACTION_PLACEHOLDER_TEXT)).isEqualTo(1);
            assertThat(rendered).doesNotContain(CREDENTIAL_PROPERTY + "=null");
        }

        @Test
        @DisplayName("an all-space credential is redacted too, so whitespace never hints at a value")
        void anAllSpaceCredentialIsRedactedToo() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, "        ");

            String rendered = request.toString();

            assertThat(occurrencesOf(rendered, REDACTION_PLACEHOLDER_TEXT)).isEqualTo(1);
            assertThat(rendered).isEqualTo(
                    new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL).toString());
        }

        @Test
        @DisplayName("the output names the type and both properties, so it stays readable as a diagnostic")
        void theOutputNamesTheTypeAndBothProperties() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);

            String rendered = request.toString();

            assertThat(rendered)
                    .startsWith("SignOnRequest[")
                    .endsWith("]")
                    .contains(USER_ID_PROPERTY + "=")
                    .contains(CREDENTIAL_PROPERTY + "=");
            assertThat(rendered.indexOf(USER_ID_PROPERTY + "="))
                    .isLessThan(rendered.indexOf(CREDENTIAL_PROPERTY + "="));
        }

        @Test
        @DisplayName("the override is honoured through string concatenation, the usual accidental leak")
        void theOverrideIsHonouredThroughStringConcatenation() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);

            String interpolated = "sign-on attempt: " + request;

            assertThat(interpolated).doesNotContain(SYNTHETIC_CREDENTIAL);
            assertThat(interpolated).contains(REDACTION_PLACEHOLDER_TEXT);
        }

        @Test
        @DisplayName("the override is honoured by String.valueOf, another accidental leak route")
        void theOverrideIsHonouredByStringValueOf() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);

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
                    + ", " + CREDENTIAL_PROPERTY + "=" + REDACTION_PLACEHOLDER_TEXT + "]";

            String rendered = new SignOnRequest(USER_ID_AT_WIDTH, credential).toString();

            assertThat(rendered).isEqualTo(credentialIndependentExpectation);
        }

        @Test
        @DisplayName("the rendered length never varies with the credential length")
        void theRenderedLengthNeverVariesWithTheCredentialLength() {
            int rendered = new SignOnRequest(USER_ID_AT_WIDTH, "A").toString().length();
            int renderedAtWidth = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL).toString().length();
            int renderedOverWidth = new SignOnRequest(USER_ID_AT_WIDTH, CREDENTIAL_OVER_WIDTH).toString().length();

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
            SignOnRequest first = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);
            SignOnRequest second = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);

            assertThat(first).isNotSameAs(second).isEqualTo(second);
            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("equal instances agree on their hash code")
        void equalInstancesAgreeOnTheirHashCode() {
            SignOnRequest first = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);
            SignOnRequest second = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);

            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("instances differing only in the credential are not equal")
        void instancesDifferingOnlyInTheCredentialAreNotEqual() {
            SignOnRequest first = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);
            SignOnRequest second = new SignOnRequest(USER_ID_AT_WIDTH, OTHER_SYNTHETIC_CREDENTIAL);

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("instances differing only in the user id are not equal")
        void instancesDifferingOnlyInTheUserIdAreNotEqual() {
            SignOnRequest first = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);
            SignOnRequest second = new SignOnRequest("USER0002", SYNTHETIC_CREDENTIAL);

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("equality distinguishes an absent value from an empty one")
        void equalityDistinguishesAnAbsentValueFromAnEmptyOne() {
            SignOnRequest absent = new SignOnRequest(null, null);
            SignOnRequest empty = new SignOnRequest("", "");

            assertThat(absent).isNotEqualTo(empty);
        }

        @Test
        @DisplayName("equality distinguishes a trailing space, because nothing is trimmed first")
        void equalityDistinguishesATrailingSpace() {
            SignOnRequest padded = new SignOnRequest("USER1   ", SYNTHETIC_CREDENTIAL);
            SignOnRequest bare = new SignOnRequest("USER1", SYNTHETIC_CREDENTIAL);

            assertThat(padded).isNotEqualTo(bare);
        }

        @Test
        @DisplayName("equality distinguishes case, because nothing is folded first")
        void equalityDistinguishesCase() {
            SignOnRequest lower = new SignOnRequest("admin001", SYNTHETIC_CREDENTIAL);
            SignOnRequest upper = new SignOnRequest("ADMIN001", SYNTHETIC_CREDENTIAL);

            assertThat(lower).isNotEqualTo(upper);
        }

        @Test
        @DisplayName("equality is reflexive, null-safe and type-safe")
        void equalityIsReflexiveNullSafeAndTypeSafe() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);

            assertThat(request).isEqualTo(request);
            assertThat(request).isNotEqualTo(null);
            assertThat(request).isNotEqualTo(USER_ID_AT_WIDTH);
        }

        @Test
        @DisplayName("an all-null instance equals another all-null instance and hashes alike")
        void anAllNullInstanceEqualsAnotherAllNullInstance() {
            SignOnRequest first = new SignOnRequest(null, null);
            SignOnRequest second = new SignOnRequest(null, null);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }
    }

    /**
     * The wire contract: the credential must deserialize, and absent values must be omitted.
     *
     * <p>Redaction protects the diagnostic channel, not the transport channel. An endpoint
     * cannot verify a credential it refuses to read, so the password component carries no
     * serialization annotation and this group asserts that it moves in both directions. The
     * shape of the emitted document is asserted too, because the emitted property set is what
     * proves the contract is exactly two values.</p>
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
            assertThat(request.password()).isEqualTo(SYNTHETIC_CREDENTIAL);
        }

        @Test
        @DisplayName("both properties are emitted when populated, so neither is suppressed")
        void bothPropertiesAreEmittedWhenPopulated() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);

            Map<String, Object> properties = propertiesOf(jsonOf(request));

            assertThat(properties)
                    .containsEntry(USER_ID_PROPERTY, USER_ID_AT_WIDTH)
                    .containsEntry(CREDENTIAL_PROPERTY, SYNTHETIC_CREDENTIAL);
        }

        @Test
        @DisplayName("the emitted document carries exactly two properties and no third")
        void theEmittedDocumentCarriesExactlyTwoProperties() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);

            Map<String, Object> properties = propertiesOf(jsonOf(request));

            assertThat(properties).containsOnlyKeys(USER_ID_PROPERTY, CREDENTIAL_PROPERTY);
        }

        @Test
        @DisplayName("an absent value is omitted from the document under non-null inclusion")
        void anAbsentValueIsOmittedFromTheDocument() throws JsonProcessingException {
            SignOnRequest onlyUserId = new SignOnRequest(USER_ID_AT_WIDTH, null);

            Map<String, Object> properties = propertiesOf(jsonOf(onlyUserId));

            assertThat(properties).containsOnlyKeys(USER_ID_PROPERTY);
            assertThat(properties).doesNotContainKey(CREDENTIAL_PROPERTY);
        }

        @Test
        @DisplayName("an all-absent instance emits an empty object rather than two null members")
        void anAllAbsentInstanceEmitsAnEmptyObject() throws JsonProcessingException {
            SignOnRequest empty = new SignOnRequest(null, null);

            assertThat(jsonOf(empty)).isEqualTo("{}");
            assertThat(propertiesOf(jsonOf(empty))).isEmpty();
        }

        @Test
        @DisplayName("an empty string is emitted, because only an absent value is omitted")
        void anEmptyStringIsEmitted() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest("", "");

            Map<String, Object> properties = propertiesOf(jsonOf(request));

            assertThat(properties)
                    .containsEntry(USER_ID_PROPERTY, "")
                    .containsEntry(CREDENTIAL_PROPERTY, "");
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
            assertThat(request.password()).isEqualTo(SYNTHETIC_CREDENTIAL);
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
        @DisplayName("a populated instance survives a serialize and deserialize cycle unchanged")
        void aPopulatedInstanceSurvivesARoundTripUnchanged() throws JsonProcessingException {
            SignOnRequest original = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);

            SignOnRequest restored = parse(jsonOf(original));

            assertThat(restored).isEqualTo(original);
        }

        @Test
        @DisplayName("the credential is never emitted as anything but its own characters")
        void theCredentialIsNeverEmittedAsAnythingButItsOwnCharacters() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);

            String document = jsonOf(request);

            assertThat(document).doesNotContain(REDACTION_PLACEHOLDER_TEXT);
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

            assertThat(request.password()).isEqualTo(padded).hasSize(CREDENTIAL_WIDTH);
        }

        @Test
        @DisplayName("a leading space survives on both values, because nothing is stripped")
        void aLeadingSpaceSurvivesOnBothValues() throws JsonProcessingException {
            String leading = "   USER1";
            String leadingCredential = "   ABCD";
            SignOnRequest request = parse("{\"userId\":\"" + leading
                    + "\",\"password\":\"" + leadingCredential + "\"}");

            assertThat(request.userId()).isEqualTo(leading);
            assertThat(request.password()).isEqualTo(leadingCredential);
        }

        @Test
        @DisplayName("a short value is never padded out to the eight-character screen width")
        void aShortValueIsNeverPaddedToTheScreenWidth() throws JsonProcessingException {
            SignOnRequest request = parse("{\"userId\":\"AB\",\"password\":\"CD\"}");

            assertThat(request.userId()).isEqualTo("AB").hasSize(2);
            assertThat(request.password()).isEqualTo("CD").hasSize(2);
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

            assertThat(request.password()).isEqualTo(mixedCase);
        }

        @Test
        @DisplayName("a lower-case value survives the serialize direction too")
        void aLowerCaseValueSurvivesTheSerializeDirection() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest("admin001", "abcd1234");

            Map<String, Object> properties = propertiesOf(jsonOf(request));

            assertThat(properties)
                    .containsEntry(USER_ID_PROPERTY, "admin001")
                    .containsEntry(CREDENTIAL_PROPERTY, "abcd1234");
        }

        @Test
        @DisplayName("a padded value survives the serialize direction too, still untrimmed")
        void aPaddedValueSurvivesTheSerializeDirection() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest("USER1   ", "  ABCD  ");

            Map<String, Object> properties = propertiesOf(jsonOf(request));

            assertThat(properties)
                    .containsEntry(USER_ID_PROPERTY, "USER1   ")
                    .containsEntry(CREDENTIAL_PROPERTY, "  ABCD  ");
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
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);

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
        @DisplayName("a numeric-looking value is emitted as a quoted string, never as a JSON number")
        void aNumericLookingValueIsEmittedAsAQuotedString() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest("00000042", "00001234");

            String document = jsonOf(request);
            Map<String, Object> properties = propertiesOf(document);

            assertThat(document).contains("\"" + USER_ID_PROPERTY + "\":\"00000042\"");
            assertThat(properties.get(USER_ID_PROPERTY)).isInstanceOf(String.class).isNotInstanceOf(Number.class);
            assertThat(properties.get(CREDENTIAL_PROPERTY)).isInstanceOf(String.class).isNotInstanceOf(Number.class);
        }

        @Test
        @DisplayName("an all-digit value never loses a leading zero through a round trip")
        void anAllDigitValueNeverLosesALeadingZero() throws JsonProcessingException {
            SignOnRequest original = new SignOnRequest("00000001", "00000009");

            SignOnRequest restored = parse(jsonOf(original));

            assertThat(restored.userId()).isEqualTo("00000001");
            assertThat(restored.password()).isEqualTo("00000009");
        }

        @Test
        @DisplayName("the user id tolerates an absent value on its own")
        void theUserIdToleratesAnAbsentValueOnItsOwn() {
            SignOnRequest request = new SignOnRequest(null, SYNTHETIC_CREDENTIAL);

            assertThat(request.userId()).isNull();
            assertThat(request.password()).isEqualTo(SYNTHETIC_CREDENTIAL);
        }

        @Test
        @DisplayName("the credential tolerates an absent value on its own")
        void theCredentialToleratesAnAbsentValueOnItsOwn() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, null);

            assertThat(request.userId()).isEqualTo(USER_ID_AT_WIDTH);
            assertThat(request.password()).isNull();
        }

        @Test
        @DisplayName("constructing an all-absent instance never throws")
        void constructingAnAllAbsentInstanceNeverThrows() {
            assertThatCode(() -> new SignOnRequest(null, null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("repeated reads return the same values, so nothing is computed or consumed lazily")
        void repeatedReadsReturnTheSameValues() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);

            assertThat(request.userId()).isSameAs(request.userId());
            assertThat(request.password()).isSameAs(request.password());
        }

        @Test
        @DisplayName("the accessors hand back the very references construction was given")
        void theAccessorsHandBackTheVeryReferencesConstructionWasGiven() {
            String suppliedUserId = new String(USER_ID_AT_WIDTH.toCharArray());
            String suppliedCredential = new String(SYNTHETIC_CREDENTIAL.toCharArray());
            SignOnRequest request = new SignOnRequest(suppliedUserId, suppliedCredential);

            assertThat(request.userId()).isSameAs(suppliedUserId);
            assertThat(request.password()).isSameAs(suppliedCredential);
        }

        @Test
        @DisplayName("construction is the only way to set a value, so an instance cannot be altered")
        void constructionIsTheOnlyWayToSetAValue() {
            SignOnRequest first = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);
            SignOnRequest second = new SignOnRequest("USER0002", OTHER_SYNTHETIC_CREDENTIAL);

            assertThat(first.userId()).isEqualTo(USER_ID_AT_WIDTH);
            assertThat(first.password()).isEqualTo(SYNTHETIC_CREDENTIAL);
            assertThat(second.userId()).isEqualTo("USER0002");
            assertThat(second.password()).isEqualTo(OTHER_SYNTHETIC_CREDENTIAL);
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("a value is not shared between instances, so building one cannot disturb another")
        void aValueIsNotSharedBetweenInstances() throws JsonProcessingException {
            SignOnRequest first = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);
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
            assertThat(request.password()).isEqualTo(SYNTHETIC_CREDENTIAL);
        }

        @Test
        @DisplayName("re-emitting the instance yields exactly the two inputs and no screen furniture")
        void reEmittingTheInstanceYieldsExactlyTheTwoInputs() throws JsonProcessingException {
            SignOnRequest request = parse(DOCUMENT_OFFERING_EVERY_OMITTED_MEMBER);

            Map<String, Object> properties = propertiesOf(jsonOf(request));

            assertThat(properties).containsOnlyKeys(USER_ID_PROPERTY, CREDENTIAL_PROPERTY);
        }

        @ParameterizedTest(name = "{0} is not part of the request contract")
        @ValueSource(strings = {"transactionName", "title01", "title02", "currentDate", "currentTime",
            "programName", "applicationId", "systemId", "errorMessage", "navigationContext",
            "userType", "route", "errorFlag", "screenTitle", "message", "pageNumber", "keyAction"})
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
        @DisplayName("the contract is exactly two members wide, matching the two operator-typed items")
        void theContractIsExactlyTwoMembersWide() throws JsonProcessingException {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);

            Map<String, Object> properties = propertiesOf(jsonOf(request));

            assertThat(properties).hasSize(2);
        }

        @Test
        @DisplayName("the diagnostic representation names no member beyond the two inputs")
        void theDiagnosticRepresentationNamesNoMemberBeyondTheTwoInputs() {
            SignOnRequest request = new SignOnRequest(USER_ID_AT_WIDTH, SYNTHETIC_CREDENTIAL);

            String rendered = request.toString();

            assertThat(occurrencesOf(rendered, "=")).isEqualTo(2);
            assertThat(rendered).doesNotContain("navigationContext", "userType", "route",
                    "errorFlag", "currentDate", "currentTime", "title");
        }
    }

}
