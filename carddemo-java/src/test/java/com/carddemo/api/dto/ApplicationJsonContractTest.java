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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.carddemo.domain.enums.KeyAction;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.StreamWriteFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the JSON mapper the deployed application actually uses.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Five request and response types in this package are covered by fast, pure unit suites that
 * build a local mapper by hand, configured to mirror the four {@code spring.jackson} settings the
 * module declares in {@code src/main/resources/application.yml}. Those suites are valuable and are
 * deliberately retained: they run in milliseconds, they start nothing, and they pin the wire shape
 * of each type in isolation. What they cannot do is prove that the declared configuration is the
 * configuration a deployed instance ends up with. A hand-built mapper asserts the settings the test
 * author believed were in force; if {@code application.yml} were edited, or if a framework upgrade
 * moved a default, every one of those suites would keep passing while the real endpoint changed
 * shape underneath them.
 *
 * <p>This slice closes that gap. It obtains the {@link ObjectMapper} from a real Spring context in
 * which the module's own {@code application.yml} has been read through the framework's
 * configuration-data machinery and the framework's own Jackson auto-configuration has run. Nothing
 * about the mapper is constructed here, so every assertion below is an assertion about the
 * deployed object.
 *
 * <h2>Which of the four settings are genuinely discriminating</h2>
 *
 * <p>Two of the four declared settings differ from the framework's own defaults and therefore prove
 * on their own that the file was read and applied:
 *
 * <ul>
 *   <li>{@code default-property-inclusion: non_null} - the framework default writes {@code null}
 *       members; the module omits them, because a screen field the legacy map did not carry must be
 *       absent rather than present-and-empty.</li>
 *   <li>{@code generator.write-bigdecimal-as-plain: true} - the framework default permits
 *       scientific notation, which no consumer of a fixed-width monetary field can interpret.</li>
 * </ul>
 *
 * <p>The other two - lenient unknown input and ISO-8601 rather than epoch dates - happen to
 * coincide with current framework defaults. They are still asserted, and that is deliberate: a
 * setting whose value merely coincides with a default today is exactly the setting a future upgrade
 * can flip without anybody noticing. Asserting the behaviour rather than the declaration means the
 * contract holds whichever side of the coincidence the framework moves.
 *
 * <h2>What this test does not do</h2>
 *
 * <p>It starts no web server, opens no database connection, launches no container and reads no
 * profile-scoped overlay: the base configuration is the published wire contract, and the local,
 * test and production overlays alter datasource, messaging and observability settings rather than
 * serialization. It asserts no timing figure, and it reads a clock only where a temporal value has
 * to be constructed to observe the date setting.
 *
 * <p>It does not re-assert the per-field validation constraints or the per-type invariants of the
 * request and response records. Those belong to the five focused suites, which remain the primary
 * cover for each type; this file asserts only what the mapper contributes.
 *
 * <p>Provenance: the wire contract derives from the 17 symbolic screen maps of the legacy online
 * estate at checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Behaviour is cited, never transcribed.
 */
@DisplayName("Application JSON contract: the mapper the deployed application actually uses")
class ApplicationJsonContractTest {

    /**
     * A context carrying the module's own {@code application.yml} and the framework's Jackson
     * auto-configuration, and nothing else.
     *
     * <p>The configuration-data initializer is what makes this a contract test rather than a
     * framework test: it reads the very file that ships inside the artifact, from the classpath, by
     * the same mechanism a running instance uses. The auto-configuration then builds the mapper
     * from the bound properties exactly as it does at start-up.
     *
     * <p>No application configuration class is registered, and none is searched for: this module's
     * entry point is not required for the wire contract to be observable, so the slice stays
     * independent of it.
     */
    private static final ApplicationContextRunner DEPLOYED_CONTEXT = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    /** Summary line used wherever a response needs one. */
    private static final String SUMMARY = "Account update rejected - correct the marked fields";

    /** Screen field identifier of the account status. */
    private static final String SCREEN_ACCT_STATUS = "ACSTTUS";

    /** Request-contract property name paired with the account status. */
    private static final String PROP_ACCT_STATUS = "acctStatus";

    /** Screen field identifier of the credit limit - the account update's monetary field. */
    private static final String SCREEN_CREDIT_LIMIT = "ACRDLIM";

