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
package com.carddemo.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.api.dto.AccountUpdateRequest;
import com.carddemo.api.dto.AccountUpdateResponse;
import com.carddemo.api.dto.AccountViewResponse;
import com.carddemo.api.dto.BillPaymentRequest;
import com.carddemo.api.dto.BillPaymentResponse;
import com.carddemo.api.dto.CardDetailRequest;
import com.carddemo.api.dto.CardDetailResponse;
import com.carddemo.api.dto.CardListRequest;
import com.carddemo.api.dto.CardListResponse;
import com.carddemo.api.dto.CardUpdateRequest;
import com.carddemo.api.dto.CardUpdateResponse;
import com.carddemo.api.dto.MenuResponse;
import com.carddemo.api.dto.ReportRequest;
import com.carddemo.api.dto.ReportResponse;
import com.carddemo.api.dto.SignOnRequest;
import com.carddemo.api.dto.SignOnResponse;
import com.carddemo.api.dto.TransactionAddRequest;
import com.carddemo.api.dto.TransactionAddResponse;
import com.carddemo.api.dto.TransactionListRequest;
import com.carddemo.api.dto.TransactionListResponse;
import com.carddemo.api.dto.TransactionViewResponse;
import com.carddemo.api.dto.UserRequest;
import com.carddemo.api.dto.UserResponse;
import com.carddemo.service.AccountUpdateService;
import com.carddemo.service.AccountViewService;
import com.carddemo.service.AuthenticationService;
import com.carddemo.service.BillPaymentService;
import com.carddemo.service.CardDetailService;
import com.carddemo.service.CardListService;
import com.carddemo.service.CardUpdateService;
import com.carddemo.service.MenuService;
import com.carddemo.service.ReportRequestService;
import com.carddemo.service.TransactionAddService;
import com.carddemo.service.TransactionListService;
import com.carddemo.service.TransactionViewService;
import com.carddemo.service.UserManagementService;
import com.carddemo.util.PfKeyTranslator;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Holds every per-field identity the screen tier can publish to the contract it is published against.
 *
 * <h2>The two defects this exists to remove</h2>
 *
 * <p>A per-field error carries a field twice, and a reader is meant to be able to act on either name.
 * {@code fieldErrors[].fieldName} is the name a client matches against its own form so it can highlight
 * the input the operator has to correct, and {@code fieldErrors[].screenFieldId} is the legacy map field
 * name a client matches against a rendered screen. {@code focusScreenFieldId} is the same second kind of
 * name, used to position a cursor, which is why every one of the thirteen screen response records bounds
 * it to the seven characters a BMS field name occupies.
 *
 * <p>Nothing asserted either half, and both had drifted:
 *
 * <ul>
 *   <li>The card-update screen published <em>response property names</em> as its focus hint -
 *       {@code embossedName}, {@code accountId}, {@code activeStatus} - so a value of nine to twelve
 *       characters was emitted through a component the same record declares a seven-character bound on.
 *       Twelve of the thirteen screens were correct, and the outlier's own nested field errors carried the
 *       right identifiers at the same moment, which is what made the defect invisible: the correct value
 *       was available and simply not the one published.</li>
 *   <li>Five names on the transaction-add screen, three on the report screen, two on the card-detail
 *       screen and one on the account-view screen resolved to <em>no published property at all</em> -
 *       {@code typeCd} against a request declaring {@code typeCode}, {@code startDate} against a request
 *       declaring {@code startMonth}, {@code accountId} against a request declaring
 *       {@code accountIdFilter}, and an aggregate {@code reportType} the contract never declares. A client
 *       doing the mapping the other ten fields of the same endpoint support silently failed to attribute
 *       the message to any field, and because unknown request properties are ignored rather than refused,
 *       a client that used one of those names got an empty field instead of a refusal.</li>
 * </ul>
 *
 * <h2>Why the constants are read reflectively rather than enumerated here</h2>
 *
 * <p>An enumerated oracle would hold today's names to the contract and say nothing about tomorrow's: a
 * constant added to a service after this class was written would never be looked at. So each enrolled
 * declaring class is read for <em>every</em> constant under its own naming convention, and every value
 * found has to satisfy the rule. A newly added property constant that names nothing the contract declares
 * therefore fails here, by name, at the moment it is introduced.
 *
 * <p>The published side is read the same way, from the record components of the request and response
 * records themselves, so this class hardcodes no property name either. Where an operation takes its input
 * as query parameters rather than a body - account view, transaction view - the parameter names are
 * enrolled explicitly, because there is no record to read them from.
 *
 * <h2>Gate 6</h2>
 *
 * <p>The module's unsafe-code audit commits to zero reflection and scopes itself to
 * {@code src/main/java/**} precisely so that a test may verify a structural property of the production
 * sources without becoming production code. No class enrolled below uses reflection, and this class is
 * not shipped. The same scoping already governs {@code CrossLayerConstantAgreementTest}, which reads
 * private constants for the same kind of reason.
 *
 * <p>The rule this class enforces, and the drift it was written in response to, are recorded in
 * {@code docs/decision-log.md} DL-354.
 */
@DisplayName("published field identity :: every emitted field name resolves, every screen id is a BMS name")
class PublishedFieldIdentityAuditTest {

    /** A BMS map field name: upper-case letters and digits, nothing else. */
    private static final Pattern BMS_FIELD_NAME = Pattern.compile("[A-Z0-9]+");

    /**
     * The width every screen response record bounds its focus hint to, and therefore the width a screen
     * field identifier may not exceed.
     *
     * <p>Not asserted from this constant alone: {@link TheFocusHintBoundIsDeclared} reads the bound off
     * each record's own {@code @Size} declaration and holds it to this value, so the two cannot drift
     * apart quietly.
     */
    private static final int SCREEN_FIELD_ID_WIDTH = 7;

    /**
     * The number of identities the enrolment reads today, asserted exactly rather than as a floor.
     *
     * <p>An exact figure is what makes a silently narrowed read fail: a floor would still pass if a
     * renamed constant family dropped a screen out of the audit while another screen grew. A legitimate
     * addition changes this number, and changing it is the moment to check that the addition is enrolled
     * under the right family.
     */
    private static final int ENROLLED_IDENTITY_COUNT = 95;

    /**
     * One enrolled declaring class: where its identities are declared and what they are published against.
     *
     * @param description how a failure should name the screen
     * @param declaringType the class that declares the identity constants
     * @param propertyConstantMatchers name fragments that select a constant carrying a <em>published
     *     property name</em>; a name matches when it starts with a value that ends in {@code _} and when
     *     it ends with a value that starts with {@code _}
     * @param screenFieldConstantMatchers the same, for a constant carrying a <em>BMS map field name</em>
     * @param excludedConstantNames constants that match a selector by name but carry neither kind of
     *     identity, each excluded for a reason stated at the enrolment site
     * @param publishedTypes the request and response records whose components are the published names
     * @param publishedParameterNames names the operation publishes as query parameters, which no record
     *     carries
     */
    private record ScreenIdentities(String description,
                                    Class<?> declaringType,
                                    List<String> propertyConstantMatchers,
                                    List<String> screenFieldConstantMatchers,
                                    Set<String> excludedConstantNames,
                                    List<Class<?>> publishedTypes,
                                    Set<String> publishedParameterNames) {

        @Override
        public String toString() {
            return description;
        }
    }

    /**
     * Every screen whose service or controller declares a per-field identity as a constant.
     *
     * <p>The card-update row is the one whose two families are named the other way round: it calls the
     * published property {@code FIELD_} and the map field {@code BMS_}, which is exactly the ambiguity the
     * focus-hint defect grew in, and it is enrolled as it is rather than renamed because the property
     * constants are public and referenced from its specification.
     *
     * @return the enrolment, one row per declaring class
     */
    private static Stream<ScreenIdentities> enrolledScreens() {
        return Stream.of(
                new ScreenIdentities("sign-on", AuthenticationService.class,
                        List.of(), List.of("FIELD_"), Set.of(),
                        List.of(SignOnRequest.class, SignOnResponse.class), Set.of()),
                new ScreenIdentities("menu", MenuService.class,
                        List.of(), List.of("_SCREEN_FIELD_ID"), Set.of(),
                        List.of(MenuResponse.class), Set.of("option")),
                new ScreenIdentities("account view :: service", AccountViewService.class,
                        List.of(), List.of("_SCREEN_FIELD_ID"), Set.of(),
                        List.of(AccountViewResponse.class), Set.of("accountId")),
                new ScreenIdentities("account view :: controller", AccountController.class,
                        List.of("_PROPERTY"), List.of(), Set.of(),
                        List.of(AccountViewResponse.class), Set.of("accountId")),
                new ScreenIdentities("account update", AccountUpdateService.class,
                        List.of(), List.of("BMS_"), Set.of(),
                        List.of(AccountUpdateRequest.class, AccountUpdateResponse.class), Set.of()),
                new ScreenIdentities("card list", CardListService.class,
                        List.of(), List.of("BMS_FIELD_"), Set.of(),
                        List.of(CardListRequest.class, CardListResponse.class), Set.of()),
                new ScreenIdentities("card detail", CardDetailService.class,
                        List.of("PROPERTY_"), List.of("FIELD_"), Set.of(),
                        List.of(CardDetailRequest.class, CardDetailResponse.class), Set.of()),
                new ScreenIdentities("card update", CardUpdateService.class,
                        List.of("FIELD_"), List.of("BMS_"), Set.of(),
                        List.of(CardUpdateRequest.class, CardUpdateResponse.class), Set.of()),
                new ScreenIdentities("transaction list", TransactionListService.class,
                        List.of("_PROPERTY"), List.of(), Set.of(),
                        List.of(TransactionListRequest.class, TransactionListResponse.class), Set.of()),
                new ScreenIdentities("transaction view", TransactionViewService.class,
                        List.of("PROPERTY_"), List.of("FIELD_"), Set.of(),
                        List.of(TransactionViewResponse.class), Set.of("transactionId")),
                new ScreenIdentities("transaction add", TransactionAddService.class,
                        List.of("PROPERTY_"), List.of("FIELD_"), Set.of(),
                        List.of(TransactionAddRequest.class, TransactionAddResponse.class), Set.of()),
                new ScreenIdentities("report request", ReportRequestService.class,
                        List.of("PROPERTY_"), List.of("FIELD_"), Set.of(),
                        List.of(ReportRequest.class, ReportResponse.class), Set.of()),
                new ScreenIdentities("bill payment", BillPaymentService.class,
                        List.of("PROPERTY_"), List.of("FIELD_"),
                        // A COBOL record field name carried into a codec diagnostic, not a map field.
                        Set.of("FIELD_ACCT_CURR_BAL"),
                        List.of(BillPaymentRequest.class, BillPaymentResponse.class), Set.of()),
                new ScreenIdentities("admin user", UserManagementService.class,
                        List.of("PROPERTY_"), List.of("FIELD_"), Set.of(),
                        List.of(UserRequest.class, UserResponse.class), Set.of()));
    }

    /** Every screen response record that publishes a focus hint. */
    private static Stream<Class<?>> focusHintCarriers() {
        return Stream.of(SignOnResponse.class, MenuResponse.class, AccountViewResponse.class,
                AccountUpdateResponse.class, CardListResponse.class, CardDetailResponse.class,
                CardUpdateResponse.class, TransactionListResponse.class, TransactionViewResponse.class,
                TransactionAddResponse.class, ReportResponse.class, BillPaymentResponse.class,
                UserResponse.class);
    }

    /**
     * Reads the constants of a class that the selectors pick out and the exclusions do not remove.
     *
     * <p>Non-public constants are made accessible only inside this test, for the reason the class comment
     * gives. A constant that cannot be read fails rather than being skipped, because a silent skip is how
     * an audit stops auditing.
     *
     * @param row the enrolled screen
     * @param matchers the selectors of the family being read
     * @return the values by constant name, in name order; empty when the family is not declared
     */
    private static TreeMap<String, String> identitiesOf(final ScreenIdentities row,
                                                        final List<String> matchers) {
        final TreeMap<String, String> identities = new TreeMap<>();
        if (matchers.isEmpty()) {
            return identities;
        }
        for (final Field field : row.declaringType().getDeclaredFields()) {
            final int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)
                    || field.getType() != String.class) {
                continue;
            }
            final String name = field.getName();
            if (row.excludedConstantNames().contains(name) || !selects(matchers, name)) {
                continue;
            }
            if (!field.canAccess(null) && !field.trySetAccessible()) {
                throw new AssertionError("a constant of " + row.declaringType().getName()
                        + " could not be made accessible: " + name);
            }
            try {
                identities.put(name, (String) field.get(null));
            } catch (final IllegalAccessException unreachable) {
                throw new AssertionError("a constant of " + row.declaringType().getName()
                        + " could not be read: " + name, unreachable);
            }
        }
        return identities;
    }

    /**
     * Tests whether a constant name belongs to a family.
     *
     * @param matchers the family's selectors, prefixes ending in an underscore and suffixes starting with
     *     one
     * @param name the constant name
     * @return {@code true} when any selector matches
     */
    private static boolean selects(final List<String> matchers, final String name) {
        for (final String matcher : matchers) {
            if (matcher.startsWith("_") ? name.endsWith(matcher) : name.startsWith(matcher)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every name the operation's own contract declares: the components of its records, plus any query
     * parameter it takes instead of a body.
     *
     * @param row the enrolled screen
     * @return the published names
     */
    private static Set<String> publishedNamesOf(final ScreenIdentities row) {
        final Set<String> published = new TreeSet<>(row.publishedParameterNames());
        for (final Class<?> type : row.publishedTypes()) {
            final RecordComponent[] components = type.getRecordComponents();
            assertThat(components)
                    .as("%s must be a record so its published components can be read", type.getName())
                    .isNotNull();
            for (final RecordComponent component : components) {
                published.add(component.getName());
            }
        }
        return published;
    }

    /**
     * Reads the width bound declared on a record's focus hint.
     *
     * <p>Read from the accessor rather than the record component, because the bound annotation does not
     * target a record component and is propagated to the field, the accessor and the constructor
     * parameter instead.
     *
     * @param type the response record
     * @return the declared maximum width
     */
    private static int declaredFocusHintBound(final Class<?> type) {
        try {
            final Method accessor = type.getDeclaredMethod("focusScreenFieldId");
            final Size bound = accessor.getAnnotation(Size.class);
            assertThat(bound)
                    .as("%s must bound its focus hint, otherwise nothing stops a property name being"
                            + " published through it", type.getSimpleName())
                    .isNotNull();
            return bound.max();
        } catch (final NoSuchMethodException absent) {
            throw new AssertionError(type.getName() + " publishes no focus hint", absent);
        }
    }

    @Nested
    @DisplayName("the reader itself is sound, so an empty read cannot pass as conformance")
    final class TheReaderIsSound {

        @ParameterizedTest(name = "{0} declares at least one identity")
        @MethodSource("com.carddemo.api.PublishedFieldIdentityAuditTest#enrolledScreens")
        @DisplayName("every enrolled screen really declares identities under the conventions it is "
                + "enrolled with, so a renamed constant family is reported rather than passing unseen")
        void everyEnrolledScreenDeclaresIdentities(final ScreenIdentities row) {
            final int found = identitiesOf(row, row.propertyConstantMatchers()).size()
                    + identitiesOf(row, row.screenFieldConstantMatchers()).size();
            assertThat(found)
                    .as("%s is enrolled with property selectors %s and screen selectors %s and matched"
                            + " nothing, which means the convention changed and this audit stopped"
                            + " looking at it", row.description(), row.propertyConstantMatchers(),
                            row.screenFieldConstantMatchers())
                    .isPositive();
        }

        @Test
        @DisplayName("the enrolment together reads exactly the identities the screen tier declares")
        void theEnrolmentCoversTheKnownIdentities() {
            final int total = enrolledScreens()
                    .mapToInt(row -> identitiesOf(row, row.propertyConstantMatchers()).size()
                            + identitiesOf(row, row.screenFieldConstantMatchers()).size())
                    .sum();
            assertThat(total)
                    .as("a change in what the enrolment reads means either a screen gained an identity or"
                            + " a constant family was renamed and a screen dropped out of this audit"
                            + " unnoticed; the two are indistinguishable from a floor, so the figure is"
                            + " exact")
                    .isEqualTo(ENROLLED_IDENTITY_COUNT);
        }
    }

    @Nested
    @DisplayName("every name a per-field error can publish resolves to a property the contract declares")
    final class EveryEmittedFieldNameResolves {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.api.PublishedFieldIdentityAuditTest#enrolledScreens")
        @DisplayName("no screen emits a field name its own request or response does not declare, which is"
                + " what lets a client map a message back to the input the operator has to correct")
        void noScreenEmitsAnUnpublishedFieldName(final ScreenIdentities row) {
            final Set<String> published = publishedNamesOf(row);
            final List<String> unresolvable = new ArrayList<>();
            identitiesOf(row, row.propertyConstantMatchers()).forEach((name, value) -> {
                if (!published.contains(value)) {
                    unresolvable.add(name + " = \"" + value + "\"");
                }
            });

            assertThat(unresolvable)
                    .as("%s would emit a field name no caller can resolve; the names its contract"
                            + " declares are %s", row.description(), published)
                    .isEmpty();
        }

        @Test
        @DisplayName("the card list's positional selection stem resolves once per row slot, because the "
                + "name it emits is the stem followed by the slot number rather than the stem itself")
        void theSelectionStemResolvesPerSlot() {
            final String stem = identitiesOf(enrolledScreens()
                    .filter(row -> row.declaringType() == CardListService.class)
                    .findFirst().orElseThrow(), List.of("PROPERTY_")).values().iterator().next();

            final Set<String> slots = new TreeSet<>();
            for (final RecordComponent component : CardListRequest.class.getRecordComponents()) {
                if (component.getName().matches(Pattern.quote(stem) + "\\d+")) {
                    slots.add(component.getName());
                }
            }

            assertThat(slots)
                    .as("the card list faults a marked row by the stem followed by its slot number, so"
                            + " the request has to declare one component per row slot under that stem")
                    .containsExactly(stem + "1", stem + "2", stem + "3", stem + "4",
                            stem + "5", stem + "6", stem + "7");
        }
    }

    @Nested
    @DisplayName("every screen field identifier is a BMS map field name that fits the published bound")
    final class EveryScreenFieldIdentifierIsABmsName {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.api.PublishedFieldIdentityAuditTest#enrolledScreens")
        @DisplayName("no screen carries a screen field identifier that is not an upper-case map field "
                + "name of at most seven characters, which is the defect the card-update focus hint was")
        void noScreenCarriesANonBmsScreenFieldIdentifier(final ScreenIdentities row) {
            final List<String> offending = new ArrayList<>();
            identitiesOf(row, row.screenFieldConstantMatchers()).forEach((name, value) -> {
                if (value == null || value.length() > SCREEN_FIELD_ID_WIDTH
                        || !BMS_FIELD_NAME.matcher(value).matches()) {
                    offending.add(name + " = \"" + value + "\"");
                }
            });

            assertThat(offending)
                    .as("%s carries a screen field identifier that no rendered screen could match and"
                            + " that the focus hint's own bound would refuse", row.description())
                    .isEmpty();
        }

        @Test
        @DisplayName("the card-update focus cascade answers with map field names, never with the response "
                + "property names the same class also declares")
        void theCardUpdateFocusCascadeAnswersWithMapFieldNames() {
            final Set<String> propertyNames = new LinkedHashSet<>(
                    identitiesOf(enrolledScreens()
                            .filter(row -> row.declaringType() == CardUpdateService.class)
                            .findFirst().orElseThrow(), List.of("FIELD_")).values());

            assertThat(CardUpdateService.BMS_ACCOUNT_ID).isEqualTo("ACCTSID");
            assertThat(propertyNames)
                    .as("the two families must stay distinct: publishing one where the other belongs is"
                            + " exactly how the focus hint came to carry embossedName")
                    .doesNotContain(CardUpdateService.BMS_ACCOUNT_ID, CardUpdateService.BMS_CARD_NUMBER,
                            CardUpdateService.BMS_EMBOSSED_NAME, CardUpdateService.BMS_ACTIVE_STATUS,
                            CardUpdateService.BMS_EXPIRY_MONTH, CardUpdateService.BMS_EXPIRY_YEAR);
        }
    }

    @Nested
    @DisplayName("the focus hint bound is declared, identical on every screen, and seven characters wide")
    final class TheFocusHintBoundIsDeclared {

        @ParameterizedTest(name = "{0} bounds its focus hint to seven characters")
        @MethodSource("com.carddemo.api.PublishedFieldIdentityAuditTest#focusHintCarriers")
        @DisplayName("every screen response declares the same bound, so a value that fits one screen's "
                + "hint fits all of them")
        void everyScreenResponseDeclaresTheSameBound(final Class<?> type) {
            assertThat(declaredFocusHintBound(type))
                    .as("%s declares a focus-hint bound of its own, which would let a value legal on one"
                            + " screen be illegal on another", type.getSimpleName())
                    .isEqualTo(SCREEN_FIELD_ID_WIDTH);
        }
    }

    @Nested
    @DisplayName("the attention-key parameter publishes the vocabulary it accepts")
    final class TheAttentionKeyVocabularyIsPublished {

        @Test
        @DisplayName("both account operations publish the transmitted-identifier vocabulary as an "
                + "enumeration, and publish exactly the identifiers the key translator recognises")
        void bothAccountOperationsPublishTheVocabulary() {
            final List<Method> declaring = new ArrayList<>();
            for (final Method method : AccountController.class.getDeclaredMethods()) {
                for (final Parameter parameter : method.getParameters()) {
                    final RequestParam bound = parameter.getAnnotation(RequestParam.class);
                    if (bound != null
                            && AccountController.ATTENTION_KEY_PARAM.equals(bound.name())) {
                        declaring.add(method);
                    }
                }
            }

            assertThat(declaring)
                    .as("both the view and the update operation take the transmitted identifier, so both"
                            + " have to publish what it accepts")
                    .hasSize(2);

            final Set<String> recognised = new TreeSet<>(PfKeyTranslator.recognisedIdentifiers());
            for (final Method method : declaring) {
                assertThat(publishedVocabularyOf(method))
                        .as("%s publishes a vocabulary that differs from the one the attention-key"
                                + " translator actually recognises, so a caller reading the contract"
                                + " would send an identifier the turn refuses, or would never learn of"
                                + " one it accepts", method.getName())
                        .isEqualTo(recognised);
            }
        }

        @Test
        @DisplayName("the published vocabulary is upper case and carries no zero-padded function-key "
                + "number, because the copybook declares neither")
        void thePublishedVocabularyIsUpperCaseAndUnpadded() {
            for (final String identifier : PfKeyTranslator.recognisedIdentifiers()) {
                assertThat(identifier)
                        .isEqualTo(identifier.toUpperCase(Locale.ROOT))
                        .doesNotMatch("DFHPF0\\d");
            }
        }

        /**
         * Reads the enumeration a method publishes for the transmitted attention identifier.
         *
         * @param method the operation
         * @return the published values
         */
        private Set<String> publishedVocabularyOf(final Method method) {
            for (final Parameter parameter : method.getParameters()) {
                final RequestParam bound = parameter.getAnnotation(RequestParam.class);
                if (bound == null || !AccountController.ATTENTION_KEY_PARAM.equals(bound.name())) {
                    continue;
                }
                final io.swagger.v3.oas.annotations.Parameter documented =
                        parameter.getAnnotation(io.swagger.v3.oas.annotations.Parameter.class);
                assertThat(documented)
                        .as("%s must document the transmitted identifier", method.getName())
                        .isNotNull();
                return new TreeSet<>(Arrays.asList(documented.schema().allowableValues()));
            }
            throw new AssertionError(method.getName() + " does not take the attention identifier");
        }
    }
}
