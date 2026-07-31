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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * Immutable membership sets for the three validation lookup tables that the legacy estate carried as
 * level-88 condition-name literal lists, together with the startup verification that proves the
 * externalised resources still carry exactly the values the copybook declared.
 *
 * <h2>Provenance</h2>
 * Translated from the AWS CardDemo z/OS mainframe application at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p>The legacy authority is {@code app/cpy/CSLKPCDY.cpy}, a copybook of <strong>1318</strong> lines. A
 * figure of 1,319 is sometimes quoted for this member; that count treats the newline terminating the last
 * line as introducing a further, empty line. The member has 1318 lines and this class cites 1318. The
 * copybook is also the estate's only member that places a descriptive banner (lines 1 to 7) <em>above</em>
 * its Apache licence header (lines 8 to 21) rather than below it; the generated Java nevertheless leads
 * with the licence header, as every other generated source does.</p>
 *
 * <p>{@code app/cbl/COACTUPC.cbl} is the <strong>sole</strong> includer of the copybook anywhere in the
 * estate. The entire validation-lookup surface is therefore reached through the account-update feature
 * alone, which is why the covering tests for this class have to be comprehensive rather than
 * representative: no other program exercises any part of it.</p>
 *
 * <p>No COBOL source is read at runtime and no COBOL statement is reproduced here. Only member names,
 * condition-name identifiers, field names, declared widths and measured cardinalities cross the boundary.
 * The lookup values themselves live exclusively in the three JSON resources named below, never in Java
 * source and never in a SQL migration.</p>
 *
 * <h2>The three copybook items and the five conditions declared on them</h2>
 * <ul>
 *   <li>{@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX} at line 24 hosts <em>three</em> conditions over
 *       the same three-byte item: {@code VALID-PHONE-AREA-CODE} at line 30, {@code VALID-GENERAL-PURP-CODE}
 *       at line 521 and {@code VALID-EASY-RECOG-AREA-CODE} at line 931.</li>
 *   <li>{@code 01 US-STATE-CODE-TO-EDIT PIC X(2)} at line 1012 hosts {@code VALID-US-STATE-CODE} at line
 *       1013.</li>
 *   <li>{@code 01 US-STATE-ZIPCODE-TO-EDIT} at line 1071 is a seven-byte group of
 *       {@code 02 US-STATE-AND-FIRST-ZIP2 PIC X(4)} at line 1072 followed by
 *       {@code 02 LAST-3-OF-ZIP PIC X(3)} at line 1314. {@code VALID-US-STATE-ZIP-CD2-COMBO} at line 1073
 *       is declared on the {@code PIC X(4)} sub-field only, so the last three ZIP digits take no part in
 *       the membership test.</li>
 * </ul>
 *
 * <h2>Cardinalities, and why one of the five is derived rather than stored</h2>
 * Every count below was obtained by parsing the copybook's code area rather than by reading prose, and
 * every one is re-verified against the loaded resources when this bean is constructed:
 * <ul>
 *   <li>{@code VALID-GENERAL-PURP-CODE} &mdash; 410 general-purpose area codes.</li>
 *   <li>{@code VALID-EASY-RECOG-AREA-CODE} &mdash; 80 easily-recognisable area codes.</li>
 *   <li>{@code VALID-PHONE-AREA-CODE} &mdash; 490 area codes, which is provably the <em>disjoint union</em>
 *       of the two sets above: the two subsets share no element, 410 plus 80 is 490 exactly, and their
 *       union equals the 490-value list element for element. This class therefore <strong>derives</strong>
 *       the 490-member set by unioning the other two and <strong>never stores it as a third array</strong>,
 *       because a third copy would create a three-way consistency hazard that nothing could detect.</li>
 *   <li>{@code VALID-US-STATE-CODE} &mdash; 56 two-character codes, the last six being the district and
 *       territory codes {@code DC}, {@code AS}, {@code GU}, {@code MP}, {@code PR} and {@code VI}.</li>
 *   <li>{@code VALID-US-STATE-ZIP-CD2-COMBO} &mdash; 240 four-character combinations, running from
 *       {@code AA34} to {@code WY83}.</li>
 * </ul>
 *
 * <h2>The two lists are deliberately never intersected</h2>
 * The 240 state-plus-ZIP combinations use 62 distinct two-character prefixes, and six of those prefixes
 * &mdash; {@code AA}, {@code AE}, {@code AP}, {@code FM}, {@code MH} and {@code PW} &mdash; do not appear
 * in the 56-code state list at all. They are the military and freely-associated-state prefixes. Any
 * attempt to intersect, cross-filter, sort or split the two lists against one another would reject
 * addresses that the legacy system accepts, so this class keeps the two tables strictly independent and
 * offers no operation that relates them.
 *
 * <h2>Values are alphanumeric tokens, never numbers</h2>
 * Every value in all three tables is an alphanumeric token drawn from a COBOL {@code PIC X} item. Nothing
 * here is parsed into {@code int}, {@code Integer} or {@code long}, and nothing loaded from a resource is
 * trimmed, case-folded, normalised, re-sorted or re-ordered. That is what keeps a leading zero, were one
 * ever added upstream, from being silently coerced away.
 *
 * <h2>Division of labour with the account-update validator</h2>
 * This class answers membership questions and nothing else. Message composition, error-flag state and
 * field-level error decoration belong to the account-update service and to the presentation DTO layer, and
 * the blank, numeric and zero pre-checks that precede the area-code membership test belong to the caller
 * that owns the phone cascade. The literals the caller emits on failure are recorded in the Javadoc of the
 * individual predicates so that the contract stays discoverable from here, but this class never composes
 * or returns them.
 *
 * <h2>Layer position and dependencies</h2>
 * A tier-zero service: it injects no other service. Its only collaborators are the Jackson object mapper
 * that Spring Boot auto-configures and Spring's resource abstraction, both supplied through the
 * constructor. Nothing in the API, batch or configuration packages is referenced.
 *
 * <h2>Thread safety</h2>
 * A stateless singleton. Every field is {@code private static final} or {@code private final}, the three
 * published sets are built with {@code Set.copyOf} and are therefore genuinely unmodifiable, there is no
 * setter, no lazy re-read and no mutable state of any kind, so the bean is safe for unrestricted
 * concurrent use. Each membership question is a hash-set containment test.
 */
