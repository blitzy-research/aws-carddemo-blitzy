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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
 * Unit tests for the three externalised validation lookup tables, migrated from
 * {@code app/cpy/CSLKPCDY.cpy} - a copybook whose entire body is level-88 condition-name literal
 * lists rather than a data structure. Its only includer anywhere in the estate is
 * {@code app/cbl/COACTUPC.cbl}, so the whole lookup surface is reached through the account-update
 * feature alone; there is no second caller whose own tests would catch a table that had quietly
 * drifted, which is why these tests are exhaustive rather than representative.
 *
 * <p><strong>The five cardinalities are the contract:</strong> 410 general-purpose and 80
 * easily-recognisable area codes, both loaded; 490 area codes in total, <em>derived</em> by unioning
 * those two subsets rather than stored, because the partition is exact - 410 plus 80 is 490 and the
 * subsets share no element - and a third stored copy would create a three-way consistency hazard; 56
 * state, district and territory codes; and 240 four-character state-plus-ZIP combinations.
 *
 * <p><strong>Two failure modes justify the highest-value assertions here.</strong> The partition
 * could stop being exact: one value added to both area-code subsets, or moved between them, changes
 * what the general-purpose predicate accepts without changing any count a casual reviewer checks, so
 * union-equality and empty-intersection are asserted directly. And the two address tables could be
 * conflated: six of the 62 prefixes appearing in the 240 combinations are absent from the 56 state
 * codes, so cross-checking a composite key's leading two characters against the state table would
 * reject addresses the legacy system accepts.
 *
 * <p><strong>Provenance of the expectations, stated plainly.</strong> The expected counts are
 * literals declared in this class, independent of the code they judge. The expected memberships are
 * not independent of the shipped resources in origin: the golden fixtures under
 * {@code fixtures/expected/lookup/} and the production JSON resources were both derived from the same
 * copybook condition lists. Comparing them therefore proves faithful transport and pins both sides
 * against a later hand-edit - each fixture is additionally pinned by a SHA-256 digest literal
 * declared here - but it is not a second, independent authority on what the legacy lists contain. The
 * one authority is the copybook, cited and never transcribed.
 *
 * <p>Widths are measured in encoded bytes rather than characters, since these are byte-width
 * contracts and a character count would pass on a value that is byte-wrong. Failure paths are driven
 * through the resource-loading collaborator the constructor already accepts, using an in-test double
 * that serves doctored content for one location and the real resource for the others; no private
 * field is read and there is no reflection anywhere in this class.
 */
@DisplayName("Validation lookup tables: the five legacy condition lists keep their exact membership")
class ValidationLookupServiceTest {
    private static final int EXPECTED_PHONE_AREA_CODE_COUNT = 490;

    private static final int EXPECTED_GENERAL_PURPOSE_AREA_CODE_COUNT = 410;

    private static final int EXPECTED_EASY_RECOGNITION_AREA_CODE_COUNT = 80;

    private static final int EXPECTED_US_STATE_CODE_COUNT = 56;

    private static final int EXPECTED_US_STATE_ZIP_COMBINATION_COUNT = 240;

    private static final int EXPECTED_AREA_CODE_WIDTH = 3;

    private static final int EXPECTED_US_STATE_CODE_WIDTH = 2;

    private static final int EXPECTED_STATE_AND_FIRST_ZIP2_WIDTH = 4;

    private static final String NANPA_RESOURCE_PATH = "/lookup/nanpa-area-codes.json";

    private static final String US_STATE_RESOURCE_PATH = "/lookup/us-state-codes.json";

    private static final String STATE_ZIP_RESOURCE_PATH = "/lookup/state-zip-prefixes.json";

    // The golden fixtures. Each is one canonical ascending token-per-line US-ASCII file carrying the
    // values a single level-88 condition of app/cpy/CSLKPCDY.cpy declares, extracted mechanically from
    // that copybook - the same source the production JSON resources were derived from. Comparing the
    // two therefore proves faithful transport and pins both sides against a later hand-edit; it is not
    // a second independent authority on what the legacy lists contain, and the digests below exist so
    // that editing a fixture to match a drifted resource fails rather than passes. The 490-value union
    // has no stored fixture: the extraction established that the copybook's own union condition equals
    // the union of its two subsets, so it is derived here exactly as the class under test derives it,
    // which keeps no third copy to fall out of step.
    private static final String GOLDEN_GENERAL_PURPOSE_PATH =
            "/fixtures/expected/lookup/nanpa-general-purpose-area-codes.txt";

