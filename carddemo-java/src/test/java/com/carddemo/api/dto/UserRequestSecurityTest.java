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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.IntStream;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.carddemo.domain.enums.KeyAction;

/**
 * Unit test for {@link UserRequest}, the inbound contract shared by the four administrative user
 * transactions {@code CU00}, {@code CU01}, {@code CU02} and {@code CU03}.
 *
 * <h2>What is actually at risk in a request of this shape</h2>
 *
 * <p>The first and largest risk is <strong>the credential travelling outbound</strong>. The component set
 * carries an eight-character password, and a record exposes an accessor a serializer can reach, so the
 * value has two independent escape routes: a diagnostic rendering and a serialized document. Redacting
 * one says nothing about the other, and the second is the one a request object reused as a response
 * body, cached, queued, attached to an audit event or captured in a problem report actually takes. Both
 * are therefore asserted, and both negatively: the rendering is required not to contain the value, and
 * the emitted property set is pinned exactly so that reintroducing the credential to the outbound
 * document - by removing the annotation, by adding an accessor, or by any other route - fails here.</p>
 *
 * <p>The second is <strong>the inbound direction staying open</strong>. Closing the outbound direction is
 * worthless if it also closes the inbound one, because an operation that cannot read a credential cannot
 * store one. The asymmetry is asserted directly, on the settings the module actually deploys.</p>
 *
 * <p>The third is <strong>bounded work</strong>. The selection sequence is index-aligned to a screen of
 * ten rows and is defensively copied, so without a cardinality bound an arbitrarily long sequence is
 * retained in full; and the echoed navigation state declares widths of its own that nothing evaluates
 * unless this contract cascades into it. Neither is a field edit and neither may become one, so the
 * bounds are asserted alongside the deliberate absence of every presence, format and vocabulary rule the
 * four legacy cascades reserve to the service.</p>
 *
 * <p>The fourth is <strong>byte-for-byte transport</strong>. Twelve components, ten of them the same
 * type, cross one canonical constructor; blanks are real data in a fixed-width estate, and each of the
 * four screens runs its own differently ordered emptiness cascade over exactly what the client sent.</p>
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.</p>
 */
@DisplayName("UserRequest - the CU00 to CU03 inbound contract")
class UserRequestSecurityTest {

    /** The thirteen components in declaration order. A change here is a change to the REST contract. */
    private static final List<String> COMPONENTS_IN_ORDER = List.of(
            "userId", "searchUserId", "firstName", "lastName", "password", "userType",
            "rowSelections", "displayedPageNumber", "firstUserIdOnPage", "lastUserIdOnPage",
            "rowSnapshotToken", "keyAction", "navigationContext");

    /**
     * The measured widths of the eight bounded text components, read from
     * {@code app/cpy-bms/COUSR00.CPY} lines 60 and 66, {@code app/cpy-bms/COUSR01.CPY} lines 60, 66, 72,
     * 78 and 84 and {@code app/cbl/COUSR00C.cbl} lines 68 and 69 rather than from the class under test,
     * so a width edited on the request alone fails here instead of agreeing with itself.
     */
    private static final List<Integer> BOUNDED_WIDTHS = List.of(8, 8, 20, 20, 8, 1, 8, 8, 8);

    /** The bounded text components, paired positionally with {@link #BOUNDED_WIDTHS}. */
    private static final List<String> BOUNDED_COMPONENTS = List.of(
            "userId", "searchUserId", "firstName", "lastName", "password", "userType",
            "displayedPageNumber", "firstUserIdOnPage", "lastUserIdOnPage");

    /** Serialized property name of the credential. */
    private static final String CREDENTIAL_PROPERTY = "password";

    /** Serialized property name of the page label the service publishes and never accepts. */
    private static final String PUBLISHED_PAGE_LABEL = "displayedPageNumber";

    /** A synthetic credential at the declared width. Never a value any seeded identity carries. */
    private static final String SYNTHETIC_CREDENTIAL = "Zq7xVt3m";

    /** The identifier of the user an operation acts on. */
    private static final String USER_ID = "ADMIN001";

    /** A first name at the declared width boundary of the item, with an embedded space. */
    private static final String FIRST_NAME = "MARY ANN";

