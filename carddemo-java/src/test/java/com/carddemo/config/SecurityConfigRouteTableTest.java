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

import com.carddemo.api.JsonRefusalBodyRenderer;
import com.carddemo.api.BatchJobController;

import com.carddemo.config.SecurityConfig.Gating;
import com.carddemo.config.SecurityConfig.TransactionRoute;
import com.carddemo.domain.enums.UserType;
import com.carddemo.service.SignOnStateService;
import com.carddemo.support.InMemoryCredentialMaster;
import com.carddemo.util.ApiRoutePaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asserts the route-to-role table against the estate it was taken from, and asserts the one deliberate
 * behavioural change the security configuration carries.
 *
 * <p>The sibling {@code SecurityConfigTest} asserts what the filter chain <em>answers</em>, by issuing real
 * requests and reading real statuses. This class asserts the inventory those answers are derived from,
 * which is a different question and fails in a different way: a chain can answer every request correctly
 * and still be built on a table that has lost a transaction, gated a sixth one, or quietly dropped the
 * anomaly that makes the count honest.</p>
 *
 * <p><strong>The expectations here are transcribed from an executed census, not from prose.</strong> The
 * resource definition file registers eighteen transaction definitions and eighteen program definitions, and
 * each transaction definition names exactly one program. The identifiers, the bound program names and the
 * definition lines asserted below are that census. They are held as a literal table in this class rather
 * than re-extracted at test time, deliberately: re-deriving the expectation from the same source the code
 * derived it from would assert only that two readers agree, and would pass if both were wrong.</p>
 *
 * <p><strong>No credential value appears in this class.</strong> The hashing assertions below use values
 * that are visibly test values, and none of them is the value carried by the provisioning job that seeds
 * the ten known sign-on identities.</p>
 */
@DisplayName("Route-to-role table: eighteen registered transactions, five administrative, one anomaly")
class SecurityConfigRouteTableTest {

    /**
     * The eighteen registered transaction identifiers in ascending order.
     *
     * <p>This is the completeness assertion: the table must contain exactly these, no more and no
     * fewer.</p>
     */
    private static final List<String> REGISTERED_TRANSACTION_IDS = List.of(
            "CA00", "CAUP", "CAVW", "CB00", "CC00", "CCDL", "CCLI", "CCUP", "CDV1",
            "CM00", "CR00", "CT00", "CT01", "CT02", "CU00", "CU01", "CU02", "CU03");

    /**
     * The five transaction identifiers that require the administrative authority, in ascending order: the
     * administrative menu, and the four transactions that list, add, update and delete a sign-on record.
     */
    private static final List<String> ADMINISTRATIVE_TRANSACTION_IDS =
            List.of("CA00", "CU00", "CU01", "CU02", "CU03");

    /** The one transaction identifier reachable without a credential: sign-on, which issues them. */
    private static final String ANONYMOUS_TRANSACTION_ID = "CC00";

    /** The one transaction identifier whose bound program definition has no source member. */
    private static final String DANGLING_TRANSACTION_ID = "CDV1";

    /** The program name that dangling definition names, for which no source member exists. */
    private static final String DANGLING_PROGRAM_NAME = "COCRDSEC";

    /** Width of a transaction identifier in the resource definitions. */
    private static final int TRANSACTION_ID_WIDTH = 4;

    /** Width of a program name in the resource definitions. */
    private static final int PROGRAM_NAME_WIDTH = 8;

    /** Length of a hash produced by the algorithm the encoder fixes. */
    private static final int DIGEST_LENGTH = 60;

    /** Prefix every hash produced by that algorithm carries. */
    private static final String DIGEST_PREFIX = "$2";

    /**
     * The eighteen registered transaction identifiers in the order the resource definition file defines
     * them, which is ascending by definition line rather than ascending by identifier.
     *
     * <p>Held separately from {@link #CENSUS} because an unmodifiable map makes no promise about its
     * iteration order, so an order assertion has to be written against a list.</p>
     */
    private static final List<String> DEFINITION_ORDER = List.of(
            "CAUP", "CAVW", "CA00", "CB00", "CCDL", "CCLI", "CCUP", "CC00", "CDV1",
            "CM00", "CR00", "CT00", "CT01", "CT02", "CU00", "CU01", "CU02", "CU03");