    /** Request-contract property name paired with the credit limit. */
    private static final String PROP_CREDIT_LIMIT = "creditLimit";

    /** Wire property that must be absent from a response carrying no focus hint. */
    private static final String KEY_FOCUS = "focusScreenFieldId";

    /** Wire property naming the per-field error collection. */
    private static final String KEY_FIELD_ERRORS = "fieldErrors";

    /** Wire property naming the summary line. */
    private static final String KEY_MESSAGE = "message";

    /** Wire property naming the per-entry published state. */
    private static final String KEY_STATE = "state";

    /**
     * A value whose default textual form is scientific. Its plain form is {@code 100}, so the two
     * forms are trivially distinguishable and the assertion cannot pass by coincidence.
     */
    private static final String SCIENTIFIC_AMOUNT = "1E+2";

    /** The plain rendering the contract requires of {@link #SCIENTIFIC_AMOUNT}. */
    private static final String PLAIN_AMOUNT = "100";

    /**
     * Builds the mapper the five fast pure-DTO suites build for themselves, from the same four
     * settings, so that the two can be compared.
     *
     * <p>This is the drift sentinel. It exists in exactly one place - here - and its only purpose
     * is to fail when the hand-built configuration those suites rely on stops matching the deployed
     * one. Nothing in this file asserts the wire contract through it.
     *
     * @return a mapper configured as the fast suites configure theirs
     */
    private static ObjectMapper fastSuiteEquivalentMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    /**
     * A response carrying a summary, two entries in the two published states and a focus hint.
     *
     * @return a fully populated error body
     */
    private static ErrorResponse populatedErrorResponse() {
        return new ErrorResponse(SUMMARY,
                List.of(new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                                ErrorResponse.FieldState.MISSING),
                        new ErrorResponse.FieldError(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                                ErrorResponse.FieldState.INVALID,
                                "Credit limit must be a signed amount")),
                SCREEN_CREDIT_LIMIT);
    }

    /**
     * A statement row whose every carriage hazard is present at once: leading zeroes a numeric type
     * would erase, trailing blanks a trimming type would eat, mixed case a folding type would
     * flatten and a negative amount at scale two.
     *
     * @return a populated statement row
     */
    private static StatementSummary populatedStatementSummary() {
        return new StatementSummary("0000000000000011", "0000000000000042", "01", "0005",
                "POS TERM  ", "Purchase at merchant  ", new BigDecimal("-1234.56"), "000000001",
                "Merchant Name Ltd  ", "Anytown  ", "0000012345", "2024-01-31-10.15.30.123456",
                "2024-02-01-00.00.00.000000");
    }

    /**
     * A navigation context in the re-entry state with a representative subset populated.
     *
     * @return a populated navigation context
     */
    private static NavigationContext populatedNavigationContext() {
        return new NavigationContext("CAUP", "COACTUPC", "CAVW", "COACTVWC", "ADMIN001", "A",
                NavigationContext.ProgramContext.REENTER, "000000011", "Mary Ann", "  ", "Smith",
                "00000000011", "Y", "0000000000000011", "CACTUPA", "COACTUP");
    }

    @Nested
    @DisplayName("The object under test is the deployed mapper, built from the module's own file")
    class DeployedMapperProvenance {

        @Test
        @DisplayName("the context starts with the module's configuration file applied and supplies exactly "
                + "one mapper, so there is no second mapper an endpoint could pick up instead")
        void theContextSuppliesExactlyOneMapper() {
            DEPLOYED_CONTEXT.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(ObjectMapper.class);
                assertThat(context.getBean(ObjectMapper.class))
                        .as("the mapper is the framework's, not one this test built")
                        .isNotSameAs(fastSuiteEquivalentMapper());
            });
        }

        @Test
        @DisplayName("the declared inclusion policy reached the deployed mapper, which is the setting that "
                + "differs from the framework default and therefore proves the file was read")
        void theDeclaredInclusionPolicyReachedTheMapper() {
            DEPLOYED_CONTEXT.run(context -> {
                ObjectMapper mapper = context.getBean(ObjectMapper.class);

                String payload = mapper.writeValueAsString(new ErrorResponse(SUMMARY));

                assertThat(payload)
                        .as("a framework-default mapper would have written the absent focus hint as "
                                + "null; this one omits it, which only the module's file asks for")
                        .doesNotContain(KEY_FOCUS)
                        .doesNotContain("null");
            });
        }

        @Test
        @DisplayName("the declared plain-decimal policy reached the deployed mapper, the second setting "
                + "that differs from the framework default")
        void theDeclaredPlainDecimalPolicyReachedTheMapper() {
            DEPLOYED_CONTEXT.run(context -> {
                ObjectMapper mapper = context.getBean(ObjectMapper.class);

                String rendered = mapper.writeValueAsString(new BigDecimal(SCIENTIFIC_AMOUNT));

                assertThat(rendered)
                        .as("a framework-default mapper would have written this as %s",
                                SCIENTIFIC_AMOUNT)
                        .isEqualTo(PLAIN_AMOUNT);
            });
        }

        @Test
        @DisplayName("the mapper the five fast pure-DTO suites build still behaves identically to the "
                + "deployed one, so a change to the module's file fails here rather than silently "
                + "invalidating those suites")
        void theFastSuiteMapperStillMatchesTheDeployedMapper() {
            DEPLOYED_CONTEXT.run(context -> {
                ObjectMapper deployed = context.getBean(ObjectMapper.class);
                ObjectMapper handBuilt = fastSuiteEquivalentMapper();

                assertThat(deployed.writeValueAsString(populatedErrorResponse()))
                        .isEqualTo(handBuilt.writeValueAsString(populatedErrorResponse()));
                assertThat(deployed.writeValueAsString(new ErrorResponse(SUMMARY)))
                        .isEqualTo(handBuilt.writeValueAsString(new ErrorResponse(SUMMARY)));
                assertThat(deployed.writeValueAsString(populatedStatementSummary()))
                        .isEqualTo(handBuilt.writeValueAsString(populatedStatementSummary()));
                assertThat(deployed.writeValueAsString(populatedNavigationContext()))
                        .isEqualTo(handBuilt.writeValueAsString(populatedNavigationContext()));
                assertThat(deployed.writeValueAsString(new BigDecimal(SCIENTIFIC_AMOUNT)))
                        .isEqualTo(handBuilt.writeValueAsString(new BigDecimal(SCIENTIFIC_AMOUNT)));

                String withUnknown = "{\"userId\":\"ADMIN001\",\"clientEcho\":\"ignored\"}";
                assertThat(deployed.readValue(withUnknown, SignOnRequest.class))
                        .isEqualTo(handBuilt.readValue(withUnknown, SignOnRequest.class));
            });
        }
    }

    @Nested
    @DisplayName("Absent members are omitted rather than sent as null")
    class NonNullOmission {

        @Test
        @DisplayName("a response with no focus hint omits that property and still carries the summary and "
                + "an empty entry collection, because empty is not the same as absent")
        void aResponseWithoutAFocusHintOmitsIt() {
            DEPLOYED_CONTEXT.run(context -> {
                JsonNode payload = context.getBean(ObjectMapper.class)
                        .valueToTree(new ErrorResponse(SUMMARY));

                assertThat(payload.has(KEY_FOCUS)).isFalse();
                assertThat(payload.get(KEY_MESSAGE).asText()).isEqualTo(SUMMARY);
                assertThat(payload.get(KEY_FIELD_ERRORS).isArray()).isTrue();
                assertThat(payload.get(KEY_FIELD_ERRORS)).isEmpty();
            });
        }

        @Test
        @DisplayName("an entry with no per-field wording omits that property and keeps the other three, so "
                + "the optional member is genuinely optional through the deployed mapper")
        void anEntryWithoutAWordingOmitsIt() {
            DEPLOYED_CONTEXT.run(context -> {
                JsonNode entry = context.getBean(ObjectMapper.class)
                        .valueToTree(new ErrorResponse.FieldError(PROP_ACCT_STATUS,
                                SCREEN_ACCT_STATUS, ErrorResponse.FieldState.MISSING));

                assertThat(entry.has(KEY_MESSAGE)).isFalse();
                assertThat(entry.get("fieldName").asText()).isEqualTo(PROP_ACCT_STATUS);
                assertThat(entry.get("screenFieldId").asText()).isEqualTo(SCREEN_ACCT_STATUS);
                assertThat(entry.get(KEY_STATE).asText()).isEqualTo("MISSING");
            });
        }

        @Test
        @DisplayName("a 43-component account update carrying three values renders exactly three properties, "
                + "so an unkeyed screen field is absent from the wire rather than present and null")
        void anAccountUpdateRendersOnlyTheComponentsItCarries() {
            DEPLOYED_CONTEXT.run(context -> {
                ObjectMapper mapper = context.getBean(ObjectMapper.class);
                AccountUpdateRequest request = mapper.readValue(
                        "{\"accountId\":\"00000000011\",\"accountStatus\":\"Y\","
                                + "\"creditLimit\":\"5000.00\"}",
                        AccountUpdateRequest.class);

                JsonNode payload = mapper.valueToTree(request);

                assertThat(payload.properties())
                        .as("three of forty-three components were supplied")
                        .hasSize(3);
                assertThat(payload.get("accountId").asText()).isEqualTo("00000000011");
                assertThat(payload.get("accountStatus").asText()).isEqualTo("Y");
                assertThat(payload.get("creditLimit").asText())
                        .as("the monetary components carry the raw screen lexeme, so the wire form is "
                                + "the quoted text the operator typed")
                        .isEqualTo("5000.00");
                assertThat(payload.get("creditLimit").isTextual()).isTrue();
                assertThat(payload.has("middleName")).isFalse();
                assertThat(payload.has("addressLine2")).isFalse();
            });
        }

        @Test
        @DisplayName("page metadata omits its absent text members while its primitive and mandatory "
                + "members remain, because a primitive has no absent state to omit and the browse "
                + "direction is never optional")
        void pageMetadataOmitsAbsentTextButKeepsPrimitives() {
            DEPLOYED_CONTEXT.run(context -> {
                // The direction is deliberately mandatory on this type - the legacy programs always
                // branch on an explicit attention key - so the omittable members here are the three
                // text ones: the browse's two boundary cursors, both absent on a single-page
                // result, and the displayed page number.
                JsonNode payload = context.getBean(ObjectMapper.class).valueToTree(
                        new PageMetadata(PageMetadata.CARD_LIST_PAGE_SIZE, null, null,
                                PageMetadata.PagingDirection.FORWARD, false, false, null));

                assertThat(payload.has("previousCursorKey")).isFalse();
                assertThat(payload.has("nextCursorKey")).isFalse();
                assertThat(payload.has("displayedPageNumber")).isFalse();
                assertThat(payload.get("direction").asText()).isEqualTo("FORWARD");
                assertThat(payload.get("pageSize").asInt())
                        .isEqualTo(PageMetadata.CARD_LIST_PAGE_SIZE);
                assertThat(payload.get("hasMorePages").asBoolean()).isFalse();
                assertThat(payload.get("hasPreviousPages").asBoolean()).isFalse();
                assertThat(payload.properties())
                        .as("the page size, the direction and the two availability flags, and none"
                                + " of the three absent text members")
                        .hasSize(4);
            });
        }
    }

    @Nested
    @DisplayName("Input a client echoes back is tolerated rather than rejected")
    class UnknownPropertyTolerance {

        @Test
        @DisplayName("an unknown top-level property is ignored, which is what lets a client echo a whole "
                + "response body back as its next request")
        void anUnknownTopLevelPropertyIsIgnored() {
            DEPLOYED_CONTEXT.run(context -> {
                SignOnRequest request = context.getBean(ObjectMapper.class).readValue(
                        "{\"userId\":\"ADMIN001\",\"password\":\"unset   \","
                                + "\"screenTitle\":\"echoed back\"}",
                        SignOnRequest.class);

                assertThat(request.userId()).isEqualTo("ADMIN001");
                assertThat(request.password()).isEqualTo("unset   ");
            });
        }

        @Test
        @DisplayName("an unknown property nested inside an entry is ignored too, so tolerance is not only "
                + "a top-level property of the contract")
        void anUnknownNestedPropertyIsIgnored() {
            DEPLOYED_CONTEXT.run(context -> {
                ErrorResponse response = context.getBean(ObjectMapper.class).readValue(
                        "{\"message\":\"rejected\",\"fieldErrors\":[{\"fieldName\":\"acctStatus\","
                                + "\"screenFieldId\":\"ACSTTUS\",\"state\":\"MISSING\","
                                + "\"terminalAttribute\":\"DFHRED\"}]}",
                        ErrorResponse.class);

                assertThat(response.fieldErrors()).hasSize(1);
                assertThat(response.fieldErrors().get(0).screenFieldId())
                        .isEqualTo(SCREEN_ACCT_STATUS);
                assertThat(response.fieldErrors().get(0).state())
                        .isEqualTo(ErrorResponse.FieldState.MISSING);
                assertThat(response.fieldErrors().get(0).message()).isNull();
            });
        }

        @Test
        @DisplayName("an unknown property on the largest request type is ignored, so a client may echo the "
                + "four keyable-but-undecorated screen fields it received without being rejected")
        void anUnknownPropertyOnTheLargestRequestIsIgnored() {
            DEPLOYED_CONTEXT.run(context -> {
                AccountUpdateRequest request = context.getBean(ObjectMapper.class).readValue(
                        "{\"accountId\":\"00000000011\",\"ACSTTUS\":\"Y\","
                                + "\"screenTitle\":\"Update Account\"}",
                        AccountUpdateRequest.class);

                assertThat(request.accountId()).isEqualTo("00000000011");
                assertThat(request.accountStatus())
                        .as("the legacy screen identifier is not a property name, so it is ignored "
                                + "rather than mapped")
                        .isNull();
            });
        }
    }

    @Nested
    @DisplayName("Decimal members cross the wire in plain form, at the scale they were given")
    class DecimalWireForm {

        @Test
        @DisplayName("a statement amount whose default form is scientific is written plainly, because no "
                + "consumer of a fixed-width monetary field can interpret an exponent")
        void aScientificAmountIsWrittenPlainly() {
            DEPLOYED_CONTEXT.run(context -> {
                StatementSummary row = new StatementSummary(null, null, null, null, null, null,
                        new BigDecimal(SCIENTIFIC_AMOUNT), null, null, null, null, null, null);

                String payload = context.getBean(ObjectMapper.class).writeValueAsString(row);

                assertThat(payload)
                        .contains(PLAIN_AMOUNT)
                        .doesNotContain(SCIENTIFIC_AMOUNT)
                        .doesNotContain("E+")
                        .doesNotContain("e+");
            });
        }

        @Test
        @DisplayName("a very small amount is written plainly as well, so the policy holds for negative "
                + "exponents and not only for positive ones")
        void aVerySmallAmountIsWrittenPlainly() {
            DEPLOYED_CONTEXT.run(context -> {
                String rendered = context.getBean(ObjectMapper.class)
                        .writeValueAsString(new BigDecimal("0.00000001"));

                assertThat(rendered).isEqualTo("0.00000001").doesNotContain("E").doesNotContain("e");
            });
        }

        @Test
        @DisplayName("a scale-two amount survives a round trip through the deployed mapper with its scale "
                + "and its trailing zero intact, so no floating-point substitution happens on the wire")
        void aScaleTwoAmountSurvivesWithItsScale() {
            DEPLOYED_CONTEXT.run(context -> {
                ObjectMapper mapper = context.getBean(ObjectMapper.class);
                StatementSummary row = new StatementSummary(null, null, null, null, null, null,
                        new BigDecimal("100.00"), null, null, null, null, null, null);

                String payload = mapper.writeValueAsString(row);
                StatementSummary back = mapper.readValue(payload, StatementSummary.class);

                assertThat(payload).contains("100.00");
                assertThat(back.amount()).isEqualTo(new BigDecimal("100.00"));
                assertThat(back.amount().scale())
                        .as("two decimal places, exactly as the record layout declares")
                        .isEqualTo(2);
                assertThat(back.amount()).isNotInstanceOf(Double.class);
            });
        }

        @Test
        @DisplayName("a negative amount keeps its sign and its scale, so a credit is never confused with a "
                + "debit by the serialization layer")
        void aNegativeAmountKeepsItsSignAndScale() {
            DEPLOYED_CONTEXT.run(context -> {
                ObjectMapper mapper = context.getBean(ObjectMapper.class);

                StatementSummary back = mapper.readValue(
                        mapper.writeValueAsString(populatedStatementSummary()),
                        StatementSummary.class);

                assertThat(back.amount()).isEqualTo(new BigDecimal("-1234.56"));
                assertThat(back.amount().signum()).isNegative();
                assertThat(back.amount().scale()).isEqualTo(2);
            });
        }

        @Test
        @DisplayName("an account update's monetary component is text, so the decimal policy never "
                + "reaches it and the operator's characters survive exactly")
        void anAccountUpdateAmountIsTextAndIsNeverNormalised() {
            DEPLOYED_CONTEXT.run(context -> {
                ObjectMapper mapper = context.getBean(ObjectMapper.class);
                AccountUpdateRequest request = mapper.readValue(
                        "{\"creditLimit\":\"" + SCIENTIFIC_AMOUNT + "\"}",
                        AccountUpdateRequest.class);

                String payload = mapper.writeValueAsString(request);

                assertThat(request.creditLimit())
                        .as("the five monetary components carry the 15-character screen lexeme, so a "
                                + "shape the decimal policy would have rewritten to %s is carried "
                                + "through instead", PLAIN_AMOUNT)
                        .isEqualTo(SCIENTIFIC_AMOUNT);
                assertThat(payload)
                        .as("the lexeme is republished as the quoted text it arrived as, because "
                                + "classifying it is the account-update cascade's decision")
                        .isEqualTo("{\"creditLimit\":\"" + SCIENTIFIC_AMOUNT + "\"}");
            });
        }
    }

    @Nested
    @DisplayName("Enumerated and temporal members cross the wire as text")
    class EnumAndTemporalWireForm {

        @Test
        @DisplayName("a published field state crosses as its own name, never as an ordinal, so a client "
                + "cannot silently read MISSING as INVALID after a constant is reordered")
        void aFieldStateCrossesAsItsName() {
            DEPLOYED_CONTEXT.run(context -> {
                JsonNode payload = context.getBean(ObjectMapper.class)
                        .valueToTree(populatedErrorResponse());
                JsonNode states = payload.get(KEY_FIELD_ERRORS);

                assertThat(states.get(0).get(KEY_STATE).isTextual()).isTrue();
                assertThat(states.get(0).get(KEY_STATE).asText()).isEqualTo("MISSING");
                assertThat(states.get(1).get(KEY_STATE).asText()).isEqualTo("INVALID");
                assertThat(states.get(0).get(KEY_STATE).isNumber()).isFalse();
            });
        }

        @Test
        @DisplayName("a paging direction and a program context both cross as their names, and both parse "
                + "back to the same constant")
        void navigationEnumsCrossAsTheirNames() {
            DEPLOYED_CONTEXT.run(context -> {
                ObjectMapper mapper = context.getBean(ObjectMapper.class);
                PageMetadata page = PageMetadata.backward(PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                        "00000000000000000000000042", "00000000000000000000000051", true, true,
                        "0000002");

                JsonNode pagePayload = mapper.valueToTree(page);
                JsonNode contextPayload = mapper.valueToTree(populatedNavigationContext());

                assertThat(pagePayload.get("direction").asText()).isEqualTo("BACKWARD");
                assertThat(contextPayload.get("programContext").asText()).isEqualTo("REENTER");
                assertThat(mapper.readValue(mapper.writeValueAsString(page), PageMetadata.class)
                        .direction())
                        .isEqualTo(PageMetadata.PagingDirection.BACKWARD);
            });
        }

        @Test
        @DisplayName("an attention key crosses as its constant name rather than its five-byte legacy "
                + "value, so the terminal encoding stays out of the published contract")
        void anAttentionKeyCrossesAsItsConstantName() {
            DEPLOYED_CONTEXT.run(context -> {
                ObjectMapper mapper = context.getBean(ObjectMapper.class);
                ScreenWorkArea area = new ScreenWorkArea(KeyAction.PFK03, null, null, null, null,
                        null, "00000000011", null, null);

                JsonNode payload = mapper.valueToTree(area);

                assertThat(payload.get("keyAction").asText()).isEqualTo("PFK03");
                assertThat(mapper.readValue(mapper.writeValueAsString(area), ScreenWorkArea.class)
                        .keyAction())
                        .isEqualTo(KeyAction.PFK03);
            });
        }

        @Test
        @DisplayName("a temporal value crosses as ISO-8601 text rather than an epoch number, pinning the "
                + "setting before any request or response type needs it")
        void aTemporalValueCrossesAsIsoText() {
            DEPLOYED_CONTEXT.run(context -> {
                ObjectMapper mapper = context.getBean(ObjectMapper.class);

                // No request or response record in this module carries a temporal component: every
                // legacy date and timestamp is a fixed-width character field and is carried as text
                // for that reason. The setting is asserted at the mapper so that it cannot drift in
                // the interval before a type does need it.
                assertThat(mapper.writeValueAsString(LocalDate.of(2024, 1, 31)))
                        .isEqualTo("\"2024-01-31\"");
                assertThat(mapper.writeValueAsString(Instant.parse("2024-01-31T10:15:30Z")))
                        .isEqualTo("\"2024-01-31T10:15:30Z\"")
                        .doesNotContain("1706696130");
            });
        }
    }

    @Nested
    @DisplayName("Representative types round trip through the deployed mapper unchanged")
    class RoundTripThroughTheDeployedMapper {

        @Test
        @DisplayName("a sign-on request is carried inbound with its space-padded credential field intact "
                + "byte for byte, and is never carried outbound at all, because the credential property "
                + "is write-only")
        void aSignOnRequestIsCarriedInboundAndNeverOutbound() {
            // A whole-object round trip is the wrong instrument for this type and would test the wrong
            // thing. The credential property is declared WRITE_ONLY, so it is read from a request body
            // and never written to a response body; a round trip therefore compares a request against
            // one whose credential was dropped on the way out, and the two are correctly unequal. That
            // asymmetry is the security property, not a defect in the mapper.
            //
            // The finding this replaces is preserved and is asserted directly on the direction that
            // exists: SEC-USR-PWD is PIC X(08) [app/cpy/CSUSR01Y.cpy:L21], so the eight bytes including
            // the trailing blanks must survive deserialization unchanged - a mapper that trimmed them
            // would silently change the value being verified.
            DEPLOYED_CONTEXT.run(context -> {
                ObjectMapper mapper = context.getBean(ObjectMapper.class);
                SignOnRequest request = new SignOnRequest("ADMIN001", "unset   ");

                // Inbound: the padded credential arrives byte for byte.
                SignOnRequest inbound = mapper.readValue(
                        "{\"userId\":\"ADMIN001\",\"password\":\"unset   \"}", SignOnRequest.class);

                assertThat(inbound).isEqualTo(request).hasSameHashCodeAs(request);
                assertThat(inbound.password())
                        .as("the fixed-width credential field is not trimmed on the way in")
                        .isEqualTo("unset   ")
                        .hasSize(8);
                assertThat(inbound.userId()).isEqualTo("ADMIN001");

                // Outbound: the credential is absent from the document altogether, and the other
                // component still travels, so the omission is scoped rather than wholesale.
                String outbound = mapper.writeValueAsString(request);

                assertThat(outbound)
                        .as("no credential leaves this type by any route, not even a padded or empty one")
                        .doesNotContain("password")
                        .doesNotContain("unset");
                assertThat(outbound).contains("\"userId\":\"ADMIN001\"");

                // And the omission is total rather than a null placeholder, so a client cannot infer
                // from the document that a credential component exists.
                SignOnRequest afterOutbound = mapper.readValue(outbound, SignOnRequest.class);
                assertThat(afterOutbound.password())
                        .as("what was never written cannot be read back")
                        .isNull();
                assertThat(afterOutbound.userId()).isEqualTo("ADMIN001");
            });
        }

        @Test
        @DisplayName("a fully populated error response round trips with both states, the optional wording "
                + "and the focus hint intact")
        void anErrorResponseRoundTrips() {
            DEPLOYED_CONTEXT.run(context -> {
                ObjectMapper mapper = context.getBean(ObjectMapper.class);
                ErrorResponse response = populatedErrorResponse();

                ErrorResponse back = mapper.readValue(mapper.writeValueAsString(response),
                        ErrorResponse.class);

                assertThat(back).isEqualTo(response).hasSameHashCodeAs(response);
                assertThat(back.focusScreenFieldId()).isEqualTo(SCREEN_CREDIT_LIMIT);
                assertThat(back.fieldErrors()).hasSize(2);
            });
        }

        @Test
        @DisplayName("a statement row round trips with every carriage hazard intact: leading zeroes, "
                + "trailing blanks, mixed case and a negative scale-two amount")
        void aStatementRowRoundTrips() {
            DEPLOYED_CONTEXT.run(context -> {
                ObjectMapper mapper = context.getBean(ObjectMapper.class);
                StatementSummary row = populatedStatementSummary();

                StatementSummary back = mapper.readValue(mapper.writeValueAsString(row),
                        StatementSummary.class);

                assertThat(back).isEqualTo(row).hasSameHashCodeAs(row);
                assertThat(back.cardNumber()).isEqualTo("0000000000000011");
                assertThat(back.source()).isEqualTo("POS TERM  ");
                assertThat(back.merchantName()).isEqualTo("Merchant Name Ltd  ");
            });
        }

        @Test
        @DisplayName("a navigation context round trips, including the all-blank middle name and the "
                + "re-entry program context")
        void aNavigationContextRoundTrips() {
            DEPLOYED_CONTEXT.run(context -> {
                ObjectMapper mapper = context.getBean(ObjectMapper.class);
                NavigationContext navigation = populatedNavigationContext();

                NavigationContext back = mapper.readValue(mapper.writeValueAsString(navigation),
                        NavigationContext.class);

                assertThat(back).isEqualTo(navigation).hasSameHashCodeAs(navigation);
                assertThat(back.customerMiddleName()).isEqualTo("  ");
                assertThat(back.reEntry()).isTrue();
                assertThat(back.echoesAdministratorCode()).isTrue();
            });
        }

        @Test
        @DisplayName("page metadata round trips in both directions of the browse, keeping whichever "
                + "boundary cursor the page carries and its displayed page number as text")
        void pageMetadataRoundTripsInBothDirections() {
            DEPLOYED_CONTEXT.run(context -> {
                ObjectMapper mapper = context.getBean(ObjectMapper.class);
                PageMetadata forward = PageMetadata.forward(PageMetadata.CARD_LIST_PAGE_SIZE,
                        null, "0000000000000011", true, false, "0000001");
                PageMetadata backward = PageMetadata.backward(PageMetadata.USER_LIST_PAGE_SIZE,
                        "ADMIN001", null, false, true, "0000003");

                assertThat(mapper.readValue(mapper.writeValueAsString(forward), PageMetadata.class))
                        .isEqualTo(forward);
                assertThat(mapper.readValue(mapper.writeValueAsString(backward), PageMetadata.class))
                        .isEqualTo(backward);
            });
        }

        @Test
        @DisplayName("a partially populated account update round trips and the omitted components come "
                + "back absent rather than blank, so absent and empty stay distinguishable")
        void aPartialAccountUpdateRoundTrips() {
            DEPLOYED_CONTEXT.run(context -> {
                ObjectMapper mapper = context.getBean(ObjectMapper.class);
                AccountUpdateRequest request = mapper.readValue(
                        "{\"accountId\":\"00000000011\",\"middleName\":\"  \","
                                + "\"creditLimit\":\"5000.00\"}",
                        AccountUpdateRequest.class);

                AccountUpdateRequest back = mapper.readValue(mapper.writeValueAsString(request),
                        AccountUpdateRequest.class);

                assertThat(back).isEqualTo(request);
                assertThat(back.middleName())
                        .as("a blank supplied value is carried; it is not the same as an absent one")
                        .isEqualTo("  ");
                assertThat(back.addressLine2()).isNull();
                assertThat(back.creditLimit()).isEqualTo("5000.00");
            });
        }

        @Test
        @DisplayName("a screen work area round trips with its attention key and its numeric views still "
                + "derivable from the carried text")
        void aScreenWorkAreaRoundTrips() {
            DEPLOYED_CONTEXT.run(context -> {
                ObjectMapper mapper = context.getBean(ObjectMapper.class);
                ScreenWorkArea area = new ScreenWorkArea(KeyAction.ENTER, "COACTUPC", "COACTUP",
                        "CACTUPA", null, null, "00000000011", "0000000000000011", "000000011");

                ScreenWorkArea back = mapper.readValue(mapper.writeValueAsString(area),
                        ScreenWorkArea.class);

                assertThat(back).isEqualTo(area).hasSameHashCodeAs(area);
                assertThat(back.accountIdNumeric()).isPresent();
                assertThat(back.customerIdNumeric()).isPresent();
            });
        }
    }
}
