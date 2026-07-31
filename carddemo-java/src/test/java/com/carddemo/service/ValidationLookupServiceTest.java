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
package com.carddemo.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for the three externalised validation lookup tables.
 *
 * <h2>What is under test and why it matters</h2>
 * The class under test is the migrated form of {@code app/cpy/CSLKPCDY.cpy}, a 1318-line copybook whose
 * entire body is level-88 condition-name literal lists rather than a data structure. It declares three
 * elementary items carrying five conditions between them: one three-byte item hosting the full area-code
 * list and its two subsets, one two-byte item hosting the state-code list, and one four-byte sub-field
 * hosting the state-plus-first-two-of-ZIP list. The migration externalises those lists into three JSON
 * classpath resources and publishes them as immutable sets.
 *
 * <p>The copybook has exactly one includer anywhere in the estate, {@code app/cbl/COACTUPC.cbl}, so the
 * whole lookup surface is reached through the account-update feature alone. No other program exercises any
 * part of it. That is why these tests are exhaustive rather than representative: there is no second caller
 * whose own tests would catch a table that had quietly drifted.</p>
 *
 * <h2>The cardinalities are themselves the contract</h2>
 * Five counts are binding, and the class under test re-verifies all five when it is constructed rather than
 * trusting the resources it loads. Four are stored and one is derived:
 * <ul>
 *   <li>410 general-purpose area codes and 80 easily-recognisable area codes, both loaded.</li>
 *   <li>490 area codes in total, <em>derived</em> by unioning those two subsets rather than stored as a
 *       third array. The 490 is an exact partition of the two subsets: 410 plus 80 is 490, and the two
 *       subsets share no element at all. Deriving the union is what removes the three-way consistency
 *       hazard that a third stored copy would create.</li>
 *   <li>56 state, district and territory codes.</li>
 *   <li>240 state-plus-ZIP combinations, four characters each.</li>
 * </ul>
 *
 * <h2>The two failure modes these tests exist to catch</h2>
 * Both are properties that a hand-edit to one of the JSON resources would break silently, producing a
 * service that starts, answers questions and is wrong:
 * <ul>
 *   <li><strong>The partition could stop being exact.</strong> A single value added to both area-code
 *       subsets, or moved from one to the other, changes what the general-purpose predicate accepts without
 *       changing any count that a casual reviewer would check. The union-equality and empty-intersection
 *       assertions below are the highest-value assertions in this class.</li>
 *   <li><strong>The two address tables could be conflated.</strong> Six of the 62 prefixes appearing in the
 *       240 combinations are absent from the 56 state codes, so cross-checking a composite key's leading
 *       two characters against the state table would reject addresses the legacy system accepts. These
 *       tests prove the two predicates are independent and are never intersected.</li>
 * </ul>
 *
 * <h2>How these tests are written</h2>
 * <ul>
 *   <li>Every expected count is a literal declared in this class, so the oracle is independent of the code
 *       it judges. No expected value is produced by calling the class under test, or any production
 *       formatter, codec, template holder or record mapper.</li>
 *   <li>Membership is checked exhaustively rather than sampled, against a second, independent view of each
 *       resource parsed directly in the test. The service's published set and that independent parse must
 *       agree element for element.</li>
 *   <li>Every width assertion measures encoded bytes rather than character count, because these are byte
 *       width contracts and a character-count assertion would pass on a value that is byte-wrong.</li>
 *   <li>This is a plain unit test. It starts no container, opens no database connection, binds no port and
 *       loads no application context. It does read the three JSON resources from the classpath, because
 *       that is precisely the production load path and reproducing it is the point.</li>
 *   <li>The failure paths are driven through the resource-loading collaborator the constructor already
 *       accepts, using an in-test double that serves doctored content for one location and the real
 *       resource for the others. No private field is ever read; there is no reflection anywhere in this
 *       class.</li>
 * </ul>
 *
 * <h2>Provenance</h2>
 * Legacy authorities read at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Only member names, condition-name
 * identifiers, declared widths and measured cardinalities are carried across; no COBOL statement is
 * reproduced here.
 */
@DisplayName("Validation lookup tables: the five legacy condition lists keep their exact membership")
class ValidationLookupServiceTest {

    /**
     * Expected size of the full area-code table, measured from the copybook's 490-value condition list.
     * Declared as a literal so that this class never asks the code under test what the answer should be.
     */
    private static final int EXPECTED_PHONE_AREA_CODE_COUNT = 490;

    /**
     * Expected size of the general-purpose area-code subset, measured from the copybook's 410-value
     * condition list. This is the subset the legacy account-update program actually tests a telephone
     * number against.
     */
    private static final int EXPECTED_GENERAL_PURPOSE_AREA_CODE_COUNT = 410;

    /**
     * Expected size of the easily-recognisable area-code subset, measured from the copybook's 80-value
     * condition list.
     */
    private static final int EXPECTED_EASY_RECOGNITION_AREA_CODE_COUNT = 80;

    /**
     * Expected size of the state-code table, measured from the copybook's 56-value condition list. The
     * final six values are the district and territory codes rather than states.
     */
    private static final int EXPECTED_US_STATE_CODE_COUNT = 56;

    /**
     * Expected size of the state-plus-first-two-of-ZIP table, measured from the copybook's 240-value
     * condition list.
     */
    private static final int EXPECTED_US_STATE_ZIP_COMBINATION_COUNT = 240;

    /**
     * Expected encoded byte width of every area code, from the {@code PIC XXX} clause on the item that
     * hosts all three area-code conditions.
     */
    private static final int EXPECTED_AREA_CODE_WIDTH = 3;

    /**
     * Expected encoded byte width of every state code, from the {@code PIC X(2)} clause on the item that
     * hosts the state-code condition.
     */
    private static final int EXPECTED_US_STATE_CODE_WIDTH = 2;

    /**
     * Expected encoded byte width of every state-plus-ZIP combination, from the {@code PIC X(4)} clause on
     * the sub-field that hosts the combination condition. Two characters of state code followed by the
     * first two characters of the ZIP code; the remaining three ZIP digits live in a separate sub-field and
     * take no part in the membership test.
     */
    private static final int EXPECTED_STATE_AND_FIRST_ZIP2_WIDTH = 4;

    /**
     * Class-loader path of the area-code resource, used by the independent oracle. This is the same file the
     * class under test loads through the resource abstraction, read here by a different route so that the
     * two views are genuinely independent.
     */
    private static final String NANPA_RESOURCE_PATH = "/lookup/nanpa-area-codes.json";

    /**
     * Class-loader path of the state-code resource, used by the independent oracle.
     */
    private static final String US_STATE_RESOURCE_PATH = "/lookup/us-state-codes.json";

    /**
     * Class-loader path of the state-plus-ZIP resource, used by the independent oracle.
     */
    private static final String STATE_ZIP_RESOURCE_PATH = "/lookup/state-zip-prefixes.json";

    /**
     * Name of the general-purpose array inside the area-code resource, spelled out here rather than read
     * from the class under test so that a rename could not silently satisfy both sides at once.
     */
    private static final String ORACLE_KEY_GENERAL_PURPOSE = "generalPurpose";

    /**
     * Name of the easily-recognisable array inside the area-code resource, spelled out for the same reason.
     */
    private static final String ORACLE_KEY_EASY_RECOGNITION = "easyRecognition";

    /**
     * An area code that belongs to the general-purpose subset and therefore <em>not</em> to the
     * easily-recognisable subset, because the two are disjoint.
     */
    private static final String GENERAL_PURPOSE_ONLY_AREA_CODE = "201";

    /**
     * An area code that belongs to the easily-recognisable subset and therefore <em>not</em> to the
     * general-purpose subset. This is the canonical example of the distinction that matters most: it is a
     * member of the 490-value table yet the legacy telephone edit rejects it.
     */
    private static final String EASY_RECOGNITION_ONLY_AREA_CODE = "800";

    /**
     * A genuine member of the 56-value state table.
     */
    private static final String GENUINE_STATE_CODE = "CA";

    /**
     * A four-character combination that is a genuine member of the 240-value table and whose leading two
     * characters are also a genuine member of the 56-value state table.
     */
    private static final String GENUINE_STATE_WITH_VALID_COMBINATION = "CA90";

    /**
     * A four-character combination assembled from a genuine state code and a ZIP prefix that genuinely
     * occurs in the table under a <em>different</em> state, yet which is itself absent from the 240 values.
     *
     * <p>This is the sharpest available proof that the combination test is a single whole-key lookup rather
     * than a state check followed by a ZIP check: both halves are individually real and the whole is still
     * correctly rejected.</p>
     */
    private static final String GENUINE_STATE_WITH_ABSENT_COMBINATION = "CA34";

    /**
     * Legacy condition name that a verification failure must quote when the general-purpose subset has
     * drifted, so that the failure identifies which of the five lists is at fault.
     */
    private static final String CONDITION_GENERAL_PURPOSE = "VALID-GENERAL-PURP-CODE";