@Service
public final class ValidationLookupService {

    /**
     * Structured logger for this service. The {@code com.carddemo} logger hierarchy is configured in
     * {@code logback-spring.xml} with its levels set per Spring profile.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ValidationLookupService.class);

    /**
     * Number of general-purpose North American area codes declared by
     * {@code VALID-GENERAL-PURP-CODE} at {@code app/cpy/CSLKPCDY.cpy} line 521, whose value list runs to
     * line 930.
     */
    public static final int GENERAL_PURPOSE_AREA_CODE_COUNT = 410;

    /**
     * Number of easily-recognisable North American area codes declared by
     * {@code VALID-EASY-RECOG-AREA-CODE} at {@code app/cpy/CSLKPCDY.cpy} line 931, whose value list runs to
     * line 1010. These are the repeated-digit and service codes such as the toll-free and directory ranges.
     */
    public static final int EASY_RECOGNITION_AREA_CODE_COUNT = 80;

    /**
     * Number of area codes declared by {@code VALID-PHONE-AREA-CODE} at {@code app/cpy/CSLKPCDY.cpy}
     * line 30, whose value list runs to line 520.
     *
     * <p>This constant is <strong>computed by summing</strong> the two subset counts rather than written as
     * a literal, and the corresponding set is derived by unioning the two subsets rather than loaded from a
     * third array. The copybook's own 490-value list is provably the disjoint union of the 410
     * general-purpose codes and the 80 easily-recognisable codes, so deriving it removes the only way the
     * three declarations could ever drift apart.</p>
     */
    public static final int PHONE_AREA_CODE_COUNT =
            GENERAL_PURPOSE_AREA_CODE_COUNT + EASY_RECOGNITION_AREA_CODE_COUNT;

    /**
     * Number of United States state, district and territory codes declared by
     * {@code VALID-US-STATE-CODE} at {@code app/cpy/CSLKPCDY.cpy} line 1013, whose value list runs to
     * line 1069.
     */
    public static final int US_STATE_CODE_COUNT = 56;

    /**
     * Number of state-plus-first-two-of-ZIP combinations declared by
     * {@code VALID-US-STATE-ZIP-CD2-COMBO} at {@code app/cpy/CSLKPCDY.cpy} line 1073, whose value list runs
     * to line 1313.
     */
    public static final int US_STATE_ZIP_COMBINATION_COUNT = 240;

    /**
     * Declared width of {@code WS-US-PHONE-AREA-CODE-TO-EDIT}, from its {@code PIC XXX} clause at
     * {@code app/cpy/CSLKPCDY.cpy} line 24. All three area-code conditions are declared on this one item,
     * so every area-code key is normalised to this width before it is tested.
     */
    public static final int AREA_CODE_WIDTH = 3;

    /**
     * Declared width of {@code US-STATE-CODE-TO-EDIT}, from its {@code PIC X(2)} clause at
     * {@code app/cpy/CSLKPCDY.cpy} line 1012.
     */
    public static final int US_STATE_CODE_WIDTH = 2;

    /**
     * Declared width of {@code US-STATE-AND-FIRST-ZIP2}, from its {@code PIC X(4)} clause at
     * {@code app/cpy/CSLKPCDY.cpy} line 1072. Two characters of state code followed by the first two
     * characters of the ZIP code.
     */
    public static final int US_STATE_AND_FIRST_ZIP2_WIDTH = 4;