    /**
     * The census, as a lookup from transaction identifier to bound program name and the line its definition
     * begins on. Iteration order is not relied upon; {@link #DEFINITION_ORDER} carries the order.
     */
    private static final Map<String, Map.Entry<String, Integer>> CENSUS = census();

    /**
     * Builds the census table.
     *
     * @return an unmodifiable map from transaction identifier to its bound program name and definition line
     */
    private static Map<String, Map.Entry<String, Integer>> census() {
        final Map<String, Map.Entry<String, Integer>> binding = new HashMap<>();
        binding.put("CAUP", Map.entry("COACTUPC", 306));
        binding.put("CAVW", Map.entry("COACTVWC", 317));
        binding.put("CA00", Map.entry("COADM01C", 327));
        binding.put("CB00", Map.entry("COBIL00C", 337));
        binding.put("CCDL", Map.entry("COCRDSLC", 347));
        binding.put("CCLI", Map.entry("COCRDLIC", 357));
        binding.put("CCUP", Map.entry("COCRDUPC", 367));
        binding.put("CC00", Map.entry("COSGN00C", 378));
        binding.put("CDV1", Map.entry("COCRDSEC", 388));
        binding.put("CM00", Map.entry("COMEN01C", 399));
        binding.put("CR00", Map.entry("CORPT00C", 409));
        binding.put("CT00", Map.entry("COTRN00C", 419));
        binding.put("CT01", Map.entry("COTRN01C", 429));
        binding.put("CT02", Map.entry("COTRN02C", 439));
        binding.put("CU00", Map.entry("COUSR00C", 449));
        binding.put("CU01", Map.entry("COUSR01C", 459));
        binding.put("CU02", Map.entry("COUSR02C", 469));
        binding.put("CU03", Map.entry("COUSR03C", 479));
        return Map.copyOf(binding);
    }

    /**
     * Builds a configuration instance for the assertions that need a bean off it.
     *
     * <p>Only the hashing bean is read from it, and that bean reads no field, so the settings passed here
     * are the least that lets the constructor complete. The signing secret is visibly a test value and is
     * long enough only because the signature algorithm fixes a minimum length; the lifetime is a fixture
     * value and asserts no service level.</p>
     *
     * <p>The credential master handed to the token provider is empty, and stays empty, because nothing
     * here mints or checks a token: the provider is a constructor argument and no more. An empty fixture
     * is the honest expression of that - a seeded one would suggest these assertions depended on a
     * record.</p>
     *
     * @return a configuration instance
     */
    private static SecurityConfig configuration() {
        final JwtProperties properties = new JwtProperties(
                "unit-test-signing-secret-not-a-real-credential-0123456789",
                "carddemo-java",
                Duration.ofMinutes(30));
        return new SecurityConfig(
                new JwtTokenProvider(properties, Clock.systemUTC(),
                        new SignOnStateService(new InMemoryCredentialMaster().repository())),
                new JsonRefusalBodyRenderer(new ObjectMapper()),
                false,
                false,
                false,
                "/v3/api-docs",
                "/actuator",
                "/",
                // Empty, because nothing here reaches the management chain: these assertions read the
                // route table, and an operator credential would suggest they depended on one.
                "");
    }

    /**
     * Reads a transaction identifier's entry, failing the test rather than returning empty when the table
     * does not have it.
     *
     * @param transactionId the identifier to resolve
     * @return the entry
     */
    private static TransactionRoute entryFor(final String transactionId) {
        return TransactionRoute.forTransactionId(transactionId)
                .orElseThrow(() -> new AssertionError(
                        "the table has no entry for registered transaction " + transactionId));
    }

    /**
     * The identifiers of every entry carrying one entitlement, in ascending order.
     *
     * @param gating the entitlement to select
     * @return the sorted identifiers
     */
    private static List<String> sortedIdsWith(final Gating gating) {
        return TransactionRoute.withGating(gating).stream()
                .map(TransactionRoute::getTransactionId)
                .sorted()
                .toList();
    }

    @Nested
    @DisplayName("The inventory")
    class Inventory {