    /**
     * Legacy condition name quoted when the easily-recognisable subset has drifted.
     */
    private static final String CONDITION_EASY_RECOGNITION = "VALID-EASY-RECOG-AREA-CODE";

    /**
     * Legacy condition name quoted for the derived area-code union.
     */
    private static final String CONDITION_PHONE_AREA_CODE = "VALID-PHONE-AREA-CODE";

    /**
     * Legacy condition name quoted when the state-code table has drifted.
     */
    private static final String CONDITION_US_STATE_CODE = "VALID-US-STATE-CODE";

    /**
     * Legacy condition name quoted when the state-plus-ZIP table has drifted.
     */
    private static final String CONDITION_US_STATE_ZIP_COMBO = "VALID-US-STATE-ZIP-CD2-COMBO";

    /**
     * Fully parameterised binding target for a resource that is a bare JSON array of tokens. The type
     * argument is spelled out so that deserialisation involves no unchecked operation, which under this
     * module's compiler settings would fail the build rather than merely warn.
     */
    private static final TypeReference<List<String>> TOKEN_ARRAY = new TypeReference<List<String>>() { };

    /**
     * Fully parameterised binding target for the area-code resource, which is a mapping of array name to
     * the tokens that array holds.
     */
    private static final TypeReference<Map<String, List<String>>> NAMED_TOKEN_ARRAYS =
            new TypeReference<Map<String, List<String>>>() { };

    /**
     * The JSON mapper used both to construct the class under test and to parse the independent oracle. A
     * plain, unconfigured mapper is deliberate: the class under test is documented to use the mapper
     * exactly as supplied, so supplying a customised one would test a configuration the application never
     * has.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The class under test, constructed once against the real classpath resources through the real resource
     * abstraction.
     *
     * <p>Initialising it here rather than before each test is safe and deliberate. The service is
     * documented as a stateless singleton whose five published sets are genuinely unmodifiable, and the
     * immutability group below proves that rather than assuming it, so no test can contaminate another
     * through this field. Constructing it in a field initialiser also means the happy-path construction,
     * including every cardinality and structural check the constructor performs, is exercised before any
     * assertion in this class runs.</p>
     */
    private static final ValidationLookupService SERVICE =
            new ValidationLookupService(MAPPER, new DefaultResourceLoader());

    // ------------------------------------------------------------------------------------------------
    // Independent oracle. Each of these reads one JSON resource through the class loader directly, which
    // is a different route from the resource abstraction the class under test uses. Nothing here consults
    // the class under test, so a table that had drifted would be caught by disagreement between the two
    // views rather than by both views being wrong in the same way.
    // ------------------------------------------------------------------------------------------------

    /**
     * Parses a resource that is a bare JSON array of tokens, preserving declaration order and duplicates so
     * that both can be asserted on.
     *
     * @param classpathPath absolute class-loader path of the resource
     * @return the tokens exactly as the resource declares them
     * @throws IOException if the resource cannot be read or is not the expected shape
     */
    private static List<String> parseTokenArray(final String classpathPath) throws IOException {
        try (InputStream json = ValidationLookupServiceTest.class.getResourceAsStream(classpathPath)) {
            assertThat(json)
                    .as("the resource %s must be present on the test classpath", classpathPath)
                    .isNotNull();
            return MAPPER.readValue(json, TOKEN_ARRAY);
        }
    }

    /**
     * Parses the area-code resource, which is an object of named arrays rather than a bare array.
     *
     * @return a mutable copy of the parsed mapping, so that callers may doctor it
     * @throws IOException if the resource cannot be read or is not the expected shape
     */
    private static Map<String, List<String>> parseAreaCodeArrays() throws IOException {
        try (InputStream json = ValidationLookupServiceTest.class.getResourceAsStream(NANPA_RESOURCE_PATH)) {
            assertThat(json)
                    .as("the resource %s must be present on the test classpath", NANPA_RESOURCE_PATH)
                    .isNotNull();
            return new LinkedHashMap<>(MAPPER.readValue(json, NAMED_TOKEN_ARRAYS));
        }
    }

    /**
     * Reads one named array out of the area-code resource.
     *
     * @param arrayName name of the array to read
     * @return the tokens exactly as the resource declares them
     * @throws IOException if the resource cannot be read or is not the expected shape
     */
    private static List<String> parseAreaCodeSubset(final String arrayName) throws IOException {
        final List<String> subset = parseAreaCodeArrays().get(arrayName);
        assertThat(subset)
                .as("the resource %s must declare the array %s", NANPA_RESOURCE_PATH, arrayName)
                .isNotNull();
        return subset;
    }

    // ------------------------------------------------------------------------------------------------
    // Failure-path seam. The constructor already accepts the resource-loading collaborator, so pointing
    // the service at doctored content needs no reflection, no subclass of the service and no change to
    // production code. One location is overridden per case; every other location resolves normally, which
    // keeps each failure attributable to the single table being doctored.
    // ------------------------------------------------------------------------------------------------

    /**
     * A resource loader that serves supplied content for one location and delegates everything else to the
     * real classpath loader.
     *
     * <p>Written as a hand-rolled double rather than a mock on purpose. It has no stubbing to leave
     * unused, so it cannot fall foul of strict stubbing; it delegates the untouched locations to the
     * genuine loader, so a doctored area-code table is still checked against real state and ZIP tables; and
     * it is declared final, so it can neither be extended nor leak a partially built instance.</p>
     */
    private static final class SingleOverrideResourceLoader implements ResourceLoader {

        /** Real loader used for every location this double does not override. */
        private final ResourceLoader delegate = new DefaultResourceLoader();

        /** The one location whose content is replaced. */
        private final String overriddenLocation;

        /** The replacement served for that location. */
        private final Resource replacement;

        /**
         * @param overriddenLocation the location to override, as the class under test spells it
         * @param replacement        the resource to serve for that location
         */
        SingleOverrideResourceLoader(final String overriddenLocation, final Resource replacement) {
            this.overriddenLocation = overriddenLocation;
            this.replacement = replacement;
        }

        @Override
        public Resource getResource(final String location) {
            if (this.overriddenLocation.equals(location)) {
                return this.replacement;
            }
            return this.delegate.getResource(location);
        }

        @Override
        public ClassLoader getClassLoader() {
            return this.delegate.getClassLoader();
        }
    }