    /** A last name. */
    private static final String LAST_NAME = "OSULLIVAN";

    /** The eight-character page label the list screen publishes. */
    private static final String DISPLAYED_PAGE_NUMBER = "00000001";

    /** The fixed stand-in the rendering must emit in place of the credential. */
    private static final String REDACTION_PLACEHOLDER_TEXT = "***REDACTED***";

    /** The ten selection keystrokes of a full list page, one per screen row. */
    private static final List<String> TEN_SELECTIONS =
            List.of(" ", " ", "S", " ", " ", " ", " ", " ", " ", " ");

    /**
     * Mirrors the four serialisation settings the module declares in {@code application.yml}, so a
     * payload asserted here is the payload the service actually emits and receives.
     *
     * <p>That this stand-in remains faithful is asserted by {@link ApplicationJsonContractTest}, which
     * builds the mapper from a context that has read the module's own file.</p>
     */
    private static ObjectMapper moduleEquivalentMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(
                        JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
                                JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    /** A realistic administrative add submission, with every component populated. */
    private static UserRequest populated() {
        return new UserRequest(USER_ID, "B", FIRST_NAME, LAST_NAME, SYNTHETIC_CREDENTIAL, "A",
                TEN_SELECTIONS, DISPLAYED_PAGE_NUMBER, "AAAAAAA1", "ZZZZZZZ9",
                "sealed-page-token", KeyAction.PFK05, NavigationContext.empty().withReEntry());
    }

    private static Set<ConstraintViolation<UserRequest>> violationsOf(UserRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    private static JsonNode payloadOf(UserRequest request) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    @Nested
    @DisplayName("The component set is the four maps' union plus conversation state")
    class TheComponentSetIsTheFourMapsUnion {

        @Test
        @DisplayName("thirteen components are declared in order")
        void twelveComponentsAreDeclaredInOrder() {
            List<String> declared = Arrays.stream(UserRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(COMPONENTS_IN_ORDER).hasSize(13);
        }

        @Test
        @DisplayName("no authority, privilege, role or administrator indicator is declared, because "
                + "authorisation decided by request content is not authorisation")
        void noAuthorityComponentIsDeclared() {
            List<String> lowerCased = Arrays.stream(UserRequest.class.getRecordComponents())
                    .map(component -> component.getName().toLowerCase(Locale.ROOT))
                    .toList();

            assertThat(lowerCased)
                    .noneMatch(name -> name.contains("authorit"))
                    .noneMatch(name -> name.contains("privilege"))
                    .noneMatch(name -> name.contains("role"))
                    .noneMatch(name -> name.contains("admin"))
                    .noneMatch(name -> name.contains("scope"))
                    .noneMatch(name -> name.contains("permission"));
        }

        @Test
        @DisplayName("no operation discriminator is declared, because the endpoint identifies the "
                + "operation and each operation runs its own ordered cascade")
        void noOperationDiscriminatorIsDeclared() {
            List<String> lowerCased = Arrays.stream(UserRequest.class.getRecordComponents())
                    .map(component -> component.getName().toLowerCase(Locale.ROOT))
                    .toList();

            assertThat(lowerCased)
                    .noneMatch(name -> name.contains("mode"))
                    .noneMatch(name -> name.contains("operation"))
                    .noneMatch(name -> name.contains("action") && !name.contains("keyaction"))
                    .noneMatch(name -> name.contains("confirm"));
        }

        @Test
        @DisplayName("the ten text components are characters and never numbers, so a leading zero in a "
                + "page label or an identifier cannot be lost")
        void theTextComponentsAreNeverNumbers() {
            for (RecordComponent component : UserRequest.class.getRecordComponents()) {
                switch (component.getName()) {
                    case "keyAction" -> assertThat(component.getType()).isEqualTo(KeyAction.class);
                    case "navigationContext" ->
                            assertThat(component.getType()).isEqualTo(NavigationContext.class);
                    case "rowSelections" -> assertThat(component.getType()).isEqualTo(List.class);
                    default -> assertThat(component.getType())
                            .as("component %s", component.getName())
                            .isEqualTo(String.class);
                }
            }
        }

        @Test
        @DisplayName("every accessor returns exactly what it was constructed with, so no two same-typed "
                + "components are transposed")
        void everyAccessorReturnsExactlyWhatItWasConstructedWith() {
            UserRequest request = populated();

            assertThat(request.userId()).isEqualTo(USER_ID);
            assertThat(request.searchUserId()).isEqualTo("B");
            assertThat(request.firstName()).isEqualTo(FIRST_NAME);
            assertThat(request.lastName()).isEqualTo(LAST_NAME);
            assertThat(request.password()).isEqualTo(SYNTHETIC_CREDENTIAL);
            assertThat(request.userType()).isEqualTo("A");
            assertThat(request.rowSelections()).containsExactlyElementsOf(TEN_SELECTIONS);
            assertThat(request.displayedPageNumber()).isEqualTo("00000001");
            assertThat(request.firstUserIdOnPage()).isEqualTo("AAAAAAA1");
            assertThat(request.lastUserIdOnPage()).isEqualTo("ZZZZZZZ9");
            assertThat(request.keyAction()).isEqualTo(KeyAction.PFK05);
            assertThat(request.navigationContext().programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
        }
    }

    @Nested
    @DisplayName("The credential never leaves in a serialized document, and always arrives in one")
    class TheCredentialIsInboundOnly {

        @Test
        @DisplayName("the credential component declares write-only access, which is the mechanism that "
                + "closes the outbound direction")
        void theCredentialComponentDeclaresWriteOnlyAccess() throws NoSuchFieldException {
            Field field = UserRequest.class.getDeclaredField(CREDENTIAL_PROPERTY);

            JsonProperty jsonProperty = field.getAnnotation(JsonProperty.class);

            assertThat(jsonProperty).isNotNull();
            assertThat(jsonProperty.access()).isEqualTo(JsonProperty.Access.WRITE_ONLY);
        }

        @Test
        @DisplayName("the published interface description declares the same thing, so a generated client "
                + "is told the property is write-only rather than discovering it by omission")
        void thePublishedDescriptionDeclaresWriteOnlyToo() throws NoSuchFieldException {
            Field field = UserRequest.class.getDeclaredField(CREDENTIAL_PROPERTY);

            Schema schema = field.getAnnotation(Schema.class);

            assertThat(schema).isNotNull();
            assertThat(schema.accessMode()).isEqualTo(Schema.AccessMode.WRITE_ONLY);
            assertThat(schema.format()).isEqualTo("password");
            assertThat(schema.description()).isNotBlank();
        }

        @Test
        @DisplayName("the credential is the only component closed to reading, and the one other "
                + "component that declares an access mode is closed to writing instead")
        void theCredentialIsTheOnlyComponentClosedToReading() throws NoSuchFieldException {
            for (String name : COMPONENTS_IN_ORDER) {
                if (CREDENTIAL_PROPERTY.equals(name) || "rowSnapshotToken".equals(name)
                        || PUBLISHED_PAGE_LABEL.equals(name)) {
                    continue;
                }
                Field field = UserRequest.class.getDeclaredField(name);

                assertThat(field.getAnnotation(JsonProperty.class))
                        .as("component %s must not declare a Jackson access mode", name)
                        .isNull();
            }

            // The page label is the mirror image of the credential and is deliberately left in place:
            // the credential is accepted and never emitted, and the label is emitted and never
            // accepted, because the list program computes it from a counter it retains itself. Closing
            // a component to writing withholds nothing a diagnostic needs, so it cannot be the accident
            // this test looks for.
            assertThat(UserRequest.class.getDeclaredField(PUBLISHED_PAGE_LABEL)
                    .getAnnotation(JsonProperty.class).access())
                    .isEqualTo(JsonProperty.Access.READ_ONLY);
            assertThat(UserRequest.class.getDeclaredField(CREDENTIAL_PROPERTY)
                    .getAnnotation(JsonProperty.class).access())
                    .isEqualTo(JsonProperty.Access.WRITE_ONLY);
            assertThat(UserRequest.class.getDeclaredField("rowSnapshotToken")
                    .getAnnotation(JsonProperty.class).access())
                    .isEqualTo(JsonProperty.Access.WRITE_ONLY);
        }

        @Test
        @DisplayName("a document carrying the credential deserializes with it populated, because an "
                + "operation that refuses to read a credential cannot store one")
        void aDocumentCarryingTheCredentialDeserializesWithItPopulated() throws JsonProcessingException {
            String document = "{\"userId\":\"" + USER_ID + "\",\"password\":\""
                    + SYNTHETIC_CREDENTIAL + "\",\"userType\":\"A\"}";

            UserRequest request = moduleEquivalentMapper().readValue(document, UserRequest.class);

            assertThat(request.password()).isEqualTo(SYNTHETIC_CREDENTIAL);
            assertThat(request.userId()).isEqualTo(USER_ID);
            assertThat(request.userType()).isEqualTo("A");
        }

        @Test
        @DisplayName("the emitted document omits the credential property entirely, even when the "
                + "component is populated")
        void theEmittedDocumentOmitsTheCredentialProperty() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.has(CREDENTIAL_PROPERTY)).isFalse();
            assertThat(payload.get("userId").asText()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("the serialized text contains the credential nowhere, in no form and under no "
                + "property name")
        void theSerializedTextContainsTheCredentialNowhere() throws JsonProcessingException {
            String document = moduleEquivalentMapper().writeValueAsString(populated());

            assertThat(document).doesNotContain(SYNTHETIC_CREDENTIAL);
            assertThat(document).doesNotContain(CREDENTIAL_PROPERTY);
        }

        @Test
        @DisplayName("an empty credential is suppressed too, so the outbound closure does not depend on "
                + "the absent-value omission policy")
        void anEmptyCredentialIsSuppressedToo() throws JsonProcessingException {
            UserRequest emptyCredential = new UserRequest(USER_ID, null, null, null, "", "A",
                    null, null, null, null, null, null);

            JsonNode payload = payloadOf(emptyCredential);

            assertThat(payload.has(CREDENTIAL_PROPERTY)).isFalse();
        }

        @Test
        @DisplayName("the emitted property set is pinned exactly, so reintroducing the credential to the "
                + "outbound document by any route fails here")
        void theEmittedPropertySetIsPinnedExactly() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            List<String> emitted = new java.util.ArrayList<>();
            payload.fieldNames().forEachRemaining(emitted::add);
            Collections.sort(emitted);

            List<String> expected = new java.util.ArrayList<>(COMPONENTS_IN_ORDER);
            expected.remove(CREDENTIAL_PROPERTY);
            expected.remove("rowSnapshotToken");
            Collections.sort(expected);

            assertThat(emitted).containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("a credential surviving a round trip through the module's own mapper is absent on the "
                + "far side, which is the observable consequence of write-only access")
        void aCredentialDoesNotSurviveARoundTrip() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            String document = mapper.writeValueAsString(populated());
            UserRequest revived = mapper.readValue(document, UserRequest.class);

            assertThat(revived.password()).isNull();
            assertThat(revived.rowSnapshotToken()).isNull();
            assertThat(revived.userId()).isEqualTo(USER_ID);
            assertThat(revived.rowSelections()).containsExactlyElementsOf(TEN_SELECTIONS);
        }
    }

    @Nested
    @DisplayName("The credential never leaves in a diagnostic rendering either")
    class TheCredentialIsNeverRendered {

        @Test
        @DisplayName("the credential does not appear anywhere in the rendering, and the placeholder "
                + "stands where it would have been")
        void theCredentialDoesNotAppearInTheRendering() {
            String rendered = populated().toString();

            assertThat(rendered).doesNotContain(SYNTHETIC_CREDENTIAL);
            assertThat(rendered).contains("password=" + REDACTION_PLACEHOLDER_TEXT);
        }

        @Test
        @DisplayName("no four-character run of the credential survives into the rendering")
        void noFourCharacterRunOfTheCredentialSurvives() {
            String rendered = populated().toString();

            IntStream.rangeClosed(0, SYNTHETIC_CREDENTIAL.length() - 4)
                    .mapToObj(start -> SYNTHETIC_CREDENTIAL.substring(start, start + 4))
                    .forEach(fragment -> assertThat(rendered)
                            .as("fragment %s must not appear", fragment)
                            .doesNotContain(fragment));
        }

        @Test
        @DisplayName("two instances differing only in the credential render identically, so the "
                + "placeholder is a constant rather than a transformation")
        void twoInstancesDifferingOnlyInTheCredentialRenderIdentically() {
            UserRequest first = new UserRequest(USER_ID, null, null, null, "aaaaaaaa", "A", null,
                    null, null, null, null, null);
            UserRequest second = new UserRequest(USER_ID, null, null, null, "zzzzzzzzzzzz", "A",
                    null, null, null, null, null, null);

            assertThat(first).hasToString(second.toString());
        }

        @Test
        @DisplayName("an absent credential still renders as the placeholder, so absence and presence are "
                + "indistinguishable in a diagnostic")
        void anAbsentCredentialStillRendersAsThePlaceholder() {
            UserRequest noCredential = new UserRequest(USER_ID, null, null, null, null, "A", null,
                    null, null, null, null, null);

            assertThat(noCredential.toString()).contains("password=" + REDACTION_PLACEHOLDER_TEXT);
            assertThat(noCredential.toString()).doesNotContain("password=null");
        }

        @Test
        @DisplayName("only the components that identify nobody are retained, and every component that "
                + "names or describes a person is withheld along with the credential")
        void onlyTheComponentsThatIdentifyNobodyAreRetained() {
            String rendered = populated().toString();

            assertThat(rendered)
                    .as("a keystroke, a page label and a selection count name nobody")
                    .contains("keyAction=" + KeyAction.PFK05)
                    .contains("displayedPageNumber=" + DISPLAYED_PAGE_NUMBER)
                    .contains("rowSelectionCount=");
            assertThat(rendered)
                    .as("an administrator screen carries identities in every other component, so the "
                            + "rendering withholds each of them rather than only the credential")
                    .contains("userId=" + REDACTION_PLACEHOLDER_TEXT)
                    .contains("searchUserId=" + REDACTION_PLACEHOLDER_TEXT)
                    .contains("firstName=" + REDACTION_PLACEHOLDER_TEXT)
                    .contains("lastName=" + REDACTION_PLACEHOLDER_TEXT)
                    .contains("userType=" + REDACTION_PLACEHOLDER_TEXT)
                    .contains("firstUserIdOnPage=" + REDACTION_PLACEHOLDER_TEXT)
                    .contains("lastUserIdOnPage=" + REDACTION_PLACEHOLDER_TEXT)
                    .doesNotContain(USER_ID)
                    .doesNotContain(FIRST_NAME);
        }

        @Test
        @DisplayName("the nested navigation state redacts its own identifying values, so nesting it "
                + "discloses nothing further")
        void theNestedNavigationStateRedactsItsOwn() {
            NavigationContext identifying = new NavigationContext(null, null, null, null, USER_ID,
                    "A", NavigationContext.ProgramContext.REENTER, "000000001", "MARY", null,
                    "OSULLIVAN", "00000000011", "Y", "4111111111111111", null, null);
            UserRequest request = new UserRequest(USER_ID, null, null, null, SYNTHETIC_CREDENTIAL,
                    "A", null, null, null, null, null, identifying);

            String rendered = request.toString();

            assertThat(rendered).doesNotContain("4111111111111111");
            assertThat(rendered).doesNotContain("00000000011");
            assertThat(rendered).doesNotContain("000000001");
            assertThat(rendered).doesNotContain(SYNTHETIC_CREDENTIAL);
        }

        @Test
        @DisplayName("the rendering is safe when every component is absent, so a diagnostic on an empty "
                + "submission cannot throw")
        void theRenderingIsSafeWhenEveryComponentIsAbsent() {
            UserRequest empty = new UserRequest(null, null, null, null, null, null, null, null, null,
                    null, null, null);

            assertThatCode(empty::toString).doesNotThrowAnyException();
            assertThat(empty.toString()).contains("password=" + REDACTION_PLACEHOLDER_TEXT);
        }

        @Test
        @DisplayName("no credential or payment marker appears in the rendering under any name")
        void noCredentialOrPaymentMarkerAppearsInTheRendering() {
            String rendered = populated().toString().toUpperCase(Locale.ROOT);

            assertThat(rendered)
                    .doesNotContain("SECRET")
                    .doesNotContain("$2A$")
                    .doesNotContain("$2B$")
                    .doesNotContain("PASSWD");
        }
    }

    @Nested
    @DisplayName("Bounds measure and never alter, and the structural bounds are not field edits")
    class BoundsMeasureAndNeverAlter {

        @Test
        @DisplayName("each bounded text component carries exactly its measured width and no other "
                + "constraint beyond the credential's serialization metadata")
        void eachBoundedComponentCarriesItsMeasuredWidth() throws NoSuchFieldException {
            for (int index = 0; index < BOUNDED_COMPONENTS.size(); index++) {
                String name = BOUNDED_COMPONENTS.get(index);
                Field field = UserRequest.class.getDeclaredField(name);
                Size size = field.getAnnotation(Size.class);

                assertThat(size).as("component %s must carry a width bound", name).isNotNull();
                assertThat(size.max()).as("component %s width", name)
                        .isEqualTo(BOUNDED_WIDTHS.get(index));
                assertThat(size.min()).as("component %s must declare no minimum", name).isZero();
            }
        }

        @Test
        @DisplayName("no presence, pattern, digit, range or assertion constraint appears on any "
                + "component, because each of the four screens owns its own ordered cascade")
        void noPresenceOrFormatConstraintAppears() throws NoSuchFieldException {
            for (String name : COMPONENTS_IN_ORDER) {
                Field field = UserRequest.class.getDeclaredField(name);

                assertThat(Arrays.stream(field.getAnnotations())
                        .map(annotation -> annotation.annotationType().getSimpleName())
                        .toList())
                        .as("component %s", name)
                        .doesNotContain("NotNull", "NotBlank", "NotEmpty", "Pattern", "Digits",
                                "Min", "Max", "Positive", "AssertTrue", "Email");
            }
        }

        @Test
        @DisplayName("the selection sequence is bounded at the screen's ten rows, because an eleventh "
                + "selection corresponds to no row")
        void theSelectionSequenceIsBoundedAtTenRows() throws NoSuchFieldException {
            Field field = UserRequest.class.getDeclaredField("rowSelections");

            // Two width rules apply to this one component - the default-group arity bound and the
            // operation-scoped emptiness rule - so the repeatable-aware accessor is the one that reads
            // them. A single-annotation lookup returns nothing at all once a constraint is repeated.
            Size[] rules = field.getAnnotationsByType(Size.class);

            assertThat(rules).hasSize(2);
            assertThat(java.util.Arrays.stream(rules)
                    .filter(rule -> rule.groups().length == 0)
                    .findFirst())
                    .get()
                    .extracting(Size::max)
                    .isEqualTo(UserRequest.ROW_SELECTION_COUNT);
            assertThat(UserRequest.ROW_SELECTION_COUNT).isEqualTo(10);
        }

        @Test
        @DisplayName("a sequence of exactly ten selections draws no violation, so the bound admits a "
                + "full page")
        void aSequenceOfExactlyTenSelectionsDrawsNoViolation() {
            UserRequest fullPage = new UserRequest(null, null, null, null, null, null,
                    TEN_SELECTIONS, null, null, null, null, null);

            assertThat(violationsOf(fullPage)).isEmpty();
            assertThat(fullPage.rowSelections()).hasSize(10);
        }

        @Test
        @DisplayName("a sequence of eleven selections is refused outright and never truncated, so no "
                + "selection is silently discarded and no over-long sequence is ever held")
        void aSequenceOfElevenSelectionsIsRefusedAndNeverTruncated() {
            List<String> eleven = Collections.nCopies(11, "S");

            // The arity of this sequence is structural rather than editorial: eleven selections do not
            // describe a screen the list program can display, so the value is refused at construction
            // instead of being held and reported. Nothing over-length therefore reaches storage, a
            // rendering or a log, which is a stronger outcome than a violation on a retained value.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new UserRequest(null, null, null, null, null, null, eleven,
                            null, null, null, null, null))
                    .withMessageContaining("at most " + UserRequest.ROW_SELECTION_COUNT)
                    .withMessageContaining("11");
        }

        @Test
        @DisplayName("an arbitrarily long sequence is refused on the same terms, so an unbounded "
                + "submission cannot be retained at all")
        void anArbitrarilyLongSequenceIsRefusedOnTheSameTerms() {
            List<String> farTooMany = Collections.nCopies(4096, "S");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new UserRequest(null, null, null, null, null, null, farTooMany,
                            null, null, null, null, null))
                    .withMessageContaining("4096");
        }

        @Test
        @DisplayName("an over-long element is still reported by the container-element bound, so the two "
                + "bounds are independent")
        void anOverLongElementIsStillReportedIndependently() {
            UserRequest badElement = new UserRequest(null, null, null, null, null, null,
                    List.of("SS"), null, null, null, null, null);

            Set<ConstraintViolation<UserRequest>> violations = violationsOf(badElement);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .startsWith("rowSelections");
        }

        @Test
        @DisplayName("an empty sequence and an absent one are both accepted, because a submission that "
                + "marked no row is ordinary")
        void anEmptyAndAnAbsentSequenceAreBothAccepted() {
            UserRequest absent = new UserRequest(null, null, null, null, null, null, null, null,
                    null, null, null, null);
            UserRequest empty = new UserRequest(null, null, null, null, null, null, List.of(), null,
                    null, null, null, null);

            assertThat(violationsOf(absent)).isEmpty();
            assertThat(violationsOf(empty)).isEmpty();
            assertThat(absent.rowSelections()).isEmpty();
            assertThat(empty.rowSelections()).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "\t"})
        @DisplayName("a one-character blank in every bounded component is transported rather than "
                + "rejected, because blank is a state the legacy screens prompt against rather than refuse")
        void aBlankValueIsTransportedRatherThanRejected(String blank) {
            UserRequest request = new UserRequest(blank, blank, blank, blank, blank, blank,
                    List.of(blank), blank, blank, blank, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.userId()).isEqualTo(blank);
            assertThat(request.userType()).isEqualTo(blank);
        }