        @Test
        @DisplayName("holds exactly the eighteen registered transaction identifiers, so no transaction is "
                + "silently absent and none is invented")
        void holdsExactlyTheEighteenRegisteredIdentifiers() {
            assertThat(TransactionRoute.registeredTransactions())
                    .extracting(TransactionRoute::getTransactionId)
                    .containsExactlyInAnyOrderElementsOf(REGISTERED_TRANSACTION_IDS)
                    .hasSize(REGISTERED_TRANSACTION_IDS.size());
        }

        @Test
        @DisplayName("declares its entries in the order the resource definition file defines them, so a "
                + "reviewer can read the two side by side")
        void declaresEntriesInDefinitionOrder() {
            assertThat(TransactionRoute.registeredTransactions())
                    .extracting(TransactionRoute::getTransactionId)
                    .containsExactlyElementsOf(DEFINITION_ORDER);
        }

        @Test
        @DisplayName("declares its entries in ascending definition-line order, so the declaration order is "
                + "the file's order and not an arrangement of its own")
        void declaresEntriesInAscendingDefinitionLineOrder() {
            assertThat(TransactionRoute.registeredTransactions())
                    .extracting(TransactionRoute::getCsdDefinitionLine)
                    .isSorted();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"CA00", "CAUP", "CAVW", "CB00", "CC00", "CCDL", "CCLI", "CCUP", "CDV1",
                "CM00", "CR00", "CT00", "CT01", "CT02", "CU00", "CU01", "CU02", "CU03"})
        @DisplayName("binds each transaction to the program the definition names, at the line it names it")
        void bindsEachTransactionToItsDefinedProgram(final String transactionId) {
            final Map.Entry<String, Integer> expected = CENSUS.get(transactionId);
            final TransactionRoute entry = entryFor(transactionId);

            assertThat(entry.getBoundProgramName()).isEqualTo(expected.getKey());
            assertThat(entry.getCsdDefinitionLine()).isEqualTo(expected.getValue());
        }

        @ParameterizedTest
        @EnumSource(TransactionRoute.class)
        @DisplayName("carries a four-character identifier and an eight-character program name on every "
                + "entry, matching the widths the definitions use")
        void carriesTheDefinedWidths(final TransactionRoute entry) {
            assertThat(entry.getTransactionId()).hasSize(TRANSACTION_ID_WIDTH);
            assertThat(entry.getBoundProgramName()).hasSize(PROGRAM_NAME_WIDTH);
        }

        @Test
        @DisplayName("binds a distinct program to every transaction, as the definitions do")
        void bindsADistinctProgramToEveryTransaction() {
            assertThat(TransactionRoute.registeredTransactions())
                    .extracting(TransactionRoute::getBoundProgramName)
                    .doesNotHaveDuplicates()
                    .hasSize(REGISTERED_TRANSACTION_IDS.size());
        }