    /**
     * Classpath location of the externalised North American area-code table. The resource is a JSON object
     * carrying the two subset arrays under the keys named by this class, and deliberately carries no third
     * array for the derived union.
     */
    public static final String NANPA_AREA_CODES_LOCATION = "classpath:lookup/nanpa-area-codes.json";

    /**
     * Classpath location of the externalised United States state-code table, a JSON array of strings.
     */
    public static final String US_STATE_CODES_LOCATION = "classpath:lookup/us-state-codes.json";

    /**
     * Classpath location of the externalised state-plus-ZIP-prefix table, a JSON array of strings.
     */
    public static final String STATE_ZIP_PREFIXES_LOCATION = "classpath:lookup/state-zip-prefixes.json";

    /**
     * Name of the array in the area-code resource holding the general-purpose subset.
     */
    public static final String JSON_KEY_GENERAL_PURPOSE = "generalPurpose";

    /**
     * Name of the array in the area-code resource holding the easily-recognisable subset.
     */
    public static final String JSON_KEY_EASY_RECOGNITION = "easyRecognition";

    /** Legacy condition name reported in a verification failure for the general-purpose subset. */
    private static final String CONDITION_GENERAL_PURPOSE = "VALID-GENERAL-PURP-CODE";

    /** Legacy condition name reported in a verification failure for the easily-recognisable subset. */
    private static final String CONDITION_EASY_RECOGNITION = "VALID-EASY-RECOG-AREA-CODE";

    /** Legacy condition name reported in a verification failure for the derived area-code union. */
    private static final String CONDITION_PHONE_AREA_CODE = "VALID-PHONE-AREA-CODE";

    /** Legacy condition name reported in a verification failure for the state-code table. */
    private static final String CONDITION_US_STATE_CODE = "VALID-US-STATE-CODE";

    /** Legacy condition name reported in a verification failure for the state-plus-ZIP table. */
    private static final String CONDITION_US_STATE_ZIP_COMBO = "VALID-US-STATE-ZIP-CD2-COMBO";

    /**
     * Fully parameterised binding target for the area-code resource: a mapping of array name to the list of
     * alphanumeric tokens it holds. The type argument is spelled out so that deserialisation involves no
     * unchecked operation, which under this module's compiler settings would fail the build rather than
     * merely warn.
     */
    private static final TypeReference<Map<String, List<String>>> NAMED_STRING_ARRAYS =
            new TypeReference<Map<String, List<String>>>() { };

    /**
     * Fully parameterised binding target for the two resources that are a bare JSON array of alphanumeric
     * tokens, spelled out for the same reason as the mapping above.
     */
    private static final TypeReference<List<String>> STRING_ARRAY =
            new TypeReference<List<String>>() { };

    /** The 410 general-purpose area codes, unmodifiable. */
    private final Set<String> generalPurposeAreaCodes;

    /** The 80 easily-recognisable area codes, unmodifiable. */
    private final Set<String> easilyRecognisableAreaCodes;

    /** The derived union of the two area-code subsets, unmodifiable. */
    private final Set<String> phoneAreaCodes;

    /** The 56 state, district and territory codes, unmodifiable. */
    private final Set<String> usStateCodes;

    /** The 240 state-plus-first-two-of-ZIP combinations, unmodifiable. */
    private final Set<String> usStateZipCodeCombinations;