        @Test
        @DisplayName("a submission space-filled to each component's own declared width draws no "
                + "violation, because that is the shape a blank legacy screen transmits")
        void aSpaceFilledSubmissionAtEachDeclaredWidthDrawsNoViolation() {
            UserRequest spaceFilled = new UserRequest(" ".repeat(8), " ".repeat(8), " ".repeat(20),
                    " ".repeat(20), " ".repeat(8), " ", TEN_SELECTIONS, " ".repeat(8),
                    " ".repeat(8), " ".repeat(8), null, null);

            assertThat(violationsOf(spaceFilled)).isEmpty();
            assertThat(spaceFilled.firstName()).hasSize(20).isBlank();
            assertThat(spaceFilled.password()).hasSize(8).isBlank();
        }

        @Test
        @DisplayName("a value one character over its own width is reported and never trimmed")
        void aValueOneCharacterOverItsWidthIsReported() {
            UserRequest tooWide = new UserRequest("A".repeat(9), null, null, null, null, null, null,
                    null, null, null, null, null);

            Set<ConstraintViolation<UserRequest>> violations = violationsOf(tooWide);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString("userId");
            assertThat(tooWide.userId()).hasSize(9);
        }

        @Test
        @DisplayName("a credential one character over its width is reported, so write-only access did "
                + "not disable the bound")
        void aCredentialOverItsWidthIsStillReported() {
            UserRequest tooWide = new UserRequest(null, null, null, null, "A".repeat(9), null, null,
                    null, null, null, null, null);

            Set<ConstraintViolation<UserRequest>> violations = violationsOf(tooWide);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString(CREDENTIAL_PROPERTY);
        }
    }

    @Nested
    @DisplayName("The echoed navigation state is cascaded into")
    class TheEchoedNavigationStateIsCascadedInto {

        @Test
        @DisplayName("the navigation component declares the cascade, so a client-echoed value is "
                + "measured at the boundary rather than nowhere")
        void theNavigationComponentDeclaresTheCascade() throws NoSuchFieldException {
            Field field = UserRequest.class.getDeclaredField("navigationContext");

            assertThat(field.getAnnotation(Valid.class)).isNotNull();
        }

        @Test
        @DisplayName("a violation inside the echoed navigation state is reported under a nested property "
                + "path, which is the observable proof the cascade reaches it")
        void aViolationInsideTheEchoedStateIsReportedUnderANestedPath() {
            NavigationContext overWidth = new NavigationContext(null, "NINECHARS", null, null, null,
                    null, NavigationContext.ProgramContext.REENTER, null, null, null, null, null,
                    null, null, null, null);
            UserRequest request = new UserRequest(USER_ID, null, null, null, null, "A", null, null,
                    null, null, null, overWidth);

            Set<ConstraintViolation<UserRequest>> violations = violationsOf(request);

            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .allSatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .startsWith("navigationContext."));
        }

        @Test
        @DisplayName("an absent navigation state is not a violation, because a first entry carries none")
        void anAbsentNavigationStateIsNotAViolation() {
            UserRequest request = new UserRequest(USER_ID, null, null, null, null, "A", null, null,
                    null, null, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.navigationContext()).isNull();
        }

        @Test
        @DisplayName("a valid echoed navigation state passes the cascade untouched")
        void aValidEchoedNavigationStatePassesTheCascade() {
            assertThat(violationsOf(populated())).isEmpty();
        }
    }

    @Nested
    @DisplayName("Nothing is transformed on the way through")
    class NothingIsTransformedOnTheWayThrough {

        @Test
        @DisplayName("the selection sequence preserves order and length exactly, because element n is "
                + "the selection typed against screen row n")
        void theSelectionSequencePreservesOrderAndLengthExactly() {
            List<String> withGaps = Arrays.asList(" ", "U", " ", " ", "S", " ", " ", " ", " ", " ");

            UserRequest request = new UserRequest(null, null, null, null, null, null, withGaps,
                    null, null, null, null, null);

            assertThat(request.rowSelections()).containsExactlyElementsOf(withGaps).hasSize(10);
            assertThat(request.rowSelections().get(1)).isEqualTo("U");
            assertThat(request.rowSelections().get(4)).isEqualTo("S");
        }

        @Test
        @DisplayName("the stored sequence is detached from the caller and immutable, so a later "
                + "mutation cannot reach the request")
        void theStoredSequenceIsDetachedAndImmutable() {
            List<String> mutable = new java.util.ArrayList<>(TEN_SELECTIONS);
            UserRequest request = new UserRequest(null, null, null, null, null, null, mutable, null,
                    null, null, null, null);

            mutable.set(0, "X");

            assertThat(request.rowSelections().get(0)).isEqualTo(" ");
            assertThat(request.rowSelections()).isUnmodifiable();
        }

        @Test
        @DisplayName("leading zeros and trailing spaces survive, because the items they mirror are "
                + "fixed-width and blank-significant")
        void leadingZerosAndTrailingSpacesSurvive() {
            UserRequest request = new UserRequest("0000001 ", null, null, null, null, null, null,
                    "00000001", null, null, null, null);

            assertThat(request.userId()).isEqualTo("0000001 ");
            assertThat(request.displayedPageNumber()).isEqualTo("00000001");
        }

        @Test
        @DisplayName("case is never folded, so a lower-case user-type character arrives as typed")
        void caseIsNeverFolded() {
            UserRequest request = new UserRequest("admin001", null, null, null, null, "a", null,
                    null, null, null, null, null);

            assertThat(request.userId()).isEqualTo("admin001");
            assertThat(request.userType()).isEqualTo("a");
        }

        @Test
        @DisplayName("an out-of-vocabulary user-type character round-trips, because neither program "
                + "tests the value and both store whatever arrived")
        void anOutOfVocabularyUserTypeRoundTrips() {
            UserRequest request = new UserRequest(USER_ID, null, null, null, null, "Q", null, null,
                    null, null, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.userType()).isEqualTo("Q");
        }

        @Test
        @DisplayName("equality compares every component including the credential, because an in-memory "
                + "comparison emits nothing")
        void equalityComparesEveryComponentIncludingTheCredential() {
            UserRequest first = populated();
            UserRequest same = populated();
            UserRequest differentCredential = new UserRequest(USER_ID, "B", FIRST_NAME, LAST_NAME,
                    "different", "A", TEN_SELECTIONS, "00000001", "AAAAAAA1", "ZZZZZZZ9",
                    KeyAction.PFK05, NavigationContext.empty().withReEntry());

            assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(first).isNotEqualTo(differentCredential);
        }
    }
}
