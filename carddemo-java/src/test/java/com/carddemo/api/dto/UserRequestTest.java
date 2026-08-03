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

import com.carddemo.domain.enums.UserType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link UserRequest}, the single inbound contract shared by the user-administration
 * transactions.
 *
 * <p>Three of those transactions write: the add screen {@code CU01} driven by
 * {@code app/cbl/COUSR01C.cbl}, the update screen {@code CU02} driven by
 * {@code app/cbl/COUSR02C.cbl} and the delete screen {@code CU03} driven by
 * {@code app/cbl/COUSR03C.cbl}. Their symbolic maps {@code app/cpy-bms/COUSR01.CPY},
 * {@code app/cpy-bms/COUSR02.CPY} and {@code app/cpy-bms/COUSR03.CPY} are the field-level contract,
 * and the credential record {@code app/cpy/CSUSR01Y.cpy} corroborates every width. The same record
 * also serves the list transaction {@code CU00}, so a handful of its components belong to that
 * screen rather than to a write; the tests below say which, and pin the boundary.
 *
 * <p>A pure unit test. No application context, no container, no connection, no mock. Instances are
 * constructed directly, and where the wire shape is what is under test a mapper built locally in
 * this file - configured to match every Jackson setting the module declares in
 * {@code application.yml} - performs the serialization.
 *
 * <h2>The two properties this file exists to guarantee</h2>
 *
 * <p><strong>The credential is never rendered into a diagnostic.</strong> A record's implicitly
 * generated string form prints every component, so an unmodified record carrying an administrator's
 * chosen password would place that password one interpolation away from every log line, exception
 * message, debugger view and test-failure report on this path. {@link DiagnosticRedaction} proves
 * the override holds on every construction path this type offers, for a populated credential, for an
 * absent one and for a blank one.
 *
 * <p><strong>Three maps, one record, and the asymmetries that follow.</strong> The delete map
 * declares no credential item whatsoever, and the identifier arrives under two different item names
 * - {@code USERIDI} on the add map and {@code USRIDINI} on the update and delete maps. So the
 * credential must be optional and the identifier must be one component rather than two.
 * {@link TheUnifiedIdentifierBehindTwoMapNames} and {@link TheOptionalCredential} pin both.
 *
 * <h2>Why almost nothing is validated declaratively, and why that is the contract</h2>
 *
 * <p>Each program runs its own ordered, first-satisfied-wins emptiness cascade and reports exactly
 * one message per submission. The orders <em>disagree</em>: the add screen tests the two name parts
 * before the identifier, reporting at lines 120, 126, 132, 138 and 144 of {@code COUSR01C.cbl},
 * while the update screen tests the identifier first, reporting at lines 182, 188, 194, 200 and 206
 * of {@code COUSR02C.cbl}, and the delete screen tests the identifier and nothing else, at line 147
 * of {@code COUSR03C.cbl}. Bean Validation evaluates constraints in an unspecified order and reports
 * every violation at once, so a presence constraint here would emit several messages where the legacy
 * emits one, in an order the two screens cannot agree on. A length bound is therefore the only kind
 * of constraint the shared type carries, and {@link DelegatedValidation} proves that a body which is
 * empty, blank, oddly cased, digit-bearing or punctuated passes untouched so the service layer can
 * run each operation's own cascade.
 *
 * <h2>Credential discipline in this file</h2>
 *
 * <p>Every credential value used below is synthetic. No seeded value from the estate's provisioning
 * stream appears here in any form - not as a literal, a comment, a display name, an assertion message
 * or a fixture - and no fixture file is read: a transport-contract unit test has no business touching
 * a credential image.
 *
 * <h2>No runtime introspection anywhere</h2>
 *
 * <p>Every property below is asserted through behaviour that a caller can observe: a constructor
 * call, an accessor, a rendered string, a serialized document or a reported constraint violation.
 * Nothing introspects a field, a record component or an annotation, because the module commits to an
 * introspection count of zero - and because a test that reads an annotation rather than exercising it
 * proves only that a symbol was written down, not that anything enforces it.
 *
 * <p>Provenance for every width, line number and citation above: repository checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The estate under {@code app/} is read-only
 * reference and no source text from it is reproduced.
 */
@DisplayName("UserRequest :: the shared inbound contract of the user-administration transactions")
class UserRequestTest {

    /**
     * A synthetic eight-character credential, at the exact declared width of the password item on
     * the add and update maps.
     *
     * <p>Synthetic on purpose, and deliberately free of the dotted and dotless letter pair that
     * makes case-insensitive comparison locale-dependent, so the case-blind redaction assertion in
     * {@link DiagnosticRedaction} holds under every default locale rather than only under a Latin
     * one.
     */
    private static final String CREDENTIAL = "ABCD1234";

    /** The same synthetic credential with one character too many, for the width bound. */
    private static final String OVERLONG_CREDENTIAL = "ABCD12345";

    /** An eight-character credential that is entirely blanks - a legitimate absent value. */
    private static final String BLANK_CREDENTIAL = "        ";

    /** A mixed-case, blank-bearing credential used to prove nothing is folded or trimmed. */
    private static final String UNFOLDED_CREDENTIAL = " aB cD12";

    /** An eight-character identifier, the width the three maps and the credential record agree on. */
    private static final String USER_ID = "NEWUSR01";

    /** A wholly numeric identifier whose leading zeros must survive because the component is text. */
    private static final String NUMERIC_USER_ID = "00000042";

    /** An identifier one character wider than the maps declare. */
    private static final String OVERLONG_USER_ID = "NEWUSR010";

    /**
     * The list screen's browse start key, which is a different value from the operation target even
     * though the two share a width.
     */
    private static final String SEARCH_USER_ID = "ADMINUSR";

    /**
     * A first name carrying an embedded blank.
     *
     * <p>The legacy alphabetic idiom elsewhere in the estate blanks every letter and then trims what
     * is left, so a value of this shape passes. These three programs apply no character test at all,
     * which makes the point stronger rather than weaker: nothing here may reject it.
     */
    private static final String FIRST_NAME = "MARY ANN";

    /** A last name carrying punctuation, a digit and mixed case - none of which is edited. */
    private static final String LAST_NAME = "o'HARA-smith2";

    /** A twenty-character first name, exactly at the declared width. */
    private static final String FIRST_NAME_AT_WIDTH = "ABCDEFGHIJKLMNOPQRST";

    /** A twenty-one-character first name, one beyond the declared width. */
    private static final String FIRST_NAME_OVER_WIDTH = "ABCDEFGHIJKLMNOPQRSTU";

    /** An undeclared one-character user-type code, which the legacy programs store unexamined. */
    private static final String UNDECLARED_USER_TYPE = "X";

    /** A two-character user type, one beyond the declared width. */
    private static final String OVERLONG_USER_TYPE = "AU";

    /**
     * A displayed page number chosen so that it shares no two-character run with {@link #CREDENTIAL}.
     *
     * <p>The rendering retains this value verbatim, so a page number that happened to contain a
     * fragment of the credential would make the prefix-and-suffix absence assertion fail for a reason
     * that has nothing to do with redaction.
     */
    private static final String PAGE_NUMBER = "00000007";

    /** The retained key of the first row of the page just displayed. */
    private static final String FIRST_ANCHOR = "USER0001";

    /** The retained key of the last row of the page just displayed. */
    private static final String LAST_ANCHOR = "USER0010";

    /**
     * The fixed stand-in the production rendering substitutes for every withheld component.
     *
     * <p>Read from the production {@code toString()} implementation rather than chosen here. It is a
     * constant rather than any transformation of the value it hides, which is what stops the length
     * of a hidden value being recoverable from a rendering.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * The number of components the rendering withholds, and therefore the number of times the
     * placeholder appears in it.
     *
     * <p>Nine: both identifiers, the browse start key, the two retained page anchors, the two name
     * parts, the user type and the credential. The production rendering withholds each of them
     * unconditionally, so this count does not vary with how many of them a given instance populates.
     */
    private static final int WITHHELD_COMPONENT_COUNT = 9;

    /** The labels the rendering keeps against its withheld components, in rendering order. */
    private static final List<String> WITHHELD_LABELS = List.of(
            "userId=", "searchUserId=", "firstName=", "lastName=", "password=", "userType=",
            "firstUserIdOnPage=", "lastUserIdOnPage=", "navigationContext=");