    /**
     * Loads the three externalised lookup tables, verifies every cardinality and structural property the
     * copybook guarantees, and logs the confirmed counts once.
     *
     * <p>Loading happens here rather than in a lifecycle callback because the five sets are {@code final}:
     * assigning them in the constructor is what makes them genuinely immutable, and it also means a
     * resource that has drifted prevents the bean from being created at all rather than leaving a
     * half-verified singleton in the context. Verification uses explicit checks that throw, never the
     * {@code assert} statement, because assertions are disabled unless the JVM is started with them enabled
     * and a verification that can be switched off is not a verification.</p>
     *
     * @param objectMapper   the JSON mapper Spring Boot auto-configures for the application; used as
     *                       supplied, with no additional module, feature change or custom deserialiser
     * @param resourceLoader Spring's resource abstraction, used to resolve the three classpath locations
     * @throws IllegalStateException if any resource is absent, unreadable, malformed, structurally
     *                               unexpected, contains a duplicate or a wrongly sized value, fails a
     *                               cardinality check, or if the two area-code subsets are not disjoint
     * @throws NullPointerException  if either collaborator is {@code null}
     */
    public ValidationLookupService(final ObjectMapper objectMapper, final ResourceLoader resourceLoader) {
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        Objects.requireNonNull(resourceLoader, "resourceLoader must not be null");

        // ------------------------------------------------------------------
        // Area codes. One resource, two named arrays, three exposed sets: the
        // two subsets as loaded and their union derived on the spot.
        // ------------------------------------------------------------------
        final Map<String, List<String>> areaCodeArrays =
                readJson(objectMapper, resourceLoader, NANPA_AREA_CODES_LOCATION, NAMED_STRING_ARRAYS);
        requireExactlyTheTwoSubsetArrays(areaCodeArrays);

        final List<String> generalPurposeValues =
                requireNamedArray(areaCodeArrays, JSON_KEY_GENERAL_PURPOSE);
        final List<String> easyRecognitionValues =
                requireNamedArray(areaCodeArrays, JSON_KEY_EASY_RECOGNITION);

        this.generalPurposeAreaCodes = verifiedSet(generalPurposeValues, NANPA_AREA_CODES_LOCATION,
                CONDITION_GENERAL_PURPOSE, GENERAL_PURPOSE_AREA_CODE_COUNT, AREA_CODE_WIDTH);
        this.easilyRecognisableAreaCodes = verifiedSet(easyRecognitionValues, NANPA_AREA_CODES_LOCATION,
                CONDITION_EASY_RECOGNITION, EASY_RECOGNITION_AREA_CODE_COUNT, AREA_CODE_WIDTH);
        requireSubsetsDisjoint(this.generalPurposeAreaCodes, this.easilyRecognisableAreaCodes);
        this.phoneAreaCodes = deriveAreaCodeUnion(generalPurposeValues, easyRecognitionValues);

        // ------------------------------------------------------------------
        // State codes and state-plus-ZIP combinations. Two independent
        // resources that are never related to one another; see the class
        // documentation for why intersecting them would be a defect.
        // ------------------------------------------------------------------
        this.usStateCodes = verifiedSet(
                readJson(objectMapper, resourceLoader, US_STATE_CODES_LOCATION, STRING_ARRAY),
                US_STATE_CODES_LOCATION, CONDITION_US_STATE_CODE, US_STATE_CODE_COUNT, US_STATE_CODE_WIDTH);
        this.usStateZipCodeCombinations = verifiedSet(
                readJson(objectMapper, resourceLoader, STATE_ZIP_PREFIXES_LOCATION, STRING_ARRAY),
                STATE_ZIP_PREFIXES_LOCATION, CONDITION_US_STATE_ZIP_COMBO, US_STATE_ZIP_COMBINATION_COUNT,
                US_STATE_AND_FIRST_ZIP2_WIDTH);

        LOG.info("Validation lookup tables loaded and verified from {}, {} and {}: {}={}, {}={}, {}={} "
                        + "(derived union of the two subsets), {}={}, {}={}",
                NANPA_AREA_CODES_LOCATION, US_STATE_CODES_LOCATION, STATE_ZIP_PREFIXES_LOCATION,
                CONDITION_GENERAL_PURPOSE, this.generalPurposeAreaCodes.size(),
                CONDITION_EASY_RECOGNITION, this.easilyRecognisableAreaCodes.size(),
                CONDITION_PHONE_AREA_CODE, this.phoneAreaCodes.size(),
                CONDITION_US_STATE_CODE, this.usStateCodes.size(),
                CONDITION_US_STATE_ZIP_COMBO, this.usStateZipCodeCombinations.size());
    }

    /**
     * Tests an area code against the 410-member <em>general-purpose</em> table, which is the membership
     * stage of the legacy area-code edit.
     *
     * <p>Translated from the fourth and final stage of {@code EDIT-AREA-CODE} at
     * {@code app/cbl/COACTUPC.cbl} line 2246, where lines 2296 to 2298 move the trimmed field into the
     * three-byte work item and the following statement tests {@code VALID-GENERAL-PURP-CODE}. On failure the
     * legacy program raises the general input-error flag and the area-code not-OK flag and composes the
     * message {@code ": Not valid North America general purpose area code"} &mdash; with no trailing full
     * stop &mdash; at line 2306. Composing that message and setting those flags belong to the caller that
     * owns the phone cascade; this method returns only the outcome of the membership test.</p>
     *
     * <p><strong>This predicate deliberately excludes the 80 easily-recognisable codes.</strong> The legacy
     * program tests the general-purpose subset and not the 490-member union, so accepting an
     * easily-recognisable code here would accept 80 area codes that the mainframe rejects. Callers
     * validating a customer or account telephone number must use this method and not
     * {@code isValidPhoneAreaCode}.</p>
     *
     * <p>The three stages that precede this one in the legacy cascade &mdash; the blank check whose message
     * is {@code ": Area code must be supplied."}, the numeric check whose message is
     * {@code ": Area code must be A 3 digit number."} and the zero check whose message is
     * {@code ": Area code cannot be zero"} &mdash; are the caller's responsibility and are deliberately not
     * reproduced here. A key that would have failed one of them simply fails this membership test as well,
     * but with no message and no distinction between the four failure reasons.</p>
     *
     * @param areaCode the candidate area code; normalised as described by {@code areaCodeKey} before the
     *                 test, so a value carrying the surrounding spaces of a fixed-width field is accepted
     * @return {@code true} if the normalised key is one of the 410 general-purpose codes, {@code false}
     *         otherwise, including when {@code areaCode} is {@code null}
     */
    public boolean isValidGeneralPurposeAreaCode(final String areaCode) {
        if (areaCode == null) {
            return false;
        }
        return this.generalPurposeAreaCodes.contains(areaCodeKey(areaCode));
    }