    /**
     * Wraps JSON text as a readable resource.
     *
     * @param json the content to serve
     * @return a resource whose stream yields that content
     */
    private static Resource jsonResource(final String json) {
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8), "doctored lookup resource");
    }

    /**
     * Serialises a test-built collection to JSON text.
     *
     * <p>Serialising input is not the same thing as computing an expected value: the counts and membership
     * this class asserts on are all literals or independent parses. This only spares the tests from
     * hand-escaping large arrays.</p>
     *
     * @param value the collection to serialise
     * @return its JSON form
     * @throws IOException if serialisation fails
     */
    private static String asJson(final Object value) throws IOException {
        return MAPPER.writeValueAsString(value);
    }

    /**
     * Attempts to construct the service with one lookup resource replaced by the supplied resource.
     *
     * @param location    the location to override
     * @param replacement the resource to serve instead
     * @return the constructed service, for the cases that are expected to succeed
     */
    private static ValidationLookupService serviceWithReplacement(final String location,
            final Resource replacement) {
        return new ValidationLookupService(MAPPER, new SingleOverrideResourceLoader(location, replacement));
    }

    /**
     * Attempts to construct the service with one lookup resource replaced by the supplied JSON text.
     *
     * @param location the location to override
     * @param json     the content to serve instead
     * @return the constructed service, for the cases that are expected to succeed
     */
    private static ValidationLookupService serviceWithJson(final String location, final String json) {
        return serviceWithReplacement(location, jsonResource(json));
    }

    /**
     * Builds an area-code resource whose general-purpose array has been doctored, leaving the
     * easily-recognisable array exactly as the real resource declares it.
     *
     * @param doctoredGeneralPurpose the replacement general-purpose values
     * @return the JSON form of the doctored resource
     * @throws IOException if the real resource cannot be read
     */
    private static String areaCodeResourceWithGeneralPurpose(final List<String> doctoredGeneralPurpose)
            throws IOException {
        final Map<String, List<String>> arrays = parseAreaCodeArrays();
        arrays.put(ORACLE_KEY_GENERAL_PURPOSE, doctoredGeneralPurpose);
        return asJson(arrays);
    }

    /**
     * Measures a token's width the way a fixed-width contract has to be measured, on encoded bytes rather
     * than on character count.
     *
     * @param token the token to measure
     * @return its width in encoded bytes
     */
    private static int encodedWidth(final String token) {
        return token.getBytes(StandardCharsets.US_ASCII).length;
    }

    @Nested
    @DisplayName("The five measured cardinalities, which the legacy condition lists fix exactly")
    class Cardinalities {

        @Test
        @DisplayName("the general-purpose area-code table holds exactly 410 codes")
        void generalPurposeTableHoldsFourHundredAndTenCodes() {
            assertThat(SERVICE.generalPurposeAreaCodes())
                    .hasSize(EXPECTED_GENERAL_PURPOSE_AREA_CODE_COUNT);
        }

        @Test
        @DisplayName("the easily-recognisable area-code table holds exactly 80 codes")
        void easilyRecognisableTableHoldsEightyCodes() {
            assertThat(SERVICE.easilyRecognisableAreaCodes())
                    .hasSize(EXPECTED_EASY_RECOGNITION_AREA_CODE_COUNT);
        }

        @Test
        @DisplayName("the full area-code table holds exactly 490 codes, even though it is derived rather "
                + "than stored")
        void fullAreaCodeTableHoldsFourHundredAndNinetyCodes() {
            assertThat(SERVICE.phoneAreaCodes()).hasSize(EXPECTED_PHONE_AREA_CODE_COUNT);
        }

        @Test
        @DisplayName("the state-code table holds exactly 56 codes, the fifty states plus six districts and "
                + "territories")
        void stateCodeTableHoldsFiftySixCodes() {
            assertThat(SERVICE.usStateCodes()).hasSize(EXPECTED_US_STATE_CODE_COUNT);
        }

        @Test
        @DisplayName("the state-plus-ZIP table holds exactly 240 combinations")
        void stateZipTableHoldsTwoHundredAndFortyCombinations() {
            assertThat(SERVICE.usStateZipCodeCombinations())
                    .hasSize(EXPECTED_US_STATE_ZIP_COMBINATION_COUNT);
        }

        @Test
        @DisplayName("every area code in every area-code table is exactly three encoded bytes wide")
        void everyAreaCodeIsThreeEncodedBytesWide() {
            assertThat(SERVICE.phoneAreaCodes())
                    .allSatisfy(code -> assertThat(encodedWidth(code))
                            .as("encoded width of area code '%s'", code)
                            .isEqualTo(EXPECTED_AREA_CODE_WIDTH));
        }

        @Test
        @DisplayName("every state code is exactly two encoded bytes wide")
        void everyStateCodeIsTwoEncodedBytesWide() {
            assertThat(SERVICE.usStateCodes())
                    .allSatisfy(code -> assertThat(encodedWidth(code))
                            .as("encoded width of state code '%s'", code)
                            .isEqualTo(EXPECTED_US_STATE_CODE_WIDTH));
        }

        @Test
        @DisplayName("every state-plus-ZIP combination is exactly four encoded bytes wide")
        void everyCombinationIsFourEncodedBytesWide() {
            assertThat(SERVICE.usStateZipCodeCombinations())
                    .allSatisfy(combination -> assertThat(encodedWidth(combination))
                            .as("encoded width of combination '%s'", combination)
                            .isEqualTo(EXPECTED_STATE_AND_FIRST_ZIP2_WIDTH));
        }
    }

    @Nested
    @DisplayName("The full area-code table is an exact partition of its two subsets")
    class ExactPartition {

        @Test
        @DisplayName("the two declared subset counts sum to the declared full count, so 410 plus 80 is 490")
        void theTwoSubsetCountsSumToTheFullCount() {
            assertThat(EXPECTED_GENERAL_PURPOSE_AREA_CODE_COUNT + EXPECTED_EASY_RECOGNITION_AREA_CODE_COUNT)
                    .isEqualTo(EXPECTED_PHONE_AREA_CODE_COUNT);
        }

        @Test
        @DisplayName("the two published subset sizes also sum to the published full size, so no code is "
                + "lost or double counted")
        void theTwoPublishedSubsetSizesSumToThePublishedFullSize() {
            final int summed = SERVICE.generalPurposeAreaCodes().size()
                    + SERVICE.easilyRecognisableAreaCodes().size();

            assertThat(summed).isEqualTo(SERVICE.phoneAreaCodes().size());
        }

        @Test
        @DisplayName("the union of the two subsets equals the full table element for element, not merely in "
                + "size")
        void theUnionOfTheTwoSubsetsEqualsTheFullTable() {
            final Set<String> union = new LinkedHashSet<>(SERVICE.generalPurposeAreaCodes());
            union.addAll(SERVICE.easilyRecognisableAreaCodes());

            assertThat(union).isEqualTo(SERVICE.phoneAreaCodes());
        }

        @Test
        @DisplayName("the two subsets are disjoint, so a single overlapping code introduced by a future "
                + "edit would fail here")
        void theTwoSubsetsAreDisjoint() {
            assertThat(Collections.disjoint(SERVICE.generalPurposeAreaCodes(),
                    SERVICE.easilyRecognisableAreaCodes()))
                    .as("the general-purpose and easily-recognisable subsets must share no code")
                    .isTrue();
        }

        @Test
        @DisplayName("intersecting the two subsets explicitly yields nothing at all")
        void intersectingTheTwoSubsetsYieldsNothing() {
            final Set<String> intersection = new LinkedHashSet<>(SERVICE.generalPurposeAreaCodes());
            intersection.retainAll(SERVICE.easilyRecognisableAreaCodes());

            assertThat(intersection).isEmpty();
        }

        @Test
        @DisplayName("every general-purpose code is a member of the full table")
        void everyGeneralPurposeCodeIsInTheFullTable() {
            assertThat(SERVICE.phoneAreaCodes()).containsAll(SERVICE.generalPurposeAreaCodes());
        }

        @Test
        @DisplayName("every easily-recognisable code is a member of the full table")
        void everyEasilyRecognisableCodeIsInTheFullTable() {
            assertThat(SERVICE.phoneAreaCodes()).containsAll(SERVICE.easilyRecognisableAreaCodes());
        }

        @Test
        @DisplayName("every code in the full table belongs to exactly one of the two subsets, never both "
                + "and never neither")
        void everyFullTableCodeBelongsToExactlyOneSubset() {
            assertThat(SERVICE.phoneAreaCodes()).allSatisfy(code -> {
                final boolean general = SERVICE.generalPurposeAreaCodes().contains(code);
                final boolean easy = SERVICE.easilyRecognisableAreaCodes().contains(code);

                assertThat(general ^ easy)
                        .as("area code '%s' must belong to exactly one subset, but general-purpose was %s "
                                + "and easily-recognisable was %s", code, general, easy)
                        .isTrue();
            });
        }
    }

    @Nested
    @DisplayName("The three area-code predicates, which are queried separately and never conflated")
    class AreaCodePredicates {

        @ParameterizedTest(name = "[{index}] {0} -> general-purpose={1}, easily-recognisable={2}, full={3}")
        @CsvSource({
            "201, true,  false, true",
            "202, true,  false, true",
            "205, true,  false, true",
            "319, true,  false, true",
            "646, true,  false, true",
            "972, true,  false, true",
            "989, true,  false, true",
            "200, false, true,  true",
            "211, false, true,  true",
            "222, false, true,  true",
            "800, false, true,  true",
            "866, false, true,  true",
            "877, false, true,  true",
            "888, false, true,  true",
            "911, false, true,  true",
            "999, false, true,  true"
        })
        @DisplayName("each area code answers all three predicates independently, so a code in one subset is "
                + "rejected by the other while still passing the full table")
        void eachAreaCodeAnswersAllThreePredicatesIndependently(final String areaCode,
                final boolean expectedGeneralPurpose, final boolean expectedEasilyRecognisable,
                final boolean expectedFullTable) {
            assertThat(SERVICE.isValidGeneralPurposeAreaCode(areaCode))
                    .as("general-purpose predicate for area code '%s'", areaCode)
                    .isEqualTo(expectedGeneralPurpose);
            assertThat(SERVICE.isValidEasilyRecognisableAreaCode(areaCode))
                    .as("easily-recognisable predicate for area code '%s'", areaCode)
                    .isEqualTo(expectedEasilyRecognisable);
            assertThat(SERVICE.isValidPhoneAreaCode(areaCode))
                    .as("full-table predicate for area code '%s'", areaCode)
                    .isEqualTo(expectedFullTable);
        }

        @Test
        @DisplayName("an easily-recognisable code passes the full table yet fails the general-purpose "
                + "predicate the legacy telephone edit actually uses")
        void easilyRecognisableOnlyCodePassesFullTableButFailsGeneralPurpose() {
            assertThat(SERVICE.isValidPhoneAreaCode(EASY_RECOGNITION_ONLY_AREA_CODE))
                    .as("the full 490-code table must accept '%s'", EASY_RECOGNITION_ONLY_AREA_CODE)
                    .isTrue();
            assertThat(SERVICE.isValidEasilyRecognisableAreaCode(EASY_RECOGNITION_ONLY_AREA_CODE))
                    .as("the easily-recognisable subset must accept '%s'", EASY_RECOGNITION_ONLY_AREA_CODE)
                    .isTrue();
            assertThat(SERVICE.isValidGeneralPurposeAreaCode(EASY_RECOGNITION_ONLY_AREA_CODE))
                    .as("the general-purpose subset must reject '%s', because accepting it would accept "
                            + "eighty codes the legacy telephone edit rejects",
                            EASY_RECOGNITION_ONLY_AREA_CODE)
                    .isFalse();
        }

        @Test
        @DisplayName("a general-purpose code passes both the general-purpose predicate and the full table "
                + "yet fails the easily-recognisable predicate")
        void generalPurposeOnlyCodeFailsEasilyRecognisable() {
            assertThat(SERVICE.isValidGeneralPurposeAreaCode(GENERAL_PURPOSE_ONLY_AREA_CODE))
                    .as("the general-purpose subset must accept '%s'", GENERAL_PURPOSE_ONLY_AREA_CODE)
                    .isTrue();
            assertThat(SERVICE.isValidPhoneAreaCode(GENERAL_PURPOSE_ONLY_AREA_CODE))
                    .as("the full 490-code table must accept '%s'", GENERAL_PURPOSE_ONLY_AREA_CODE)
                    .isTrue();
            assertThat(SERVICE.isValidEasilyRecognisableAreaCode(GENERAL_PURPOSE_ONLY_AREA_CODE))
                    .as("the easily-recognisable subset must reject '%s'", GENERAL_PURPOSE_ONLY_AREA_CODE)
                    .isFalse();
        }

        @ParameterizedTest(name = "[{index}] {0} belongs to no area-code table")
        @ValueSource(strings = {"000", "001", "100", "123", "ABC"})
        @DisplayName("a code that appears in neither subset is rejected by all three predicates")
        void aCodeInNeitherSubsetIsRejectedByAllThreePredicates(final String areaCode) {
            assertThat(SERVICE.isValidGeneralPurposeAreaCode(areaCode)).isFalse();
            assertThat(SERVICE.isValidEasilyRecognisableAreaCode(areaCode)).isFalse();
            assertThat(SERVICE.isValidPhoneAreaCode(areaCode)).isFalse();
        }

        @Test
        @DisplayName("every published general-purpose code satisfies the general-purpose predicate, so the "
                + "published table and the predicate cannot disagree")
        void everyPublishedGeneralPurposeCodeSatisfiesItsPredicate() {
            assertThat(SERVICE.generalPurposeAreaCodes())
                    .allSatisfy(code -> assertThat(SERVICE.isValidGeneralPurposeAreaCode(code))
                            .as("general-purpose predicate for published code '%s'", code)
                            .isTrue());
        }

        @Test
        @DisplayName("every published easily-recognisable code satisfies the easily-recognisable predicate")
        void everyPublishedEasilyRecognisableCodeSatisfiesItsPredicate() {
            assertThat(SERVICE.easilyRecognisableAreaCodes())
                    .allSatisfy(code -> assertThat(SERVICE.isValidEasilyRecognisableAreaCode(code))
                            .as("easily-recognisable predicate for published code '%s'", code)
                            .isTrue());
        }

        @Test
        @DisplayName("every published code in the full table satisfies the full-table predicate")
        void everyPublishedFullTableCodeSatisfiesItsPredicate() {
            assertThat(SERVICE.phoneAreaCodes())
                    .allSatisfy(code -> assertThat(SERVICE.isValidPhoneAreaCode(code))
                            .as("full-table predicate for published code '%s'", code)
                            .isTrue());
        }
    }

    @Nested
    @DisplayName("Area-code keys are normalised the way the legacy move into a three-byte item normalised "
            + "them")
    class AreaCodeKeyNormalisation {

        @ParameterizedTest(name = "[{index}] '{0}' is accepted after the surrounding spaces are removed")
        @ValueSource(strings = {"201", " 201", "201 ", " 201 ", "   201   "})
        @DisplayName("surrounding spaces are removed before the membership test, so a value carrying the "
                + "padding of a fixed-width field is still accepted")
        void surroundingSpacesAreRemovedBeforeTheTest(final String areaCode) {
            assertThat(SERVICE.isValidGeneralPurposeAreaCode(areaCode))
                    .as("general-purpose predicate for '%s'", areaCode)
                    .isTrue();
        }

        @Test
        @DisplayName("only the space character is stripped, so a tab-prefixed code is rejected rather than "
                + "quietly accepted")
        void onlyTheSpaceCharacterIsStripped() {
            assertThat(SERVICE.isValidGeneralPurposeAreaCode("\t201"))
                    .as("a tab is not a space and must not be stripped")
                    .isFalse();
            assertThat(SERVICE.isValidGeneralPurposeAreaCode("\n201"))
                    .as("a line feed is not a space and must not be stripped")
                    .isFalse();
        }

        @Test
        @DisplayName("a value longer than three characters is tested on its first three, exactly as the "
                + "legacy move into a three-byte item truncated it")
        void aLongerValueIsTestedOnItsFirstThreeCharacters() {
            assertThat(SERVICE.isValidGeneralPurposeAreaCode("2010"))
                    .as("'2010' truncates to the valid code '201' and is therefore accepted, which is what "
                            + "the mainframe does")
                    .isTrue();
            assertThat(SERVICE.isValidGeneralPurposeAreaCode("2019999"))
                    .as("a much longer value truncates to the same three characters")
                    .isTrue();
        }

        @Test
        @DisplayName("a value shorter than three characters is space-filled and therefore matches nothing, "
                + "because no table value contains a space")
        void aShorterValueIsSpaceFilledAndMatchesNothing() {
            assertThat(SERVICE.isValidGeneralPurposeAreaCode("20")).isFalse();
            assertThat(SERVICE.isValidGeneralPurposeAreaCode("2")).isFalse();
            assertThat(SERVICE.isValidPhoneAreaCode("80")).isFalse();
        }

        @Test
        @DisplayName("a leading zero is never coerced away, so a numerically equal value with a leading zero "
                + "is rejected while the real code is accepted")
        void aLeadingZeroIsNeverCoercedAway() {
            assertThat(SERVICE.isValidGeneralPurposeAreaCode(GENERAL_PURPOSE_ONLY_AREA_CODE))
                    .as("the real code '%s' is accepted", GENERAL_PURPOSE_ONLY_AREA_CODE)
                    .isTrue();
            assertThat(SERVICE.isValidGeneralPurposeAreaCode("0201"))
                    .as("'0201' is numerically equal to '201' but is a different token, and a lookup that "
                            + "parsed it as a number would wrongly accept it")
                    .isFalse();
            assertThat(SERVICE.isValidPhoneAreaCode("0800"))
                    .as("'0800' is numerically equal to '800' and must likewise be rejected")
                    .isFalse();
            assertThat(SERVICE.isValidEasilyRecognisableAreaCode("0800")).isFalse();
        }

        @Test
        @DisplayName("a signed or punctuated form of a real code is rejected, because the tables are keyed "
                + "by token and not by numeric value")
        void aSignedOrPunctuatedFormIsRejected() {
            assertThat(SERVICE.isValidGeneralPurposeAreaCode("+201")).isFalse();
            assertThat(SERVICE.isValidGeneralPurposeAreaCode("-201")).isFalse();
            assertThat(SERVICE.isValidPhoneAreaCode("+800")).isFalse();
        }
    }

    @Nested
    @DisplayName("The state-code predicate, which is a flat token lookup with no normalisation at all")
    class StateCodePredicate {

        @ParameterizedTest(name = "[{index}] {0} is a valid state, district or territory code")
        @ValueSource(strings = {"AL", "AK", "CA", "NY", "TX", "WY", "DC", "AS", "GU", "MP", "PR", "VI"})
        @DisplayName("each of the fifty states and the six districts and territories is accepted")
        void eachGenuineStateCodeIsAccepted(final String stateCode) {
            assertThat(SERVICE.isValidUsStateCode(stateCode))
                    .as("state-code predicate for '%s'", stateCode)
                    .isTrue();
        }

        @ParameterizedTest(name = "[{index}] {0} is not a valid state code")
        @ValueSource(strings = {"ZZ", "XX", "QQ", "A", "CAL"})
        @DisplayName("a code that is not one of the fifty-six is rejected, whatever its length")
        void aCodeOutsideTheTableIsRejected(final String stateCode) {
            assertThat(SERVICE.isValidUsStateCode(stateCode))
                    .as("state-code predicate for '%s'", stateCode)
                    .isFalse();
        }

        @Test
        @DisplayName("the predicate applies no trimming, because the legacy paragraph moved a two-byte field "
                + "into a two-byte item and had nothing to trim")
        void thePredicateAppliesNoTrimming() {
            assertThat(SERVICE.isValidUsStateCode(GENUINE_STATE_CODE))
                    .as("the bare code '%s' is accepted", GENUINE_STATE_CODE)
                    .isTrue();
            assertThat(SERVICE.isValidUsStateCode(" CA"))
                    .as("a leading space is not stripped, so the value is not the two-byte token")
                    .isFalse();
            assertThat(SERVICE.isValidUsStateCode("CA "))
                    .as("a trailing space is not stripped either")
                    .isFalse();
            assertThat(SERVICE.isValidUsStateCode(" CA "))
                    .as("nor are both together")
                    .isFalse();
        }

        @Test
        @DisplayName("the predicate is case sensitive, so a lower-case code is rejected rather than folded")
        void thePredicateIsCaseSensitive() {
            assertThat(SERVICE.isValidUsStateCode("ca")).isFalse();
            assertThat(SERVICE.isValidUsStateCode("Ca")).isFalse();
            assertThat(SERVICE.isValidUsStateCode("cA")).isFalse();
            assertThat(SERVICE.isValidUsStateCode("CA")).isTrue();
        }

        @Test
        @DisplayName("every state code is alphabetic, so no lookup path could parse one as a number even in "
                + "principle")
        void everyStateCodeIsAlphabetic() {
            assertThat(SERVICE.usStateCodes()).allSatisfy(code -> assertThat(code)
                    .as("state code '%s' must be two upper-case letters", code)
                    .matches("[A-Z]{2}"));
        }

        @ParameterizedTest(name = "[{index}] the numeric token {0} is never a valid state code")
        @ValueSource(strings = {"00", "01", "10", "56", "99"})
        @DisplayName("a numeric token is never a valid state code, so the table cannot be reached through a "
                + "numeric key")
        void aNumericTokenIsNeverAValidStateCode(final String candidate) {
            assertThat(SERVICE.isValidUsStateCode(candidate))
                    .as("state-code predicate for numeric token '%s'", candidate)
                    .isFalse();
        }

        @Test
        @DisplayName("every published state code satisfies the state-code predicate, so the published table "
                + "and the predicate cannot disagree")
        void everyPublishedStateCodeSatisfiesItsPredicate() {
            assertThat(SERVICE.usStateCodes())
                    .allSatisfy(code -> assertThat(SERVICE.isValidUsStateCode(code))
                            .as("state-code predicate for published code '%s'", code)
                            .isTrue());
        }
    }

    @Nested
    @DisplayName("The state-plus-ZIP predicate, which is independent of the state table and never "
            + "intersected with it")
    class StateZipCombinationPredicate {

        @ParameterizedTest(name = "[{index}] prefix {0} is not a state code, yet combination {1} is valid")
        @CsvSource({
            "AA, AA34",
            "AE, AE90",
            "AP, AP96",
            "FM, FM96",
            "MH, MH96",
            "PW, PW96"
        })
        @DisplayName("the military and freely-associated prefixes are absent from the state table yet their "
                + "combinations are accepted, which is why the two tables must never be intersected")
        void prefixesAbsentFromTheStateTableStillFormValidCombinations(final String prefix,
                final String combination) {
            assertThat(SERVICE.isValidUsStateCode(prefix))
                    .as("prefix '%s' must NOT be a member of the fifty-six state codes", prefix)
                    .isFalse();
            assertThat(SERVICE.isValidUsStateZipCodeCombination(combination))
                    .as("combination '%s' must be accepted even though its prefix is not a state code",
                            combination)
                    .isTrue();
        }

        @Test
        @DisplayName("a genuine state code combined with a ZIP prefix it never uses is rejected, so the "
                + "lookup is one whole-key test rather than a state test followed by a ZIP test")
        void aGenuineStateWithAnUnusedZipPrefixIsRejected() {
            assertThat(SERVICE.isValidUsStateCode(GENUINE_STATE_CODE))
                    .as("'%s' is a genuine state code", GENUINE_STATE_CODE)
                    .isTrue();
            assertThat(SERVICE.isValidUsStateZipCodeCombination("AA34"))
                    .as("the ZIP prefix 34 genuinely occurs in the table, under a different prefix")
                    .isTrue();
            assertThat(SERVICE.isValidUsStateZipCodeCombination(GENUINE_STATE_WITH_ABSENT_COMBINATION))
                    .as("both halves of '%s' are individually real, yet the whole key is absent from the "
                            + "two hundred and forty combinations and must be rejected",
                            GENUINE_STATE_WITH_ABSENT_COMBINATION)
                    .isFalse();
        }

        @ParameterizedTest(name = "[{index}] {0} is a valid state-plus-ZIP combination")
        @ValueSource(strings = {"CA90", "CA96", "NY10", "TX75", "WV24", "WY82", "WY83", "AE98"})
        @DisplayName("a combination the table declares is accepted")
        void aDeclaredCombinationIsAccepted(final String combination) {
            assertThat(SERVICE.isValidUsStateZipCodeCombination(combination))
                    .as("combination predicate for '%s'", combination)
                    .isTrue();
        }

        @ParameterizedTest(name = "[{index}] {0} is not a valid state-plus-ZIP combination")
        @ValueSource(strings = {"CA34", "NY34", "WY99", "ZZ99", "AA35"})
        @DisplayName("a combination the table does not declare is rejected")
        void anUndeclaredCombinationIsRejected(final String combination) {
            assertThat(SERVICE.isValidUsStateZipCodeCombination(combination))
                    .as("combination predicate for '%s'", combination)
                    .isFalse();
        }

        @Test
        @DisplayName("the key must be exactly four characters, so a three-character key is rejected rather "
                + "than padded")
        void aShortKeyIsRejectedRatherThanPadded() {
            assertThat(SERVICE.isValidUsStateZipCodeCombination("AA3")).isFalse();
            assertThat(SERVICE.isValidUsStateZipCodeCombination("AA")).isFalse();
            assertThat(SERVICE.isValidUsStateZipCodeCombination("CA9")).isFalse();
        }

        @Test
        @DisplayName("a five-character key is rejected rather than truncated, which is the opposite of how "
                + "an over-long area code behaves")
        void aLongKeyIsRejectedRatherThanTruncated() {
            assertThat(SERVICE.isValidUsStateZipCodeCombination("AA345"))
                    .as("'AA345' must not be truncated to the valid key 'AA34'")
                    .isFalse();
            assertThat(SERVICE.isValidUsStateZipCodeCombination("CA900"))
                    .as("'CA900' must not be truncated to the valid key 'CA90'")
                    .isFalse();
            assertThat(SERVICE.isValidUsStateZipCodeCombination("AA034"))
                    .as("inserting a leading zero into the ZIP half produces a different token, and a "
                            + "numeric lookup would wrongly collapse it onto a real key")
                    .isFalse();
        }

        @Test
        @DisplayName("the predicate applies no trimming, because the legacy paragraph assembled the key "
                + "positionally and had nothing to trim")
        void thePredicateAppliesNoTrimming() {
            assertThat(SERVICE.isValidUsStateZipCodeCombination(GENUINE_STATE_WITH_VALID_COMBINATION))
                    .as("the bare four-character key '%s' is accepted",
                            GENUINE_STATE_WITH_VALID_COMBINATION)
                    .isTrue();
            assertThat(SERVICE.isValidUsStateZipCodeCombination(" CA90")).isFalse();
            assertThat(SERVICE.isValidUsStateZipCodeCombination("CA90 ")).isFalse();
            assertThat(SERVICE.isValidUsStateZipCodeCombination("CA 90")).isFalse();
        }

        @Test
        @DisplayName("the predicate is case sensitive, so a lower-case combination is rejected rather than "
                + "folded")
        void thePredicateIsCaseSensitive() {
            assertThat(SERVICE.isValidUsStateZipCodeCombination("ca90")).isFalse();
            assertThat(SERVICE.isValidUsStateZipCodeCombination("Ca90")).isFalse();
            assertThat(SERVICE.isValidUsStateZipCodeCombination("CA90")).isTrue();
        }

        @Test
        @DisplayName("every published combination satisfies the combination predicate, so the published "
                + "table and the predicate cannot disagree")
        void everyPublishedCombinationSatisfiesItsPredicate() {
            assertThat(SERVICE.usStateZipCodeCombinations())
                    .allSatisfy(combination -> assertThat(
                            SERVICE.isValidUsStateZipCodeCombination(combination))
                            .as("combination predicate for published key '%s'", combination)
                            .isTrue());
        }

        @Test
        @DisplayName("exactly six of the prefixes in use are not state codes, which is the structural "
                + "reason the combination test can never be reduced to a state test")
        void exactlySixPrefixesInUseAreNotStateCodes() {
            // This is a structural assertion about the two tables, never a validation rule. It asks the
            // state predicate about each prefix that combinations actually use, and records which ones it
            // rejects. Nothing here makes acceptance of a combination depend on the state table; the point
            // is precisely to demonstrate that such a dependency would be wrong.
            final Set<String> prefixesRejectedByTheStatePredicate = new LinkedHashSet<>();
            for (final String combination : SERVICE.usStateZipCodeCombinations()) {
                final String prefix = combination.substring(0, EXPECTED_US_STATE_CODE_WIDTH);
                if (!SERVICE.isValidUsStateCode(prefix)) {
                    prefixesRejectedByTheStatePredicate.add(prefix);
                }
            }

            assertThat(prefixesRejectedByTheStatePredicate)
                    .as("the prefixes that appear in real combinations yet are not state codes")
                    .containsExactlyInAnyOrder("AA", "AE", "AP", "FM", "MH", "PW");
        }
    }

    @Nested
    @DisplayName("Each resource judged against an independent parse of itself, so membership is exhaustive "
            + "rather than sampled")
    class ResourceOracle {

        @Test
        @DisplayName("the area-code resource declares exactly the two subset arrays and no third array for "
                + "the derived union")
        void theAreaCodeResourceDeclaresExactlyTwoArrays() throws IOException {
            assertThat(parseAreaCodeArrays())
                    .containsOnlyKeys(ORACLE_KEY_GENERAL_PURPOSE, ORACLE_KEY_EASY_RECOGNITION);
        }

        @Test
        @DisplayName("the general-purpose array holds 410 entries with no duplicate, each three bytes wide "
                + "and none blank")
        void theGeneralPurposeArrayIsWellFormed() throws IOException {
            assertWellFormedTable(parseAreaCodeSubset(ORACLE_KEY_GENERAL_PURPOSE),
                    EXPECTED_GENERAL_PURPOSE_AREA_CODE_COUNT, EXPECTED_AREA_CODE_WIDTH);
        }

        @Test
        @DisplayName("the easily-recognisable array holds 80 entries with no duplicate, each three bytes "
                + "wide and none blank")
        void theEasilyRecognisableArrayIsWellFormed() throws IOException {
            assertWellFormedTable(parseAreaCodeSubset(ORACLE_KEY_EASY_RECOGNITION),
                    EXPECTED_EASY_RECOGNITION_AREA_CODE_COUNT, EXPECTED_AREA_CODE_WIDTH);
        }

        @Test
        @DisplayName("the state-code resource holds 56 entries with no duplicate, each two bytes wide and "
                + "none blank")
        void theStateCodeResourceIsWellFormed() throws IOException {
            assertWellFormedTable(parseTokenArray(US_STATE_RESOURCE_PATH), EXPECTED_US_STATE_CODE_COUNT,
                    EXPECTED_US_STATE_CODE_WIDTH);
        }

        @Test
        @DisplayName("the state-plus-ZIP resource holds 240 entries with no duplicate, each four bytes wide "
                + "and none blank")
        void theStateZipResourceIsWellFormed() throws IOException {
            assertWellFormedTable(parseTokenArray(STATE_ZIP_RESOURCE_PATH),
                    EXPECTED_US_STATE_ZIP_COMBINATION_COUNT, EXPECTED_STATE_AND_FIRST_ZIP2_WIDTH);
        }

        @Test
        @DisplayName("the two arrays in the resource are themselves disjoint, so the partition holds in the "
                + "file and not only in the loaded sets")
        void theTwoArraysInTheResourceAreDisjoint() throws IOException {
            final List<String> generalPurpose = parseAreaCodeSubset(ORACLE_KEY_GENERAL_PURPOSE);
            final List<String> easilyRecognisable = parseAreaCodeSubset(ORACLE_KEY_EASY_RECOGNITION);

            assertThat(Collections.disjoint(generalPurpose, easilyRecognisable))
                    .as("the two arrays in %s must share no value", NANPA_RESOURCE_PATH)
                    .isTrue();

            final Set<String> union = new LinkedHashSet<>(generalPurpose);
            union.addAll(easilyRecognisable);
            assertThat(union).hasSize(EXPECTED_PHONE_AREA_CODE_COUNT);
        }

        @Test
        @DisplayName("the published general-purpose table agrees with the independently parsed array element "
                + "for element")
        void thePublishedGeneralPurposeTableAgreesWithTheResource() throws IOException {
            assertThat(SERVICE.generalPurposeAreaCodes())
                    .containsExactlyInAnyOrderElementsOf(parseAreaCodeSubset(ORACLE_KEY_GENERAL_PURPOSE));
        }

        @Test
        @DisplayName("the published easily-recognisable table agrees with the independently parsed array "
                + "element for element")
        void thePublishedEasilyRecognisableTableAgreesWithTheResource() throws IOException {
            assertThat(SERVICE.easilyRecognisableAreaCodes())
                    .containsExactlyInAnyOrderElementsOf(parseAreaCodeSubset(ORACLE_KEY_EASY_RECOGNITION));
        }

        @Test
        @DisplayName("the published full area-code table agrees with the union of the two independently "
                + "parsed arrays")
        void thePublishedFullTableAgreesWithTheUnionOfTheResourceArrays() throws IOException {
            final Set<String> union = new LinkedHashSet<>(parseAreaCodeSubset(ORACLE_KEY_GENERAL_PURPOSE));
            union.addAll(parseAreaCodeSubset(ORACLE_KEY_EASY_RECOGNITION));

            assertThat(SERVICE.phoneAreaCodes()).isEqualTo(union);
        }

        @Test
        @DisplayName("the published state-code table agrees with the independently parsed resource element "
                + "for element")
        void thePublishedStateCodeTableAgreesWithTheResource() throws IOException {
            assertThat(SERVICE.usStateCodes())
                    .containsExactlyInAnyOrderElementsOf(parseTokenArray(US_STATE_RESOURCE_PATH));
        }

        @Test
        @DisplayName("the published state-plus-ZIP table agrees with the independently parsed resource "
                + "element for element")
        void thePublishedStateZipTableAgreesWithTheResource() throws IOException {
            assertThat(SERVICE.usStateZipCodeCombinations())
                    .containsExactlyInAnyOrderElementsOf(parseTokenArray(STATE_ZIP_RESOURCE_PATH));
        }

        /**
         * Asserts the four properties every one of the three resources has to satisfy: the entry count the
         * copybook fixes, freedom from duplicates, the declared encoded byte width on every entry, and no
         * blank or null entry anywhere.
         *
         * @param table         the independently parsed entries, in declaration order
         * @param expectedCount the count this class declares as a literal
         * @param expectedWidth the encoded byte width this class declares as a literal
         */
        private void assertWellFormedTable(final List<String> table, final int expectedCount,
                final int expectedWidth) {
            assertThat(table)
                    .as("declared entry count")
                    .hasSize(expectedCount);
            assertThat(Set.copyOf(table))
                    .as("distinct entry count, which must equal the declared count because the copybook "
                            + "list carries no duplicate")
                    .hasSize(expectedCount);
            assertThat(table).allSatisfy(entry -> {
                assertThat(entry).as("no entry may be null or blank").isNotBlank();
                assertThat(encodedWidth(entry))
                        .as("encoded width of entry '%s'", entry)
                        .isEqualTo(expectedWidth);
            });
        }
    }

    @Nested
    @DisplayName("Every published table is unmodifiable, so no caller can corrupt a verified lookup")
    class PublishedTableImmutability {

        @Test
        @DisplayName("no accessor returns null")
        void noAccessorReturnsNull() {
            assertThat(SERVICE.generalPurposeAreaCodes()).isNotNull();
            assertThat(SERVICE.easilyRecognisableAreaCodes()).isNotNull();
            assertThat(SERVICE.phoneAreaCodes()).isNotNull();
            assertThat(SERVICE.usStateCodes()).isNotNull();
            assertThat(SERVICE.usStateZipCodeCombinations()).isNotNull();
        }

        @Test
        @DisplayName("the general-purpose table rejects addition, removal and clearing")
        void theGeneralPurposeTableIsUnmodifiable() {
            assertTableIsUnmodifiable(SERVICE.generalPurposeAreaCodes(), GENERAL_PURPOSE_ONLY_AREA_CODE);
        }

        @Test
        @DisplayName("the easily-recognisable table rejects addition, removal and clearing")
        void theEasilyRecognisableTableIsUnmodifiable() {
            assertTableIsUnmodifiable(SERVICE.easilyRecognisableAreaCodes(),
                    EASY_RECOGNITION_ONLY_AREA_CODE);
        }

        @Test
        @DisplayName("the derived full area-code table rejects addition, removal and clearing, so deriving "
                + "it did not cost immutability")
        void theFullAreaCodeTableIsUnmodifiable() {
            assertTableIsUnmodifiable(SERVICE.phoneAreaCodes(), GENERAL_PURPOSE_ONLY_AREA_CODE);
        }

        @Test
        @DisplayName("the state-code table rejects addition, removal and clearing")
        void theStateCodeTableIsUnmodifiable() {
            assertTableIsUnmodifiable(SERVICE.usStateCodes(), GENUINE_STATE_CODE);
        }

        @Test
        @DisplayName("the state-plus-ZIP table rejects addition, removal and clearing")
        void theStateZipTableIsUnmodifiable() {
            assertTableIsUnmodifiable(SERVICE.usStateZipCodeCombinations(),
                    GENUINE_STATE_WITH_VALID_COMBINATION);
        }

        /**
         * Asserts that a published table refuses every mutating operation.
         *
         * @param table         the published table
         * @param existingEntry an entry the table genuinely holds, so that the removal attempt is a real
         *                      attempt to change the contents rather than a no-op the implementation might
         *                      be entitled to ignore
         */
        private void assertTableIsUnmodifiable(final Set<String> table, final String existingEntry) {
            assertThat(table)
                    .as("the entry used to attempt removal must genuinely be present")
                    .contains(existingEntry);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("adding to a published table must be refused")
                    .isThrownBy(() -> table.add("ZZZ"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("removing from a published table must be refused")
                    .isThrownBy(() -> table.remove(existingEntry));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("clearing a published table must be refused")
                    .isThrownBy(table::clear);

            assertThat(table)
                    .as("the table is unchanged after every rejected attempt")
                    .contains(existingEntry);
        }
    }

    @Nested
    @DisplayName("Startup verification refuses to build the service on a resource that has drifted")
    class StartupVerification {

        @Test
        @DisplayName("the service builds successfully against the real resources, which is the verification "
                + "passing rather than being skipped")
        void theServiceBuildsAgainstTheRealResources() {
            final ValidationLookupService service =
                    new ValidationLookupService(MAPPER, new DefaultResourceLoader());

            assertThat(service.generalPurposeAreaCodes())
                    .hasSize(EXPECTED_GENERAL_PURPOSE_AREA_CODE_COUNT);
            assertThat(service.easilyRecognisableAreaCodes())
                    .hasSize(EXPECTED_EASY_RECOGNITION_AREA_CODE_COUNT);
            assertThat(service.phoneAreaCodes()).hasSize(EXPECTED_PHONE_AREA_CODE_COUNT);
            assertThat(service.usStateCodes()).hasSize(EXPECTED_US_STATE_CODE_COUNT);
            assertThat(service.usStateZipCodeCombinations())
                    .hasSize(EXPECTED_US_STATE_ZIP_COMBINATION_COUNT);
        }

        @Test
        @DisplayName("a missing resource is refused, naming the location that could not be found")
        void aMissingResourceIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> serviceWithReplacement(
                            ValidationLookupService.NANPA_AREA_CODES_LOCATION,
                            new ClassPathResource("lookup/deliberately-absent-lookup-resource.json")))
                    .withMessageContaining(ValidationLookupService.NANPA_AREA_CODES_LOCATION)
                    .withMessageContaining("is not present on the classpath");
        }

        @Test
        @DisplayName("a malformed resource is refused rather than partially loaded")
        void aMalformedResourceIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> serviceWithJson(ValidationLookupService.US_STATE_CODES_LOCATION,
                            "[ \"AL\", \"AK\", "))
                    .withMessageContaining(ValidationLookupService.US_STATE_CODES_LOCATION)
                    .withMessageContaining("could not be read");
        }

        @Test
        @DisplayName("a resource holding a JSON null is refused")
        void aResourceHoldingJsonNullIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> serviceWithJson(
                            ValidationLookupService.STATE_ZIP_PREFIXES_LOCATION, "null"))
                    .withMessageContaining(ValidationLookupService.STATE_ZIP_PREFIXES_LOCATION)
                    .withMessageContaining("bound to null");
        }

        @Test
        @DisplayName("an area-code resource carrying a third array is refused, because the full table is "
                + "derived and must never be stored")
        void anAreaCodeResourceCarryingAThirdArrayIsRefused() throws IOException {
            final Map<String, List<String>> arrays = parseAreaCodeArrays();
            arrays.put("phoneAreaCodes", List.of(GENERAL_PURPOSE_ONLY_AREA_CODE));
            final String doctored = asJson(arrays);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> serviceWithJson(
                            ValidationLookupService.NANPA_AREA_CODES_LOCATION, doctored))
                    .withMessageContaining("must declare exactly the two arrays")
                    .withMessageContaining(CONDITION_PHONE_AREA_CODE);
        }

        @Test
        @DisplayName("an area-code resource whose named array is a JSON null is refused, naming the array")
        void anAreaCodeResourceWithANullArrayIsRefused() throws IOException {
            final Map<String, List<String>> arrays = parseAreaCodeArrays();
            arrays.put(ORACLE_KEY_GENERAL_PURPOSE, null);
            final String doctored = asJson(arrays);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> serviceWithJson(
                            ValidationLookupService.NANPA_AREA_CODES_LOCATION, doctored))
                    .withMessageContaining("does not declare the array")
                    .withMessageContaining(ORACLE_KEY_GENERAL_PURPOSE);
        }

        @Test
        @DisplayName("a general-purpose array short by one code is refused, naming the general-purpose "
                + "condition and both counts")
        void aGeneralPurposeArrayShortByOneIsRefused() throws IOException {
            final List<String> shortened = mutableGeneralPurpose();
            shortened.remove(shortened.size() - 1);
            final String doctored = areaCodeResourceWithGeneralPurpose(shortened);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> serviceWithJson(
                            ValidationLookupService.NANPA_AREA_CODES_LOCATION, doctored))
                    .withMessageContaining(CONDITION_GENERAL_PURPOSE)
                    .withMessageContaining("must hold exactly "
                            + EXPECTED_GENERAL_PURPOSE_AREA_CODE_COUNT + " values");
        }

        @Test
        @DisplayName("a general-purpose array carrying a duplicate is refused, naming the condition and the "
                + "distinct count")
        void aGeneralPurposeArrayWithADuplicateIsRefused() throws IOException {
            final List<String> duplicated = mutableGeneralPurpose();
            duplicated.set(1, duplicated.get(0));
            final String doctored = areaCodeResourceWithGeneralPurpose(duplicated);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> serviceWithJson(
                            ValidationLookupService.NANPA_AREA_CODES_LOCATION, doctored))
                    .withMessageContaining(CONDITION_GENERAL_PURPOSE)
                    .withMessageContaining("are distinct");
        }

        @Test
        @DisplayName("a general-purpose array carrying a wrongly sized code is refused, naming the declared "
                + "width")
        void aGeneralPurposeArrayWithAWronglySizedCodeIsRefused() throws IOException {
            final List<String> wronglySized = mutableGeneralPurpose();
            wronglySized.set(0, "2011");
            final String doctored = areaCodeResourceWithGeneralPurpose(wronglySized);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> serviceWithJson(
                            ValidationLookupService.NANPA_AREA_CODES_LOCATION, doctored))
                    .withMessageContaining(CONDITION_GENERAL_PURPOSE)
                    .withMessageContaining("the copybook declares width " + EXPECTED_AREA_CODE_WIDTH);
        }

        @Test
        @DisplayName("a general-purpose array carrying a null entry is refused, naming the position")
        void aGeneralPurposeArrayWithANullEntryIsRefused() throws IOException {
            final List<String> withNull = mutableGeneralPurpose();
            withNull.set(0, null);
            final String doctored = areaCodeResourceWithGeneralPurpose(withNull);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> serviceWithJson(
                            ValidationLookupService.NANPA_AREA_CODES_LOCATION, doctored))
                    .withMessageContaining(CONDITION_GENERAL_PURPOSE)
                    .withMessageContaining("holds a null at position 0");
        }

        @Test
        @DisplayName("two area-code subsets that overlap are refused, naming both conditions and the shared "
                + "code, because disjointness is what makes the full table derivable")
        void overlappingAreaCodeSubsetsAreRefused() throws IOException {
            // An overlap has to be created by injecting an easily-recognisable code into the
            // general-purpose array alone. Exchanging a value between the two arrays would leave them
            // disjoint and would not exercise this check at all.
            final List<String> infiltrated = mutableGeneralPurpose();
            infiltrated.set(0, EASY_RECOGNITION_ONLY_AREA_CODE);
            final String doctored = areaCodeResourceWithGeneralPurpose(infiltrated);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> serviceWithJson(
                            ValidationLookupService.NANPA_AREA_CODES_LOCATION, doctored))
                    .withMessageContaining(CONDITION_GENERAL_PURPOSE)
                    .withMessageContaining(CONDITION_EASY_RECOGNITION)
                    .withMessageContaining("must be disjoint")
                    .withMessageContaining(EASY_RECOGNITION_ONLY_AREA_CODE);
        }

        @Test
        @DisplayName("a state-code resource short by one code is refused, naming the state condition")
        void aStateCodeResourceShortByOneIsRefused() throws IOException {
            final List<String> shortened = new ArrayList<>(parseTokenArray(US_STATE_RESOURCE_PATH));
            shortened.remove(shortened.size() - 1);
            final String doctored = asJson(shortened);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> serviceWithJson(
                            ValidationLookupService.US_STATE_CODES_LOCATION, doctored))
                    .withMessageContaining(CONDITION_US_STATE_CODE)
                    .withMessageContaining("must hold exactly " + EXPECTED_US_STATE_CODE_COUNT + " values");
        }

        @Test
        @DisplayName("a state-code resource carrying a three-character code is refused, naming the declared "
                + "width of two")
        void aStateCodeResourceWithAWronglySizedCodeIsRefused() throws IOException {
            final List<String> wronglySized = new ArrayList<>(parseTokenArray(US_STATE_RESOURCE_PATH));
            wronglySized.set(0, "CAL");
            final String doctored = asJson(wronglySized);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> serviceWithJson(
                            ValidationLookupService.US_STATE_CODES_LOCATION, doctored))
                    .withMessageContaining(CONDITION_US_STATE_CODE)
                    .withMessageContaining("the copybook declares width " + EXPECTED_US_STATE_CODE_WIDTH);
        }

        @Test
        @DisplayName("a state-plus-ZIP resource short by one combination is refused, naming the combination "
                + "condition")
        void aStateZipResourceShortByOneIsRefused() throws IOException {
            final List<String> shortened = new ArrayList<>(parseTokenArray(STATE_ZIP_RESOURCE_PATH));
            shortened.remove(shortened.size() - 1);
            final String doctored = asJson(shortened);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> serviceWithJson(
                            ValidationLookupService.STATE_ZIP_PREFIXES_LOCATION, doctored))
                    .withMessageContaining(CONDITION_US_STATE_ZIP_COMBO)
                    .withMessageContaining("must hold exactly "
                            + EXPECTED_US_STATE_ZIP_COMBINATION_COUNT + " values");
        }

        @Test
        @DisplayName("a state-plus-ZIP resource carrying a duplicate combination is refused")
        void aStateZipResourceWithADuplicateIsRefused() throws IOException {
            final List<String> duplicated = new ArrayList<>(parseTokenArray(STATE_ZIP_RESOURCE_PATH));
            duplicated.set(1, duplicated.get(0));
            final String doctored = asJson(duplicated);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> serviceWithJson(
                            ValidationLookupService.STATE_ZIP_PREFIXES_LOCATION, doctored))
                    .withMessageContaining(CONDITION_US_STATE_ZIP_COMBO)
                    .withMessageContaining("are distinct");
        }

        @Test
        @DisplayName("a state-plus-ZIP resource carrying a five-character combination is refused, naming the "
                + "declared width of four")
        void aStateZipResourceWithAWronglySizedCombinationIsRefused() throws IOException {
            final List<String> wronglySized = new ArrayList<>(parseTokenArray(STATE_ZIP_RESOURCE_PATH));
            wronglySized.set(0, "AA345");
            final String doctored = asJson(wronglySized);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> serviceWithJson(
                            ValidationLookupService.STATE_ZIP_PREFIXES_LOCATION, doctored))
                    .withMessageContaining(CONDITION_US_STATE_ZIP_COMBO)
                    .withMessageContaining("the copybook declares width "
                            + EXPECTED_STATE_AND_FIRST_ZIP2_WIDTH);
        }

        @Test
        @DisplayName("a null JSON mapper is rejected, naming the collaborator")
        void aNullJsonMapperIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ValidationLookupService(null, new DefaultResourceLoader()))
                    .withMessageContaining("objectMapper");
        }

        @Test
        @DisplayName("a null resource loader is rejected, naming the collaborator")
        void aNullResourceLoaderIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ValidationLookupService(MAPPER, null))
                    .withMessageContaining("resourceLoader");
        }

        /**
         * Returns a mutable copy of the real general-purpose array, ready to be doctored.
         *
         * <p>The copy comes from an independent parse of the resource rather than from the class under
         * test, so a doctored case never depends on the very loading path it is trying to break.</p>
         *
         * @return a mutable copy of the 410 general-purpose codes in declaration order
         * @throws IOException if the resource cannot be read
         */
        private List<String> mutableGeneralPurpose() throws IOException {
            return new ArrayList<>(parseAreaCodeSubset(ORACLE_KEY_GENERAL_PURPOSE));
        }
    }

    @Nested
    @DisplayName("Absent and blank input is answered rather than thrown at, for every predicate")
    class NullAndBlankInput {

        @ParameterizedTest(name = "[{index}] every predicate answers false for [{0}]")
        @NullSource
        @ValueSource(strings = {"", " ", "  ", "   ", "    ", "\t", "\n"})
        @DisplayName("a null, empty or blank candidate is rejected by all five predicates without any "
                + "exception escaping")
        void everyPredicateRejectsAbsentOrBlankInput(final String candidate) {
            assertThat(SERVICE.isValidGeneralPurposeAreaCode(candidate))
                    .as("general-purpose predicate")
                    .isFalse();
            assertThat(SERVICE.isValidEasilyRecognisableAreaCode(candidate))
                    .as("easily-recognisable predicate")
                    .isFalse();
            assertThat(SERVICE.isValidPhoneAreaCode(candidate))
                    .as("full-table predicate")
                    .isFalse();
            assertThat(SERVICE.isValidUsStateCode(candidate))
                    .as("state-code predicate")
                    .isFalse();
            assertThat(SERVICE.isValidUsStateZipCodeCombination(candidate))
                    .as("state-plus-ZIP predicate")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("The published counts, widths, resource locations and array names")
    class PublishedContract {

        @Test
        @DisplayName("the published counts equal the cardinalities measured from the copybook")
        void thePublishedCountsEqualTheMeasuredCardinalities() {
            assertThat(ValidationLookupService.GENERAL_PURPOSE_AREA_CODE_COUNT)
                    .isEqualTo(EXPECTED_GENERAL_PURPOSE_AREA_CODE_COUNT);
            assertThat(ValidationLookupService.EASY_RECOGNITION_AREA_CODE_COUNT)
                    .isEqualTo(EXPECTED_EASY_RECOGNITION_AREA_CODE_COUNT);
            assertThat(ValidationLookupService.PHONE_AREA_CODE_COUNT)
                    .isEqualTo(EXPECTED_PHONE_AREA_CODE_COUNT);
            assertThat(ValidationLookupService.US_STATE_CODE_COUNT)
                    .isEqualTo(EXPECTED_US_STATE_CODE_COUNT);
            assertThat(ValidationLookupService.US_STATE_ZIP_COMBINATION_COUNT)
                    .isEqualTo(EXPECTED_US_STATE_ZIP_COMBINATION_COUNT);
        }

        @Test
        @DisplayName("the published widths equal the widths the copybook picture clauses declare")
        void thePublishedWidthsEqualTheDeclaredWidths() {
            assertThat(ValidationLookupService.AREA_CODE_WIDTH).isEqualTo(EXPECTED_AREA_CODE_WIDTH);
            assertThat(ValidationLookupService.US_STATE_CODE_WIDTH)
                    .isEqualTo(EXPECTED_US_STATE_CODE_WIDTH);
            assertThat(ValidationLookupService.US_STATE_AND_FIRST_ZIP2_WIDTH)
                    .isEqualTo(EXPECTED_STATE_AND_FIRST_ZIP2_WIDTH);
        }

        @Test
        @DisplayName("each published count agrees with the size of the table it describes")
        void eachPublishedCountAgreesWithItsTable() {
            assertThat(SERVICE.generalPurposeAreaCodes())
                    .hasSize(ValidationLookupService.GENERAL_PURPOSE_AREA_CODE_COUNT);
            assertThat(SERVICE.easilyRecognisableAreaCodes())
                    .hasSize(ValidationLookupService.EASY_RECOGNITION_AREA_CODE_COUNT);
            assertThat(SERVICE.phoneAreaCodes()).hasSize(ValidationLookupService.PHONE_AREA_CODE_COUNT);
            assertThat(SERVICE.usStateCodes()).hasSize(ValidationLookupService.US_STATE_CODE_COUNT);
            assertThat(SERVICE.usStateZipCodeCombinations())
                    .hasSize(ValidationLookupService.US_STATE_ZIP_COMBINATION_COUNT);
        }

        @Test
        @DisplayName("the published resource locations point at the three externalised lookup tables")
        void thePublishedResourceLocationsPointAtTheLookupTables() {
            assertThat(ValidationLookupService.NANPA_AREA_CODES_LOCATION)
                    .isEqualTo("classpath:lookup/nanpa-area-codes.json");
            assertThat(ValidationLookupService.US_STATE_CODES_LOCATION)
                    .isEqualTo("classpath:lookup/us-state-codes.json");
            assertThat(ValidationLookupService.STATE_ZIP_PREFIXES_LOCATION)
                    .isEqualTo("classpath:lookup/state-zip-prefixes.json");
        }

        @Test
        @DisplayName("the published array names match the names the area-code resource actually declares")
        void thePublishedArrayNamesMatchTheResource() throws IOException {
            assertThat(ValidationLookupService.JSON_KEY_GENERAL_PURPOSE)
                    .isEqualTo(ORACLE_KEY_GENERAL_PURPOSE);
            assertThat(ValidationLookupService.JSON_KEY_EASY_RECOGNITION)
                    .isEqualTo(ORACLE_KEY_EASY_RECOGNITION);
            assertThat(parseAreaCodeArrays()).containsOnlyKeys(
                    ValidationLookupService.JSON_KEY_GENERAL_PURPOSE,
                    ValidationLookupService.JSON_KEY_EASY_RECOGNITION);
        }
    }
}