        @Test
        @DisplayName("classifies every entry, so no transaction is left without an entitlement")
        void classifiesEveryEntry() {
            final List<String> classified = new ArrayList<>();
            for (final Gating gating : Gating.values()) {
                classified.addAll(sortedIdsWith(gating));
            }

            assertThat(classified).containsExactlyInAnyOrderElementsOf(REGISTERED_TRANSACTION_IDS);
        }
    }

    @Nested
    @DisplayName("The five administrative transactions")
    class AdministrativeTransactions {

        @Test
        @DisplayName("are exactly five, because gating a sixth would refuse a caller the estate admits and "
                + "gating four would admit one it refuses")
        void areExactlyFive() {
            assertThat(sortedIdsWith(Gating.ADMINISTRATIVE))
                    .containsExactlyElementsOf(ADMINISTRATIVE_TRANSACTION_IDS);
        }

        @Test
        @DisplayName("are the administrative menu and the four sign-on-record maintenance transactions, "
                + "and nothing else is administrative")
        void areTheMenuAndTheFourMaintenanceTransactions() {
            for (final String administrative : ADMINISTRATIVE_TRANSACTION_IDS) {
                assertThat(entryFor(administrative).getGating())
                        .as("transaction %s must require the administrative authority", administrative)
                        .isEqualTo(Gating.ADMINISTRATIVE);
            }
            for (final String registered : REGISTERED_TRANSACTION_IDS) {
                if (!ADMINISTRATIVE_TRANSACTION_IDS.contains(registered)) {
                    assertThat(entryFor(registered).getGating())
                            .as("transaction %s must not require the administrative authority", registered)
                            .isNotEqualTo(Gating.ADMINISTRATIVE);
                }
            }
        }

        @Test
        @DisplayName("yield one enforcement rule between them, because they share one path prefix")
        void yieldOneEnforcementRule() {
            assertThat(TransactionRoute.enforcementPatternsFor(Gating.ADMINISTRATIVE))
                    .containsExactly(SecurityConfig.ADMIN_PATH_PREFIX + "/**");
        }
    }

    @Nested
    @DisplayName("The one anonymous transaction")
    class AnonymousTransaction {

        @Test
        @DisplayName("is sign-on and only sign-on, because it is the route that issues credentials")
        void isSignOnAndOnlySignOn() {
            assertThat(sortedIdsWith(Gating.ANONYMOUS)).containsExactly(ANONYMOUS_TRANSACTION_ID);
        }

        @Test
        @DisplayName("is enforced at the address the sign-on controller declares, so the rule and the "
                + "handler name one authority")
        void isEnforcedAtTheControllersOwnAddress() {
            assertThat(TransactionRoute.enforcementPatternsFor(Gating.ANONYMOUS))
                    .containsExactly(SecurityConfig.SIGN_ON_PATH);
        }
    }

    @Nested
    @DisplayName("The dangling binding kept for the audit")
    class DanglingBinding {

        @Test
        @DisplayName("is present in the table, so the eighteen-definition inventory is complete")
        void isPresentInTheTable() {
            assertThat(TransactionRoute.forTransactionId(DANGLING_TRANSACTION_ID)).isPresent();
        }

        @Test
        @DisplayName("is marked as having no implemented bound program, and is the only entry so marked")
        void isTheOnlyEntryWithNoImplementedProgram() {
            assertThat(TransactionRoute.registeredTransactions())
                    .filteredOn(entry -> !entry.isBoundProgramImplemented())
                    .extracting(TransactionRoute::getTransactionId)
                    .containsExactly(DANGLING_TRANSACTION_ID);
        }

        @Test
        @DisplayName("names the program definition that has no source member")
        void namesTheProgramWithNoSourceMember() {
            assertThat(entryFor(DANGLING_TRANSACTION_ID).getBoundProgramName())
                    .isEqualTo(DANGLING_PROGRAM_NAME);
        }

        @Test
        @DisplayName("is not administrative, so keeping it for the audit widens nothing")
        void isNotAdministrative() {
            assertThat(entryFor(DANGLING_TRANSACTION_ID).getGating())
                    .isEqualTo(Gating.AUTHENTICATED);
        }

        @Test
        @DisplayName("has no target type anywhere in the module, so the anomaly is recorded rather than "
                + "implemented")
        void hasNoTargetTypeAnywhereInTheModule() throws IOException {
            final Path productionRoot = Path.of("src", "main", "java");

            try (Stream<Path> sources = Files.walk(productionRoot)) {
                assertThat(sources.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".java"))
                        .filter(name -> name.toUpperCase(Locale.ROOT)
                                .startsWith(DANGLING_PROGRAM_NAME))
                        .toList())
                        .as("no type may be generated for the dangling program definition")
                        .isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("The table as a structure")
    class Structure {

        @Test
        @DisplayName("publishes an unmodifiable list, so no caller can add a route or remove a gate")
        void publishesAnUnmodifiableList() {
            final List<TransactionRoute> published = TransactionRoute.registeredTransactions();

            assertThatThrownBy(() -> published.remove(0))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @ParameterizedTest
        @EnumSource(Gating.class)
        @DisplayName("publishes an unmodifiable group per entitlement, including one no transaction "
                + "carries, so a caller never distinguishes an absent key from an empty group")
        void publishesAnUnmodifiableGroupPerEntitlement(final Gating gating) {
            final List<TransactionRoute> group = TransactionRoute.withGating(gating);

            assertThat(group).isNotNull();
            assertThatThrownBy(() -> group.add(TransactionRoute.SIGN_ON))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("returns the same instances on every call, so nothing is assembled per request")
        void returnsTheSameInstancesOnEveryCall() {
            assertThat(TransactionRoute.registeredTransactions())
                    .isSameAs(TransactionRoute.registeredTransactions());
            assertThat(TransactionRoute.withGating(Gating.ADMINISTRATIVE))
                    .isSameAs(TransactionRoute.withGating(Gating.ADMINISTRATIVE));
        }

        @Test
        @DisplayName("rejects a null entitlement rather than answering for one")
        void rejectsANullEntitlement() {
            assertThatNullPointerException().isThrownBy(() -> TransactionRoute.withGating(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionRoute.enforcementPatternsFor(null));
        }
    }

    @Nested
    @DisplayName("Resolving an identifier")
    class Resolution {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"CA00", "CAUP", "CAVW", "CB00", "CC00", "CCDL", "CCLI", "CCUP", "CDV1",
                "CM00", "CR00", "CT00", "CT01", "CT02", "CU00", "CU01", "CU02", "CU03"})
        @DisplayName("resolves every registered identifier")
        void resolvesEveryRegisteredIdentifier(final String transactionId) {
            assertThat(TransactionRoute.forTransactionId(transactionId))
                    .isPresent()
                    .get()
                    .extracting(TransactionRoute::getTransactionId)
                    .isEqualTo(transactionId);
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"cc00", "CC0", "CC000", "ZZ99", " CC00", "CC00 ", ""})
        @DisplayName("yields nothing for an identifier the estate does not register, without throwing, "
                + "because asking about one is a legitimate question")
        void yieldsNothingForAnUnregisteredIdentifier(final String candidate) {
            assertThat(TransactionRoute.forTransactionId(candidate)).isEmpty();
        }

        @Test
        @DisplayName("yields nothing for an absent identifier rather than failing on it")
        void yieldsNothingForAnAbsentIdentifier() {
            assertThat(TransactionRoute.forTransactionId(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Entitlements and the rules they install")
    class Entitlements {

        @Test
        @DisplayName("give the ordinary entitlement one rule per delivered address, so an ordinary route "
                + "requires one of the two sign-on authorities by name and an address no controller "
                + "serves is granted to nobody")
        void giveTheOrdinaryEntitlementOneRulePerDeliveredAddress() {
            // Two defects were closed here in turn. This entitlement first named no pattern at all and was
            // answered by the chain's closing authenticated() rule, which asks whether an identity exists
            // and not whose - admitting any authority minted anywhere in the process to every ordinary
            // business route. It was then given one rule over the API root, which still granted either
            // sign-on authority every address beneath the root including the ones nothing serves. The
            // patterns are now the delivered addresses themselves.
            assertThat(Gating.AUTHENTICATED.enforcementPatterns())
                    .as("the eleven ordinary addresses, and no region containing them")
                    .isEqualTo(ApiRoutePaths.ORDINARY_ROUTE_PATHS)
                    .hasSize(11)
                    .doesNotContain(SecurityConfig.API_PATH_PREFIX + "/**");
            assertThat(TransactionRoute.enforcementPatternsFor(Gating.AUTHENTICATED))
                    .as("the twelve ordinary entries name the same eleven addresses between them, so the "
                            + "chain installs eleven rules and not twelve")
                    .isEqualTo(ApiRoutePaths.ORDINARY_ROUTE_PATHS);
            assertThat(Gating.AUTHENTICATED.enforcementPatterns())
                    .as("a pattern with a wildcard would re-admit whatever is mapped beneath it next")
                    .allSatisfy(pattern -> assertThat(pattern)
                            .startsWith(SecurityConfig.API_PATH_PREFIX + "/")
                            .doesNotContain("*"));
        }

        @Test
        @DisplayName("leave no entitlement without a named rule, which is what makes the closing catch-all "
                + "a boundary for unmapped addresses rather than the gate on a business route")
        void leaveNoEntitlementWithoutANamedRule() {
            assertThat(Gating.values())
                    .allSatisfy(gating -> assertThat(gating.enforcementPatterns())
                            .as("%s would otherwise be enforced by whatever the closing rules happen to "
                                    + "say", gating)
                            .isNotEmpty()
                            .allSatisfy(pattern -> assertThat(pattern).isNotBlank()));
        }

        @Test
        @DisplayName("give the ordinary entitlement to the remaining twelve registered transactions")
        void giveTheOrdinaryEntitlementToTheRemainingTwelve() {
            assertThat(sortedIdsWith(Gating.AUTHENTICATED))
                    .hasSize(REGISTERED_TRANSACTION_IDS.size()
                            - ADMINISTRATIVE_TRANSACTION_IDS.size() - 1)
                    .contains(DANGLING_TRANSACTION_ID)
                    .doesNotContain(ANONYMOUS_TRANSACTION_ID)
                    .doesNotContainAnyElementsOf(ADMINISTRATIVE_TRANSACTION_IDS);
        }

        @Test
        @DisplayName("name the patterns of all three entitlements, and keep the ordinary addresses clear "
                + "of the two surfaces that are gated differently")
        void nameThePatternsOfAllThreeEntitlements() {
            assertThat(Gating.ANONYMOUS.enforcementPatterns())
                    .containsExactly(SecurityConfig.SIGN_ON_PATH);
            assertThat(Gating.ADMINISTRATIVE.enforcementPatterns())
                    .containsExactly(SecurityConfig.ADMIN_PATH_PREFIX + "/**");
            assertThat(Gating.AUTHENTICATED.enforcementPatterns())
                    .isEqualTo(ApiRoutePaths.ORDINARY_ROUTE_PATHS);
            // The ordinary patterns are now exact addresses, so they no longer contain the other two
            // surfaces and their disjointness is assertable rather than something chain order has to
            // rescue. Chain order still decides, because the closing refusal spans the root.
            assertThat(Gating.AUTHENTICATED.enforcementPatterns())
                    .as("an ordinary grant naming the sign-on route would make the anonymous permit "
                            + "redundant; one beneath the administrative prefix would widen it")
                    .doesNotContain(SecurityConfig.SIGN_ON_PATH)
                    .allSatisfy(pattern -> assertThat(pattern)
                            .doesNotStartWith(SecurityConfig.ADMIN_PATH_PREFIX)
                            .doesNotStartWith(SecurityConfig.BATCH_CONTROL_PATH_PREFIX));
            assertThat(SecurityConfig.SIGN_ON_PATH)
                    .startsWith(SecurityConfig.API_PATH_PREFIX + "/");
            assertThat(SecurityConfig.ADMIN_PATH_PREFIX)
                    .startsWith(SecurityConfig.API_PATH_PREFIX + "/");
        }

        @Test
        @DisplayName("place the administrative pattern beneath the published administrative prefix, so a "
                + "route added beneath it is administrator-only from the moment it exists")
        void placeTheAdministrativePatternBeneathThePublishedPrefix() {
            assertThat(TransactionRoute.enforcementPatternsFor(Gating.ADMINISTRATIVE))
                    .allSatisfy(pattern ->
                            assertThat(pattern).startsWith(SecurityConfig.ADMIN_PATH_PREFIX));
        }

        @Test
        @DisplayName("keep the anonymous surface to one pattern, so exactly one business route is "
                + "reachable without a credential")
        void keepTheAnonymousSurfaceToOnePattern() {
            assertThat(TransactionRoute.enforcementPatternsFor(Gating.ANONYMOUS)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("The batch prefix, the one gated prefix with no transaction behind it")
    class BatchPrefix {

        @Test
        @DisplayName("covers the address the batch controller publishes, because the surface is gated by "
                + "prefix and an address outside it would fall through to the closing catch-all")
        void coversTheAddressTheBatchControllerPublishes() {
            assertThat(BatchJobController.BATCH_JOBS_PATH)
                    .startsWith(SecurityConfig.BATCH_PATH_PREFIX + "/");
        }

        @Test
        @DisplayName("is disjoint from the administrative prefix, so the five administrative transactions "
                + "and the non-transaction batch surface stay separately countable")
        void isDisjointFromTheAdministrativePrefix() {
            assertThat(SecurityConfig.BATCH_PATH_PREFIX)
                    .isNotEqualTo(SecurityConfig.ADMIN_PATH_PREFIX)
                    .doesNotStartWith(SecurityConfig.ADMIN_PATH_PREFIX);
            assertThat(SecurityConfig.ADMIN_PATH_PREFIX)
                    .doesNotStartWith(SecurityConfig.BATCH_PATH_PREFIX);
        }

        @Test
        @DisplayName("is behind no registered transaction, which is why the gate is a rule of its own "
                + "rather than a row in the table")
        void isBehindNoRegisteredTransaction() {
            assertThat(TransactionRoute.registeredTransactions())
                    .as("a fabricated row would corrupt an audit whose value is matching the resource "
                            + "definition exactly")
                    .allSatisfy(route -> assertThat(route.getGating().enforcementPatterns())
                            .allSatisfy(pattern -> assertThat(pattern)
                                    .doesNotStartWith(SecurityConfig.BATCH_PATH_PREFIX)));
        }
    }

    @Nested
    @DisplayName("The entitlement split the sign-on program encodes")
    class EntitlementSplit {

        @Test
        @DisplayName("grants the administrative authority for the administrative user-type code and for "
                + "nothing else")
        void grantsTheAdministrativeAuthorityForOneCodeOnly() {
            assertThat(JwtTokenProvider.authorityOf(UserType.ADMIN))
                    .isEqualTo(JwtTokenProvider.ADMIN_AUTHORITY);
            assertThat(JwtTokenProvider.authorityOf(UserType.USER))
                    .isEqualTo(JwtTokenProvider.USER_AUTHORITY);
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"X", "a", "u", "1", " ", "AA"})
        @DisplayName("never fails on a user-type code the estate does not declare, because the program's "
                + "alternative branch is unconditional and has no third outcome")
        void neverFailsOnAnUndeclaredUserTypeCode(final String code) {
            assertThat(UserType.fromCode(code)).isEmpty();
        }

        @Test
        @DisplayName("never fails on an absent user-type code either")
        void neverFailsOnAnAbsentUserTypeCode() {
            assertThat(UserType.fromCode(null)).isEmpty();
        }

        @Test
        @DisplayName("declares exactly the two user-type codes the communication area declares")
        void declaresExactlyTheTwoDeclaredCodes() {
            assertThat(UserType.values())
                    .extracting(UserType::getCode)
                    .containsExactlyInAnyOrder("A", "U");
        }
    }

    @Nested
    @DisplayName("Credential hashing, the one documented parity exception")
    class CredentialHashing {

        @Test
        @DisplayName("publishes a hashing encoder, never a pass-through or a delegating one with a "
                + "cleartext branch")
        void publishesAHashingEncoder() {
            assertThat(configuration().passwordEncoder()).isInstanceOf(BCryptPasswordEncoder.class);
        }

        @Test
        @DisplayName("produces a digest of the fixed length and prefix the algorithm defines, which is why "
                + "the stored column is wider than the legacy field")
        void producesADigestOfTheFixedLengthAndPrefix() {
            final String digest = configuration().passwordEncoder().encode("A-TEST-VALUE-ONLY");

            assertThat(digest).hasSize(DIGEST_LENGTH).startsWith(DIGEST_PREFIX);
        }

        @Test
        @DisplayName("verifies a submission that was folded to upper case, which is what the sign-on "
                + "program hands verification")
        void verifiesAnUpperCasedSubmission() {
            final PasswordEncoder encoder = configuration().passwordEncoder();
            final String folded = "a-test-value-only".toUpperCase(Locale.ROOT);
            final String digest = encoder.encode(folded);

            assertThat(encoder.matches(folded, digest)).isTrue();
        }

        @Test
        @DisplayName("refuses a submission that was not folded, so the fold is part of the contract rather "
                + "than a convenience")
        void refusesAnUnfoldedSubmission() {
            final PasswordEncoder encoder = configuration().passwordEncoder();
            final String unfolded = "a-test-value-only";
            final String digest = encoder.encode(unfolded.toUpperCase(Locale.ROOT));

            assertThat(encoder.matches(unfolded, digest)).isFalse();
        }

        @Test
        @DisplayName("never returns the submitted value as its own digest, so no cleartext survives the "
                + "encoder")
        void neverReturnsTheSubmittedValueAsItsOwnDigest() {
            final String submitted = "A-TEST-VALUE-ONLY";

            assertThat(configuration().passwordEncoder().encode(submitted)).isNotEqualTo(submitted);
        }

        @Test
        @DisplayName("produces a different digest for the same value each time, so two identities sharing "
                + "one value do not share one stored row value")
        void producesADifferentDigestEachTime() {
            final PasswordEncoder encoder = configuration().passwordEncoder();
            final String submitted = "A-TEST-VALUE-ONLY";

            assertThat(encoder.encode(submitted)).isNotEqualTo(encoder.encode(submitted));
        }
    }
}