    /**
     * Tests an area code against the full 490-member table, the derived union of the general-purpose and
     * easily-recognisable subsets.
     *
     * <p>Translated from {@code VALID-PHONE-AREA-CODE} at {@code app/cpy/CSLKPCDY.cpy} line 30. The
     * condition is exposed because the copybook declares it, but it is recorded here that <strong>no caller
     * anywhere in the estate uses it for phone validation</strong>: the only program that includes the
     * copybook tests the general-purpose subset instead. Use {@code isValidGeneralPurposeAreaCode} to
     * reproduce legacy telephone validation.</p>
     *
     * @param areaCode the candidate area code, normalised as described by {@code areaCodeKey}
     * @return {@code true} if the normalised key is one of the 490 codes, {@code false} otherwise,
     *         including when {@code areaCode} is {@code null}
     */
    public boolean isValidPhoneAreaCode(final String areaCode) {
        if (areaCode == null) {
            return false;
        }
        return this.phoneAreaCodes.contains(areaCodeKey(areaCode));
    }

    /**
     * Tests an area code against the 80-member easily-recognisable table.
     *
     * <p>Translated from {@code VALID-EASY-RECOG-AREA-CODE} at {@code app/cpy/CSLKPCDY.cpy} line 931. As
     * with the 490-member union, the condition is exposed because the copybook declares it, and
     * <strong>no caller anywhere in the estate uses it for phone validation</strong>. A code that satisfies
     * this predicate necessarily fails {@code isValidGeneralPurposeAreaCode}, because the two subsets are
     * disjoint and that disjointness is verified when this bean is constructed.</p>
     *
     * @param areaCode the candidate area code, normalised as described by {@code areaCodeKey}
     * @return {@code true} if the normalised key is one of the 80 easily-recognisable codes, {@code false}
     *         otherwise, including when {@code areaCode} is {@code null}
     */
    public boolean isValidEasilyRecognisableAreaCode(final String areaCode) {
        if (areaCode == null) {
            return false;
        }
        return this.easilyRecognisableAreaCodes.contains(areaCodeKey(areaCode));
    }

    /**
     * Tests a two-character state code against the 56-member table.
     *
     * <p>Translated from {@code 1270-EDIT-US-STATE-CD} at {@code app/cbl/COACTUPC.cbl} line 2493, whose exit
     * paragraph is at line 2511. The legacy paragraph is a single flat membership test and nothing more: it
     * moves the address state-code field, itself declared {@code PIC X(02)} at line 807, straight into the
     * {@code PIC X(2)} work item and tests {@code VALID-US-STATE-CODE}. <strong>There is no trim, no numeric
     * check and no blank pre-check</strong>, and because the sending and receiving items have the same
     * declared width the move neither truncates nor pads. This method reproduces exactly that, applying no
     * normalisation of any kind to the supplied value.</p>
     *
     * <p>On failure the legacy program raises the general input-error flag and the state not-OK flag &mdash;
     * that flag only &mdash; and composes the message {@code ": is not a valid state code"} at line 2503,
     * with no trailing full stop. Flag state and message composition belong to the caller.</p>
     *
     * @param stateCode the candidate state, district or territory code, tested exactly as supplied
     * @return {@code true} if the value is one of the 56 codes, {@code false} otherwise, including when
     *         {@code stateCode} is {@code null}
     */
    public boolean isValidUsStateCode(final String stateCode) {
        if (stateCode == null) {
            return false;
        }
        return this.usStateCodes.contains(stateCode);
    }

