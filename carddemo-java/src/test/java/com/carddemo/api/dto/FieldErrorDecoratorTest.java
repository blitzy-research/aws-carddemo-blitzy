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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Unit tests for {@link FieldErrorDecorator}, the migrated form of the parameterised procedural
 * macro {@code app/cpy/CSSETATY.cpy}.
 *
 * <h2>What is under test and why it matters</h2>
 *
 * <p>The macro is the only field-level error mechanism the legacy presentation layer had, and it
 * is expanded 39 times inside a single program, {@code app/cbl/COACTUPC.cbl}. Collapsing those 39
 * textual expansions into one method call is the largest single de-duplication in the migration,
 * which makes the method's behaviour load-bearing: every account-update rejection a client ever
 * sees is assembled through it. Three properties of the macro body were translated and each is
 * asserted here against the production type rather than described in prose:
 *
 * <ul>
 *   <li>The blank flag produced <em>two</em> screen effects and the not-OK flag produced one, so
 *       the two states are not interchangeable and must stay separately reportable. They arrive
 *       as {@link FieldErrorDecorator.FlagState} and leave as
 *       {@link ErrorResponse.FieldState}.</li>
 *   <li>The expansions fired in source sequence with no re-ordering and no suppression, so the
 *       accumulation appends and never sorts, replaces or de-duplicates.</li>
 *   <li>The macro evaluated no rule of its own - it recorded an outcome another paragraph had
 *       already determined - so this type performs no validation and can be handed any field
 *       name at all, including the two the source decorates but never validates.</li>
 * </ul>
 *
 * <h2>Where the expectations come from</h2>
 *
 * <p>The 39 expansion sites are not asserted from a constant this file invents. They are read
 * from a golden fixture extracted mechanically from the legacy program - one screen field
 * identifier per line, in expansion order - and that fixture is pinned by a SHA-256 literal
 * declared below, so an edit to either the fixture or the extraction is a failure rather than a
 * silent change of expectation. A second, independently extracted fixture lists the 43
 * unprotected input fields of the corresponding map, {@code app/bms/COACTUP.bms}, which lets the
 * four decorated-nowhere fields be <em>derived</em> by difference instead of hard-coded.
 *
 * <p>That the two fixtures are genuinely independent is itself asserted: the map declares the
 * electronic-transfer account identifier before the primary-cardholder flag, while the program
 * expands the macro over them in the opposite sequence. One extraction cannot therefore have
 * been produced from the other, and the expansion order this test pins is demonstrably the
 * program's own rather than the map's.
 *
 * <p>Every assertion in this file drives {@link FieldErrorDecorator#mark(String, String,
 * FieldErrorDecorator.FlagState)} and reads the value it returns. Nothing here asserts a
 * relationship between two test-owned constants, because such an assertion would pass whatever
 * the production type did.
 *
 * <h2>Source oddities that are contract rather than defect</h2>
 *
 * <p>Four irregularities in the legacy source bear on what this file may assert, and all four are
 * reproduced rather than tidied away, because tidying any of them would change behaviour a client
 * already depends on.
 *
 * <ul>
 *   <li>The macro's own descriptive line is corrupted - an unrelated screen field name runs on to
 *       the end of it. The substitution tokens, not the commentary, define what the macro does,
 *       and it is the tokens that were translated.</li>
 *   <li>Two of the descriptive lines near the end of the expansion range are transposed relative
 *       to the code they label, and one line repeats the state label immediately ahead of the
 *       postal-code expansion. The same rule applies: the tokens govern, so the expansion order
 *       this file pins is the order the tokens produce, which the boundary assertions state
 *       explicitly.</li>
 *   <li>Two of the 39 decorated fields - the middle name and the second address line - carry
 *       source comments saying no edits are coded for them. They are therefore decorated but never
 *       validated, so this type must be able to mark them although no caller ever will, and no
 *       constraint may be attached to them anywhere.</li>
 *   <li>The map declares 43 keyable fields but the macro decorates only 39. The four keyable but
 *       undecorated fields are real and are derived here by difference rather than asserted, so a
 *       reader who expects 43 finds the arithmetic rather than a bare claim.</li>
 * </ul>
 *
 * <h2>What this test deliberately does not do</h2>
 *
 * <p>It does not evaluate the re-entry gate. The legacy macro was gated on the program-context
 * re-enter condition, and that gate belongs to the calling service: this type is reached only
 * once a caller has decided to report. An accumulation on which {@code mark} was never called is
 * the first-submission shape, and that is the only aspect of the gate visible here.
 *
 * <p>It attaches no expectation to <em>which</em> fields a caller marks. The type holds no
 * registry of the 39, deliberately, and this test asserts that it holds none: marking one field
 * yields exactly one entry and never back-fills the other 38.
 *
 * <p>It reads no clock, opens no context, touches no file the fixtures aside, and asserts no
 * timing figure: the type carries no temporal component and no numeric component at all.
 *
 * <p>It starts no application context even where it asserts the wire shape. The mapper used for
 * that comes from {@link JsonContractSupport#declaredSettingsMapper()}, which carries the four
 * settings the module's configuration file declares and is the single place in the test tree where
 * they are written out by hand, so the shape assertions stay part of a unit suite; the separate
 * question of whether that shared mapper corresponds to the deployed bean belongs to the one suite
 * in this package that does start a context, and is asserted there against this very factory rather
 * than assumed here.
 *
 * <p>It reproduces none of the macro's 3270 mechanisms. Neither the attribute-level colour change
 * nor the marker character written over a blank field's displayed value has a REST counterpart, so
 * only the two states they signified are asserted, and their absence from the payload is asserted
 * as well.
 */
@DisplayName("FieldErrorDecorator — the parameterised CSSETATY macro as one accumulator")
class FieldErrorDecoratorTest {

    /**
     * Class-loader path of the golden list of decorated screen field identifiers, extracted from
     * the 39 macro expansion sites of {@code app/cbl/COACTUPC.cbl}, lines 3208 to 3432, in
     * expansion order. Only the substituted identifiers were extracted; no directive text,
     * declaration or statement of the legacy source is reproduced anywhere in this file.
     */
    private static final String GOLDEN_DECORATED_PATH =
            "/fixtures/expected/decoration/coactupc-decorated-screen-fields.txt";

    /**
     * Class-loader path of the golden list of unprotected input fields of
     * {@code app/bms/COACTUP.bms}, in map declaration order. This is the second, independent
     * extraction: it is what makes the four undecorated fields derivable rather than asserted.
     */
    private static final String GOLDEN_UNPROTECTED_PATH =
            "/fixtures/expected/decoration/coactup-unprotected-screen-fields.txt";

    /** Digest algorithm used to pin the golden fixtures. Mandatory in every conforming JVM. */
    private static final String GOLDEN_DIGEST_ALGORITHM = "SHA-256";

    /**
     * Pinned digest of the decorated-field fixture, recomputed from {@code app/cbl/COACTUPC.cbl}.
     */
    private static final String GOLDEN_DECORATED_DIGEST =
            "ea41070312572e2efc87aec7db0d017aa6e366a9ec5996afb90dcbeb22de8407";

    /**
     * Pinned digest of the unprotected-field fixture. Recomputed from
     * {@code app/bms/COACTUP.bms} at the same checkout.
     */
    private static final String GOLDEN_UNPROTECTED_DIGEST =
            "85c1b37e40612fdf4a332d8cbe66cd61a08794796474e8268e588afc05a159cd";

    /** The number of macro expansion sites in the legacy program. */
    private static final int EXPANSION_SITE_COUNT = 39;

    /** The number of unprotected input fields the corresponding map declares. */
    private static final int UNPROTECTED_FIELD_COUNT = 43;

    /** Unprotected fields the macro never decorates: 43 editable less 39 decorated. */
    private static final int UNDECORATED_FIELD_COUNT = 4;

    /**
     * Greatest width in characters a legacy screen field identifier may have.
     *
     * <p>Seven is a platform ceiling rather than a style choice: the generated symbolic map
     * appends a one-character suffix to every field name to form the input, output, flag and
     * attribute data names, and a COBOL data name in that generation is eight characters. Most
     * identifiers in this map use all seven; the eight month and day members of the four date
     * triples use six, which the fixtures record and this test pins rather than rounds off.
     */
    private static final int SCREEN_FIELD_ID_MAX_WIDTH = 7;

    /**
     * Number of six-character identifiers in either fixture: the month and day members of the
     * open, expiry, reissue and date-of-birth triples.
     */
    private static final int SIX_CHARACTER_IDENTIFIER_COUNT = 8;

    /**
     * Prefix used to derive a request-contract property name from a screen field identifier.
     *
     * <p>Prefixing rather than case folding is deliberate: no value in this file is ever case
     * folded, because the legacy conversions are table driven and a locale-aware fold is not
     * their equivalent. The type under test treats the property name as opaque, so any injective
     * derivation serves - what matters is that the name a caller supplies comes back unchanged.
     */
    private static final String PROPERTY_NAME_PREFIX = "propertyFor";

    /** Screen field identifier of the account status - the first of the 39 expansions. */
    private static final String SCREEN_ACCT_STATUS = "ACSTTUS";

    /** Screen field identifier of the credit limit - the map's monetary field. */
    private static final String SCREEN_CREDIT_LIMIT = "ACRDLIM";

    /** Screen field identifier of the first address line. */
    private static final String SCREEN_ADDRESS_LINE_1 = "ACSADL1";

    /** Screen field identifier of the state - expanded between the two address lines. */
    private static final String SCREEN_STATE = "ACSSTTE";

    /** Screen field identifier of the second address line - decorated, never validated. */
    private static final String SCREEN_ADDRESS_LINE_2 = "ACSADL2";

    /** Screen field identifier of the postal code - expanded ahead of city and country. */
    private static final String SCREEN_POSTAL_CODE = "ACSZIPC";

    /** Screen field identifier of the city. */
    private static final String SCREEN_CITY = "ACSCITY";

    /** Screen field identifier of the country. */
    private static final String SCREEN_COUNTRY = "ACSCTRY";

    /** Screen field identifier of the middle name - decorated, never validated. */
    private static final String SCREEN_MIDDLE_NAME = "ACSMNAM";

    /** Screen field identifier of the primary-cardholder flag - penultimate expansion. */
    private static final String SCREEN_PRIMARY_CARDHOLDER = "ACSPFLG";

    /** Screen field identifier of the electronic-transfer account identifier - final expansion. */
    private static final String SCREEN_EFT_ACCOUNT_ID = "ACSEFTC";

    /** Request-contract property name paired with the account status. */
    private static final String PROP_ACCT_STATUS = "acctStatus";

    /** Request-contract property name paired with the credit limit. */
    private static final String PROP_CREDIT_LIMIT = "creditLimit";

    /** Request-contract property name for the middle name. No constraint may be attached to it. */
    private static final String PROP_MIDDLE_NAME = "middleName";

    /** Request-contract property name for the second address line. Likewise unconstrained. */
    private static final String PROP_ADDRESS_LINE_2 = "addressLine2";

    /**
     * An identifier belonging to neither fixture, used to prove the type holds no table.
     *
     * <p>It is shaped like a screen field identifier but is not one: the assertions that use it
     * derive its absence from both fixtures rather than assuming it, so the proof survives any
     * future re-extraction.
     */
    private static final String SCREEN_NOT_IN_THE_MAP = "ZZNOSUC";

    /** A property name belonging to no request contract, paired with the identifier above. */
    private static final String PROP_NOT_IN_ANY_CONTRACT = "propertyNoContractDeclares";

    /**
     * A value whose leading zeros are significant.
     *
     * <p>Eleven digits with two leading zeros. The legacy account identifier is an eleven-digit
     * unsigned display field, so a value that begins with a zero is ordinary rather than
     * exceptional, and a numeric parameter type would silently discard the zeros. The assertions
     * that use this value demonstrate that loss independently rather than asserting it away.
     */
    private static final String LEADING_ZERO_VALUE = "00000000123";

    /** A digits-only screen identifier with a leading zero, for the same reason. */
    private static final String LEADING_ZERO_IDENTIFIER = "0123456";

    /**
     * A value long enough that any plausible length constraint would have rejected it.
     *
     * <p>Two hundred characters, well past the widest field the map declares. Carrying it
     * unchanged is the behavioural proof that this type enforces no length of its own.
     */
    private static final String OVERLONG_VALUE = "A".repeat(200);

    /**
     * A value combining digits, punctuation and embedded spaces.
     *
     * <p>A pattern or alphabetic constraint would reject it. The legacy alphabetic edit accepted
     * embedded spaces, and this type performs no character-class edit at all, so the value must
     * come back exactly as supplied.
     */
    private static final String MIXED_CONTENT_VALUE = "12 ab-CD_ef.  34";

    /**
     * The single decoration operation, bound as an unbound instance-method reference.
     *
     * <p>This declaration is itself an assertion, discharged by the compiler rather than at run
     * time, which is why no part of this file inspects a method table at run time. The reference
     * resolves only if the production type declares exactly one applicable {@code mark} taking a
     * property name, a screen field identifier and a flag state, in that order, and returning a
     * new accumulation. A second overload would make the reference ambiguous, a fourth parameter
     * - a re-entry flag, say, or the map name the macro's third token carried - would leave it
     * unresolvable, and a numeric parameter in place of either identifier would reject the
     * {@code String} arguments the interface supplies. Under warnings-as-errors compilation each
     * of those is a build failure.
     */
    @FunctionalInterface
    private interface MarkOperation {

        /**
         * Applies the production operation to a receiver.
         *
         * @param receiver   the accumulation to mark against
         * @param field      the request-contract property name
         * @param bmsFieldId the legacy screen field identifier
         * @param flagState  the legacy validation-flag state
         * @return the resulting accumulation
         */
        FieldErrorDecorator applyTo(FieldErrorDecorator receiver,
                                    String field,
                                    String bmsFieldId,
                                    FieldErrorDecorator.FlagState flagState);
    }

    /**
     * Supplies a mapper carrying the four wire settings declared in
     * {@code src/main/resources/application.yml}.
     *
     * <p>This suite starts no application context, so the mapper is not the deployed object. It is
     * obtained from {@link JsonContractSupport#declaredSettingsMapper()}, the single place in the
     * test tree where those settings are written out by hand - {@code null} values omitted, dates not
     * written as timestamps, unknown properties tolerated on read and plain rather than scientific
     * rendering of decimals. The last of those cannot affect this type, which has no numeric
     * component, and is in force anyway because the factory is shared rather than per-file, so this
     * mapper carries the full set and not a subset of it.
     *
     * <p>What it evidences is the shape this type takes under those settings, and nothing more. The
     * correspondence with the deployed object is established in {@link ApplicationJsonContractTest},
     * which compares a mapper taken from a real context against this very factory.
     *
     * @return a mapper carrying the module's four declared serialisation settings
     */
    private static ObjectMapper declaredSettingsMapper() {
        return JsonContractSupport.declaredSettingsMapper();
    }

    /**
     * Reads a golden fixture as raw bytes so its digest can be pinned before it is trusted.
     *
     * @param classpathPath absolute class-loader path of the fixture
     * @return the fixture's bytes
     * @throws IOException if the fixture cannot be read
     */
    private static byte[] goldenBytes(final String classpathPath) throws IOException {
        try (InputStream fixture = FieldErrorDecoratorTest.class.getResourceAsStream(classpathPath)) {
            assertThat(fixture)
                    .as("the golden fixture %s must be present on the test classpath", classpathPath)
                    .isNotNull();
            return fixture.readAllBytes();
        }
    }

    /**
     * Reads a golden fixture as its tokens, one per line, in the order the file declares them.
     *
     * <p>The digest is verified first, so a token list is never used as an expectation unless the
     * file it came from is byte for byte the file the legacy extraction produced.
     *
     * @param classpathPath   absolute class-loader path of the fixture
     * @param expectedDigest  the pinned lower-case hexadecimal SHA-256 digest of the fixture
     * @return the tokens the fixture declares, in file order
     * @throws IOException if the fixture cannot be read
     */
    private static List<String> goldenTokens(final String classpathPath, final String expectedDigest)
            throws IOException {
        final byte[] content = goldenBytes(classpathPath);
        assertThat(sha256Hex(content))
                .as("golden fixture %s must be byte identical to the pinned legacy extraction",
                        classpathPath)
                .isEqualTo(expectedDigest);

        final List<String> tokens = new ArrayList<>();
        for (final String line : new String(content, StandardCharsets.US_ASCII).split("\n", -1)) {
            if (!line.isEmpty()) {
                tokens.add(line);
            }
        }
        return tokens;
    }

    /**
     * Computes the lower-case hexadecimal SHA-256 digest of the supplied content.
     *
     * @param content the bytes to digest
     * @return the digest in lower-case hexadecimal
     */
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

    /** @return the 39 decorated screen field identifiers, in legacy expansion order */
    private static List<String> decoratedScreenFields() throws IOException {
        return goldenTokens(GOLDEN_DECORATED_PATH, GOLDEN_DECORATED_DIGEST);
    }

    /** @return the 43 unprotected screen field identifiers, in map declaration order */
    private static List<String> unprotectedScreenFields() throws IOException {
        return goldenTokens(GOLDEN_UNPROTECTED_PATH, GOLDEN_UNPROTECTED_DIGEST);
    }

    /**
     * Derives the unprotected fields the macro never decorates, by difference rather than by
     * assertion, so the count of four is a consequence of the two extractions and not a claim.
     *
     * @return the editable-but-undecorated identifiers, in map declaration order
     * @throws IOException if either fixture cannot be read
     */
    private static Set<String> undecoratedScreenFields() throws IOException {
        final Set<String> undecorated = new LinkedHashSet<>(unprotectedScreenFields());
        undecorated.removeAll(decoratedScreenFields());
        return undecorated;
    }

    /**
     * Derives the request-contract property name this test pairs with a screen field identifier.
     *
     * @param screenFieldId the legacy screen field identifier
     * @return the paired property name
     */
    private static String propertyNameFor(final String screenFieldId) {
        return PROPERTY_NAME_PREFIX + screenFieldId;
    }

    /**
     * Drives the production {@code mark} method once per supplied screen field, in the supplied
     * order, threading the returned value forward exactly as a caller must.
     *
     * @param screenFieldIds the identifiers to mark, in the order to mark them
     * @param flagState      the flag state to report for every one of them
     * @return the resulting accumulation
     */
    private static FieldErrorDecorator markAll(final List<String> screenFieldIds,
                                               final FieldErrorDecorator.FlagState flagState) {
        FieldErrorDecorator errors = FieldErrorDecorator.none();
        for (final String screenFieldId : screenFieldIds) {
            errors = errors.mark(propertyNameFor(screenFieldId), screenFieldId, flagState);
        }
        return errors;
    }

    /**
     * Extracts the screen field identifiers the accumulation actually holds, in its own order.
     *
     * @param errors the accumulation to read
     * @return the identifiers of its entries, in entry order
     */
    private static List<String> screenFieldsOf(final FieldErrorDecorator errors) {
        final List<String> identifiers = new ArrayList<>();
        for (final ErrorResponse.FieldError entry : errors.fieldErrors()) {
            identifiers.add(entry.screenFieldId());
        }
        return identifiers;
    }

    @Nested
    @DisplayName("The golden fixtures: two independent extractions from the legacy estate")
    class GoldenFixtureIntegrity {

        @Test
        @DisplayName("the decorated-field fixture is byte identical to its pinned digest and declares "
                + "exactly 39 distinct, well-formed identifiers")
        void decoratedFixtureIsPinnedAndWellFormed() throws IOException {
            assertThat(sha256Hex(goldenBytes(GOLDEN_DECORATED_PATH)))
                    .isEqualTo(GOLDEN_DECORATED_DIGEST);

            final List<String> decorated = decoratedScreenFields();

            assertThat(decorated).hasSize(EXPANSION_SITE_COUNT).doesNotHaveDuplicates();
            assertThat(decorated).allSatisfy(identifier -> {
                assertThat(identifier.length())
                        .isBetween(1, SCREEN_FIELD_ID_MAX_WIDTH);
                assertThat(identifier).isEqualTo(identifier.strip());
                assertThat(identifier).matches("[A-Z0-9]+");
            });
            assertThat(decorated.stream().filter(identifier -> identifier.length() == 6).toList())
                    .as("the month and day members of the four date triples are the six-character ones")
                    .hasSize(SIX_CHARACTER_IDENTIFIER_COUNT)
                    .containsExactly("OPNMON", "OPNDAY", "EXPMON", "EXPDAY",
                            "RISMON", "RISDAY", "DOBMON", "DOBDAY");
        }

        @Test
        @DisplayName("the unprotected-field fixture is byte identical to its pinned digest and declares "
                + "exactly 43 distinct identifiers")
        void unprotectedFixtureIsPinnedAndWellFormed() throws IOException {
            assertThat(sha256Hex(goldenBytes(GOLDEN_UNPROTECTED_PATH)))
                    .isEqualTo(GOLDEN_UNPROTECTED_DIGEST);

            final List<String> unprotected = unprotectedScreenFields();

            assertThat(unprotected).hasSize(UNPROTECTED_FIELD_COUNT).doesNotHaveDuplicates();
            assertThat(unprotected).allSatisfy(identifier -> {
                assertThat(identifier.length()).isBetween(1, SCREEN_FIELD_ID_MAX_WIDTH);
                assertThat(identifier).matches("[A-Z0-9]+");
            });
            assertThat(unprotected.stream().filter(identifier -> identifier.length() == 6).toList())
                    .as("the same eight date members are the only six-character keyable fields")
                    .hasSize(SIX_CHARACTER_IDENTIFIER_COUNT);
        }

        @Test
        @DisplayName("every decorated identifier is an unprotected field of the map, so the macro "
                + "decorates only fields an operator can key")
        void everyDecoratedFieldIsAnUnprotectedMapField() throws IOException {
            assertThat(unprotectedScreenFields()).containsAll(decoratedScreenFields());
        }

        @Test
        @DisplayName("the four editable-but-undecorated fields are derived by difference and are exactly "
                + "the account filter, the group, the customer number and the government identifier")
        void theFourUndecoratedFieldsAreDerivedNotAsserted() throws IOException {
            final Set<String> undecorated = undecoratedScreenFields();

            assertThat(undecorated)
                    .as("43 unprotected fields less 39 decorated ones leaves exactly four")
                    .hasSize(UNDECORATED_FIELD_COUNT)
                    .containsExactlyInAnyOrder("ACCTSID", "AADDGRP", "ACSTNUM", "ACSGOVT");
        }

        @Test
        @DisplayName("the map's declaration order is not the program's expansion order, which proves the "
                + "two fixtures are independent extractions rather than copies of one another")
        void theMapOrderDiffersFromTheExpansionOrder() throws IOException {
            final List<String> unprotected = unprotectedScreenFields();
            final List<String> decorated = decoratedScreenFields();

            assertThat(unprotected.indexOf(SCREEN_EFT_ACCOUNT_ID))
                    .as("the map declares the transfer account identifier before the cardholder flag")
                    .isLessThan(unprotected.indexOf(SCREEN_PRIMARY_CARDHOLDER));
            assertThat(decorated.indexOf(SCREEN_PRIMARY_CARDHOLDER))
                    .as("the program expands them the other way round")
                    .isLessThan(decorated.indexOf(SCREEN_EFT_ACCOUNT_ID));
        }
    }

    @Nested
    @DisplayName("Flag-state translation: two legacy conditions, two published states")
    class FlagStateTranslation {

        @Test
        @DisplayName("a blank flag becomes MISSING, because the macro's blank case additionally wrote a "
                + "marker over the field and the remedy is to supply a value")
        void blankFlagBecomesMissing() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);

            assertThat(errors.fieldErrors()).hasSize(1);
            assertThat(errors.fieldErrors().get(0).state())
                    .isEqualTo(ErrorResponse.FieldState.MISSING);
        }

        @Test
        @DisplayName("a not-OK flag becomes INVALID, because the macro left the operator's keystrokes in "
                + "place and the remedy is to correct the value")
        void notOkFlagBecomesInvalid() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                            FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(errors.fieldErrors()).hasSize(1);
            assertThat(errors.fieldErrors().get(0).state())
                    .isEqualTo(ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("the two flag states never collapse onto one published state, so a blank field and a "
                + "badly keyed field stay separately actionable")
        void theTwoFlagStatesStayDistinguishable() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK)
                    .mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                            FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(errors.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsExactly(ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("FlagState declares exactly two constants, BLANK then NOT_OK, because the macro "
                + "tested exactly two conditions")
        void flagStateDeclaresExactlyTwoConstants() {
            assertThat(FieldErrorDecorator.FlagState.values())
                    .containsExactly(FieldErrorDecorator.FlagState.BLANK,
                            FieldErrorDecorator.FlagState.NOT_OK);
            assertThat(FieldErrorDecorator.FlagState.BLANK.ordinal()).isZero();
            assertThat(FieldErrorDecorator.FlagState.NOT_OK.ordinal()).isEqualTo(1);
        }

        @Test
        @DisplayName("no third flag constant exists: a field that passed its edit is unrepresentable "
                + "here, because it produces no entry at all")
        void noThirdFlagConstantExists() {
            final List<String> names = Arrays.stream(FieldErrorDecorator.FlagState.values())
                    .map(Enum::name)
                    .toList();

            assertThat(names).containsExactly("BLANK", "NOT_OK");
            assertThat(names).doesNotContain("OK", "VALID", "NONE", "UNKNOWN", "WARNING", "PASSED");
        }

        @Test
        @DisplayName("a passing field contributes nothing: marking two of the 39 leaves the other 37 "
                + "entirely absent rather than present in a passing state")
        void aPassingFieldContributesNoEntry() throws IOException {
            final List<String> decorated = decoratedScreenFields();
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK)
                    .mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                            FieldErrorDecorator.FlagState.NOT_OK);

            final List<String> reported = screenFieldsOf(errors);
            final List<String> unreported = new ArrayList<>(decorated);
            unreported.removeAll(reported);

            assertThat(reported).containsExactly(SCREEN_ACCT_STATUS, SCREEN_CREDIT_LIMIT);
            assertThat(unreported).hasSize(EXPANSION_SITE_COUNT - 2);
            assertThat(reported).doesNotContainAnyElementsOf(unreported);
        }
    }

    @Nested
    @DisplayName("Accumulation: appending in call sequence, with nothing sorted or suppressed")
    class MarkAccumulation {

        @Test
        @DisplayName("a fresh accumulation holds nothing, which is the first-submission shape the "
                + "re-entry gate produced")
        void aFreshAccumulationHoldsNothing() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none();

            assertThat(errors.isEmpty()).isTrue();
            assertThat(errors.fieldErrors()).isEmpty();
        }

        @Test
        @DisplayName("one mark yields one entry carrying the supplied property name, the supplied screen "
                + "identifier and no per-field wording, because the macro emitted no text of its own")
        void oneMarkYieldsOneFullyFormedEntry() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);

            assertThat(errors.fieldErrors()).hasSize(1);
            final ErrorResponse.FieldError entry = errors.fieldErrors().get(0);
            assertThat(entry.fieldName()).isEqualTo(PROP_ACCT_STATUS);
            assertThat(entry.screenFieldId()).isEqualTo(SCREEN_ACCT_STATUS);
            assertThat(entry.state()).isEqualTo(ErrorResponse.FieldState.MISSING);
            assertThat(entry.message())
                    .as("the legacy explanatory text lived in the caller's single summary line")
                    .isNull();
            assertThat(errors.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("successive marks are appended in call sequence and never re-ordered, not even into "
                + "alphabetical or map order")
        void successiveMarksKeepCallSequence() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                            FieldErrorDecorator.FlagState.NOT_OK)
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK)
                    .mark(PROP_MIDDLE_NAME, SCREEN_MIDDLE_NAME,
                            FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(screenFieldsOf(errors))
                    .containsExactly(SCREEN_CREDIT_LIMIT, SCREEN_ACCT_STATUS, SCREEN_MIDDLE_NAME);
            assertThat(screenFieldsOf(errors))
                    .as("the marked sequence is not the sorted sequence, so no sort can be hiding")
                    .isNotEqualTo(screenFieldsOf(errors).stream().sorted().toList());
        }

        @Test
        @DisplayName("marking leaves the receiver untouched and returns a different value, so the "
                + "reassignment idiom is the only way to accumulate")
        void markingIsPureAndReturnsANewValue() {
            final FieldErrorDecorator empty = FieldErrorDecorator.none();
            final FieldErrorDecorator one = empty.mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                    FieldErrorDecorator.FlagState.BLANK);
            final FieldErrorDecorator two = one.mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                    FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(empty.fieldErrors()).isEmpty();
            assertThat(one.fieldErrors()).hasSize(1);
            assertThat(two.fieldErrors()).hasSize(2);
            assertThat(one).isNotSameAs(empty).isNotSameAs(two);
            assertThat(screenFieldsOf(one)).containsExactly(SCREEN_ACCT_STATUS);
        }

        @Test
        @DisplayName("the same field marked twice yields two entries, each keeping its own state, because "
                + "de-duplication is a decision this type has no standing to make")
        void theSameFieldMarkedTwiceYieldsTwoEntries() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK)
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                            FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(errors.fieldErrors()).hasSize(2);
            assertThat(errors.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsExactly(ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("even an identical repeat is carried twice, so no entry is ever silently swallowed")
        void anIdenticalRepeatIsCarriedTwice() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK)
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);

            assertThat(errors.fieldErrors()).hasSize(2);
            assertThat(errors.fieldErrors().get(0)).isEqualTo(errors.fieldErrors().get(1));
        }

        @Test
        @DisplayName("the presence test agrees with the collection at zero, one and thirty-nine entries")
        void thePresenceTestAgreesWithTheCollection() throws IOException {
            final FieldErrorDecorator empty = FieldErrorDecorator.none();
            final FieldErrorDecorator one = empty.mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                    FieldErrorDecorator.FlagState.BLANK);
            final FieldErrorDecorator all = markAll(decoratedScreenFields(),
                    FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(empty.isEmpty()).isEqualTo(empty.fieldErrors().isEmpty()).isTrue();
            assertThat(one.isEmpty()).isEqualTo(one.fieldErrors().isEmpty()).isFalse();
            assertThat(all.isEmpty()).isEqualTo(all.fieldErrors().isEmpty()).isFalse();
            assertThat(all.fieldErrors()).hasSize(EXPANSION_SITE_COUNT);
        }

        @Test
        @DisplayName("the empty starting value is a fresh instance every time yet equal, so the type "
                + "carries no shared static state a request could leak through")
        void theEmptyStartingValueIsFreshYetEqual() {
            final FieldErrorDecorator first = FieldErrorDecorator.none();
            final FieldErrorDecorator second = FieldErrorDecorator.none();

            assertThat(first).isNotSameAs(second).isEqualTo(second);
            assertThat(first.mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                    FieldErrorDecorator.FlagState.BLANK).fieldErrors()).hasSize(1);
            assertThat(second.fieldErrors())
                    .as("marking one empty value cannot populate another")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Mandatory arguments: all three components are load-bearing")
    class MandatoryArguments {

        @Test
        @DisplayName("a null property name is rejected, because a client cannot locate a field it is not "
                + "named")
        void aNullPropertyNameIsRejected() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> errors.mark(null, SCREEN_ACCT_STATUS,
                            FieldErrorDecorator.FlagState.BLANK))
                    .withMessage("field must not be null");
        }

        @Test
        @DisplayName("a null screen identifier is rejected, because the entry would lose its traceability "
                + "to the map it derives from")
        void aNullScreenIdentifierIsRejected() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> errors.mark(PROP_ACCT_STATUS, null,
                            FieldErrorDecorator.FlagState.BLANK))
                    .withMessage("bmsFieldId must not be null");
        }

        @Test
        @DisplayName("a null flag state is rejected, because a client could not tell the operator whether "
                + "to supply a value or to correct one")
        void aNullFlagStateIsRejected() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> errors.mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, null))
                    .withMessage("flagState must not be null");
        }

        @Test
        @DisplayName("a rejected mark adds nothing: the receiver is unchanged, so a caught rejection "
                + "cannot leave a half-formed entry behind")
        void aRejectedMarkAddsNothing() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> errors.mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT, null));

            assertThat(errors.fieldErrors()).hasSize(1);
            assertThat(screenFieldsOf(errors)).containsExactly(SCREEN_ACCT_STATUS);
        }

        @Test
        @DisplayName("an empty or space-padded name is carried verbatim rather than trimmed or rejected, "
                + "because the legacy fields these values derive from are space significant")
        void emptyAndPaddedNamesAreCarriedVerbatim() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark("", "", FieldErrorDecorator.FlagState.BLANK)
                    .mark("  padded  ", " ACSTTUS ", FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(errors.fieldErrors()).hasSize(2);
            assertThat(errors.fieldErrors().get(0).fieldName()).isEmpty();
            assertThat(errors.fieldErrors().get(0).screenFieldId()).isEmpty();
            assertThat(errors.fieldErrors().get(1).fieldName()).isEqualTo("  padded  ");
            assertThat(errors.fieldErrors().get(1).screenFieldId()).isEqualTo(" ACSTTUS ");
        }

        @Test
        @DisplayName("case is never folded in either direction on either component, so a screen "
                + "identifier and a property name both survive exactly as supplied")
        void caseIsNeverFolded() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);
            final ErrorResponse.FieldError entry = errors.fieldErrors().get(0);

            assertThat(entry.fieldName()).isEqualTo("acctStatus").isNotEqualTo("ACCTSTATUS");
            assertThat(entry.screenFieldId()).isEqualTo("ACSTTUS").isNotEqualTo("acsttus");
        }
    }

    @Nested
    @DisplayName("Defensive copying and immutability of the accumulated entries")
    class DefensiveCopyAndImmutability {

        @Test
        @DisplayName("a null collection becomes the empty collection rather than being stored, so no "
                + "accessor has to be null tested")
        void aNullCollectionBecomesEmpty() {
            final FieldErrorDecorator errors = new FieldErrorDecorator(null);

            assertThat(errors.fieldErrors()).isNotNull().isEmpty();
            assertThat(errors.isEmpty()).isTrue();
            assertThat(errors).isEqualTo(FieldErrorDecorator.none());
        }

        @Test
        @DisplayName("the supplied collection is copied at construction, so a caller mutating its own "
                + "list afterwards cannot alter the accumulation")
        void theSuppliedCollectionIsCopied() {
            final List<FieldErrorDecorator.MarkedField> callerOwned = new ArrayList<>();
            callerOwned.add(new FieldErrorDecorator.MarkedField(PROP_ACCT_STATUS,
                    SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK));

            final FieldErrorDecorator errors = new FieldErrorDecorator(callerOwned);
            callerOwned.add(new FieldErrorDecorator.MarkedField(PROP_CREDIT_LIMIT,
                    SCREEN_CREDIT_LIMIT, FieldErrorDecorator.FlagState.NOT_OK));
            callerOwned.clear();

            assertThat(errors.fieldErrors()).hasSize(1);
            assertThat(screenFieldsOf(errors)).containsExactly(SCREEN_ACCT_STATUS);
        }

        @Test
        @DisplayName("a null entry inside the collection is rejected rather than stored, because an entry "
                + "with no state is one a client cannot act on")
        void aNullEntryInsideTheCollectionIsRejected() {
            final List<FieldErrorDecorator.MarkedField> withNull = new ArrayList<>();
            withNull.add(new FieldErrorDecorator.MarkedField(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                    FieldErrorDecorator.FlagState.BLANK));
            withNull.add(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new FieldErrorDecorator(withNull));
        }

        @Test
        @DisplayName("the accessor hands back an unmodifiable collection, so nothing can be added, "
                + "removed, replaced or cleared through it")
        void theAccessorIsUnmodifiable() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);
            final List<ErrorResponse.FieldError> published = errors.fieldErrors();
            final ErrorResponse.FieldError intruder = new ErrorResponse.FieldError(
                    PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT, ErrorResponse.FieldState.INVALID);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> published.add(intruder));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> published.remove(0));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> published.set(0, intruder));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(published::clear);
            assertThat(errors.fieldErrors()).hasSize(1);
        }

        @Test
        @DisplayName("a value threaded through several marks cannot be altered through an earlier "
                + "reference, which is what makes the accumulation safe to pass around")
        void anEarlierReferenceCannotAlterALaterValue() {
            final FieldErrorDecorator first = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);
            final FieldErrorDecorator second = first.mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                    FieldErrorDecorator.FlagState.NOT_OK);
            final List<ErrorResponse.FieldError> firstView = first.fieldErrors();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(firstView::clear);
            assertThat(second.fieldErrors()).hasSize(2);
            assertThat(screenFieldsOf(second))
                    .containsExactly(SCREEN_ACCT_STATUS, SCREEN_CREDIT_LIMIT);
        }
    }

    @Nested
    @DisplayName("Value semantics: equality by content, and a text form that leaks nothing")
    class ValueSemantics {

        @Test
        @DisplayName("two accumulations marked identically are equal and hash alike, so an accumulation "
                + "can be compared rather than inspected")
        void identicalAccumulationsAreEqual() {
            final FieldErrorDecorator left = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK)
                    .mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                            FieldErrorDecorator.FlagState.NOT_OK);
            final FieldErrorDecorator right = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK)
                    .mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                            FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);
        }

        @Test
        @DisplayName("marking the same two fields in the opposite sequence is a different value, because "
                + "the sequence is part of what the accumulation reports")
        void sequenceIsPartOfTheValue() {
            final FieldErrorDecorator statusFirst = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK)
                    .mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                            FieldErrorDecorator.FlagState.NOT_OK);
            final FieldErrorDecorator limitFirst = FieldErrorDecorator.none()
                    .mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                            FieldErrorDecorator.FlagState.NOT_OK)
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);

            assertThat(statusFirst).isNotEqualTo(limitFirst);
        }

        @Test
        @DisplayName("a difference in a single flag state makes two otherwise identical accumulations "
                + "unequal, so the two remedies cannot be confused by comparison")
        void aDifferenceInStateMakesThemUnequal() {
            final FieldErrorDecorator missing = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);
            final FieldErrorDecorator invalid = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                            FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(missing).isNotEqualTo(invalid);
        }

        @Test
        @DisplayName("the three ways of expressing an empty accumulation are all equal to one another")
        void theThreeEmptyFormsAreEqual() {
            assertThat(FieldErrorDecorator.none())
                    .isEqualTo(new FieldErrorDecorator(List.of()))
                    .isEqualTo(new FieldErrorDecorator(null));
        }

        @Test
        @DisplayName("the text form names the component and its entries and nothing else, so a log line "
                + "built from it leaks no internal detail")
        void theTextFormNamesTheComponentAndNothingElse() {
            final String rendered = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK)
                    .toString();

            assertThat(rendered)
                    .contains("markedFields")
                    .contains(PROP_ACCT_STATUS)
                    .contains(SCREEN_ACCT_STATUS)
                    .contains(FieldErrorDecorator.FlagState.BLANK.name());
            assertThat(rendered)
                    .as("no package path, no hash and no published state name is rendered")
                    .doesNotContain("com.carddemo")
                    .doesNotContain("@")
                    .doesNotContain(ErrorResponse.FieldState.MISSING.name());
        }
    }

    @Nested
    @DisplayName("The 39 legacy expansion sites, driven through the production method")
    class LegacyExpansionSites {

        @Test
        @DisplayName("marking all 39 sites in expansion order yields 39 entries whose identifiers are "
                + "exactly the legacy sequence, so the irregular order is reproduced and not normalised")
        void allThirtyNineSitesReproduceTheLegacySequence() throws IOException {
            final List<String> decorated = decoratedScreenFields();

            final FieldErrorDecorator errors =
                    markAll(decorated, FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(errors.fieldErrors()).hasSize(EXPANSION_SITE_COUNT);
            assertThat(screenFieldsOf(errors)).containsExactlyElementsOf(decorated);
        }

        @Test
        @DisplayName("the accumulation's own output places the state between the two address lines and "
                + "the postal code ahead of city and country, exactly as the program expands them")
        void theIrregularPlacementsSurviveInTheOutput() throws IOException {
            final FieldErrorDecorator errors =
                    markAll(decoratedScreenFields(), FieldErrorDecorator.FlagState.BLANK);
            final List<String> reported = screenFieldsOf(errors);

            assertThat(reported.indexOf(SCREEN_STATE))
                    .isGreaterThan(reported.indexOf(SCREEN_ADDRESS_LINE_1))
                    .isLessThan(reported.indexOf(SCREEN_ADDRESS_LINE_2));
            assertThat(reported.indexOf(SCREEN_POSTAL_CODE))
                    .isLessThan(reported.indexOf(SCREEN_CITY))
                    .isLessThan(reported.indexOf(SCREEN_COUNTRY));
        }

        @Test
        @DisplayName("the first entry is the account status and the last two are the cardholder flag then "
                + "the transfer account identifier, following the substitution tokens rather than the "
                + "transposed descriptive lines")
        void theBoundaryEntriesFollowTheTokensNotTheCommentary() throws IOException {
            final FieldErrorDecorator errors =
                    markAll(decoratedScreenFields(), FieldErrorDecorator.FlagState.NOT_OK);
            final List<String> reported = screenFieldsOf(errors);

            assertThat(reported).startsWith(SCREEN_ACCT_STATUS);
            assertThat(reported).endsWith(SCREEN_PRIMARY_CARDHOLDER, SCREEN_EFT_ACCOUNT_ID);
        }

        @Test
        @DisplayName("no editable-but-undecorated field appears in the output, so the difference between "
                + "43 keyable fields and 39 decorated ones is never invented away")
        void noUndecoratedFieldAppearsInTheOutput() throws IOException {
            final FieldErrorDecorator errors =
                    markAll(decoratedScreenFields(), FieldErrorDecorator.FlagState.BLANK);

            assertThat(screenFieldsOf(errors))
                    .doesNotContainAnyElementsOf(undecoratedScreenFields());
        }

        @Test
        @DisplayName("the two fields the source decorates but never validates can be marked and are "
                + "reported, because the type must represent them even though no caller will")
        void theTwoDecoratedButUnvalidatedFieldsCanBeMarked() throws IOException {
            assertThat(decoratedScreenFields())
                    .contains(SCREEN_MIDDLE_NAME, SCREEN_ADDRESS_LINE_2);

            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_MIDDLE_NAME, SCREEN_MIDDLE_NAME,
                            FieldErrorDecorator.FlagState.BLANK)
                    .mark(PROP_ADDRESS_LINE_2, SCREEN_ADDRESS_LINE_2,
                            FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(errors.fieldErrors())
                    .extracting(ErrorResponse.FieldError::screenFieldId)
                    .containsExactly(SCREEN_MIDDLE_NAME, SCREEN_ADDRESS_LINE_2);
            assertThat(errors.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsExactly(ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("a mixture of the two flag states across all 39 sites keeps each entry's own state, "
                + "so 39 independent legacy flags stay 39 independent published states")
        void aMixtureAcrossAllSitesKeepsEachOwnState() throws IOException {
            final List<String> decorated = decoratedScreenFields();
            final List<ErrorResponse.FieldState> expected = new ArrayList<>();

            FieldErrorDecorator errors = FieldErrorDecorator.none();
            for (int index = 0; index < decorated.size(); index++) {
                final boolean blank = index % 2 == 0;
                final String screenFieldId = decorated.get(index);
                errors = errors.mark(propertyNameFor(screenFieldId), screenFieldId,
                        blank ? FieldErrorDecorator.FlagState.BLANK
                              : FieldErrorDecorator.FlagState.NOT_OK);
                expected.add(blank ? ErrorResponse.FieldState.MISSING
                                   : ErrorResponse.FieldState.INVALID);
            }

            assertThat(errors.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsExactlyElementsOf(expected);
            assertThat(screenFieldsOf(errors)).containsExactlyElementsOf(decorated);
        }

        @Test
        @DisplayName("the accumulation holds no registry of the 39: marking one of them yields exactly "
                + "one entry and never back-fills the other 38")
        void theAccumulationHoldsNoRegistry() throws IOException {
            final List<String> decorated = decoratedScreenFields();

            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(propertyNameFor(decorated.get(0)), decorated.get(0),
                            FieldErrorDecorator.FlagState.BLANK);

            assertThat(errors.fieldErrors()).hasSize(1);
            assertThat(screenFieldsOf(errors)).containsExactly(decorated.get(0));
        }

        @Test
        @DisplayName("every property name a caller supplies comes back unchanged across all 39 sites, so "
                + "the type derives no name of its own from the screen identifier")
        void everySuppliedPropertyNameComesBackUnchanged() throws IOException {
            final List<String> decorated = decoratedScreenFields();
            final List<String> expectedNames = decorated.stream()
                    .map(FieldErrorDecoratorTest::propertyNameFor)
                    .toList();

            final FieldErrorDecorator errors =
                    markAll(decorated, FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(errors.fieldErrors())
                    .extracting(ErrorResponse.FieldError::fieldName)
                    .containsExactlyElementsOf(expectedNames);
        }
    }

    @Nested
    @DisplayName("The neutral accumulation that both tiers read")
    class NeutralAccumulation {

        @Test
        @DisplayName("what is accumulated is the legacy flag state itself, untranslated, so neither the "
                + "response contract nor the failure carrier dictates the shape of the accumulation")
        void whatIsAccumulatedIsTheLegacyFlagState() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK)
                    .mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                            FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(errors.markedFields())
                    .extracting(FieldErrorDecorator.MarkedField::flagState)
                    .containsExactly(FieldErrorDecorator.FlagState.BLANK,
                            FieldErrorDecorator.FlagState.NOT_OK);
            assertThat(errors.markedFields())
                    .extracting(FieldErrorDecorator.MarkedField::field,
                            FieldErrorDecorator.MarkedField::bmsFieldId)
                    .containsExactly(tuple(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS),
                            tuple(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT));
        }

        @Test
        @DisplayName("a marked field holds the three identifiers and nothing else, so neither a message "
                + "nor a submitted value can ride along inside the accumulation")
        void aMarkedFieldHoldsTheThreeIdentifiersAndNothingElse() {
            final FieldErrorDecorator.MarkedField marked = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK)
                    .markedFields()
                    .getFirst();

            assertThat(marked).hasToString("MarkedField[field=" + PROP_ACCT_STATUS
                    + ", bmsFieldId=" + SCREEN_ACCT_STATUS + ", flagState=BLANK]");
        }

        @Test
        @DisplayName("the published entries are a projection of the accumulation rather than a second "
                + "store, so the two can never hold different fields or a different sequence")
        void thePublishedEntriesAreAProjectionOfTheAccumulation() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_ADDRESS_LINE_2, SCREEN_ADDRESS_LINE_2,
                            FieldErrorDecorator.FlagState.BLANK)
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                            FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(errors.fieldErrors())
                    .extracting(ErrorResponse.FieldError::fieldName)
                    .containsExactlyElementsOf(errors.markedFields().stream()
                            .map(FieldErrorDecorator.MarkedField::field).toList());
            assertThat(errors.fieldErrors())
                    .extracting(ErrorResponse.FieldError::screenFieldId)
                    .containsExactlyElementsOf(errors.markedFields().stream()
                            .map(FieldErrorDecorator.MarkedField::bmsFieldId).toList());
        }

        @Test
        @DisplayName("a fresh projection is built on each call, so a consumer that keeps one cannot "
                + "change what a later consumer reads")
        void aFreshProjectionIsBuiltOnEachCall() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);

            assertThat(errors.fieldErrors())
                    .isNotSameAs(errors.fieldErrors())
                    .isEqualTo(errors.fieldErrors())
                    .isUnmodifiable();
        }

        @Test
        @DisplayName("the accumulation itself is unmodifiable, so nothing can be appended to it behind "
                + "the one operation that grows it")
        void theAccumulationItselfIsUnmodifiable() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);

            assertThat(errors.markedFields()).isUnmodifiable();
        }

        @Test
        @DisplayName("a marked field built directly rejects the same absent components that marking "
                + "does, so there is one enforcement point rather than two")
        void aDirectlyBuiltMarkedFieldRejectsTheSameAbsentComponents() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new FieldErrorDecorator.MarkedField(
                            null, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK))
                    .withMessage("field must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> new FieldErrorDecorator.MarkedField(
                            PROP_ACCT_STATUS, null, FieldErrorDecorator.FlagState.BLANK))
                    .withMessage("bmsFieldId must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> new FieldErrorDecorator.MarkedField(
                            PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, null))
                    .withMessage("flagState must not be null");
        }

        @Test
        @DisplayName("marked fields are values, so two accumulations built the same way are equal and "
                + "a decoration can be compared rather than walked")
        void markedFieldsAreValues() {
            final FieldErrorDecorator.MarkedField first = new FieldErrorDecorator.MarkedField(
                    PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);
            final FieldErrorDecorator.MarkedField second = new FieldErrorDecorator.MarkedField(
                    PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isNotEqualTo(new FieldErrorDecorator.MarkedField(
                    PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.NOT_OK));
        }

        @Test
        @DisplayName("all 39 decorated screen fields accumulate as neutral entries in golden order, so "
                + "the largest decoration the legacy program could build survives untranslated")
        void allThirtyNineAccumulateAsNeutralEntries() throws IOException {
            final List<String> decorated = decoratedScreenFields();

            final FieldErrorDecorator errors =
                    markAll(decorated, FieldErrorDecorator.FlagState.BLANK);

            assertThat(errors.markedFields())
                    .hasSize(EXPANSION_SITE_COUNT)
                    .extracting(FieldErrorDecorator.MarkedField::bmsFieldId)
                    .containsExactlyElementsOf(decorated);
        }
    }

    @Nested
    @DisplayName("Hand-off to the response body the accumulation feeds")
    class HandOffToErrorResponse {

        @Test
        @DisplayName("an empty accumulation produces a response with no field errors, which is the "
                + "first-submission shape")
        void anEmptyAccumulationProducesAnUndecoratedResponse() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none();

            final ErrorResponse response = new ErrorResponse("Account update rejected",
                    errors.fieldErrors());

            assertThat(errors.isEmpty()).isTrue();
            assertThat(response.hasFieldErrors()).isFalse();
            assertThat(response.fieldErrors()).isEmpty();
        }

        @Test
        @DisplayName("the presence test is the exact inverse of the response's own, so a caller can "
                + "branch on either without disagreeing")
        void thePresenceTestIsTheInverseOfTheResponseTest() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);

            final ErrorResponse response = new ErrorResponse("Account update rejected",
                    errors.fieldErrors());

            assertThat(errors.isEmpty()).isNotEqualTo(response.hasFieldErrors());
        }

        @Test
        @DisplayName("all 39 entries survive the hand-off in order and with their states, so the response "
                + "echoes the accumulation and adds nothing")
        void allThirtyNineSurviveTheHandOff() throws IOException {
            final List<String> decorated = decoratedScreenFields();
            final FieldErrorDecorator errors =
                    markAll(decorated, FieldErrorDecorator.FlagState.NOT_OK);

            final ErrorResponse response = new ErrorResponse("Account update rejected",
                    errors.fieldErrors(), SCREEN_ACCT_STATUS);

            assertThat(response.fieldErrors())
                    .hasSize(EXPANSION_SITE_COUNT)
                    .containsExactlyElementsOf(errors.fieldErrors());
            assertThat(response.fieldErrors())
                    .extracting(ErrorResponse.FieldError::screenFieldId)
                    .containsExactlyElementsOf(decorated);
            assertThat(response.focusScreenFieldId()).isEqualTo(SCREEN_ACCT_STATUS);
        }
    }

    @Nested
    @DisplayName("The public surface: one decoration operation, three arguments, no fourth")
    class PublicSurface {

        @Test
        @DisplayName("the one decoration operation binds to a three-argument reference and the bound "
                + "form and the direct call are indistinguishable")
        void theDecorationOperationIsSingleAndThreeArgument() {
            final MarkOperation operation = FieldErrorDecorator::mark;

            final FieldErrorDecorator viaReference = operation.applyTo(FieldErrorDecorator.none(),
                    PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);
            final FieldErrorDecorator viaDirectCall = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);

            assertThat(viaReference)
                    .as("a single applicable operation resolves identically through either form")
                    .isEqualTo(viaDirectCall);
            assertThat(viaReference.fieldErrors()).hasSize(1);
        }

        @Test
        @DisplayName("the whole surface is the factory, the operation, the presence test and the entry "
                + "accessor, each binding at its own declared arity")
        void theSurfaceIsFourBindings() {
            final Supplier<FieldErrorDecorator> emptyFactory = FieldErrorDecorator::none;
            final MarkOperation operation = FieldErrorDecorator::mark;
            final Predicate<FieldErrorDecorator> presenceTest = FieldErrorDecorator::isEmpty;
            final Function<FieldErrorDecorator, List<ErrorResponse.FieldError>> entriesAccessor =
                    FieldErrorDecorator::fieldErrors;

            final FieldErrorDecorator empty = emptyFactory.get();
            final FieldErrorDecorator marked = operation.applyTo(empty, PROP_CREDIT_LIMIT,
                    SCREEN_CREDIT_LIMIT, FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(presenceTest.test(empty)).isTrue();
            assertThat(presenceTest.test(marked)).isFalse();
            assertThat(entriesAccessor.apply(empty)).isEmpty();
            assertThat(entriesAccessor.apply(marked)).hasSize(1);
        }

        @Test
        @DisplayName("one invocation contributes exactly one entry in either flag state, so 39 entries "
                + "require 39 invocations and no bulk operation can be hiding")
        void oneInvocationContributesExactlyOneEntry() {
            for (final FieldErrorDecorator.FlagState flagState
                    : FieldErrorDecorator.FlagState.values()) {
                assertThat(FieldErrorDecorator.none()
                        .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, flagState).fieldErrors())
                        .as("flag state %s must contribute one entry, never a batch", flagState)
                        .hasSize(1);
            }
        }

        @Test
        @DisplayName("the only collection-accepting entry point stores what it is handed and translates "
                + "nothing, so no flag state is ever translated in bulk")
        void theCollectionEntryPointTranslatesNothing() {
            final List<FieldErrorDecorator.MarkedField> handedOver = List.of(
                    new FieldErrorDecorator.MarkedField(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                            FieldErrorDecorator.FlagState.BLANK),
                    new FieldErrorDecorator.MarkedField(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                            FieldErrorDecorator.FlagState.NOT_OK));

            final FieldErrorDecorator fromEntries = new FieldErrorDecorator(handedOver);

            assertThat(fromEntries.markedFields())
                    .as("construction stores the entries untranslated, in the order handed over")
                    .containsExactlyElementsOf(handedOver);
            assertThat(fromEntries.fieldErrors())
                    .as("translation happens once, on the projection, one entry at a time")
                    .containsExactly(
                            new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                                    ErrorResponse.FieldState.MISSING),
                            new ErrorResponse.FieldError(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                                    ErrorResponse.FieldState.INVALID));
        }

        @Test
        @DisplayName("the empty value is usable the moment it is handed over, so no separate build or "
                + "completion step exists to forget")
        void theEmptyValueNeedsNoCompletionStep() {
            final FieldErrorDecorator empty = FieldErrorDecorator.none();

            assertThat(empty.isEmpty()).isTrue();
            assertThat(empty.fieldErrors()).isEmpty();
            assertThat(empty.mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                    FieldErrorDecorator.FlagState.BLANK).fieldErrors()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Purity: one receiver, invoked twice with the same arguments")
    class PurityUnderRepetition {

        @Test
        @DisplayName("invoking twice with identical arguments from one receiver yields two equal but "
                + "distinct values and leaves the receiver exactly as it was")
        void twoIdenticalInvocationsFromOneReceiverAgree() {
            final FieldErrorDecorator receiver = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);

            final FieldErrorDecorator first = receiver.mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                    FieldErrorDecorator.FlagState.NOT_OK);
            final FieldErrorDecorator second = receiver.mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                    FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second).isNotSameAs(second);
            assertThat(receiver.fieldErrors())
                    .as("neither invocation may reach back into the value they were derived from")
                    .hasSize(1);
            assertThat(screenFieldsOf(receiver)).containsExactly(SCREEN_ACCT_STATUS);
            assertThat(screenFieldsOf(first))
                    .containsExactly(SCREEN_ACCT_STATUS, SCREEN_CREDIT_LIMIT);
        }

        @Test
        @DisplayName("the arguments handed in are not altered either, so a caller can reuse the same "
                + "names across several marks")
        void theArgumentsAreNotAltered() {
            final String propertyName = PROP_ACCT_STATUS;
            final String screenFieldId = SCREEN_ACCT_STATUS;
            final FieldErrorDecorator.FlagState flagState = FieldErrorDecorator.FlagState.BLANK;

            final FieldErrorDecorator once = FieldErrorDecorator.none()
                    .mark(propertyName, screenFieldId, flagState);
            final FieldErrorDecorator twice = once.mark(propertyName, screenFieldId, flagState);

            assertThat(propertyName).isEqualTo(PROP_ACCT_STATUS);
            assertThat(screenFieldId).isEqualTo(SCREEN_ACCT_STATUS);
            assertThat(flagState).isEqualTo(FieldErrorDecorator.FlagState.BLANK);
            assertThat(twice.fieldErrors()).hasSize(2);
            assertThat(twice.fieldErrors().get(0)).isEqualTo(twice.fieldErrors().get(1));
        }

        @Test
        @DisplayName("a value re-read after being marked from reports the same content it did before, so "
                + "reading is not a consuming operation")
        void readingIsNotConsuming() {
            final FieldErrorDecorator receiver = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);
            final List<ErrorResponse.FieldError> beforeMarking = receiver.fieldErrors();

            receiver.mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                    FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(receiver.fieldErrors()).isEqualTo(beforeMarking).hasSize(1);
            assertThat(receiver.isEmpty()).isFalse();
        }
    }

    @Nested
    @DisplayName("The two states are states, not a boolean")
    class StatesAreNotBooleans {

        @Test
        @DisplayName("a boolean built from either accumulation is the same for both, which is precisely "
                + "the distinction a single flag would have destroyed")
        void aBooleanCannotTellTheTwoStatesApart() {
            final FieldErrorDecorator blank = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);
            final FieldErrorDecorator notOk = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                            FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(blank.isEmpty())
                    .as("reduced to a presence flag the two are indistinguishable")
                    .isEqualTo(notOk.isEmpty());
            assertThat(blank.fieldErrors().get(0).state())
                    .as("carried as a state they are not")
                    .isNotEqualTo(notOk.fieldErrors().get(0).state());
            assertThat(blank).isNotEqualTo(notOk);
        }

        @Test
        @DisplayName("both published states are constants of the enum nested in the response body, so "
                + "neither can be coerced into the other or into a flag")
        void bothStatesAreConstantsOfTheOneEnum() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK)
                    .mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                            FieldErrorDecorator.FlagState.NOT_OK);

            final List<ErrorResponse.FieldState> published =
                    Arrays.asList(ErrorResponse.FieldState.values());

            assertThat(errors.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsExactly(ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID)
                    .allSatisfy(state -> assertThat(published).contains(state));
            assertThat(published).containsExactly(ErrorResponse.FieldState.MISSING,
                    ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("the incoming vocabulary and the published vocabulary are the same size, so the "
                + "translation loses nothing and invents nothing")
        void theTwoVocabulariesAreTheSameSize() {
            assertThat(FieldErrorDecorator.FlagState.values())
                    .hasSameSizeAs(ErrorResponse.FieldState.values())
                    .hasSize(2);
        }

        @Test
        @DisplayName("the translation is injective across the whole incoming vocabulary, so no two flag "
                + "states can ever land on one published state")
        void theTranslationIsInjective() {
            final Set<ErrorResponse.FieldState> reached = new LinkedHashSet<>();
            for (final FieldErrorDecorator.FlagState flagState
                    : FieldErrorDecorator.FlagState.values()) {
                reached.add(FieldErrorDecorator.none()
                        .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, flagState)
                        .fieldErrors().get(0).state());
            }

            assertThat(reached).hasSize(FieldErrorDecorator.FlagState.values().length)
                    .containsExactly(ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID);
        }
    }

    /**
     * The re-entry gate the macro carried is a caller decision and is asserted here to be absent
     * from this type, not present in a weakened form.
     *
     * <p>The macro conjoined its whole body with the program-context re-enter condition, so the
     * legacy screen showed no field-level decoration at all on a first submission. Nothing in this
     * type reproduces that conjunction: there is no argument to pass it in, no component to hold it
     * and no branch to test it. A first submission is realised by <em>not calling</em> the
     * operation, which is why the end-to-end assertion for an undecorated first submission belongs
     * to the response body's own suite and is made there against
     * {@link ErrorResponse#ErrorResponse(String)}, not here.
     */
    @Nested
    @DisplayName("The re-entry gate is the caller's, and is absent from this type")
    class NoReEntryDecisionHere {

        @Test
        @DisplayName("the very first mark decorates, so nothing inside suppresses a first submission")
        void theVeryFirstMarkAlreadyDecorates() {
            final FieldErrorDecorator firstEver = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);

            assertThat(firstEver.isEmpty()).isFalse();
            assertThat(firstEver.fieldErrors()).hasSize(1);
            assertThat(firstEver.fieldErrors().get(0).state())
                    .isEqualTo(ErrorResponse.FieldState.MISSING);
        }

        @Test
        @DisplayName("the first mark and a later identical mark on a fresh value are equal, so no hidden "
                + "counter distinguishes a first submission from a re-submission")
        void noHiddenCounterDistinguishesSubmissions() {
            final FieldErrorDecorator firstSubmission = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);

            final FieldErrorDecorator warmedUp = FieldErrorDecorator.none()
                    .mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                            FieldErrorDecorator.FlagState.NOT_OK);
            assertThat(warmedUp.fieldErrors()).hasSize(1);

            final FieldErrorDecorator laterSubmission = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);

            assertThat(laterSubmission)
                    .as("earlier use of the type cannot change what a later identical call produces")
                    .isEqualTo(firstSubmission);
        }

        @Test
        @DisplayName("an undecorated result is reachable only by not calling the operation, never by "
                + "calling it in a way that declines to record")
        void anUndecoratedResultComesOnlyFromNotCalling() {
            assertThat(FieldErrorDecorator.none().isEmpty()).isTrue();

            for (final FieldErrorDecorator.FlagState flagState
                    : FieldErrorDecorator.FlagState.values()) {
                assertThat(FieldErrorDecorator.none()
                        .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, flagState).isEmpty())
                        .as("no flag state may make the operation decline to record")
                        .isFalse();
            }
        }
    }

    @Nested
    @DisplayName("No table of the 39: both identifiers arrive as arguments and are never looked up")
    class NoTableOfDecoratedFields {

        @Test
        @DisplayName("an identifier belonging to neither fixture is accepted and reported verbatim, so no "
                + "membership of any list is consulted")
        void anUnknownIdentifierIsAcceptedVerbatim() throws IOException {
            assertThat(decoratedScreenFields())
                    .as("the probe identifier must genuinely be outside the decorated set")
                    .doesNotContain(SCREEN_NOT_IN_THE_MAP);
            assertThat(unprotectedScreenFields())
                    .as("and outside the map's keyable fields as well")
                    .doesNotContain(SCREEN_NOT_IN_THE_MAP);

            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_NOT_IN_ANY_CONTRACT, SCREEN_NOT_IN_THE_MAP,
                            FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(errors.fieldErrors()).hasSize(1);
            assertThat(errors.fieldErrors().get(0).fieldName())
                    .isEqualTo(PROP_NOT_IN_ANY_CONTRACT);
            assertThat(errors.fieldErrors().get(0).screenFieldId())
                    .isEqualTo(SCREEN_NOT_IN_THE_MAP);
        }

        @Test
        @DisplayName("one of the four editable-but-undecorated fields is accepted too, because the type "
                + "enforces no membership rule - it is the caller that never marks them")
        void evenAnUndecoratedMapFieldIsAccepted() throws IOException {
            final String undecorated = undecoratedScreenFields().iterator().next();

            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(propertyNameFor(undecorated), undecorated,
                            FieldErrorDecorator.FlagState.BLANK);

            assertThat(errors.fieldErrors()).hasSize(1);
            assertThat(errors.fieldErrors().get(0).screenFieldId()).isEqualTo(undecorated);
        }

        @Test
        @DisplayName("the property name is never derived from the screen identifier: an arbitrary pairing "
                + "of the two survives exactly as paired")
        void thePairingIsTheCallersOwn() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_ADDRESS_LINE_2, SCREEN_ACCT_STATUS,
                            FieldErrorDecorator.FlagState.NOT_OK);
            final ErrorResponse.FieldError entry = errors.fieldErrors().get(0);

            assertThat(entry.fieldName()).isEqualTo(PROP_ADDRESS_LINE_2);
            assertThat(entry.screenFieldId()).isEqualTo(SCREEN_ACCT_STATUS);
        }

        @Test
        @DisplayName("marking every one of the 39 produces no entry the caller did not ask for, so no "
                + "list is being consulted to add or to withhold")
        void nothingIsAddedAndNothingIsWithheld() throws IOException {
            final List<String> decorated = decoratedScreenFields();

            final FieldErrorDecorator errors = markAll(decorated,
                    FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(screenFieldsOf(errors))
                    .hasSameSizeAs(decorated)
                    .containsExactlyElementsOf(decorated);
        }
    }

    @Nested
    @DisplayName("No shared state: independent sequences interleaved")
    class IndependentSequences {

        @Test
        @DisplayName("two accumulations grown in alternation each hold only their own entries, so no "
                + "accumulator, collector or registry is shared between them")
        void twoInterleavedSequencesStayIsolated() {
            FieldErrorDecorator left = FieldErrorDecorator.none();
            FieldErrorDecorator right = FieldErrorDecorator.none();

            left = left.mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                    FieldErrorDecorator.FlagState.BLANK);
            right = right.mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                    FieldErrorDecorator.FlagState.NOT_OK);
            left = left.mark(PROP_MIDDLE_NAME, SCREEN_MIDDLE_NAME,
                    FieldErrorDecorator.FlagState.NOT_OK);
            right = right.mark(PROP_ADDRESS_LINE_2, SCREEN_ADDRESS_LINE_2,
                    FieldErrorDecorator.FlagState.BLANK);

            assertThat(screenFieldsOf(left))
                    .containsExactly(SCREEN_ACCT_STATUS, SCREEN_MIDDLE_NAME);
            assertThat(screenFieldsOf(right))
                    .containsExactly(SCREEN_CREDIT_LIMIT, SCREEN_ADDRESS_LINE_2);
            assertThat(left.fieldErrors()).doesNotContainAnyElementsOf(right.fieldErrors());
            assertThat(right.fieldErrors()).doesNotContainAnyElementsOf(left.fieldErrors());
        }

        @Test
        @DisplayName("the 39 sites split across two interleaved sequences partition exactly, with no "
                + "entry appearing in both and none lost between them")
        void theThirtyNineSplitCleanlyAcrossTwoSequences() throws IOException {
            final List<String> decorated = decoratedScreenFields();
            final List<String> evenSites = new ArrayList<>();
            final List<String> oddSites = new ArrayList<>();

            FieldErrorDecorator even = FieldErrorDecorator.none();
            FieldErrorDecorator odd = FieldErrorDecorator.none();
            for (int index = 0; index < decorated.size(); index++) {
                final String screenFieldId = decorated.get(index);
                if (index % 2 == 0) {
                    evenSites.add(screenFieldId);
                    even = even.mark(propertyNameFor(screenFieldId), screenFieldId,
                            FieldErrorDecorator.FlagState.BLANK);
                } else {
                    oddSites.add(screenFieldId);
                    odd = odd.mark(propertyNameFor(screenFieldId), screenFieldId,
                            FieldErrorDecorator.FlagState.NOT_OK);
                }
            }

            assertThat(screenFieldsOf(even)).containsExactlyElementsOf(evenSites);
            assertThat(screenFieldsOf(odd)).containsExactlyElementsOf(oddSites);
            assertThat(even.fieldErrors()).hasSize(evenSites.size());
            assertThat(odd.fieldErrors()).hasSize(oddSites.size());
            assertThat(evenSites.size() + oddSites.size()).isEqualTo(EXPANSION_SITE_COUNT);
            assertThat(screenFieldsOf(even)).doesNotContainAnyElementsOf(screenFieldsOf(odd));
            assertThat(even.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsOnly(ErrorResponse.FieldState.MISSING);
            assertThat(odd.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsOnly(ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("abandoning one sequence part-built leaves the other untouched, so nothing is being "
                + "held anywhere beyond the values themselves")
        void abandoningOneSequenceLeavesTheOtherIntact() {
            final FieldErrorDecorator kept = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);

            FieldErrorDecorator.none()
                    .mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                            FieldErrorDecorator.FlagState.NOT_OK)
                    .mark(PROP_MIDDLE_NAME, SCREEN_MIDDLE_NAME,
                            FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(screenFieldsOf(kept)).containsExactly(SCREEN_ACCT_STATUS);
            assertThat(kept.fieldErrors()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Both identifiers are bounded text carried byte for byte")
    class IdentifiersAreCarriedByteForByte {

        @Test
        @DisplayName("leading zeros survive on both identifiers, which a numeric parameter type could not "
                + "have managed")
        void leadingZerosSurvive() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(LEADING_ZERO_VALUE, LEADING_ZERO_IDENTIFIER,
                            FieldErrorDecorator.FlagState.NOT_OK);
            final ErrorResponse.FieldError entry = errors.fieldErrors().get(0);

            assertThat(entry.fieldName()).isEqualTo(LEADING_ZERO_VALUE);
            assertThat(entry.screenFieldId()).isEqualTo(LEADING_ZERO_IDENTIFIER);
            assertThat(entry.fieldName())
                    .as("the digits are text, so the width is preserved rather than normalised")
                    .hasSameSizeAs(LEADING_ZERO_VALUE)
                    .startsWith("0");
        }

        @Test
        @DisplayName("a numeric round trip of the same value loses the zeros, which is the independent "
                + "demonstration of why the parameter is text")
        void aNumericRoundTripWouldHaveLostTheZeros() {
            final String numericallyNormalised =
                    Long.toString(Long.parseLong(LEADING_ZERO_VALUE));

            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(LEADING_ZERO_VALUE, LEADING_ZERO_IDENTIFIER,
                            FieldErrorDecorator.FlagState.BLANK);

            assertThat(numericallyNormalised)
                    .as("a numeric type drops the leading zeros and shortens the value")
                    .isNotEqualTo(LEADING_ZERO_VALUE)
                    .hasSizeLessThan(LEADING_ZERO_VALUE.length());
            assertThat(errors.fieldErrors().get(0).fieldName())
                    .as("the operation keeps them, because it takes text")
                    .isEqualTo(LEADING_ZERO_VALUE)
                    .isNotEqualTo(numericallyNormalised);
        }

        @Test
        @DisplayName("trailing and leading spaces survive on both identifiers, because the fields these "
                + "values derive from are fixed width and space significant")
        void surroundingSpacesSurvive() {
            final String paddedProperty = "acctStatus   ";
            final String paddedIdentifier = "  ACSTTUS";

            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(paddedProperty, paddedIdentifier, FieldErrorDecorator.FlagState.NOT_OK);
            final ErrorResponse.FieldError entry = errors.fieldErrors().get(0);

            assertThat(entry.fieldName()).isEqualTo(paddedProperty)
                    .hasSameSizeAs(paddedProperty)
                    .isNotEqualTo(PROP_ACCT_STATUS);
            assertThat(entry.screenFieldId()).isEqualTo(paddedIdentifier)
                    .hasSameSizeAs(paddedIdentifier)
                    .isNotEqualTo(SCREEN_ACCT_STATUS);
        }

        @Test
        @DisplayName("a value far longer than any field the map declares is carried unchanged, so this "
                + "type enforces no width of its own")
        void noWidthIsEnforced() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(OVERLONG_VALUE, OVERLONG_VALUE, FieldErrorDecorator.FlagState.BLANK);
            final ErrorResponse.FieldError entry = errors.fieldErrors().get(0);

            assertThat(entry.fieldName()).isEqualTo(OVERLONG_VALUE)
                    .hasSize(OVERLONG_VALUE.length());
            assertThat(entry.screenFieldId()).isEqualTo(OVERLONG_VALUE);
            assertThat(OVERLONG_VALUE.length())
                    .as("and it is genuinely wider than the widest identifier the map declares")
                    .isGreaterThan(SCREEN_FIELD_ID_MAX_WIDTH);
        }

        @Test
        @DisplayName("digits, punctuation and embedded spaces are all carried unchanged, so no character "
                + "class is enforced either - the legacy alphabetic edit accepted embedded spaces")
        void noCharacterClassIsEnforced() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(MIXED_CONTENT_VALUE, MIXED_CONTENT_VALUE,
                            FieldErrorDecorator.FlagState.NOT_OK);
            final ErrorResponse.FieldError entry = errors.fieldErrors().get(0);

            assertThat(entry.fieldName()).isEqualTo(MIXED_CONTENT_VALUE);
            assertThat(entry.screenFieldId()).isEqualTo(MIXED_CONTENT_VALUE);
            assertThat(entry.message())
                    .as("and still no per-field wording is invented for it")
                    .isNull();
        }

        @Test
        @DisplayName("the two fields the source decorates but never validates accept any content at all, "
                + "so no constraint may be attached to them that the legacy would have accepted")
        void theTwoUnvalidatedFieldsAcceptAnything() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_MIDDLE_NAME, SCREEN_MIDDLE_NAME,
                            FieldErrorDecorator.FlagState.BLANK)
                    .mark(PROP_ADDRESS_LINE_2, SCREEN_ADDRESS_LINE_2,
                            FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(errors.fieldErrors())
                    .extracting(ErrorResponse.FieldError::fieldName)
                    .containsExactly(PROP_MIDDLE_NAME, PROP_ADDRESS_LINE_2);

            final FieldErrorDecorator withOddContent = FieldErrorDecorator.none()
                    .mark(PROP_MIDDLE_NAME, SCREEN_MIDDLE_NAME,
                            FieldErrorDecorator.FlagState.BLANK)
                    .mark(PROP_ADDRESS_LINE_2, SCREEN_ADDRESS_LINE_2,
                            FieldErrorDecorator.FlagState.BLANK);

            assertThat(withOddContent.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsOnly(ErrorResponse.FieldState.MISSING);
        }

        @Test
        @DisplayName("an empty identifier is accepted rather than rejected, because absence and emptiness "
                + "are different and only absence is a caller defect")
        void emptinessIsAcceptedWhereAbsenceIsNot() {
            final FieldErrorDecorator accepted = FieldErrorDecorator.none()
                    .mark("", "", FieldErrorDecorator.FlagState.BLANK);

            assertThat(accepted.fieldErrors()).hasSize(1);
            assertThat(accepted.fieldErrors().get(0).fieldName()).isEmpty();
            assertThat(accepted.fieldErrors().get(0).screenFieldId()).isEmpty();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FieldErrorDecorator.none()
                            .mark(null, "", FieldErrorDecorator.FlagState.BLANK));
        }
    }

    /**
     * The 3270 mechanisms the macro used are gone, and only the two states they signified remain.
     *
     * <p>The macro's two edits were a colour change on the field's attribute sub-field and a
     * single-character marker written over its displayed value. Neither is a REST concept, and this
     * group asserts that neither leaks: no entry carries a control byte, an attribute value, a
     * marker character, a map coordinate or a field width, and the screen field identifier is an
     * opaque label a client may ignore entirely.
     */
    @Nested
    @DisplayName("No terminal presentation detail survives the translation")
    class NoTerminalPresentationDetail {

        @Test
        @DisplayName("an entry carries exactly four components - two identifiers, a state and an absent "
                + "wording - and nothing that could encode a screen attribute")
        void anEntryCarriesOnlyItsFourComponents() {
            final ErrorResponse.FieldError entry = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK)
                    .fieldErrors().get(0);

            assertThat(entry.fieldName()).isEqualTo(PROP_ACCT_STATUS);
            assertThat(entry.screenFieldId()).isEqualTo(SCREEN_ACCT_STATUS);
            assertThat(entry.state()).isEqualTo(ErrorResponse.FieldState.MISSING);
            assertThat(entry.message()).isNull();
            assertThat(entry).isEqualTo(new ErrorResponse.FieldError(PROP_ACCT_STATUS,
                    SCREEN_ACCT_STATUS, ErrorResponse.FieldState.MISSING));
        }

        @Test
        @DisplayName("the blank state adds no marker character of its own, so the state alone distinguishes "
                + "it and the legacy overwrite is not reproduced in the payload")
        void theBlankStateAddsNoMarkerCharacter() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);
            final ErrorResponse.FieldError entry = errors.fieldErrors().get(0);

            assertThat(entry.fieldName()).doesNotContain("*");
            assertThat(entry.screenFieldId()).doesNotContain("*");
            assertThat(entry.state()).isEqualTo(ErrorResponse.FieldState.MISSING);
            assertThat(errors.toString())
                    .as("nor does the rendering acquire one")
                    .doesNotContain("*");
        }

        @Test
        @DisplayName("neither state names a colour, an attribute, a control byte or a coordinate, so a "
                + "client is free to choose its own presentation")
        void neitherStateNamesAPresentationMechanism() {
            final List<String> stateNames = Arrays.stream(ErrorResponse.FieldState.values())
                    .map(Enum::name)
                    .toList();

            assertThat(stateNames).containsExactly("MISSING", "INVALID");
            assertThat(stateNames).allSatisfy(name -> assertThat(name)
                    .doesNotContain("RED")
                    .doesNotContain("COLOR")
                    .doesNotContain("COLOUR")
                    .doesNotContain("ATTR")
                    .doesNotContain("BYTE")
                    .doesNotContain("ROW")
                    .doesNotContain("COLUMN"));
        }

        @Test
        @DisplayName("the screen field identifier is opaque: a value that is plainly not an identifier is "
                + "carried just the same, so it is never parsed as a coordinate or an attribute")
        void theScreenFieldIdentifierIsOpaque() {
            final String notAnIdentifier = "row 7, column 42";

            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, notAnIdentifier, FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(errors.fieldErrors().get(0).screenFieldId()).isEqualTo(notAnIdentifier);
            assertThat(errors.fieldErrors()).hasSize(1);
        }
    }

    /**
     * The wire shape of the entries this type produces, under a mapper configured exactly as the
     * deployed one.
     *
     * <p>The mapper is built locally, by hand, from the four settings the module's configuration
     * file declares. No application context is started and no framework test slice is used, so this
     * group stays a unit test; the equivalence between a hand-built mapper and the deployed bean is
     * itself asserted elsewhere, by the one suite in this package that does start a context.
     */
    @Nested
    @DisplayName("Wire shape of the accumulated entries")
    class WireShape {

        @Test
        @DisplayName("an entry publishes the two identifiers and the state, and omits the absent wording "
                + "rather than rendering it as a null")
        void anEntryPublishesThreePropertiesAndOmitsTheFourth() throws JsonProcessingException {
            final ObjectMapper mapper = declaredSettingsMapper();
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK);

            final JsonNode entry = mapper.readTree(
                    mapper.writeValueAsString(errors.fieldErrors().get(0)));

            assertThat(entry.get("fieldName").asText()).isEqualTo(PROP_ACCT_STATUS);
            assertThat(entry.get("screenFieldId").asText()).isEqualTo(SCREEN_ACCT_STATUS);
            assertThat(entry.get("state").asText()).isEqualTo("MISSING");
            assertThat(entry.has("message"))
                    .as("an absent wording is omitted, not published as a null")
                    .isFalse();
            assertThat(entry.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("both states publish under their own names, so the two remedies stay distinguishable "
                + "on the wire and neither becomes a boolean")
        void bothStatesPublishUnderTheirOwnNames() throws JsonProcessingException {
            final ObjectMapper mapper = declaredSettingsMapper();
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FieldErrorDecorator.FlagState.BLANK)
                    .mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                            FieldErrorDecorator.FlagState.NOT_OK);

            final JsonNode entries = mapper.readTree(mapper.writeValueAsString(errors.fieldErrors()));

            assertThat(entries.isArray()).isTrue();
            assertThat(entries).hasSize(2);
            assertThat(entries.get(0).get("state").asText()).isEqualTo("MISSING");
            assertThat(entries.get(1).get("state").asText()).isEqualTo("INVALID");
            assertThat(entries.get(0).get("state").isTextual()).isTrue();
            assertThat(entries.get(0).get("state").isBoolean()).isFalse();
        }

        @Test
        @DisplayName("a digits-only identifier publishes as text and keeps its leading zeros, so nothing "
                + "on the wire turns a display field into a number")
        void digitsPublishAsTextAndKeepTheirZeros() throws JsonProcessingException {
            final ObjectMapper mapper = declaredSettingsMapper();
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark(LEADING_ZERO_VALUE, LEADING_ZERO_IDENTIFIER,
                            FieldErrorDecorator.FlagState.NOT_OK);

            final JsonNode entry = mapper.readTree(
                    mapper.writeValueAsString(errors.fieldErrors().get(0)));

            assertThat(entry.get("fieldName").isTextual()).isTrue();
            assertThat(entry.get("fieldName").isNumber()).isFalse();
            assertThat(entry.get("fieldName").asText()).isEqualTo(LEADING_ZERO_VALUE);
            assertThat(entry.get("screenFieldId").asText()).isEqualTo(LEADING_ZERO_IDENTIFIER);
        }

        @Test
        @DisplayName("space padding survives serialization untrimmed on both identifiers")
        void paddingSurvivesSerialization() throws JsonProcessingException {
            final ObjectMapper mapper = declaredSettingsMapper();
            final String paddedIdentifier = " ACSTTUS ";
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark("  padded  ", paddedIdentifier, FieldErrorDecorator.FlagState.BLANK);

            final JsonNode entry = mapper.readTree(
                    mapper.writeValueAsString(errors.fieldErrors().get(0)));

            assertThat(entry.get("fieldName").asText()).isEqualTo("  padded  ");
            assertThat(entry.get("screenFieldId").asText()).isEqualTo(paddedIdentifier);
        }

        @Test
        @DisplayName("an unknown property on an inbound entry is tolerated rather than rejected, which is "
                + "what the module's own deserialization setting requires")
        void anUnknownInboundPropertyIsTolerated() throws JsonProcessingException {
            final ObjectMapper mapper = declaredSettingsMapper();
            final String payload = "{\"fieldName\":\"" + PROP_ACCT_STATUS
                    + "\",\"screenFieldId\":\"" + SCREEN_ACCT_STATUS
                    + "\",\"state\":\"MISSING\",\"aPropertyNoVersionOfThisTypeDeclares\":true}";

            final ErrorResponse.FieldError entry =
                    mapper.readValue(payload, ErrorResponse.FieldError.class);

            assertThat(entry.fieldName()).isEqualTo(PROP_ACCT_STATUS);
            assertThat(entry.screenFieldId()).isEqualTo(SCREEN_ACCT_STATUS);
            assertThat(entry.state()).isEqualTo(ErrorResponse.FieldState.MISSING);
            assertThat(entry.message()).isNull();
        }

        @Test
        @DisplayName("all 39 entries round trip through the wire in order and with their own states, so "
                + "the sequence and the two remedies both survive publication")
        void allThirtyNineRoundTripInOrder() throws IOException {
            final ObjectMapper mapper = declaredSettingsMapper();
            final List<String> decorated = decoratedScreenFields();
            final FieldErrorDecorator errors = markAll(decorated,
                    FieldErrorDecorator.FlagState.BLANK);

            final ErrorResponse published = new ErrorResponse("Account update rejected",
                    errors.fieldErrors(), SCREEN_ACCT_STATUS);
            final ErrorResponse returned = mapper.readValue(
                    mapper.writeValueAsString(published), ErrorResponse.class);

            assertThat(returned.fieldErrors()).hasSize(EXPANSION_SITE_COUNT)
                    .containsExactlyElementsOf(errors.fieldErrors());
            assertThat(returned.fieldErrors())
                    .extracting(ErrorResponse.FieldError::screenFieldId)
                    .containsExactlyElementsOf(decorated);
            assertThat(returned.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsOnly(ErrorResponse.FieldState.MISSING);
            assertThat(returned.focusScreenFieldId()).isEqualTo(SCREEN_ACCT_STATUS);
        }

        @Test
        @DisplayName("an empty accumulation publishes the collection as an empty array rather than "
                + "omitting it, so a client never has to test it for absence")
        void anEmptyAccumulationPublishesAnEmptyArray() throws JsonProcessingException {
            final ObjectMapper mapper = declaredSettingsMapper();
            final FieldErrorDecorator errors = FieldErrorDecorator.none();

            final JsonNode payload = mapper.readTree(mapper.writeValueAsString(
                    new ErrorResponse("Account update rejected", errors.fieldErrors())));

            assertThat(payload.has("fieldErrors")).isTrue();
            assertThat(payload.get("fieldErrors").isArray()).isTrue();
            assertThat(payload.get("fieldErrors")).isEmpty();
            assertThat(payload.has("focusScreenFieldId"))
                    .as("while an absent focus hint is omitted")
                    .isFalse();
        }
    }
}