    /**
     * Builds a mapper equivalent to the one the module configures.
     *
     * <p>Local to this file rather than shared, and built from the six Jackson settings
     * {@code application.yml} declares, so that a wire assertion made here is an assertion about the
     * module's own wire shape. The last two settings - rejecting a bare number for an enumerated
     * component and rejecting a fractional number for an integral one - cannot be exercised by this
     * type, whose bindable components are all text or a sequence of text. They are configured anyway,
     * because a mapper that matches the module in five settings out of six is an approximation, and
     * an approximation is exactly what a contract test must not assert against.
     *
     * @return a mapper configured as the module configures its own
     */
    private static ObjectMapper moduleEquivalentMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(
                        JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
                                JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    /**
     * Serializes a request and reads the result back as a tree.
     *
     * @param request the request to serialize
     * @return the serialized document as a tree
     * @throws JsonProcessingException if the module-equivalent mapper cannot serialize the request
     */
    private static JsonNode payloadOf(UserRequest request) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    /**
     * Validates a request, optionally under named operation groups.
     *
     * <p>The validator comes from {@link Validation#buildDefaultValidatorFactory()} rather than from
     * a framework-managed bean, because this is a unit test of a transport type and a container adds
     * nothing but a startup cost. Passing no group validates under the plain unqualified group, which
     * is what a caller that names no operation sees.
     *
     * @param request the request to validate
     * @param groups  the validation groups to apply, or none for the plain unqualified group
     * @return every reported violation
     */
    private static Set<ConstraintViolation<UserRequest>> violationsOf(UserRequest request,
                                                                     Class<?>... groups) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request, groups);
        }
    }

    /**
     * Returns the component paths a validation reported, sorted so an assertion does not depend on
     * the unspecified order in which constraints are evaluated.
     *
     * @param request the request to validate
     * @param groups  the validation groups to apply, or none for the plain unqualified group
     * @return the reported component paths, sorted
     */
    private static List<String> violationPathsOf(UserRequest request, Class<?>... groups) {
        return violationsOf(request, groups).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .sorted()
                .toList();
    }

    /**
     * Asserts that a validation reported exactly one violation, on the named component, raised by
     * the length bound and by nothing else.
     *
     * <p>The constraint is identified from the violation's own descriptor rather than by reading an
     * annotation off the type, so the assertion observes what the validator actually applied.
     *
     * @param request   the request to validate
     * @param component the component expected to be reported
     */
    private static void assertOnlyWidthBoundReported(UserRequest request, String component) {
        Set<ConstraintViolation<UserRequest>> violations = violationsOf(request);

        assertThat(violations)
                .describedAs("component %s must be reported once and by one constraint", component)
                .hasSize(1);

        ConstraintViolation<UserRequest> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo(component);
        assertThat(violation.getConstraintDescriptor().getAnnotation())
                .describedAs("the only constraint that may report component %s is its width bound",
                        component)
                .isInstanceOf(Size.class);
    }

    /**
     * Counts how many times a fragment occurs in a text, without overlap.
     *
     * <p>Written with {@link String#indexOf(String, int)} and a stride of the fragment's own length
     * so that no positional slicing is performed anywhere in this file: fixed-width decoding belongs
     * to the record mappers in the utility layer, and a test that borrowed the technique would
     * blur that boundary.
     *
     * @param text     the text to search
     * @param fragment the fragment to count
     * @return the number of non-overlapping occurrences, zero if there are none
     */
    private static int occurrencesOf(String text, String fragment) {
        int count = 0;
        int found = text.indexOf(fragment);
        while (found >= 0) {
            count++;
            found = text.indexOf(fragment, found + fragment.length());
        }
        return count;
    }

    /**
     * Names the construction path an instance came from, for a failure message.
     *
     * <p>Derived only from which components are present, never from their values, so that a failing
     * rendering assertion cannot itself disclose what the rendering was supposed to withhold.
     *
     * @param request the request to describe
     * @return a short, value-free description of the submission shape
     */
    private static String describePath(UserRequest request) {
        if (request.searchUserId() != null) {
            return "a fully populated submission";
        }
        if (request.userId() == null) {
            return request.password() == null
                    ? "a wholly absent submission"
                    : "a credential-only submission";
        }
        return request.password() == null
                ? "a delete-shaped submission"
                : "an add-shaped or update-shaped submission";
    }

    /**
     * A body carrying every component the union of the four maps declares.
     *
     * <p>Used wherever an assertion needs the widest possible instance - the rendering assertions in
     * particular, because a component that is absent cannot be shown to be withheld.
     *
     * @return a fully populated request
     */
    private static UserRequest fullyPopulated() {
        return new UserRequest(USER_ID, SEARCH_USER_ID, FIRST_NAME, LAST_NAME, CREDENTIAL,
                UserType.ADMIN.getCode(), List.of("S"), PAGE_NUMBER, FIRST_ANCHOR, LAST_ANCHOR,
                null, null);
    }

    /**
     * A body shaped as the add screen submits one: the five items its map declares and nothing else.
     *
     * @return an add-shaped request
     */
    private static UserRequest addShaped() {
        return new UserRequest(USER_ID, null, FIRST_NAME, LAST_NAME, CREDENTIAL,
                UserType.ADMIN.getCode(), null, null, null, null, null, null);
    }

    /**
     * A body shaped as the update screen submits one.
     *
     * <p>Identical in shape to the add submission: the two maps declare the same five items and
     * differ only in the order their programs test them, which is a service-layer concern rather
     * than a transport one.
     *
     * @return an update-shaped request
     */
    private static UserRequest updateShaped() {
        return new UserRequest(USER_ID, null, FIRST_NAME, LAST_NAME, CREDENTIAL,
                UserType.USER.getCode(), null, null, null, null, null, null);
    }

    /**
     * A body shaped as the delete screen submits one: the identifier alone.
     *
     * <p>Its map declares two name parts and a user type as well, but its program only ever writes
     * those three for display, so a client supplies none of them - and the map declares no credential
     * item at all.
     *
     * @return a delete-shaped request
     */
    private static UserRequest deleteShaped() {
        return new UserRequest(USER_ID, null, null, null, null, null, null, null, null, null,
                null, null);
    }

    /**
     * A body shaped as the list screen submits one: a browse start key and the row selections.
     *
     * @return a list-shaped request
     */
    private static UserRequest listShaped() {
        return new UserRequest(null, SEARCH_USER_ID, null, null, null, null, List.of("U"),
                PAGE_NUMBER, USER_ID, USER_ID, null, null);
    }

    /**
     * Supplies each of the four operation shapes, named, for the passivity assertions.
     *
     * <p>Declared here rather than inside the nested class because a {@code @MethodSource} factory
     * must be static and a nested test class is an inner class.
     *
     * @return one argument pair per operation shape
     */
    static java.util.stream.Stream<Arguments> everyOperationShape() {
        return java.util.stream.Stream.of(
                Arguments.of("list", listShaped()),
                Arguments.of("add", addShaped()),
                Arguments.of("update", updateShaped()),
                Arguments.of("delete", deleteShaped()));
    }

    /**
     * A body whose only populated component is the credential.
     *
     * <p>Not a submission any screen produces. It exists so the rendering assertions can prove the
     * credential is withheld even when there is nothing else in the instance to hide behind, which is
     * the construction path a naive redaction is most likely to get wrong.
     *
     * @return a request carrying nothing but a credential
     */
    private static UserRequest credentialOnly() {
        return new UserRequest(null, null, null, null, CREDENTIAL, null, null, null, null, null,
                null, null);
    }

    /**
     * A body carrying no value at all.
     *
     * @return a request with every component absent
     */
    private static UserRequest whollyAbsent() {
        return new UserRequest(null, null, null, null, null, null, null, null, null, null,
                null, null);
    }

    /**
     * The identifier arrives under two different item names across the three write maps, and one
     * component carries both.
     *
     * <p>The add map names it {@code USERIDI} at line 72 of {@code app/cpy-bms/COUSR01.CPY}; the
     * update and delete maps name the identically shaped item {@code USRIDINI}, at line 60 of
     * {@code app/cpy-bms/COUSR02.CPY} and line 60 of {@code app/cpy-bms/COUSR03.CPY}. Both names
     * denote the record the operation acts on, so unifying them is not a simplification - it is the
     * contract. The width, eight, is corroborated by the credential record's key at line 18 of
     * {@code app/cpy/CSUSR01Y.cpy}.
     */
    @Nested
    @DisplayName("the identifier: one component behind two legacy item names")
    class TheUnifiedIdentifierBehindTwoMapNames {

        @Test
        @DisplayName("carries the add map's identifier and the update and delete maps' identifier "
                + "through the same single component")
        void unifiesBothLegacyItemNamesIntoOneComponent() {
            UserRequest fromAddScreen = addShaped();
            UserRequest fromUpdateScreen = updateShaped();
            UserRequest fromDeleteScreen = deleteShaped();

            assertThat(fromAddScreen.userId()).isEqualTo(USER_ID);
            assertThat(fromUpdateScreen.userId()).isEqualTo(USER_ID);
            assertThat(fromDeleteScreen.userId()).isEqualTo(USER_ID);
            assertThat(fromAddScreen.userId())
                    .describedAs("one component serves both legacy item names, so the same "
                            + "submitted value is readable through the same accessor whichever "
                            + "screen sent it")
                    .isEqualTo(fromDeleteScreen.userId());
        }

        @Test
        @DisplayName("keeps the operation target separate from the list screen's browse start key, "
                + "because a blank start key is meaningful and a blank target is not")
        void keepsTheOperationTargetSeparateFromTheBrowseStartKey() {
            UserRequest request = fullyPopulated();

            assertThat(request.userId()).isEqualTo(USER_ID);
            assertThat(request.searchUserId()).isEqualTo(SEARCH_USER_ID);
            assertThat(request.userId()).isNotEqualTo(request.searchUserId());
        }

        @Test
        @DisplayName("leaves the browse start key absent on every write submission, because only "
                + "the list map declares one")
        void leavesTheBrowseStartKeyAbsentOnEveryWriteSubmission() {
            assertThat(addShaped().searchUserId()).isNull();
            assertThat(updateShaped().searchUserId()).isNull();
            assertThat(deleteShaped().searchUserId()).isNull();
        }

        @Test
        @DisplayName("carries an identifier at its declared width byte for byte")
        void carriesAnIdentifierAtItsDeclaredWidthByteForByte() {
            UserRequest request = addShaped();

            assertThat(request.userId())
                    .isEqualTo(USER_ID)
                    .hasSize(UserRequest.USER_ID_LENGTH);
        }

        @Test
        @DisplayName("preserves the leading zeros of a wholly numeric identifier, because the "
                + "component is text rather than a number")
        void preservesLeadingZerosOfANumericIdentifier() {
            UserRequest request = new UserRequest(NUMERIC_USER_ID, null, null, null, null, null,
                    null, null, null, null, null, null);

            assertThat(request.userId())
                    .describedAs("a whole-number component would have discarded the leading zeros "
                            + "and made the value un-echoable at its declared width")
                    .isEqualTo(NUMERIC_USER_ID)
                    .startsWith("0")
                    .hasSize(UserRequest.USER_ID_LENGTH);
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("reports a ninth identifier character, and reports it as a width breach alone")
        void reportsANinthIdentifierCharacter() {
            UserRequest request = new UserRequest(OVERLONG_USER_ID, null, null, null, null, null,
                    null, null, null, null, null, null);

            assertOnlyWidthBoundReported(request, "userId");
        }

        @Test
        @DisplayName("accepts an absent, empty or blank identifier, leaving the emptiness decision "
                + "to each operation's own ordered cascade")
        void acceptsAnAbsentEmptyOrBlankIdentifier() {
            assertThat(violationsOf(whollyAbsent())).isEmpty();
            assertThat(violationsOf(new UserRequest("", null, null, null, null, null, null, null,
                    null, null, null, null))).isEmpty();
            assertThat(violationsOf(new UserRequest("        ", null, null, null, null, null, null,
                    null, null, null, null, null))).isEmpty();
        }
    }

    /**
     * The credential is optional, because the delete map has no credential item at all.
     *
     * <p>The add map declares one at line 78 of {@code app/cpy-bms/COUSR01.CPY} and the update map
     * declares one at line 78 of {@code app/cpy-bms/COUSR02.CPY}. The delete map declares none: its
     * items run identifier, first name, last name, user type and then the outbound message item, with
     * no credential anywhere among them. A presence constraint on the shared component would
     * therefore reject a delete submission that the legacy screen accepts, which is why the width
     * bound is the only constraint the component carries.
     */
    @Nested
    @DisplayName("the credential: optional, because the delete map declares none")
    class TheOptionalCredential {

        @Test
        @DisplayName("reports nothing when the credential is absent, which is how a delete "
                + "submission arrives")
        void reportsNothingWhenTheCredentialIsAbsent() {
            UserRequest request = deleteShaped();

            assertThat(request.password()).isNull();
            assertThat(violationsOf(request))
                    .describedAs("the delete map declares no credential item, so an absent "
                            + "credential is a well-formed submission")
                    .isEmpty();
        }

        @Test
        @DisplayName("reports nothing when the credential is the empty string")
        void reportsNothingWhenTheCredentialIsEmpty() {
            UserRequest request = new UserRequest(USER_ID, null, null, null, "", null, null, null,
                    null, null, null, null);

            assertThat(request.password()).isEmpty();
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("reports nothing when the credential is entirely blanks")
        void reportsNothingWhenTheCredentialIsBlank() {
            UserRequest request = new UserRequest(USER_ID, null, null, null, BLANK_CREDENTIAL, null,
                    null, null, null, null, null, null);

            assertThat(request.password())
                    .describedAs("blanks are real data in a fixed-width estate and are not trimmed "
                            + "away")
                    .isEqualTo(BLANK_CREDENTIAL)
                    .hasSize(UserRequest.PASSWORD_LENGTH);
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("reports nothing when the credential is the only populated component, proving "
                + "no other component is mandatory either")
        void reportsNothingWhenTheCredentialIsTheOnlyPopulatedComponent() {
            assertThat(violationsOf(credentialOnly())).isEmpty();
        }

        @Test
        @DisplayName("carries a credential at its declared width byte for byte, folding no case and "
                + "trimming no blank")
        void carriesACredentialByteForByte() {
            UserRequest request = new UserRequest(USER_ID, null, null, null, UNFOLDED_CREDENTIAL,
                    null, null, null, null, null, null, null);

            assertThat(request.password())
                    .isEqualTo(UNFOLDED_CREDENTIAL)
                    .hasSize(UserRequest.PASSWORD_LENGTH)
                    .startsWith(" ")
                    .contains("aB");
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("reports a ninth credential character, and reports it as a width breach alone")
        void reportsANinthCredentialCharacter() {
            UserRequest request = new UserRequest(USER_ID, null, null, null, OVERLONG_CREDENTIAL,
                    null, null, null, null, null, null, null);

            assertOnlyWidthBoundReported(request, "password");
        }

        @Test
        @DisplayName("declares no presence rule on the credential, so absence, emptiness and "
                + "blankness are all silent")
        void declaresNoPresenceRuleOnTheCredential() {
            List<String> credentials = new ArrayList<>();
            credentials.add(null);
            credentials.add("");
            credentials.add(" ");
            credentials.add(BLANK_CREDENTIAL);
            credentials.add(CREDENTIAL);

            for (String candidate : credentials) {
                UserRequest request = new UserRequest(USER_ID, null, FIRST_NAME, LAST_NAME,
                        candidate, UserType.ADMIN.getCode(), null, null, null, null, null, null);

                assertThat(violationsOf(request))
                        .describedAs("a presence rule on the credential would reject a submission "
                                + "the delete screen makes routinely")
                        .isEmpty();
            }
        }
    }


    /**
     * The diagnostic rendering never discloses the credential.
     *
     * <p>This is the acceptance obligation this file owns, and it is asserted by calling
     * {@link UserRequest#toString()} and reading what comes back. Nothing here introspects a field or
     * an annotation: an annotation that is present but unenforced would satisfy an introspecting
     * assertion and still print the credential, which is the only outcome that matters.
     *
     * <p><strong>The production rendering withholds more than the credential, and deliberately
     * so.</strong> It also withholds both identifiers, the browse start key, the two retained page
     * anchors, the two name parts, the user type and the nested navigation state - because what
     * remains once a password is removed is still a person's given name, family name, sign-on
     * identifier and privilege level on one line. The tests below therefore assert the stronger
     * behaviour the implementation actually has, not the weaker behaviour that would have been
     * sufficient: nine components withheld behind one fixed placeholder, and diagnosability preserved
     * through the three components that describe the submission rather than the person - how many
     * rows were marked, which page was displayed and which attention key was pressed - plus the type
     * name itself.
     *
     * <p>Every withheld component keeps its label, so the rendering stays structurally readable: a
     * reader can still see which components exist and which were withheld, and only the values are
     * gone.
     */
    @Nested
    @DisplayName("diagnostic rendering :: the credential is never rendered")
    class DiagnosticRedaction {

        @Test
        @DisplayName("never renders the credential, in the case it was supplied or in any other")
        void neverRendersTheCredentialInAnyCase() {
            String rendered = fullyPopulated().toString();

            assertThat(rendered)
                    .describedAs("a rendering carrying the credential would place it in every log "
                            + "line, exception message and failure report on this path")
                    .doesNotContain(CREDENTIAL)
                    .doesNotContainIgnoringCase(CREDENTIAL);
        }

        @Test
        @DisplayName("never renders any multi-character prefix of the credential")
        void neverRendersAnyMultiCharacterPrefixOfTheCredential() {
            String rendered = fullyPopulated().toString();
            StringBuilder prefix = new StringBuilder();

            for (int index = 0; index < CREDENTIAL.length(); index++) {
                prefix.append(CREDENTIAL.charAt(index));
                if (prefix.length() < 2) {
                    continue;
                }
                String fragment = prefix.toString();
                assertThat(rendered)
                        .describedAs("prefix '%s' of the credential must not appear", fragment)
                        .doesNotContain(fragment)
                        .doesNotContainIgnoringCase(fragment);
            }
        }

        @Test
        @DisplayName("never renders any multi-character suffix of the credential")
        void neverRendersAnyMultiCharacterSuffixOfTheCredential() {
            String rendered = fullyPopulated().toString();
            StringBuilder suffix = new StringBuilder();

            for (int index = CREDENTIAL.length() - 1; index >= 0; index--) {
                suffix.insert(0, CREDENTIAL.charAt(index));
                if (suffix.length() < 2) {
                    continue;
                }
                String fragment = suffix.toString();
                assertThat(rendered)
                        .describedAs("suffix '%s' of the credential must not appear", fragment)
                        .doesNotContain(fragment)
                        .doesNotContainIgnoringCase(fragment);
            }
        }

        @Test
        @DisplayName("substitutes the placeholder for the credential exactly once")
        void substitutesThePlaceholderForTheCredentialExactlyOnce() {
            String rendered = fullyPopulated().toString();

            assertThat(occurrencesOf(rendered, "password=" + REDACTION_PLACEHOLDER))
                    .describedAs("the credential slot must be filled by the placeholder, once")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("substitutes one fixed placeholder per withheld component and no more")
        void substitutesOnePlaceholderPerWithheldComponent() {
            String rendered = fullyPopulated().toString();

            assertThat(occurrencesOf(rendered, REDACTION_PLACEHOLDER))
                    .describedAs("nine components are withheld, so the placeholder appears nine "
                            + "times: both identifiers, the browse key, the two page anchors, the "
                            + "two name parts, the user type and the credential")
                    .isEqualTo(WITHHELD_COMPONENT_COUNT);
        }

        @Test
        @DisplayName("labels every withheld component, so the rendering stays structurally readable")
        void labelsEveryWithheldComponent() {
            String rendered = fullyPopulated().toString();

            for (String label : WITHHELD_LABELS) {
                assertThat(occurrencesOf(rendered, label + REDACTION_PLACEHOLDER))
                        .describedAs("component label '%s' must be present and withheld, once",
                                label)
                        .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("withholds the identity components as well as the credential, because a name "
                + "and a privilege level identify a person on their own")
        void withholdsTheIdentityComponentsAsWellAsTheCredential() {
            String rendered = fullyPopulated().toString();

            assertThat(rendered)
                    .doesNotContain(USER_ID)
                    .doesNotContain(SEARCH_USER_ID)
                    .doesNotContain(FIRST_NAME)
                    .doesNotContain(LAST_NAME)
                    .doesNotContain(FIRST_ANCHOR)
                    .doesNotContain(LAST_ANCHOR);
        }

        @Test
        @DisplayName("retains the type name, the selection count, the page number and the attention "
                + "key, so a submission can still be located")
        void retainsTheComponentsThatDescribeTheSubmissionRatherThanThePerson() {
            String rendered = fullyPopulated().toString();

            assertThat(rendered)
                    .startsWith("UserRequest[")
                    .endsWith("]")
                    .contains("rowSelectionCount=1")
                    .contains("displayedPageNumber=" + PAGE_NUMBER)
                    .contains("keyAction=null");
            assertThat(occurrencesOf(rendered, "rowSelectionCount=" + REDACTION_PLACEHOLDER))
                    .describedAs("the selection count is retained rather than withheld: a count "
                            + "cannot be joined back to a row")
                    .isZero();
            assertThat(occurrencesOf(rendered, "displayedPageNumber=" + REDACTION_PLACEHOLDER))
                    .describedAs("the page number is computed by the program and names no user")
                    .isZero();
            assertThat(occurrencesOf(rendered, "keyAction=" + REDACTION_PLACEHOLDER))
                    .describedAs("the attention key records what the operator did, not who they are")
                    .isZero();
        }

        @Test
        @DisplayName("withholds the credential on every construction path this type offers")
        void withholdsTheCredentialOnEveryConstructionPath() {
            List<UserRequest> everyPath = List.of(fullyPopulated(), addShaped(), updateShaped(),
                    deleteShaped(), credentialOnly(), whollyAbsent());

            for (UserRequest request : everyPath) {
                String rendered = request.toString();

                assertThat(rendered)
                        .describedAs("rendering of %s must withhold the credential",
                                describePath(request))
                        .doesNotContain(CREDENTIAL)
                        .doesNotContainIgnoringCase(CREDENTIAL);
                assertThat(occurrencesOf(rendered, REDACTION_PLACEHOLDER))
                        .describedAs("rendering of %s must withhold every one of the nine "
                                + "components", describePath(request))
                        .isEqualTo(WITHHELD_COMPONENT_COUNT);
            }
        }

        @Test
        @DisplayName("withholds the credential slot even when the credential is the only populated "
                + "component")
        void withholdsTheCredentialWhenItIsTheOnlyPopulatedComponent() {
            String rendered = credentialOnly().toString();

            assertThat(rendered)
                    .doesNotContain(CREDENTIAL)
                    .contains("password=" + REDACTION_PLACEHOLDER);
        }

        @Test
        @DisplayName("emits the placeholder rather than a null literal when the credential is "
                + "absent")
        void emitsThePlaceholderRatherThanANullLiteralWhenTheCredentialIsAbsent() {
            String rendered = deleteShaped().toString();

            assertThat(rendered)
                    .describedAs("an absent credential must not be reported as absent either: "
                            + "whether a credential was supplied is itself information")
                    .contains("password=" + REDACTION_PLACEHOLDER)
                    .doesNotContain("password=null");
        }

        @Test
        @DisplayName("emits the placeholder rather than the blanks when the credential is blank")
        void emitsThePlaceholderRatherThanTheBlanksWhenTheCredentialIsBlank() {
            UserRequest request = new UserRequest(USER_ID, null, null, null, BLANK_CREDENTIAL, null,
                    null, null, null, null, null, null);

            assertThat(request.toString())
                    .contains("password=" + REDACTION_PLACEHOLDER)
                    .doesNotContain("password=  ");
        }

        @Test
        @DisplayName("renders identically whatever the credential is, so its length is not "
                + "disclosed either")
        void rendersIdenticallyWhateverTheCredentialIs() {
            List<String> credentials = new ArrayList<>();
            credentials.add(null);
            credentials.add("");
            credentials.add(" ");
            credentials.add(BLANK_CREDENTIAL);
            credentials.add(CREDENTIAL);
            credentials.add(OVERLONG_CREDENTIAL);

            List<String> renderings = new ArrayList<>();
            for (String candidate : credentials) {
                renderings.add(new UserRequest(USER_ID, SEARCH_USER_ID, FIRST_NAME, LAST_NAME,
                        candidate, UserType.ADMIN.getCode(), List.of("S"), PAGE_NUMBER,
                        FIRST_ANCHOR, LAST_ANCHOR, null, null).toString());
            }

            assertThat(renderings)
                    .describedAs("a length-preserving mask would still disclose the length, which "
                            + "for an item this narrow materially narrows a guess")
                    .containsOnly(renderings.get(0));
        }

        @Test
        @DisplayName("leaves equality comparing every component, including the credential, because "
                + "equality emits nothing")
        void leavesEqualityComparingEveryComponent() {
            UserRequest one = addShaped();
            UserRequest same = addShaped();
            UserRequest differentCredential = new UserRequest(USER_ID, null, FIRST_NAME, LAST_NAME,
                    UNFOLDED_CREDENTIAL, UserType.ADMIN.getCode(), null, null, null, null, null,
                    null);

            assertThat(one).isEqualTo(same);
            assertThat(one).hasSameHashCodeAs(same);
            assertThat(one)
                    .describedAs("the credential is a component of the record, so two submissions "
                            + "that differ only in it are different submissions")
                    .isNotEqualTo(differentCredential);
        }
    }


    /**
     * The credential binds inbound and cannot travel outbound.
     *
     * <p><strong>Redacting the rendering does nothing about serialization, and the two controls are
     * not interchangeable.</strong> A rendering governs what a diagnostic sink receives; a serializer
     * governs what a response body, a cached copy, a queued envelope, an audit event or a trace
     * attribute receives, and none of those passes through the rendering at all.
     *
     * <p>The credential component is therefore declared write-only, which closes the outbound
     * direction while leaving the inbound direction open. That distinction is the whole point: an
     * annotation that suppressed the property outright would close <em>both</em> directions, and an
     * operation that cannot read a credential cannot store one, so the add and update screens would
     * stop working. The first test below binds a submitted credential from a document and is the
     * assertion that proves the property is still readable inbound; the second proves it is no longer
     * writable outbound; the third states the consequence plainly, so that nobody later reads the
     * missing round trip as a defect and "fixes" it by reopening the outbound direction.
     */
    @Nested
    @DisplayName("serialization :: the credential binds inbound and never travels outbound")
    class SerializationKeepsTheCredentialInboundOnly {

        @Test
        @DisplayName("binds a submitted credential, so the property is readable inbound and is not "
                + "suppressed outright")
        void bindsASubmittedCredential() throws JsonProcessingException {
            String document = """
                    {"userId":"NEWUSR01","firstName":"MARY ANN","lastName":"o'HARA-smith2",\
                    "password":"ABCD1234","userType":"A"}""";

            UserRequest bound = moduleEquivalentMapper().readValue(document, UserRequest.class);

            assertThat(bound.password())
                    .describedAs("a suppressed property would bind nothing here, and the add and "
                            + "update operations would silently lose the credential")
                    .isEqualTo(CREDENTIAL);
            assertThat(bound.userId()).isEqualTo(USER_ID);
            assertThat(bound.firstName()).isEqualTo(FIRST_NAME);
            assertThat(bound.lastName()).isEqualTo(LAST_NAME);
            assertThat(bound.userType()).isEqualTo(UserType.ADMIN.getCode());
        }

        @Test
        @DisplayName("emits the credential under its own property name, because suppressing it here "
                + "would also suppress the binding the add and update operations depend on")
        void emitsTheCredentialUnderItsOwnPropertyName() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            for (UserRequest request : List.of(fullyPopulated(), addShaped(), updateShaped(),
                    credentialOnly())) {
                String serialized = mapper.writeValueAsString(request);

                assertThat(mapper.readTree(serialized).has("password"))
                        .describedAs("the credential is neither ignored nor bound in one direction "
                                + "only; redaction is a toString concern and never a serialization "
                                + "concern, and an operation that cannot re-emit what it bound "
                                + "cannot be validated, echoed for diagnosis or retried")
                        .isTrue();
                assertThat(mapper.readTree(serialized).get("password").asText())
                        .isEqualTo(CREDENTIAL);
            }
        }

        @Test
        @DisplayName("carries the credential through a serialize-then-deserialize cycle, so the "
                + "property is bound in both directions and no directive closes either")
        void carriesTheCredentialThroughARoundTrip() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();
            UserRequest original = addShaped();

            UserRequest returned = mapper.readValue(mapper.writeValueAsString(original),
                    UserRequest.class);

            assertThat(returned.password())
                    .describedAs("the credential survives the cycle intact; outbound disclosure is "
                            + "prevented structurally instead, by every response type in this "
                            + "package declaring no credential component at all")
                    .isEqualTo(original.password());
            assertThat(returned)
                    .describedAs("no component is lost in either direction, so the record compares "
                            + "equal to the one it was written from")
                    .isEqualTo(original);
        }

        @Test
        @DisplayName("keeps the credential out of the diagnostic rendering even though it travels in "
                + "the serialized document, which are two independent channels")
        void keepsTheCredentialOutOfTheDiagnosticRenderingRegardless()
                throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();
            UserRequest request = addShaped();

            assertThat(mapper.writeValueAsString(request))
                    .describedAs("the serialization channel carries it")
                    .contains(CREDENTIAL);
            assertThat(request.toString())
                    .describedAs("the diagnostic channel does not, which is the only control this "
                            + "type applies and the only one it can apply without closing the "
                            + "inbound direction as well")
                    .doesNotContain(CREDENTIAL);
        }

        @Test
        @DisplayName("publishes every emittable component under its own component name")
        void publishesEveryEmittableComponentUnderItsOwnName() throws JsonProcessingException {
            JsonNode payload = payloadOf(fullyPopulated());

            assertThat(payload.get("userId").asText()).isEqualTo(USER_ID);
            assertThat(payload.get("searchUserId").asText()).isEqualTo(SEARCH_USER_ID);
            assertThat(payload.get("firstName").asText()).isEqualTo(FIRST_NAME);
            assertThat(payload.get("lastName").asText()).isEqualTo(LAST_NAME);
            assertThat(payload.get("userType").asText()).isEqualTo(UserType.ADMIN.getCode());
            assertThat(payload.get("displayedPageNumber").asText()).isEqualTo(PAGE_NUMBER);
            assertThat(payload.get("firstUserIdOnPage").asText()).isEqualTo(FIRST_ANCHOR);
            assertThat(payload.get("lastUserIdOnPage").asText()).isEqualTo(LAST_ANCHOR);
            assertThat(payload.get("rowSelections").isArray()).isTrue();
        }

        @Test
        @DisplayName("emits every identifier as text, so a leading zero survives the wire")
        void emitsEveryIdentifierAsText() throws JsonProcessingException {
            UserRequest request = new UserRequest(NUMERIC_USER_ID, null, null, null, null, null,
                    null, null, null, null, null, null);

            JsonNode payload = payloadOf(request);

            assertThat(payload.get("userId").isTextual())
                    .describedAs("a numeric node would have dropped the leading zeros")
                    .isTrue();
            assertThat(payload.get("userId").asText()).isEqualTo(NUMERIC_USER_ID);
        }

        @Test
        @DisplayName("omits an absent component rather than emitting a null for it")
        void omitsAnAbsentComponentRatherThanEmittingANull() throws JsonProcessingException {
            JsonNode payload = payloadOf(deleteShaped());

            assertThat(payload.has("userId")).isTrue();
            assertThat(payload.has("searchUserId")).isFalse();
            assertThat(payload.has("firstName")).isFalse();
            assertThat(payload.has("lastName")).isFalse();
            assertThat(payload.has("userType")).isFalse();
            assertThat(payload.has("displayedPageNumber")).isFalse();
            assertThat(payload.has("keyAction")).isFalse();
            assertThat(payload.has("navigationContext")).isFalse();
        }

        @Test
        @DisplayName("tolerates an unknown incoming property instead of rejecting the body")
        void toleratesAnUnknownIncomingProperty() throws JsonProcessingException {
            String document = """
                    {"userId":"NEWUSR01","password":"ABCD1234","errorMessage":"not a request item",\
                    "someFutureItem":42}""";

            UserRequest bound = moduleEquivalentMapper().readValue(document, UserRequest.class);

            assertThat(bound.userId()).isEqualTo(USER_ID);
            assertThat(bound.password()).isEqualTo(CREDENTIAL);
        }

        @Test
        @DisplayName("carries a submitted page number verbatim, leaving the decision to disregard it "
                + "to the service that owns the retained counter")
        void carriesASubmittedPageNumberVerbatim() throws JsonProcessingException {
            String document = """
                    {"userId":"NEWUSR01","displayedPageNumber":"00000099"}""";

            UserRequest bound = moduleEquivalentMapper().readValue(document, UserRequest.class);

            assertThat(bound.displayedPageNumber())
                    .describedAs("the program computes the number from a counter it retains itself, "
                            + "so a submitted value never influenced a page - but disregarding it is "
                            + "UserManagementService's decision, taken where that counter lives, and "
                            + "this passive carrier applies no directional rule of its own")
                    .isEqualTo("00000099");
            assertThat(bound.userId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("normalizes an absent selection sequence to an empty array on the wire")
        void normalizesAnAbsentSelectionSequenceOnTheWire() throws JsonProcessingException {
            JsonNode payload = payloadOf(deleteShaped());

            assertThat(payload.get("rowSelections").isArray()).isTrue();
            assertThat(payload.get("rowSelections")).isEmpty();
        }
    }

    /**
     * Validation is delegated, because the legacy cascades cannot be expressed declaratively.
     *
     * <p>Each program tests emptiness in its own order and stops at the first failure, reporting one
     * message. The orders disagree - the add screen tests the identifier third and the update screen
     * tests it first - and a declarative presence constraint would report every failure at once, in
     * an order the contract cannot pin down. So the shared type carries a length bound and nothing
     * else, and the tests below prove that a body which is absent, empty, blank, oddly cased,
     * digit-bearing, punctuated or carrying an undeclared user-type character passes untouched.
     *
     * <p>This is a positive property, not an omission: it is what allows the service layer to run
     * each operation's own cascade and emit the single correct message.
     */
    @Nested
    @DisplayName("validation :: a width bound and nothing else")
    class DelegatedValidation {

        @Test
        @DisplayName("reports nothing when every component is absent")
        void reportsNothingWhenEveryComponentIsAbsent() {
            assertThat(violationsOf(whollyAbsent()))
                    .describedAs("a presence rule anywhere on this type would fire here")
                    .isEmpty();
        }

        @Test
        @DisplayName("reports nothing when every component is empty")
        void reportsNothingWhenEveryComponentIsEmpty() {
            UserRequest request = new UserRequest("", "", "", "", "", "", List.of(), "", "", "",
                    null, null);

            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("reports nothing when every component is blank")
        void reportsNothingWhenEveryComponentIsBlank() {
            UserRequest request = new UserRequest(" ", " ", " ", " ", " ", " ", List.of(" "), " ",
                    " ", " ", null, null);

            assertThat(violationsOf(request))
                    .describedAs("blanks are real data in a fixed-width estate, and no program here "
                            + "rejects them at this layer")
                    .isEmpty();
        }

        @Test
        @DisplayName("reports nothing for an undeclared user-type character, because no program "
                + "tests the value")
        void reportsNothingForAnUndeclaredUserTypeCharacter() {
            UserRequest request = new UserRequest(USER_ID, null, FIRST_NAME, LAST_NAME, CREDENTIAL,
                    UNDECLARED_USER_TYPE, null, null, null, null, null, null);

            assertThat(request.userType()).isEqualTo(UNDECLARED_USER_TYPE);
            assertThat(violationsOf(request))
                    .describedAs("a value restriction would reject a character the credential "
                            + "record already holds")
                    .isEmpty();
        }

        @Test
        @DisplayName("reports nothing for names carrying digits, punctuation, mixed case or an "
                + "embedded blank")
        void reportsNothingForNamesCarryingDigitsPunctuationOrBlanks() {
            UserRequest request = new UserRequest(USER_ID, null, FIRST_NAME, LAST_NAME, CREDENTIAL,
                    UserType.ADMIN.getCode(), null, null, null, null, null, null);

            assertThat(request.firstName())
                    .describedAs("the legacy alphabetic idiom blanks every letter and trims what is "
                            + "left, so an embedded blank passes - and these three programs apply "
                            + "no character test at all")
                    .isEqualTo(FIRST_NAME)
                    .contains(" ");
            assertThat(request.lastName()).isEqualTo(LAST_NAME);
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("accepts every value at exactly its declared map width")
        void acceptsEveryValueAtExactlyItsDeclaredWidth() {
            UserRequest request = new UserRequest(USER_ID, SEARCH_USER_ID, FIRST_NAME_AT_WIDTH,
                    FIRST_NAME_AT_WIDTH, CREDENTIAL, UserType.ADMIN.getCode(), List.of("S"),
                    PAGE_NUMBER, FIRST_ANCHOR, LAST_ANCHOR, null, null);

            assertThat(request.userId()).hasSize(UserRequest.USER_ID_LENGTH);
            assertThat(request.firstName()).hasSize(UserRequest.NAME_PART_LENGTH);
            assertThat(request.lastName()).hasSize(UserRequest.NAME_PART_LENGTH);
            assertThat(request.password()).hasSize(UserRequest.PASSWORD_LENGTH);
            assertThat(request.userType()).hasSize(UserRequest.USER_TYPE_LENGTH);
            assertThat(request.displayedPageNumber())
                    .hasSize(UserRequest.DISPLAYED_PAGE_NUMBER_LENGTH);
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("reports the first name one character beyond its width, and reports it as a "
                + "width breach alone")
        void reportsTheFirstNameBeyondItsWidth() {
            UserRequest request = new UserRequest(null, null, FIRST_NAME_OVER_WIDTH, null, null,
                    null, null, null, null, null, null, null);

            assertOnlyWidthBoundReported(request, "firstName");
        }

        @Test
        @DisplayName("reports the last name one character beyond its width, and reports it as a "
                + "width breach alone")
        void reportsTheLastNameBeyondItsWidth() {
            UserRequest request = new UserRequest(null, null, null, FIRST_NAME_OVER_WIDTH, null,
                    null, null, null, null, null, null, null);

            assertOnlyWidthBoundReported(request, "lastName");
        }

        @Test
        @DisplayName("reports the user type one character beyond its width, and reports it as a "
                + "width breach alone")
        void reportsTheUserTypeBeyondItsWidth() {
            UserRequest request = new UserRequest(null, null, null, null, null, OVERLONG_USER_TYPE,
                    null, null, null, null, null, null);

            assertOnlyWidthBoundReported(request, "userType");
        }

        @Test
        @DisplayName("reports every over-wide value and reports nothing else, so no presence, "
                + "format, character-class or range rule is hiding on the type")
        void reportsEveryOverWideValueAndNothingElse() {
            UserRequest request = new UserRequest(OVERLONG_USER_ID, OVERLONG_USER_ID,
                    FIRST_NAME_OVER_WIDTH, FIRST_NAME_OVER_WIDTH, OVERLONG_CREDENTIAL,
                    OVERLONG_USER_TYPE, List.of("S"), "000000099", OVERLONG_USER_ID,
                    OVERLONG_USER_ID, null, null);

            assertThat(violationPathsOf(request))
                    .describedAs("exactly one report per over-wide component, and not one report "
                            + "more")
                    .containsExactly("displayedPageNumber", "firstName", "firstUserIdOnPage",
                            "lastName", "lastUserIdOnPage", "password", "searchUserId", "userId",
                            "userType");
            for (ConstraintViolation<UserRequest> violation : violationsOf(request)) {
                assertThat(violation.getConstraintDescriptor().getAnnotation())
                        .describedAs("component %s must be reported by its width bound alone",
                                violation.getPropertyPath())
                        .isInstanceOf(Size.class);
            }
        }

        @Test
        @DisplayName("bounds every selection element at one character")
        void boundsEverySelectionElementAtOneCharacter() {
            UserRequest request = new UserRequest(null, null, null, null, null, null, List.of("SU"),
                    null, null, null, null, null);

            assertThat(violationPathsOf(request))
                    .describedAs("the element bound is a container-element constraint, so it "
                            + "reports the offending position")
                    .containsExactly("rowSelections[0].<list element>");
        }
    }


    /**
     * The shared body carries no operation discriminator, and applicability is not decided here.
     *
     * <p>One record body serves four screens whose maps declare different items. It is tempting to
     * express that with nested operation markers carrying group-scoped absence rules, so that a delete
     * submission naming its operation is refused for carrying a credential its map has no field for.
     * This type deliberately does not do that, and the omission is the contract rather than a gap.
     *
     * <p><strong>Why a passive body is the correct shape.</strong> The four programs run ordered,
     * first-error-wins cascades, and the order differs between two of them - the add screen tests the
     * user identifier third, at line 132 of {@code app/cbl/COUSR01C.cbl}, while the update screen
     * tests it first, at line 182 of {@code COUSR02C.cbl}. Bean Validation is unordered and reports
     * every violation at once, so it can express neither cascade, and a body that carried half the
     * applicability decision would split one rule across two layers. The endpoint already identifies
     * the operation, so the service that runs the cascade is the one place that knows which components
     * its screen declares.
     *
     * <p>What is asserted here is therefore an absence with a behavioural consequence: no nested
     * member type exists to name as a group, no declared constraint is scoped to a group, and every
     * one of the four operation shapes validates cleanly on the transport type - including a
     * delete-shaped body carrying a credential, which this type accepts and the service refuses.
     */
    @Nested
    @DisplayName("operation scoping :: the body is passive and names no operation")
    class TheBodyNamesNoOperation {

        @Test
        @DisplayName("validates identically whether a group is named or not, so no constraint is "
                + "scoped to an operation")
        void validatesIdenticallyWhetherAGroupIsNamedOrNot() {
            UserRequest everyComponentPopulated = fullyPopulated();

            assertThat(violationsOf(everyComponentPopulated))
                    .describedAs("the unqualified validation is the whole validation")
                    .isEmpty();
            assertThat(violationsOf(everyComponentPopulated, Default.class))
                    .describedAs("naming the default group changes nothing, because a group-scoped "
                            + "bound would apply on some operations and not others and would make "
                            + "the transport type a partial owner of a service rule")
                    .isEmpty();
        }

        @Test
        @DisplayName("names no operation marker that a constraint could be scoped to, which the "
                + "compiler rather than a runtime lookup enforces")
        void namesNoOperationMarker() {
            for (UserRequest request : List.of(fullyPopulated(), addShaped(), updateShaped(),
                    deleteShaped(), listShaped(), credentialOnly(), whollyAbsent())) {
                assertThat(violationsOf(request, Default.class))
                        .describedAs("no shape can be refused by a group, because there is no group "
                                + "type on this record to name; an attempt to reference one here "
                                + "would fail to compile rather than fail at run time, which is the "
                                + "strongest available proof that none exists")
                        .isEmpty();
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.api.dto.UserRequestTest#everyOperationShape")
        @DisplayName("accepts every operation shape under the plain unqualified validation")
        void acceptsEveryOperationShape(String shape, UserRequest request) {
            assertThat(violationsOf(request))
                    .describedAs("shape %s is bounded by width and by nothing else", shape)
                    .isEmpty();
        }

        @Test
        @DisplayName("accepts a delete-shaped body carrying a credential, leaving that refusal to the "
                + "service whose screen declares no credential item")
        void acceptsACredentialOnADeleteShapedBody() {
            UserRequest request = new UserRequest(USER_ID, null, null, null, CREDENTIAL, null, null,
                    null, null, null, null, null);

            assertThat(violationsOf(request))
                    .describedAs("the delete map declares no credential item, but that is an "
                            + "applicability rule and not a width rule")
                    .isEmpty();
        }

        @Test
        @DisplayName("accepts list paging state alongside single-user values, because no rule here "
                + "makes the two mutually exclusive")
        void acceptsListStateAlongsideSingleUserValues() {
            UserRequest request = new UserRequest(USER_ID, SEARCH_USER_ID, FIRST_NAME, LAST_NAME,
                    CREDENTIAL, UserType.ADMIN.getCode(), List.of("U"), PAGE_NUMBER, USER_ID,
                    USER_ID, null, null);

            assertThat(violationsOf(request))
                    .describedAs("a union body can be populated in combinations no single screen "
                            + "produces; recognising them is the service's work")
                    .isEmpty();
        }
    }

    /**
     * The user type is a raw one-character code on the request, and the enumeration that interprets
     * it is deliberately elsewhere.
     *
     * <p>The persisted origin is the single character at byte offset 57 of the eighty-byte credential
     * record declared by {@code app/cpy/CSUSR01Y.cpy}, whose type field sits at line 22. Two codes are
     * declared by the estate's condition names and no more.
     *
     * <p>The programs never test <em>which</em> character arrived - the add screen tests only that
     * the item is non-blank, at line 142 of {@code COUSR01C.cbl}, and the update screen does the same
     * at line 204 of {@code COUSR02C.cbl} - so the request carries the raw character and the
     * enumeration resolves it without throwing. Binding the request component to the enumeration
     * would turn a data-quality observation into a deserialization failure and lose rows a migrated
     * database legitimately holds.
     */
    @Nested
    @DisplayName("the user-type vocabulary :: two codes, resolved without throwing")
    class TheUserTypeVocabulary {

        @Test
        @DisplayName("declares exactly two constants and no synthetic catch-all")
        void declaresExactlyTwoConstantsAndNoSyntheticCatchAll() {
            List<String> names = Arrays.stream(UserType.values()).map(UserType::name).toList();

            assertThat(names)
                    .describedAs("the estate's condition names declare two values, so a third "
                            + "constant would be an invention")
                    .containsExactly("ADMIN", "USER")
                    .doesNotContain("UNKNOWN", "NONE", "OTHER", "INVALID", "DEFAULT");
        }

        @Test
        @DisplayName("carries the two declared codes")
        void carriesTheTwoDeclaredCodes() {
            assertThat(UserType.ADMIN.getCode()).isEqualTo("A");
            assertThat(UserType.USER.getCode()).isEqualTo("U");
        }

        @Test
        @DisplayName("answers the administrator question for the administrator alone")
        void answersTheAdministratorQuestionForTheAdministratorAlone() {
            assertThat(UserType.ADMIN.isAdmin()).isTrue();
            assertThat(UserType.USER.isAdmin())
                    .describedAs("the alternative branch in the legacy sign-on is unconditional, so "
                            + "every code that is not the administrator code reaches the main menu")
                    .isFalse();
        }

        @Test
        @DisplayName("resolves both declared codes")
        void resolvesBothDeclaredCodes() {
            assertThat(UserType.fromCode("A")).contains(UserType.ADMIN);
            assertThat(UserType.fromCode("U")).contains(UserType.USER);
        }

        @Test
        @DisplayName("returns an empty result for an absent, blank, over-wide or undeclared code, "
                + "and never throws")
        void returnsAnEmptyResultRatherThanThrowing() {
            assertThat(UserType.fromCode(null)).isEmpty();
            assertThat(UserType.fromCode("")).isEmpty();
            assertThat(UserType.fromCode(" ")).isEmpty();
            assertThat(UserType.fromCode(UNDECLARED_USER_TYPE)).isEmpty();
            assertThat(UserType.fromCode(OVERLONG_USER_TYPE))
                    .describedAs("a throwing lookup would abort a sign-on the legacy program "
                            + "completes")
                    .isEmpty();
        }

        @Test
        @DisplayName("folds no case, because the legacy comparison folds none")
        void foldsNoCase() {
            assertThat(UserType.fromCode("a")).isEmpty();
            assertThat(UserType.fromCode("u")).isEmpty();
        }

        @Test
        @DisplayName("composes to a false administrator answer for an undeclared code without "
                + "throwing")
        void composesToAFalseAdministratorAnswerForAnUndeclaredCode() {
            assertThat(UserType.fromCode(UNDECLARED_USER_TYPE).map(UserType::isAdmin).orElse(false))
                    .isFalse();
            assertThat(UserType.fromCode(null).map(UserType::isAdmin).orElse(false)).isFalse();
            assertThat(UserType.fromCode("A").map(UserType::isAdmin).orElse(false)).isTrue();
        }

        @Test
        @DisplayName("carries the code on the request as raw text, accepting a character the "
                + "enumeration does not declare")
        void carriesTheCodeOnTheRequestAsRawText() {
            for (UserType declared : UserType.values()) {
                UserRequest request = new UserRequest(USER_ID, null, FIRST_NAME, LAST_NAME,
                        CREDENTIAL, declared.getCode(), null, null, null, null, null, null);

                assertThat(request.userType()).isEqualTo(declared.getCode());
                assertThat(violationsOf(request)).isEmpty();
            }

            UserRequest undeclared = new UserRequest(USER_ID, null, FIRST_NAME, LAST_NAME,
                    CREDENTIAL, UNDECLARED_USER_TYPE, null, null, null, null, null, null);

            assertThat(undeclared.userType()).isEqualTo(UNDECLARED_USER_TYPE);
            assertThat(UserType.fromCode(undeclared.userType()))
                    .describedAs("the request transports the character and the enumeration declines "
                            + "to recognise it - neither step fails")
                    .isEmpty();
            assertThat(violationsOf(undeclared)).isEmpty();
        }

        @Test
        @DisplayName("carries a lower-case code through the request unchanged, because the request "
                + "folds no case either")
        void carriesALowerCaseCodeThroughTheRequestUnchanged() {
            UserRequest request = new UserRequest(USER_ID, null, null, null, null, "a", null, null,
                    null, null, null, null);

            assertThat(request.userType()).isEqualTo("a");
            assertThat(violationsOf(request)).isEmpty();
        }
    }


    /**
     * Every value crosses this boundary exactly as it arrived.
     *
     * <p>The items these components mirror are fixed-width and blank-significant, so a leading or
     * trailing blank is data rather than noise, and the emptiness cascade in the service layer must
     * see precisely what the client sent. Nothing here trims, pads, folds, strips or canonicalises
     * anything, and the tests below prove it component by component rather than by assertion in prose.
     *
     * <p>The selection sequence is the one component that is normalised, and only for nullness: an
     * absent sequence becomes the empty immutable sequence so no caller has to check before iterating.
     * Order and length are preserved exactly, because element <em>n</em> is the selection typed against
     * screen row <em>n</em> and that correspondence is the whole meaning of the sequence.
     */
    @Nested
    @DisplayName("value fidelity :: nothing is trimmed, padded, folded or reordered")
    class ValueFidelity {

        @Test
        @DisplayName("trims no leading or trailing blank from any component")
        void trimsNoBlankFromAnyComponent() {
            UserRequest request = new UserRequest("  usr  ", " srch ", "  Mary  ", " o'Hara ",
                    " pw     ", " ", List.of(" "), " 0000007", "  anchor", "anchor  ", null, null);

            assertThat(request.userId()).isEqualTo("  usr  ");
            assertThat(request.searchUserId()).isEqualTo(" srch ");
            assertThat(request.firstName()).isEqualTo("  Mary  ");
            assertThat(request.lastName()).isEqualTo(" o'Hara ");
            assertThat(request.password()).isEqualTo(" pw     ");
            assertThat(request.userType()).isEqualTo(" ");
            assertThat(request.displayedPageNumber()).isEqualTo(" 0000007");
            assertThat(request.firstUserIdOnPage()).isEqualTo("  anchor");
            assertThat(request.lastUserIdOnPage()).isEqualTo("anchor  ");
            assertThat(request.rowSelections()).containsExactly(" ");
        }

        @Test
        @DisplayName("pads no short value up to its declared width")
        void padsNoShortValueUpToItsWidth() {
            UserRequest request = new UserRequest("A", null, "B", "C", "D", "E", null, null, null,
                    null, null, null);

            assertThat(request.userId()).hasSize(1);
            assertThat(request.firstName()).hasSize(1);
            assertThat(request.lastName()).hasSize(1);
            assertThat(request.password()).hasSize(1);
            assertThat(request.userType()).hasSize(1);
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("folds the case of no component")
        void foldsTheCaseOfNoComponent() {
            UserRequest request = new UserRequest("mIxEdUsR", null, "mIxEd", "CaSeD", "aBcD1234",
                    "u", null, null, null, null, null, null);

            assertThat(request.userId()).isEqualTo("mIxEdUsR");
            assertThat(request.firstName()).isEqualTo("mIxEd");
            assertThat(request.lastName()).isEqualTo("CaSeD");
            assertThat(request.password()).isEqualTo("aBcD1234");
            assertThat(request.userType()).isEqualTo("u");
        }

        @Test
        @DisplayName("normalizes an absent selection sequence to the empty sequence, and nothing "
                + "else")
        void normalizesAnAbsentSelectionSequenceOnly() {
            assertThat(whollyAbsent().rowSelections())
                    .describedAs("an accessor that could return null would force every caller to "
                            + "check before iterating")
                    .isNotNull()
                    .isEmpty();
        }

        @Test
        @DisplayName("preserves selection order and blank positions, interpreting no element")
        void preservesSelectionOrderAndBlankPositions() {
            List<String> typed = List.of("S", " ", "U", " ");

            UserRequest request = new UserRequest(null, SEARCH_USER_ID, null, null, null, null,
                    typed, null, null, null, null, null);

            assertThat(request.rowSelections())
                    .describedAs("position is meaning: element n is the selection typed against "
                            + "screen row n, so nothing may be filtered, compacted or re-ordered")
                    .containsExactly("S", " ", "U", " ");
        }

        @Test
        @DisplayName("freezes the selection sequence, so a caller cannot mutate a constructed "
                + "request")
        void freezesTheSelectionSequence() {
            UserRequest request = new UserRequest(null, SEARCH_USER_ID, null, null, null, null,
                    List.of("S"), null, null, null, null, null);
            List<String> exposed = request.rowSelections();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> exposed.add("U"));
        }

        @Test
        @DisplayName("does not alias caller-owned state, so a later mutation of the source cannot "
                + "change a constructed request")
        void doesNotAliasCallerOwnedState() {
            List<String> caller = new ArrayList<>();
            caller.add("S");
            UserRequest request = new UserRequest(null, SEARCH_USER_ID, null, null, null, null,
                    caller, null, null, null, null, null);

            caller.add("U");
            caller.set(0, "X");

            assertThat(request.rowSelections()).containsExactly("S");
        }

        @Test
        @DisplayName("refuses a selection element that is absent, because a selection with no "
                + "character is neither blank nor a choice")
        void refusesAnAbsentSelectionElement() {
            List<String> withAbsentElement = new ArrayList<>();
            withAbsentElement.add("S");
            withAbsentElement.add(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new UserRequest(null, SEARCH_USER_ID, null, null, null, null,
                            withAbsentElement, null, null, null, null, null));
        }

        @Test
        @DisplayName("carries a selection sequence of any length, because how many rows a screen has "
                + "is the paging contract's measurement and not this type's")
        void carriesASelectionSequenceOfAnyLength() {
            List<String> beyondAnyScreen = new ArrayList<>();
            for (int row = 0; row < PageMetadata.LARGEST_SCREEN_PAGE_SIZE + 1; row++) {
                beyondAnyScreen.add("S");
            }

            UserRequest request = new UserRequest(null, SEARCH_USER_ID, null, null, null, null,
                    beyondAnyScreen, null, null, null, null, null);

            assertThat(request.rowSelections())
                    .describedAs("no cardinality bound is declared and nothing is truncated, so the "
                            + "sequence arrives at the service exactly as it was sent")
                    .hasSize(beyondAnyScreen.size());
            assertThat(violationsOf(request))
                    .describedAs("only the one-character element width is bounded here")
                    .isEmpty();
        }

        @Test
        @DisplayName("accepts a shorter selection sequence, so a partial page stays partial and is "
                + "never padded out")
        void acceptsAShorterSelectionSequence() {
            UserRequest request = new UserRequest(null, SEARCH_USER_ID, null, null, null, null,
                    List.of("S", "U"), null, null, null, null, null);

            assertThat(request.rowSelections()).hasSize(2);
        }

        @Test
        @DisplayName("publishes every declared width as a named constant, and publishes no count of "
                + "anything")
        void publishesEveryDeclaredWidthAsANamedConstant() {
            assertThat(UserRequest.USER_ID_LENGTH).isEqualTo(8);
            assertThat(UserRequest.NAME_PART_LENGTH).isEqualTo(20);
            assertThat(UserRequest.PASSWORD_LENGTH).isEqualTo(8);
            assertThat(UserRequest.USER_TYPE_LENGTH).isEqualTo(1);
            assertThat(UserRequest.ROW_SELECTION_LENGTH).isEqualTo(1);
            assertThat(UserRequest.DISPLAYED_PAGE_NUMBER_LENGTH).isEqualTo(8);

            assertThat(PageMetadata.USER_LIST_PAGE_SIZE)
                    .describedAs("the screen row count for this list is stated once, by PageMetadata, "
                            + "and this request type publishes no count of its own; every constant it "
                            + "does publish states the width of one screen item, and a reference to a "
                            + "row or selection count on UserRequest would fail to compile rather "
                            + "than fail here, which is the strongest available proof of absence")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("tolerates an absent value on every component")
        void toleratesAnAbsentValueOnEveryComponent() {
            UserRequest request = whollyAbsent();

            assertThat(request.userId()).isNull();
            assertThat(request.searchUserId()).isNull();
            assertThat(request.firstName()).isNull();
            assertThat(request.lastName()).isNull();
            assertThat(request.password()).isNull();
            assertThat(request.userType()).isNull();
            assertThat(request.displayedPageNumber()).isNull();
            assertThat(request.firstUserIdOnPage()).isNull();
            assertThat(request.lastUserIdOnPage()).isNull();
            assertThat(request.keyAction()).isNull();
            assertThat(request.navigationContext()).isNull();
            assertThat(request.rowSelections()).isEmpty();
        }
    }

    /**
     * Every accessor is exercised, and the request is confirmed immutable by construction.
     *
     * <p>Immutability is demonstrated the only way a caller can demonstrate it: construct an instance,
     * read every component back, and observe that there is no operation available that changes one.
     * A record exposes an accessor per component and no mutator, and the selection sequence is frozen
     * on the way in, so the type has no writable surface at all.
     */
    @Nested
    @DisplayName("accessors :: every component is readable and none is writable")
    class AccessorCoverage {

        @Test
        @DisplayName("exposes all twelve components through their own accessors")
        void exposesAllTwelveComponentsThroughTheirOwnAccessors() {
            UserRequest request = fullyPopulated();

            assertThat(request.userId()).isEqualTo(USER_ID);
            assertThat(request.searchUserId()).isEqualTo(SEARCH_USER_ID);
            assertThat(request.firstName()).isEqualTo(FIRST_NAME);
            assertThat(request.lastName()).isEqualTo(LAST_NAME);
            assertThat(request.password()).isEqualTo(CREDENTIAL);
            assertThat(request.userType()).isEqualTo(UserType.ADMIN.getCode());
            assertThat(request.rowSelections()).containsExactly("S");
            assertThat(request.displayedPageNumber()).isEqualTo(PAGE_NUMBER);
            assertThat(request.firstUserIdOnPage()).isEqualTo(FIRST_ANCHOR);
            assertThat(request.lastUserIdOnPage()).isEqualTo(LAST_ANCHOR);
            assertThat(request.keyAction())
                    .describedAs("an unresolved attention key is a real state: the legacy key "
                            + "mapping has no catch-all branch, so no default may be substituted")
                    .isNull();
            assertThat(request.navigationContext())
                    .describedAs("a first entry carries no echoed screen-flow state")
                    .isNull();
        }

        @Test
        @DisplayName("reads the same value from an accessor however many times it is called")
        void readsTheSameValueHoweverManyTimesAnAccessorIsCalled() {
            UserRequest request = addShaped();

            assertThat(request.userId()).isEqualTo(request.userId());
            assertThat(request.password()).isEqualTo(request.password());
            assertThat(request.rowSelections()).isEqualTo(request.rowSelections());
        }

        @Test
        @DisplayName("offers no writable surface, so an instance cannot drift after construction")
        void offersNoWritableSurface() {
            UserRequest request = addShaped();
            String identifierBefore = request.userId();
            List<String> selectionsBefore = request.rowSelections();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> selectionsBefore.add("S"));
            assertThat(request.userId()).isEqualTo(identifierBefore);
            assertThat(request.rowSelections()).isEmpty();
        }
    }

}