    /**
     * Tests a four-character state-plus-ZIP key against the 240-member table.
     *
     * <p>Translated from the membership test in {@code 1280-EDIT-US-STATE-ZIP-CD} at
     * {@code app/cbl/COACTUPC.cbl} line 2536, whose exit paragraph is at line 2558. Lines 2537 to 2540 build
     * the key by concatenating, delimited by size and therefore purely positionally, the two-character
     * address state code with the first two characters of the ten-character address ZIP code, giving exactly
     * four characters that fill the {@code PIC X(4)} sub-field. <strong>There is no trim.</strong> The
     * remaining three ZIP digits live in a separate sub-field at copybook line 1314 and take no part in the
     * test.</p>
     *
     * <p>The caller assembles the key and passes it here already concatenated: this method owns the
     * membership test alone. It performs no trim, does not validate the state code independently as part of
     * this call, and above all does <strong>not</strong> cross-check the leading two characters against the
     * 56-code state table. Six of the 62 prefixes that appear in this table &mdash; {@code AA}, {@code AE},
     * {@code AP}, {@code FM}, {@code MH} and {@code PW} &mdash; are absent from that table, so any such
     * cross-check would reject addresses the legacy system accepts.</p>
     *
     * <p>On failure the legacy program raises the general input-error flag and <em>both</em> the state
     * not-OK flag at line 2546 and the ZIP-code not-OK flag at line 2547, then composes the bare literal
     * {@code "Invalid zip code for state"} at line 2550. That message is unique in the program for carrying
     * no field-name prefix: every other message there is composed as the trimmed field label followed by a
     * suffix. Raising both flags and preserving the absent prefix belong to the caller.</p>
     *
     * @param stateAndFirstZip2 the four-character positional key, being the two-character state code
     *                          followed by the first two characters of the ZIP code, tested exactly as
     *                          supplied
     * @return {@code true} if the key is one of the 240 combinations, {@code false} otherwise, including
     *         when {@code stateAndFirstZip2} is {@code null}
     */
    public boolean isValidUsStateZipCodeCombination(final String stateAndFirstZip2) {
        if (stateAndFirstZip2 == null) {
            return false;
        }
        return this.usStateZipCodeCombinations.contains(stateAndFirstZip2);
    }

    /**
     * Returns the 410 general-purpose area codes as an unmodifiable set, in no defined iteration order.
     *
     * @return the general-purpose area-code table; never {@code null} and never modifiable
     */
    public Set<String> generalPurposeAreaCodes() {
        return this.generalPurposeAreaCodes;
    }

    /**
     * Returns the 80 easily-recognisable area codes as an unmodifiable set, in no defined iteration order.
     *
     * @return the easily-recognisable area-code table; never {@code null} and never modifiable
     */
    public Set<String> easilyRecognisableAreaCodes() {
        return this.easilyRecognisableAreaCodes;
    }

    /**
     * Returns the 490 area codes as an unmodifiable set, in no defined iteration order. This set is the
     * union derived at construction from the two subsets and is not loaded from a resource of its own.
     *
     * @return the full area-code table; never {@code null} and never modifiable
     */
    public Set<String> phoneAreaCodes() {
        return this.phoneAreaCodes;
    }

    /**
     * Returns the 56 state, district and territory codes as an unmodifiable set, in no defined iteration
     * order.
     *
     * @return the state-code table; never {@code null} and never modifiable
     */
    public Set<String> usStateCodes() {
        return this.usStateCodes;
    }

    /**
     * Returns the 240 state-plus-first-two-of-ZIP combinations as an unmodifiable set, in no defined
     * iteration order.
     *
     * @return the state-plus-ZIP table; never {@code null} and never modifiable
     */
    public Set<String> usStateZipCodeCombinations() {
        return this.usStateZipCodeCombinations;
    }

    /**
     * Normalises a candidate area code into the key the legacy condition is actually tested against,
     * reproducing both halves of the legacy statement pair at {@code app/cbl/COACTUPC.cbl} lines 2296 to
     * 2298.
     *
     * <p>The legacy statement trims the field and moves the result into
     * {@code WS-US-PHONE-AREA-CODE-TO-EDIT}, which copybook line 24 declares {@code PIC XXX}. Two distinct
     * COBOL semantics are therefore in play and both are reproduced:</p>
     * <ul>
     *   <li><strong>The trim removes spaces only.</strong> The COBOL trimming intrinsic strips leading and
     *       trailing space characters. It is not the same as the Java methods that strip every character at
     *       or below the space code point, nor the same as the one that strips every Unicode whitespace
     *       character, so this method strips the space character and nothing else.</li>
     *   <li><strong>The move left-justifies into three bytes.</strong> A shorter value is space-filled to
     *       the declared width and a longer value is truncated on the right. Space-filling has no
     *       observable effect because no value in any of the three tables contains a space, but truncation
     *       does: a longer key is tested on its first three characters, exactly as the mainframe tests it.
     *       From the estate's only call site the field being trimmed is itself declared {@code PIC X(3)} at
     *       line 87, so a trimmed value can never exceed three characters there and truncation is
     *       unreachable; the semantics are preserved so that the behaviour is correct for any caller rather
     *       than only for the one the legacy code happened to have.</li>
     * </ul>
     *
     * <p>This is work-item normalisation on a three-byte COBOL field, not offset slicing of a fixed-width
     * record image; record images are the concern of the mapper layer and are never touched here.</p>
     *
     * @param rawAreaCode the candidate area code, never {@code null} at any call site
     * @return a key of exactly {@code AREA_CODE_WIDTH} characters
     */
    private static String areaCodeKey(final String rawAreaCode) {
        int begin = 0;
        int end = rawAreaCode.length();
        while (begin < end && rawAreaCode.charAt(begin) == ' ') {
            begin++;
        }
        while (end > begin && rawAreaCode.charAt(end - 1) == ' ') {
            end--;
        }

        final StringBuilder key = new StringBuilder(AREA_CODE_WIDTH);
        for (int index = begin; index < end && key.length() < AREA_CODE_WIDTH; index++) {
            key.append(rawAreaCode.charAt(index));
        }
        while (key.length() < AREA_CODE_WIDTH) {
            key.append(' ');
        }
        return key.toString();
    }