    private static final String GOLDEN_EASY_RECOGNITION_PATH =
            "/fixtures/expected/lookup/nanpa-easily-recognisable-area-codes.txt";

    private static final String GOLDEN_US_STATE_PATH = "/fixtures/expected/lookup/us-state-codes.txt";

    private static final String GOLDEN_STATE_ZIP_PATH =
            "/fixtures/expected/lookup/us-state-zip-prefixes.txt";

    private static final String GOLDEN_DIGEST_ALGORITHM = "SHA-256";

    private static final String GOLDEN_GENERAL_PURPOSE_DIGEST =
            "0a989369e9648196c5e4208df84c76332b2ef5d3a49fcb0fea839ec3397e4f7f";

    private static final String GOLDEN_EASY_RECOGNITION_DIGEST =
            "c92d4380d609d5ca74c226a8d09d149976ee4755ae98e9bffcc0ca118b16bd45";

    private static final String GOLDEN_PHONE_AREA_CODE_UNION_DIGEST =
            "2c3f82fc194043fe57bec77240318aae8d4f2f76ebe5a3c9df3dfe8605d678d2";

    private static final String GOLDEN_US_STATE_DIGEST =
            "8842130558100f9982cb2e490092c50479c13c723fe55d4caffa80d79447803e";

    private static final String GOLDEN_STATE_ZIP_DIGEST =
            "38169c4ad5168d2da09ea2204e74a84c5d065cfeae3130244d2fbbc7efc374b6";

    private static final String ORACLE_KEY_GENERAL_PURPOSE = "generalPurpose";

    private static final String ORACLE_KEY_EASY_RECOGNITION = "easyRecognition";

    private static final String GENERAL_PURPOSE_ONLY_AREA_CODE = "201";

    private static final String EASY_RECOGNITION_ONLY_AREA_CODE = "800";

    private static final String GENUINE_STATE_CODE = "CA";

    private static final String GENUINE_STATE_WITH_VALID_COMBINATION = "CA90";

    private static final String GENUINE_STATE_WITH_ABSENT_COMBINATION = "CA34";

    private static final String CONDITION_GENERAL_PURPOSE = "VALID-GENERAL-PURP-CODE";

    private static final String CONDITION_EASY_RECOGNITION = "VALID-EASY-RECOG-AREA-CODE";

    private static final String CONDITION_PHONE_AREA_CODE = "VALID-PHONE-AREA-CODE";

    private static final String CONDITION_US_STATE_CODE = "VALID-US-STATE-CODE";

    private static final String CONDITION_US_STATE_ZIP_COMBO = "VALID-US-STATE-ZIP-CD2-COMBO";

    private static final TypeReference<List<String>> TOKEN_ARRAY = new TypeReference<List<String>>() { };

    private static final TypeReference<Map<String, List<String>>> NAMED_TOKEN_ARRAYS =
            new TypeReference<Map<String, List<String>>>() { };

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ValidationLookupService SERVICE =
            new ValidationLookupService(MAPPER, new DefaultResourceLoader());

    private static List<String> parseTokenArray(final String classpathPath) throws IOException {
        try (InputStream json = ValidationLookupServiceTest.class.getResourceAsStream(classpathPath)) {
            assertThat(json)
                    .as("the resource %s must be present on the test classpath", classpathPath)
                    .isNotNull();
            return MAPPER.readValue(json, TOKEN_ARRAY);
        }
    }

    private static Map<String, List<String>> parseAreaCodeArrays() throws IOException {
        try (InputStream json = ValidationLookupServiceTest.class.getResourceAsStream(NANPA_RESOURCE_PATH)) {
            assertThat(json)
                    .as("the resource %s must be present on the test classpath", NANPA_RESOURCE_PATH)
                    .isNotNull();
            return new LinkedHashMap<>(MAPPER.readValue(json, NAMED_TOKEN_ARRAYS));
        }
    }

    private static List<String> parseAreaCodeSubset(final String arrayName) throws IOException {
        final List<String> subset = parseAreaCodeArrays().get(arrayName);
        assertThat(subset)
                .as("the resource %s must declare the array %s", NANPA_RESOURCE_PATH, arrayName)
                .isNotNull();
        return subset;
    }