    /**
     * Reads and binds one classpath resource with the supplied fully parameterised binding target.
     *
     * @param <T>            the bound shape, fixed by the caller's binding target so that no unchecked
     *                       operation is involved
     * @param objectMapper   the mapper to bind with, used exactly as configured
     * @param resourceLoader the loader that resolves the location
     * @param location       the classpath location, reported verbatim in any failure
     * @param binding        the fully parameterised binding target
     * @return the bound value, never {@code null}
     * @throws IllegalStateException if the resource is absent, cannot be read, is not valid JSON, does not
     *                               match the expected shape, or binds to {@code null}
     */
    private static <T> T readJson(final ObjectMapper objectMapper, final ResourceLoader resourceLoader,
            final String location, final TypeReference<T> binding) {
        final Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("Validation lookup resource " + location
                    + " is not present on the classpath. The externalised lookup tables are required at "
                    + "startup because they are the single source of the values the legacy copybook "
                    + "app/cpy/CSLKPCDY.cpy declared.");
        }

        final T bound;
        try (InputStream json = resource.getInputStream()) {
            bound = objectMapper.readValue(json, binding);
        } catch (final IOException readFailure) {
            throw new IllegalStateException("Validation lookup resource " + location
                    + " could not be read or did not match its expected JSON shape.", readFailure);
        }

        if (bound == null) {
            throw new IllegalStateException("Validation lookup resource " + location
                    + " bound to null, which means it holds a JSON null rather than the expected "
                    + "structure.");
        }
        return bound;
    }

    /**
     * Verifies that the area-code resource declares exactly the two subset arrays and nothing else.
     *
     * <p>An extra array is rejected as firmly as a missing one. The 490-member table is derived from these
     * two subsets precisely so that no third declaration of the same values can exist, and a resource that
     * grew a third array would silently reintroduce the three-way consistency hazard that deriving the union
     * exists to remove.</p>
     *
     * @param areaCodeArrays the bound area-code resource
     * @throws IllegalStateException if the set of array names is anything other than the two expected names
     */
    private static void requireExactlyTheTwoSubsetArrays(final Map<String, List<String>> areaCodeArrays) {
        final Set<String> expected = Set.of(JSON_KEY_GENERAL_PURPOSE, JSON_KEY_EASY_RECOGNITION);
        if (!expected.equals(areaCodeArrays.keySet())) {
            throw new IllegalStateException("Validation lookup resource " + NANPA_AREA_CODES_LOCATION
                    + " must declare exactly the two arrays " + JSON_KEY_GENERAL_PURPOSE + " and "
                    + JSON_KEY_EASY_RECOGNITION + ", expected " + expected.size() + " arrays but found "
                    + areaCodeArrays.size() + " " + areaCodeArrays.keySet() + ". The "
                    + CONDITION_PHONE_AREA_CODE + " table is derived by unioning the two subsets and must "
                    + "never be stored as a third array.");
        }
    }

    /**
     * Extracts one named array from the bound area-code resource.
     *
     * @param areaCodeArrays the bound area-code resource
     * @param arrayName      the name of the array to extract
     * @return the array's values in the order the resource declares them
     * @throws IllegalStateException if the named array is absent or holds a JSON null
     */
    private static List<String> requireNamedArray(final Map<String, List<String>> areaCodeArrays,
            final String arrayName) {
        final List<String> values = areaCodeArrays.get(arrayName);
        if (values == null) {
            throw new IllegalStateException("Validation lookup resource " + NANPA_AREA_CODES_LOCATION
                    + " does not declare the array " + arrayName + ".");
        }
        return values;
    }

    /**
     * Turns a loaded value list into a verified, unmodifiable set.
     *
     * <p>Four independent properties are checked, in an order chosen so that each check can assume the
     * previous one held: no element is {@code null}; every element has exactly the width the COBOL
     * {@code PIC} clause declares, which is what makes the level-88 condition satisfiable at all; no element
     * is duplicated, detected by comparing the list length with the resulting set size; and the set size
     * equals the cardinality measured from the copybook.</p>
     *
     * <p>No value is trimmed, case-folded, normalised, re-sorted or re-ordered. Every value stays the
     * {@code String} the resource declared, so a leading zero could never be coerced away.</p>
     *
     * @param values        the loaded values, in resource order
     * @param location      the classpath location, named in any failure
     * @param conditionName the legacy level-88 condition name, named in any failure
     * @param expectedCount the cardinality measured from the copybook
     * @param expectedWidth the width the copybook's {@code PIC} clause declares
     * @return an unmodifiable set of the values
     * @throws IllegalStateException if any of the four properties does not hold
     */
    private static Set<String> verifiedSet(final List<String> values, final String location,
            final String conditionName, final int expectedCount, final int expectedWidth) {
        for (int index = 0; index < values.size(); index++) {
            final String value = values.get(index);
            if (value == null) {
                throw new IllegalStateException("Validation lookup resource " + location + " table "
                        + conditionName + " holds a null at position " + index
                        + "; every value is an alphanumeric token and none may be absent.");
            }
            if (value.length() != expectedWidth) {
                throw new IllegalStateException("Validation lookup resource " + location + " table "
                        + conditionName + " holds the value '" + value + "' at position " + index
                        + " with width " + value.length() + ", but the copybook declares width "
                        + expectedWidth + "; a value of any other width could never satisfy the level-88 "
                        + "condition.");
            }
        }

        final Set<String> verified = Set.copyOf(values);
        if (verified.size() != values.size()) {
            throw new IllegalStateException("Validation lookup resource " + location + " table "
                    + conditionName + " holds " + values.size() + " values of which only "
                    + verified.size() + " are distinct; the copybook list contains no duplicate.");
        }
        if (verified.size() != expectedCount) {
            throw new IllegalStateException("Validation lookup resource " + location + " table "
                    + conditionName + " must hold exactly " + expectedCount + " values as measured from "
                    + "app/cpy/CSLKPCDY.cpy, but holds " + verified.size() + ".");
        }
        return verified;
    }

    /**
     * Verifies that the two area-code subsets share no element.
     *
     * <p>Disjointness is not an incidental property: it is what makes the 490-member table derivable from
     * the two subsets, and it is what guarantees that a code accepted by the easily-recognisable predicate
     * is rejected by the general-purpose predicate that legacy telephone validation actually uses.</p>
     *
     * @param generalPurpose   the verified general-purpose subset
     * @param easyRecognition  the verified easily-recognisable subset
     * @throws IllegalStateException if the two subsets overlap, naming the overlapping values
     */
    private static void requireSubsetsDisjoint(final Set<String> generalPurpose,
            final Set<String> easyRecognition) {
        final Set<String> overlap = new LinkedHashSet<>(generalPurpose);
        overlap.retainAll(easyRecognition);
        if (!overlap.isEmpty()) {
            throw new IllegalStateException("Validation lookup resource " + NANPA_AREA_CODES_LOCATION
                    + " tables " + CONDITION_GENERAL_PURPOSE + " and " + CONDITION_EASY_RECOGNITION
                    + " must be disjoint but share " + overlap.size() + " value(s) " + overlap
                    + "; disjointness is what makes " + CONDITION_PHONE_AREA_CODE + " derivable.");
        }
    }

    /**
     * Derives the 490-member area-code table by unioning the two subsets, and verifies the derived
     * cardinality.
     *
     * <p>Because both subsets have already been verified free of duplicates and verified disjoint, a union
     * whose size is anything other than the sum of the two subset counts is impossible; the check is kept
     * anyway so that the derived table carries the same explicit guarantee as the two loaded tables rather
     * than resting on an inference.</p>
     *
     * @param generalPurposeValues  the general-purpose values, in resource order
     * @param easyRecognitionValues the easily-recognisable values, in resource order
     * @return an unmodifiable set holding the derived union
     * @throws IllegalStateException if the derived cardinality is not the sum of the two subset counts
     */
    private static Set<String> deriveAreaCodeUnion(final List<String> generalPurposeValues,
            final List<String> easyRecognitionValues) {
        final List<String> combined =
                new ArrayList<>(generalPurposeValues.size() + easyRecognitionValues.size());
        combined.addAll(generalPurposeValues);
        combined.addAll(easyRecognitionValues);

        final Set<String> union = Set.copyOf(combined);
        if (union.size() != PHONE_AREA_CODE_COUNT) {
            throw new IllegalStateException("The derived " + CONDITION_PHONE_AREA_CODE + " table must hold "
                    + PHONE_AREA_CODE_COUNT + " values, being " + GENERAL_PURPOSE_AREA_CODE_COUNT + " plus "
                    + EASY_RECOGNITION_AREA_CODE_COUNT + " as measured from app/cpy/CSLKPCDY.cpy, but the "
                    + "union of the two subsets in " + NANPA_AREA_CODES_LOCATION + " holds "
                    + union.size() + ".");
        }
        return union;
    }
}