    private static final class SingleOverrideResourceLoader implements ResourceLoader {
        private final ResourceLoader delegate = new DefaultResourceLoader();

        private final String overriddenLocation;

        private final Resource replacement;

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

    private static Resource jsonResource(final String json) {
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8), "doctored lookup resource");
    }

    private static String asJson(final Object value) throws IOException {
        return MAPPER.writeValueAsString(value);
    }

    private static ValidationLookupService serviceWithReplacement(final String location,
            final Resource replacement) {
        return new ValidationLookupService(MAPPER, new SingleOverrideResourceLoader(location, replacement));
    }

    private static ValidationLookupService serviceWithJson(final String location, final String json) {
        return serviceWithReplacement(location, jsonResource(json));
    }

    private static String areaCodeResourceWithGeneralPurpose(final List<String> doctoredGeneralPurpose)
            throws IOException {
        final Map<String, List<String>> arrays = parseAreaCodeArrays();
        arrays.put(ORACLE_KEY_GENERAL_PURPOSE, doctoredGeneralPurpose);
        return asJson(arrays);
    }

    private static int encodedWidth(final String token) {
        return token.getBytes(StandardCharsets.US_ASCII).length;
    }

    private static byte[] goldenBytes(final String classpathPath) throws IOException {
        try (InputStream fixture = ValidationLookupServiceTest.class.getResourceAsStream(classpathPath)) {
            assertThat(fixture)
                    .as("the golden fixture %s must be present on the test classpath", classpathPath)
                    .isNotNull();
            return fixture.readAllBytes();
        }
    }

    private static List<String> goldenTokens(final String classpathPath) throws IOException {
        final String content = new String(goldenBytes(classpathPath), StandardCharsets.US_ASCII);
        final List<String> tokens = new ArrayList<>();
        for (final String line : content.split("\n", -1)) {
            if (!line.isEmpty()) {
                tokens.add(line);
            }
        }
        return tokens;
    }

    private static String sha256Hex(final byte[] content) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(GOLDEN_DIGEST_ALGORITHM);
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(
                    GOLDEN_DIGEST_ALGORITHM + " is mandatory in a conforming JVM", unavailable);
        }
        return HexFormat.of().formatHex(digest.digest(content));
    }

    private static String canonicalDigest(final Collection<String> tokens) {
        final StringBuilder canonical = new StringBuilder();
        for (final String token : new TreeSet<>(tokens)) {
            canonical.append(token).append('\n');
        }
        return sha256Hex(canonical.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static Set<String> goldenAreaCodeUnion() throws IOException {
        final Set<String> union = new LinkedHashSet<>(goldenTokens(GOLDEN_GENERAL_PURPOSE_PATH));
        union.addAll(goldenTokens(GOLDEN_EASY_RECOGNITION_PATH));
        return union;
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
    @DisplayName("The golden fixtures extracted from the legacy condition lists are intact, so they can be "
            + "trusted as the expectation")
    class GoldenFixtureIntegrity {
        @Test
        @DisplayName("the general-purpose fixture carries its pinned digest, so its 410 values are the ones "
                + "extracted from the legacy condition list")
        void theGeneralPurposeFixtureCarriesItsPinnedDigest() throws IOException {
            assertPinnedDigest(GOLDEN_GENERAL_PURPOSE_PATH, GOLDEN_GENERAL_PURPOSE_DIGEST);
        }

        @Test
        @DisplayName("the easily-recognisable fixture carries its pinned digest")
        void theEasilyRecognisableFixtureCarriesItsPinnedDigest() throws IOException {
            assertPinnedDigest(GOLDEN_EASY_RECOGNITION_PATH, GOLDEN_EASY_RECOGNITION_DIGEST);
        }

        @Test
        @DisplayName("the state-code fixture carries its pinned digest")
        void theStateCodeFixtureCarriesItsPinnedDigest() throws IOException {
            assertPinnedDigest(GOLDEN_US_STATE_PATH, GOLDEN_US_STATE_DIGEST);
        }

        @Test
        @DisplayName("the state-plus-ZIP fixture carries its pinned digest")
        void theStateZipFixtureCarriesItsPinnedDigest() throws IOException {
            assertPinnedDigest(GOLDEN_STATE_ZIP_PATH, GOLDEN_STATE_ZIP_DIGEST);
        }

        @Test
        @DisplayName("the general-purpose fixture holds 410 tokens, ascending, distinct, three bytes wide "
                + "and none blank")
        void theGeneralPurposeFixtureIsWellFormed() throws IOException {
            assertWellFormedGolden(GOLDEN_GENERAL_PURPOSE_PATH,
                    EXPECTED_GENERAL_PURPOSE_AREA_CODE_COUNT, EXPECTED_AREA_CODE_WIDTH);
        }

        @Test
        @DisplayName("the easily-recognisable fixture holds 80 tokens, ascending, distinct, three bytes wide "
                + "and none blank")
        void theEasilyRecognisableFixtureIsWellFormed() throws IOException {
            assertWellFormedGolden(GOLDEN_EASY_RECOGNITION_PATH,
                    EXPECTED_EASY_RECOGNITION_AREA_CODE_COUNT, EXPECTED_AREA_CODE_WIDTH);
        }

        @Test
        @DisplayName("the state-code fixture holds 56 tokens, ascending, distinct, two bytes wide and none "
                + "blank")
        void theStateCodeFixtureIsWellFormed() throws IOException {
            assertWellFormedGolden(GOLDEN_US_STATE_PATH, EXPECTED_US_STATE_CODE_COUNT,
                    EXPECTED_US_STATE_CODE_WIDTH);
        }

        @Test
        @DisplayName("the state-plus-ZIP fixture holds 240 tokens, ascending, distinct, four bytes wide and "
                + "none blank")
        void theStateZipFixtureIsWellFormed() throws IOException {
            assertWellFormedGolden(GOLDEN_STATE_ZIP_PATH, EXPECTED_US_STATE_ZIP_COMBINATION_COUNT,
                    EXPECTED_STATE_AND_FIRST_ZIP2_WIDTH);
        }

        @Test
        @DisplayName("the two golden area-code subsets are disjoint and union to 490, which is the partition "
                + "the legacy copybook declares")
        void theTwoGoldenSubsetsPartitionTheFourHundredAndNinety() throws IOException {
            final List<String> generalPurpose = goldenTokens(GOLDEN_GENERAL_PURPOSE_PATH);
            final List<String> easilyRecognisable = goldenTokens(GOLDEN_EASY_RECOGNITION_PATH);

            assertThat(Collections.disjoint(generalPurpose, easilyRecognisable))
                    .as("%s and %s must share no value", CONDITION_GENERAL_PURPOSE,
                            CONDITION_EASY_RECOGNITION)
                    .isTrue();
            assertThat(goldenAreaCodeUnion())
                    .as("the derived %s table", CONDITION_PHONE_AREA_CODE)
                    .hasSize(EXPECTED_PHONE_AREA_CODE_COUNT);
            assertThat(canonicalDigest(goldenAreaCodeUnion()))
                    .as("pinned digest of the derived %s table", CONDITION_PHONE_AREA_CODE)
                    .isEqualTo(GOLDEN_PHONE_AREA_CODE_UNION_DIGEST);
        }

        private void assertPinnedDigest(final String classpathPath, final String expectedDigest)
                throws IOException {
            assertThat(sha256Hex(goldenBytes(classpathPath)))
                    .as("SHA-256 of %s; a mismatch means the fixture was edited and no longer records the "
                            + "legacy values", classpathPath)
                    .isEqualTo(expectedDigest);
            assertThat(canonicalDigest(goldenTokens(classpathPath)))
                    .as("%s must already be in canonical form, so digesting its tokens reproduces the "
                            + "digest of its bytes", classpathPath)
                    .isEqualTo(expectedDigest);
        }

        private void assertWellFormedGolden(final String classpathPath, final int expectedCount,
                final int expectedWidth) throws IOException {
            final List<String> tokens = goldenTokens(classpathPath);

            assertThat(tokens)
                    .as("token count in %s", classpathPath)
                    .hasSize(expectedCount);
            assertThat(Set.copyOf(tokens))
                    .as("distinct token count in %s, which must equal the declared count because the legacy "
                            + "condition list carries no duplicate", classpathPath)
                    .hasSize(expectedCount);
            assertThat(tokens)
                    .as("%s must be stored in ascending order, which is what makes its digest canonical",
                            classpathPath)
                    .isSortedAccordingTo(Comparator.naturalOrder());
            assertThat(tokens).allSatisfy(token -> {
                assertThat(token).as("no token may be blank").isNotBlank();
                assertThat(token).as("no token may carry surrounding space").isEqualTo(token.strip());
                assertThat(encodedWidth(token))
                        .as("encoded width of token '%s'", token)
                        .isEqualTo(expectedWidth);
            });
        }
    }

    @Nested
    @DisplayName("Every published table judged against the legacy golden values, exhaustively and element "
            + "for element")
    class LegacyAnchoredMembership {
        @Test
        @DisplayName("the published general-purpose table is exactly the 410 legacy general-purpose values")
        void thePublishedGeneralPurposeTableIsExactlyTheLegacyValues() throws IOException {
            assertPublishedTableMatchesGolden(SERVICE.generalPurposeAreaCodes(), GOLDEN_GENERAL_PURPOSE_PATH,
                    GOLDEN_GENERAL_PURPOSE_DIGEST, CONDITION_GENERAL_PURPOSE);
        }

        @Test
        @DisplayName("the published easily-recognisable table is exactly the 80 legacy easily-recognisable "
                + "values")
        void thePublishedEasilyRecognisableTableIsExactlyTheLegacyValues() throws IOException {
            assertPublishedTableMatchesGolden(SERVICE.easilyRecognisableAreaCodes(),
                    GOLDEN_EASY_RECOGNITION_PATH, GOLDEN_EASY_RECOGNITION_DIGEST,
                    CONDITION_EASY_RECOGNITION);
        }

        @Test
        @DisplayName("the published state-code table is exactly the 56 legacy state, district and territory "
                + "values")
        void thePublishedStateCodeTableIsExactlyTheLegacyValues() throws IOException {
            assertPublishedTableMatchesGolden(SERVICE.usStateCodes(), GOLDEN_US_STATE_PATH,
                    GOLDEN_US_STATE_DIGEST, CONDITION_US_STATE_CODE);
        }

        @Test
        @DisplayName("the published state-plus-ZIP table is exactly the 240 legacy combination values")
        void thePublishedStateZipTableIsExactlyTheLegacyValues() throws IOException {
            assertPublishedTableMatchesGolden(SERVICE.usStateZipCodeCombinations(), GOLDEN_STATE_ZIP_PATH,
                    GOLDEN_STATE_ZIP_DIGEST, CONDITION_US_STATE_ZIP_COMBO);
        }

        @Test
        @DisplayName("the published full area-code table is exactly the 490 legacy values, derived from the "
                + "two golden subsets rather than read from a third copy")
        void thePublishedFullAreaCodeTableIsExactlyTheLegacyUnion() throws IOException {
            final Set<String> golden = goldenAreaCodeUnion();

            assertThat(SERVICE.phoneAreaCodes())
                    .as("the published %s table against the legacy values", CONDITION_PHONE_AREA_CODE)
                    .containsExactlyInAnyOrderElementsOf(golden);
            assertThat(canonicalDigest(SERVICE.phoneAreaCodes()))
                    .as("canonical digest of the published %s table", CONDITION_PHONE_AREA_CODE)
                    .isEqualTo(GOLDEN_PHONE_AREA_CODE_UNION_DIGEST);
        }

        @Test
        @DisplayName("the area-code resource still stores only the two subsets, so the 490 remains derived "
                + "and cannot fall out of step with them")
        void theAreaCodeResourceStoresOnlyTheTwoSubsets() throws IOException {
            assertThat(parseAreaCodeArrays())
                    .containsOnlyKeys(ORACLE_KEY_GENERAL_PURPOSE, ORACLE_KEY_EASY_RECOGNITION);
        }

        @Test
        @DisplayName("every legacy general-purpose value is accepted by the general-purpose predicate, "
                + "accepted by the full-table predicate and refused by the easily-recognisable predicate")
        void everyLegacyGeneralPurposeValueAnswersAllThreePredicatesCorrectly() throws IOException {
            for (final String areaCode : goldenTokens(GOLDEN_GENERAL_PURPOSE_PATH)) {
                assertThat(SERVICE.isValidGeneralPurposeAreaCode(areaCode))
                        .as("%s declares '%s'", CONDITION_GENERAL_PURPOSE, areaCode)
                        .isTrue();
                assertThat(SERVICE.isValidPhoneAreaCode(areaCode))
                        .as("%s therefore also declares '%s'", CONDITION_PHONE_AREA_CODE, areaCode)
                        .isTrue();
                assertThat(SERVICE.isValidEasilyRecognisableAreaCode(areaCode))
                        .as("%s must not declare '%s', because the two subsets are disjoint",
                                CONDITION_EASY_RECOGNITION, areaCode)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("every legacy easily-recognisable value is accepted by the easily-recognisable "
                + "predicate, accepted by the full-table predicate and refused by the general-purpose "
                + "predicate")
        void everyLegacyEasilyRecognisableValueAnswersAllThreePredicatesCorrectly() throws IOException {
            for (final String areaCode : goldenTokens(GOLDEN_EASY_RECOGNITION_PATH)) {
                assertThat(SERVICE.isValidEasilyRecognisableAreaCode(areaCode))
                        .as("%s declares '%s'", CONDITION_EASY_RECOGNITION, areaCode)
                        .isTrue();
                assertThat(SERVICE.isValidPhoneAreaCode(areaCode))
                        .as("%s therefore also declares '%s'", CONDITION_PHONE_AREA_CODE, areaCode)
                        .isTrue();
                assertThat(SERVICE.isValidGeneralPurposeAreaCode(areaCode))
                        .as("%s must not declare '%s', which is the distinction the legacy telephone edit "
                                + "depends on", CONDITION_GENERAL_PURPOSE, areaCode)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("every legacy state code is accepted by the state-code predicate")
        void everyLegacyStateCodeIsAccepted() throws IOException {
            for (final String stateCode : goldenTokens(GOLDEN_US_STATE_PATH)) {
                assertThat(SERVICE.isValidUsStateCode(stateCode))
                        .as("%s declares '%s'", CONDITION_US_STATE_CODE, stateCode)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("every legacy state-plus-ZIP combination is accepted by the combination predicate")
        void everyLegacyCombinationIsAccepted() throws IOException {
            for (final String combination : goldenTokens(GOLDEN_STATE_ZIP_PATH)) {
                assertThat(SERVICE.isValidUsStateZipCodeCombination(combination))
                        .as("%s declares '%s'", CONDITION_US_STATE_ZIP_COMBO, combination)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a value the legacy area-code lists never declare is refused by all three area-code "
                + "predicates, so the published tables are no wider than the legacy ones")
        void aValueOutsideTheLegacyAreaCodeListsIsRefused() throws IOException {
            final Set<String> golden = goldenAreaCodeUnion();
            final List<String> absent = new ArrayList<>();
            for (int candidate = 200; candidate <= 999 && absent.size() < 3; candidate++) {
                final String token = Integer.toString(candidate);
                if (!golden.contains(token)) {
                    absent.add(token);
                }
            }

            assertThat(absent)
                    .as("the legacy union covers 490 of the 800 three-digit tokens, so absent tokens exist")
                    .hasSize(3);
            assertThat(absent).allSatisfy(token -> {
                assertThat(SERVICE.isValidPhoneAreaCode(token))
                        .as("%s does not declare '%s'", CONDITION_PHONE_AREA_CODE, token)
                        .isFalse();
                assertThat(SERVICE.isValidGeneralPurposeAreaCode(token)).isFalse();
                assertThat(SERVICE.isValidEasilyRecognisableAreaCode(token)).isFalse();
            });
        }

        private void assertPublishedTableMatchesGolden(final Set<String> published, final String goldenPath,
                final String goldenDigest, final String conditionName) throws IOException {
            assertThat(sha256Hex(goldenBytes(goldenPath)))
                    .as("the golden fixture for %s must be intact before it can judge anything",
                            conditionName)
                    .isEqualTo(goldenDigest);
            assertThat(published)
                    .as("the published %s table against the legacy values", conditionName)
                    .containsExactlyInAnyOrderElementsOf(goldenTokens(goldenPath));
            assertThat(canonicalDigest(published))
                    .as("canonical digest of the published %s table", conditionName)
                    .isEqualTo(goldenDigest);
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
