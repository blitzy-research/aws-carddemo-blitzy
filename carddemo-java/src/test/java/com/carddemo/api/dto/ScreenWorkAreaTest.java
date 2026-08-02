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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.carddemo.domain.enums.KeyAction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies {@link ScreenWorkArea}, the replacement for the per-screen scratch area that five of the
 * online programs shared.
 *
 * <p><strong>What it replaces.</strong> {@code app/cpy/CVCRD01Y.cpy} declares the
 * {@code CC-WORK-AREAS} group: the attention identifier the terminal last raised, the next program,
 * mapset and map to display, an error message, a return message, and three identifier fields, each
 * of which is declared twice - once as fixed-width text and once as an unsigned numeric
 * redefinition over the very same bytes. Only the five-program family that included this copybook
 * used it, which is why the three identifier fields appear here and not on the wider communication
 * area. The provenance of every source citation in this suite is the checkout at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, whose members carry the upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No copybook text is reproduced here; only
 * field widths, line numbers and contract literals, which are metadata rather than source.
 *
 * <p><strong>This suite owns two acceptance assertions for the whole transfer-object folder.</strong>
 * They are the two that a plausible, compiling, wrong implementation would silently break, so they
 * are pinned here rather than left implicit:
 *
 * <ol>
 *   <li><em>Leading-zero identifiers survive unchanged</em> - see {@link LeadingZeroIdentifiers}.
 *       The three identifiers are fixed-width digit strings whose leading zeros are contractual, and
 *       they must cross both the accessor boundary and the JSON boundary character for character. A
 *       single stripped zero - the inevitable consequence of ever typing one of them as a number -
 *       fails this suite.</li>
 *   <li><em>The four commented-out members are absent</em> - see
 *       {@link CompletePositiveSetAndTheFourDeadMembers}. Four members of the originating copybook
 *       are commented out, and modelling any of them would invent state the legacy programs never
 *       carried across a turn.</li>
 * </ol>
 *
 * <p><strong>The three redefinitions are the interesting part.</strong> The character view is what a
 * screen sends and what a blank field contains; the numeric view is only meaningful when every byte
 * happens to be a digit. Reading the numeric view of a blank or partially alphabetic field on the
 * mainframe yields whatever the bytes happen to mean rather than a number, so the Java replacement
 * reports absence instead. This suite pins that: a value is numeric only when every single character
 * is a digit, and everything else - blanks, embedded blanks, signs, decimal points, and any letter -
 * reports absence rather than guessing.
 *
 * <p><strong>Why absence rather than zero.</strong> Zero is a legitimate identifier value in an
 * unsigned digit field, so a non-numeric field that reported zero would be indistinguishable from a
 * real zero. The suite asserts an all-zero field reports zero and a blank field reports absence,
 * which are two different answers.
 *
 * <p>The suite is a pure unit test: it constructs the record directly, mounts no application
 * context, starts no container and touches no database. Where a JSON assertion is needed it builds
 * its own mapper from the four settings the module declares, so what is asserted is the wire
 * behaviour the application itself is configured for rather than a library default.
 */
@DisplayName("ScreenWorkArea - the shared per-screen work area")
class ScreenWorkAreaTest {

    /** The nine copybook widths in declaration order. */
    private static final List<Integer> COPYBOOK_WIDTHS = List.of(5, 8, 7, 7, 75, 75, 11, 16, 9);

    /** Summed width of the whole work area. */
    private static final int WORK_AREA_WIDTH = 213;

    /**
     * The nine property names that cross the wire, in the order the record declares its components.
     *
     * <p>Named individually rather than derived from the type, because deriving them would only
     * restate whatever the implementation happens to do. Written out, the list is an independent
     * statement of the contract: a renamed component, an added one, or a leaked derived view all
     * change this set and fail the assertion that uses it.</p>
     */
    private static final List<String> WIRE_PROPERTIES = List.of(
            "keyAction", "nextProgram", "nextMapset", "nextMap",
            "errorMessage", "returnMessage", "accountId", "cardNumber", "customerId");

    /**
     * The four members the copybook comments out, named by the property each would have become.
     *
     * <p>Held as data so the assertion that proves their absence reads as a list rather than as four
     * near-identical statements. Their declarations sit at lines 20, 22, 25 and 31 of
     * {@code app/cpy/CVCRD01Y.cpy}; the return-flag and function members additionally carry two
     * condition names each, at lines 26 and 27 and at lines 32 and 33.</p>
     */
    private static final List<String> DEAD_MEMBER_PROPERTIES =
            List.of("lastProgram", "returnToProgram", "returnFlag", "function");

    /**
     * A mapper carrying the four settings the module declares, not the mapper the module itself
     * holds.
     *
     * <p>No application context is started, because this is a unit test and a context would prove
     * something else. The settings come from {@link JsonContractSupport#declaredSettingsMapper()},
     * which is the single place in the test tree where {@code src/main/resources/application.yml} is
     * transcribed: absent properties are omitted rather than emitted as nulls, dates are written as
     * text rather than as epoch numbers, an unrecognised property is tolerated rather than fatal, and
     * a plain decimal is written without an exponent.</p>
     *
     * <p>What it evidences is the shape this type takes under those settings, and nothing more.
     * {@link ApplicationJsonContractTest} compares a mapper obtained from a real context against this
     * very factory, so an edit to the module's file fails there rather than silently making this
     * stand-in unrepresentative.</p>
     */
    private static final ObjectMapper WIRE_MAPPER = JsonContractSupport.declaredSettingsMapper();

    /** The shape a serialized work area is read back into when property names are the subject. */
    private static final TypeReference<LinkedHashMap<String, Object>> WIRE_SHAPE =
            new TypeReference<>() { };

    /**
     * The mandated account identifier: eleven characters, ten of them leading zeros.
     *
     * <p>Chosen so that any numeric handling anywhere on the path collapses it to a single digit and
     * is caught immediately.</p>
     */
    private static final String LEADING_ZERO_ACCOUNT_ID = "00000000001";

    /** The mandated card number: sixteen characters, fifteen of them leading zeros. */
    private static final String LEADING_ZERO_CARD_NUMBER = "0000000000000001";

    /** The mandated customer identifier: nine characters, six of them leading zeros. */
    private static final String LEADING_ZERO_CUSTOMER_ID = "000000042";

    /**
     * Builds a work area whose three identifiers all carry the supplied text, so one assertion can
     * cover all three redefinitions at once.
     *
     * @param identifierText the text to place in all three identifier components
     * @return a populated work area
     */
    private static ScreenWorkArea withIdentifiers(final String identifierText) {
        return new ScreenWorkArea(
                KeyAction.ENTER,
                "COACTUPC",
                "COACTUP",
                "CACTUPA",
                "Account not found",
                "Update successful",
                identifierText,
                identifierText,
                identifierText);
    }

    /**
     * Builds a work area carrying the supplied attention key and nothing else.
     *
     * @param keyAction the attention key, possibly {@code null}
     * @return a work area whose only populated component is the attention key
     */
    private static ScreenWorkArea withKey(final KeyAction keyAction) {
        return new ScreenWorkArea(keyAction, null, null, null, null, null, null, null, null);
    }

    /**
     * Builds a work area carrying the three mandated leading-zero identifiers.
     *
     * @return a work area whose identifiers are the three mandated fixtures
     */
    private static ScreenWorkArea withLeadingZeroIdentifiers() {
        return new ScreenWorkArea(
                KeyAction.ENTER,
                "COCRDLIC",
                "COCRDLI",
                "CCRDLIA",
                null,
                null,
                LEADING_ZERO_ACCOUNT_ID,
                LEADING_ZERO_CARD_NUMBER,
                LEADING_ZERO_CUSTOMER_ID);
    }

    /**
     * Serializes a work area and reads it straight back, so an assertion can be made about what
     * survives a full round trip across the wire.
     *
     * @param area the work area to send and receive
     * @return the work area as it arrives back
     * @throws JsonProcessingException if either direction fails, which fails the calling test with
     *     the mapper's own diagnostic rather than a generic one
     */
    private static ScreenWorkArea roundTrip(final ScreenWorkArea area)
            throws JsonProcessingException {
        return WIRE_MAPPER.readValue(WIRE_MAPPER.writeValueAsString(area), ScreenWorkArea.class);
    }

    /**
     * Serializes a work area and reads it back as a property map, so an assertion can be made about
     * which property names cross the wire and in which order.
     *
     * @param area the work area to serialize
     * @return the serialized properties, in the order the serializer emitted them
     * @throws JsonProcessingException if serialization or the read-back fails
     */
    private static Map<String, Object> wireProperties(final ScreenWorkArea area)
            throws JsonProcessingException {
        return WIRE_MAPPER.readValue(WIRE_MAPPER.writeValueAsString(area), WIRE_SHAPE);
    }

    // COPYBOOK GEOMETRY

    /**
     * Verifies the declared widths against the work area they reproduce.
     */
    @Nested
    @DisplayName("copybook geometry")
    class CopybookGeometry {

        @Test
        @DisplayName("the nine declared widths sum to the work area's own width")
        void theWidthsSumToTheWorkAreaWidth() {
            assertThat(COPYBOOK_WIDTHS).hasSize(9);
            assertThat(COPYBOOK_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(WORK_AREA_WIDTH);
        }

        @Test
        @DisplayName("every published width constant matches its copybook field")
        void everyPublishedWidthMatchesItsCopybookField() {
            assertThat(ScreenWorkArea.ATTENTION_ID_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(0));
            assertThat(ScreenWorkArea.NEXT_PROGRAM_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(1));
            assertThat(ScreenWorkArea.NEXT_MAPSET_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(2));
            assertThat(ScreenWorkArea.NEXT_MAP_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(3));
            assertThat(ScreenWorkArea.ERROR_MESSAGE_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(4));
            assertThat(ScreenWorkArea.RETURN_MESSAGE_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(5));
            assertThat(ScreenWorkArea.ACCOUNT_ID_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(6));
            assertThat(ScreenWorkArea.CARD_NUMBER_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(7));
            assertThat(ScreenWorkArea.CUSTOMER_ID_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(8));
        }

        @Test
        @DisplayName("the two messages share one width, so neither can be told from the other by length")
        void theTwoMessagesShareOneWidth() {
            assertThat(ScreenWorkArea.ERROR_MESSAGE_LENGTH)
                    .isEqualTo(ScreenWorkArea.RETURN_MESSAGE_LENGTH)
                    .isEqualTo(75);
        }

        @Test
        @DisplayName("a mapset name and a map name are both seven characters, one shorter than a program "
                + "name")
        void theMapNamesAreSevenAndTheProgramNameIsEight() {
            assertThat(ScreenWorkArea.NEXT_MAPSET_LENGTH).isEqualTo(ScreenWorkArea.NEXT_MAP_LENGTH);
            assertThat(ScreenWorkArea.NEXT_PROGRAM_LENGTH)
                    .isEqualTo(ScreenWorkArea.NEXT_MAP_LENGTH + 1);
        }

        @Test
        @DisplayName("the three identifier widths are all different, so the three redefinitions cannot be "
                + "interchanged")
        void theThreeIdentifierWidthsAreAllDifferent() {
            assertThat(List.of(
                    ScreenWorkArea.ACCOUNT_ID_LENGTH,
                    ScreenWorkArea.CARD_NUMBER_LENGTH,
                    ScreenWorkArea.CUSTOMER_ID_LENGTH))
                    .doesNotHaveDuplicates()
                    .containsExactly(11, 16, 9);
        }
    }

    // MANDATED ACCEPTANCE ASSERTION: THE COMPLETE POSITIVE SET, AND THE FOUR DEAD MEMBERS

    /**
     * Pins the exact membership of the type: the nine live copybook fields and the three derived
     * numeric views, and nothing else.
     *
     * <p><strong>Why absence is asserted positively.</strong> A missing field cannot be observed
     * directly - run-time type inspection is used nowhere in this module, and asking the compiler
     * about a name that does not exist is not something a test can do while it runs. It can,
     * however, be pinned two other ways, and both are used here. First, by compilation: every accessor this type
     * has is invoked below, so the set of names a reader sees exercised here <em>is</em> the set the
     * type offers, and a future attempt to add one of the dead members would leave it conspicuously
     * unexercised. Second, by behaviour: the wire form is enumerated, and a payload carrying the
     * four dead names is shown to bind to nothing at all.
     *
     * <p><strong>The four members that must stay absent</strong> are commented out in
     * {@code app/cpy/CVCRD01Y.cpy} and are therefore not part of the record layout: the last-program
     * field at line 20, the return-to-program field at line 22, the return-flag field at line 25
     * together with both its condition names at lines 26 and 27, and the function field at line 31
     * together with both its condition names at lines 32 and 33. A mechanical scan of every program
     * in the estate finds zero references to any of the four names and zero to any of their four
     * condition names - all eight matches are the copybook's own commented-out declarations - which
     * is evidence of deliberate abandonment rather than of mere disuse. <strong>None of the four may
     * be reinstated.</strong> Doing so would add state to the pseudo-conversational contract that
     * nothing in the legacy system ever set or read.
     *
     * <p>One near-miss deserves calling out, because it is the trap a reader restoring "the return
     * field" would fall into: the return <em>message</em> at line 29 and its own condition name at
     * line 30 are live and are modelled; the return <em>flag</em> at line 25 and its condition name
     * at line 26 are the dead pair and are not.
     */
    @Nested
    @DisplayName("the complete positive set, and the four members the copybook comments out")
    class CompletePositiveSetAndTheFourDeadMembers {

        @Test
        @DisplayName("all nine live fields are read back exactly as supplied, at the exact widths the "
                + "copybook declares")
        void allNineLiveFieldsAreReadBackExactlyAsSupplied() {
            final String nextProgram = "COACTUPC";
            final String nextMapset = "COACTUP";
            final String nextMap = "CACTUPA";
            final String errorMessage = "E".repeat(ScreenWorkArea.ERROR_MESSAGE_LENGTH);
            final String returnMessage = "R".repeat(ScreenWorkArea.RETURN_MESSAGE_LENGTH);
            final String accountId = "01234567890";
            final String cardNumber = "0123456789012345";
            final String customerId = "012345678";

            final ScreenWorkArea area = new ScreenWorkArea(
                    KeyAction.PFK05, nextProgram, nextMapset, nextMap,
                    errorMessage, returnMessage, accountId, cardNumber, customerId);

            assertThat(area.keyAction()).isEqualTo(KeyAction.PFK05);
            assertThat(area.nextProgram()).isEqualTo(nextProgram);
            assertThat(area.nextMapset()).isEqualTo(nextMapset);
            assertThat(area.nextMap()).isEqualTo(nextMap);
            assertThat(area.errorMessage()).isEqualTo(errorMessage);
            assertThat(area.returnMessage()).isEqualTo(returnMessage);
            assertThat(area.accountId()).isEqualTo(accountId);
            assertThat(area.cardNumber()).isEqualTo(cardNumber);
            assertThat(area.customerId()).isEqualTo(customerId);

            assertThat(area.keyAction().getAid()).hasSize(ScreenWorkArea.ATTENTION_ID_LENGTH);
            assertThat(area.nextProgram()).hasSize(ScreenWorkArea.NEXT_PROGRAM_LENGTH);
            assertThat(area.nextMapset()).hasSize(ScreenWorkArea.NEXT_MAPSET_LENGTH);
            assertThat(area.nextMap()).hasSize(ScreenWorkArea.NEXT_MAP_LENGTH);
            assertThat(area.errorMessage()).hasSize(ScreenWorkArea.ERROR_MESSAGE_LENGTH);
            assertThat(area.returnMessage()).hasSize(ScreenWorkArea.RETURN_MESSAGE_LENGTH);
            assertThat(area.accountId()).hasSize(ScreenWorkArea.ACCOUNT_ID_LENGTH);
            assertThat(area.cardNumber()).hasSize(ScreenWorkArea.CARD_NUMBER_LENGTH);
            assertThat(area.customerId()).hasSize(ScreenWorkArea.CUSTOMER_ID_LENGTH);
        }

        @Test
        @DisplayName("the nine live fields land on nine distinct accessors, so no two of the eight text "
                + "fields could be transposed without detection")
        void theNineLiveFieldsLandOnNineDistinctAccessors() {
            final ScreenWorkArea area = new ScreenWorkArea(
                    KeyAction.CLEAR, "PROGRAM8", "MAPSET7", "MAPPP07",
                    "error message fixture", "return message fixture",
                    "11111111111", "2222222222222222", "333333333");

            assertThat(List.of(
                    area.nextProgram(), area.nextMapset(), area.nextMap(),
                    area.errorMessage(), area.returnMessage(),
                    area.accountId(), area.cardNumber(), area.customerId()))
                    .hasSize(8)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the same construction exercises all five derived views, which are computed rather "
                + "than stored and therefore add no field of their own")
        void theSameConstructionExercisesAllFiveDerivedViews() {
            final ScreenWorkArea area = new ScreenWorkArea(
                    KeyAction.PFK12, "COCRDSLC", "COCRDSL", "CCRDSLA",
                    "Card not found", "Returning to card list",
                    "00000000019", "0000000000000029", "000000039");

            assertThat(area.attentionKey()).contains(KeyAction.PFK12);
            assertThat(area.attentionIdText()).contains("PFK12");
            assertThat(area.accountIdNumeric()).contains(BigInteger.valueOf(19L));
            assertThat(area.cardNumberNumeric()).contains(BigInteger.valueOf(29L));
            assertThat(area.customerIdNumeric()).contains(BigInteger.valueOf(39L));

            assertThat(area.accountId()).isEqualTo("00000000019");
            assertThat(area.cardNumber()).isEqualTo("0000000000000029");
            assertThat(area.customerId()).isEqualTo("000000039");
        }

        @Test
        @DisplayName("exactly the nine live fields cross the wire, in copybook declaration order, so no "
                + "tenth field exists and no derived view leaks into the contract")
        void exactlyTheNineLiveFieldsCrossTheWire() throws JsonProcessingException {
            final ScreenWorkArea area = new ScreenWorkArea(
                    KeyAction.ENTER, "COACTVWC", "COACTVW", "CACTVWA",
                    "an error", "a return message",
                    "00000000001", "0000000000000001", "000000001");

            assertThat(wireProperties(area).keySet())
                    .hasSize(9)
                    .containsExactlyElementsOf(WIRE_PROPERTIES);
        }

        @Test
        @DisplayName("none of the four commented-out members appears on the wire, because none of them is "
                + "a field of this type")
        void noneOfTheFourCommentedOutMembersAppearsOnTheWire() throws JsonProcessingException {
            final Map<String, Object> properties = wireProperties(withIdentifiers("123456789"));

            assertThat(properties.keySet()).doesNotContainAnyElementsOf(DEAD_MEMBER_PROPERTIES);
        }

        @Test
        @DisplayName("an inbound payload carrying all four commented-out members binds none of them while "
                + "the live fields alongside them still bind, which is what proves the four are absent")
        void anInboundPayloadCarryingTheFourDeadMembersBindsNoneOfThem()
                throws JsonProcessingException {
            final String payload = """
                    {
                      "accountId": "00000000001",
                      "cardNumber": "0000000000000001",
                      "customerId": "000000042",
                      "lastProgram": "COMEN01C",
                      "returnToProgram": "COMEN01C",
                      "returnFlag": "1",
                      "function": "1"
                    }""";

            final ScreenWorkArea area = WIRE_MAPPER.readValue(payload, ScreenWorkArea.class);

            assertThat(area.accountId()).isEqualTo(LEADING_ZERO_ACCOUNT_ID);
            assertThat(area.cardNumber()).isEqualTo(LEADING_ZERO_CARD_NUMBER);
            assertThat(area.customerId()).isEqualTo(LEADING_ZERO_CUSTOMER_ID);

            assertThat(area)
                    .isEqualTo(new ScreenWorkArea(
                            null, null, null, null, null, null,
                            LEADING_ZERO_ACCOUNT_ID, LEADING_ZERO_CARD_NUMBER,
                            LEADING_ZERO_CUSTOMER_ID));

            assertThat(wireProperties(area).keySet())
                    .containsExactly("accountId", "cardNumber", "customerId");
        }

        @Test
        @DisplayName("the rendering names the nine live fields and none of the four commented-out ones, so "
                + "a reader of a log line cannot mistake a dead member for a live one")
        void theRenderingNamesTheNineLiveFieldsAndNoneOfTheDeadOnes() {
            final String rendered = withIdentifiers("123456789").toString();

            assertThat(rendered)
                    .startsWith("ScreenWorkArea[")
                    .endsWith("]")
                    .contains(
                            "keyAction=", "nextProgram=", "nextMapset=", "nextMap=",
                            "errorMessage=", "returnMessage=", "accountId=", "cardNumber=",
                            "customerId=")
                    .doesNotContain("lastProgram", "returnToProgram", "returnFlag", "function=");
        }

        @Test
        @DisplayName("the live return message is modelled while the commented-out return flag is not, "
                + "which is the distinction a reader restoring the wrong one would miss")
        void theLiveReturnMessageIsModelledWhileTheDeadReturnFlagIsNot()
                throws JsonProcessingException {
            final ScreenWorkArea area = new ScreenWorkArea(
                    null, null, null, null, null, "Returning to the account screen",
                    null, null, null);

            assertThat(area.returnMessage()).isEqualTo("Returning to the account screen");
            assertThat(wireProperties(area).keySet()).containsExactly("returnMessage");
            assertThat(wireProperties(area).keySet()).doesNotContain("returnFlag");
        }
    }

    // MANDATED ACCEPTANCE ASSERTION: LEADING-ZERO IDENTIFIERS ROUND-TRIP UNCHANGED

    /**
     * Pins that a fixed-width identifier keeps every one of its leading zeros, both when read back
     * from the record and when it has crossed the wire and come home again.
     *
     * <p><strong>Why this is the assertion that matters most.</strong> All three identifiers are
     * declared in the copybook as character fields, blank-initialised, with an unsigned numeric
     * redefinition laid over the same bytes. The character form is the contract: an eleven-character
     * account identifier is eleven characters on a screen, in a record image and in a key, and the
     * zeros in front of the significant digits occupy real positions. Typing any of the three as a
     * number in Java - or letting a serializer infer a number from a digit string - discards those
     * positions and produces a value that no longer matches the record it came from. The failure is
     * quiet: arithmetic still works, comparisons against other numbers still work, and only the
     * width is wrong, which is exactly why it is pinned by an explicit assertion here rather than
     * left to be noticed downstream.
     *
     * <p>Every comparison below is made on the {@code String} with {@code isEqualTo}. None is made
     * numerically, because a numeric comparison is precisely the comparison that cannot tell
     * {@code "00000000001"} from {@code "1"} and would therefore pass against the very defect this
     * suite exists to catch.
     */
    @Nested
    @DisplayName("leading-zero identifiers round-trip unchanged")
    class LeadingZeroIdentifiers {

        @Test
        @DisplayName("each accessor hands back the identifier character for character, leading zeros "
                + "included")
        void eachAccessorHandsBackTheIdentifierCharacterForCharacter() {
            final ScreenWorkArea area = withLeadingZeroIdentifiers();

            assertThat(area.accountId()).isEqualTo(LEADING_ZERO_ACCOUNT_ID);
            assertThat(area.cardNumber()).isEqualTo(LEADING_ZERO_CARD_NUMBER);
            assertThat(area.customerId()).isEqualTo(LEADING_ZERO_CUSTOMER_ID);
        }

        @Test
        @DisplayName("each identifier still occupies its full declared width, so not one leading position "
                + "was dropped")
        void eachIdentifierStillOccupiesItsFullDeclaredWidth() {
            final ScreenWorkArea area = withLeadingZeroIdentifiers();

            assertThat(area.accountId()).hasSize(ScreenWorkArea.ACCOUNT_ID_LENGTH);
            assertThat(area.cardNumber()).hasSize(ScreenWorkArea.CARD_NUMBER_LENGTH);
            assertThat(area.customerId()).hasSize(ScreenWorkArea.CUSTOMER_ID_LENGTH);
        }

        @Test
        @DisplayName("a serialize and deserialize round trip returns the very same characters")
        void aRoundTripReturnsTheVerySameCharacters() throws JsonProcessingException {
            final ScreenWorkArea returned = roundTrip(withLeadingZeroIdentifiers());

            assertThat(returned.accountId()).isEqualTo(LEADING_ZERO_ACCOUNT_ID);
            assertThat(returned.cardNumber()).isEqualTo(LEADING_ZERO_CARD_NUMBER);
            assertThat(returned.customerId()).isEqualTo(LEADING_ZERO_CUSTOMER_ID);
        }

        @Test
        @DisplayName("a round trip preserves the declared widths, which is the assertion a single stripped "
                + "zero fails")
        void aRoundTripPreservesTheDeclaredWidths() throws JsonProcessingException {
            final ScreenWorkArea returned = roundTrip(withLeadingZeroIdentifiers());

            assertThat(returned.accountId()).hasSize(ScreenWorkArea.ACCOUNT_ID_LENGTH);
            assertThat(returned.cardNumber()).hasSize(ScreenWorkArea.CARD_NUMBER_LENGTH);
            assertThat(returned.customerId()).hasSize(ScreenWorkArea.CUSTOMER_ID_LENGTH);
        }

        @Test
        @DisplayName("the whole work area survives the round trip intact, so nothing else was rewritten "
                + "either")
        void theWholeWorkAreaSurvivesTheRoundTripIntact() throws JsonProcessingException {
            final ScreenWorkArea sent = withLeadingZeroIdentifiers();

            assertThat(roundTrip(sent)).isEqualTo(sent).hasSameHashCodeAs(sent);
        }

        @Test
        @DisplayName("each identifier is written as quoted text rather than as a number, which is what "
                + "keeps the leading zeros on the wire in the first place")
        void eachIdentifierIsWrittenAsQuotedTextRatherThanAsANumber() throws JsonProcessingException {
            final String json = WIRE_MAPPER.writeValueAsString(withLeadingZeroIdentifiers());

            assertThat(json)
                    .contains("\"accountId\":\"" + LEADING_ZERO_ACCOUNT_ID + "\"")
                    .contains("\"cardNumber\":\"" + LEADING_ZERO_CARD_NUMBER + "\"")
                    .contains("\"customerId\":\"" + LEADING_ZERO_CUSTOMER_ID + "\"");
        }

        @Test
        @DisplayName("the values arrive back as text, not as numbers, when the wire form is inspected "
                + "property by property")
        void theValuesArriveBackAsTextNotAsNumbers() throws JsonProcessingException {
            final Map<String, Object> properties = wireProperties(withLeadingZeroIdentifiers());

            assertThat(properties)
                    .containsEntry("accountId", LEADING_ZERO_ACCOUNT_ID)
                    .containsEntry("cardNumber", LEADING_ZERO_CARD_NUMBER)
                    .containsEntry("customerId", LEADING_ZERO_CUSTOMER_ID);
            assertThat(properties.get("accountId")).isInstanceOf(String.class);
            assertThat(properties.get("cardNumber")).isInstanceOf(String.class);
            assertThat(properties.get("customerId")).isInstanceOf(String.class);
        }

        @Test
        @DisplayName("the numeric view drops the leading zeros while the text keeps them, which is the "
                + "whole point of storing the text and deriving the number")
        void theNumericViewDropsTheZerosWhileTheTextKeepsThem() throws JsonProcessingException {
            final ScreenWorkArea returned = roundTrip(withLeadingZeroIdentifiers());

            assertThat(returned.accountIdNumeric()).contains(BigInteger.ONE);
            assertThat(returned.cardNumberNumeric()).contains(BigInteger.ONE);
            assertThat(returned.customerIdNumeric()).contains(BigInteger.valueOf(42L));

            assertThat(returned.accountId()).isEqualTo(LEADING_ZERO_ACCOUNT_ID).isNotEqualTo("1");
            assertThat(returned.cardNumber()).isEqualTo(LEADING_ZERO_CARD_NUMBER).isNotEqualTo("1");
            assertThat(returned.customerId()).isEqualTo(LEADING_ZERO_CUSTOMER_ID).isNotEqualTo("42");
        }

        @Test
        @DisplayName("two identifiers differing only in leading zeros remain distinct values even though "
                + "they share one numeric view")
        void twoIdentifiersDifferingOnlyInLeadingZerosRemainDistinct() throws JsonProcessingException {
            final ScreenWorkArea padded = withIdentifiers("000000001");
            final ScreenWorkArea unpadded = withIdentifiers("1");

            assertThat(padded).isNotEqualTo(unpadded);
            assertThat(padded.accountIdNumeric()).isEqualTo(unpadded.accountIdNumeric());
            assertThat(roundTrip(padded).accountId()).isEqualTo("000000001");
            assertThat(roundTrip(unpadded).accountId()).isEqualTo("1");
        }

        @Test
        @DisplayName("an all-zero identifier keeps every position it was given, because zero is a value "
                + "and not an absence")
        void anAllZeroIdentifierKeepsEveryPositionItWasGiven() throws JsonProcessingException {
            final String allZeroAccountId = "0".repeat(ScreenWorkArea.ACCOUNT_ID_LENGTH);
            final ScreenWorkArea returned = roundTrip(new ScreenWorkArea(
                    null, null, null, null, null, null, allZeroAccountId, null, null));

            assertThat(returned.accountId())
                    .isEqualTo(allZeroAccountId)
                    .hasSize(ScreenWorkArea.ACCOUNT_ID_LENGTH);
            assertThat(returned.accountIdNumeric()).contains(BigInteger.ZERO);
        }
    }

    // THE ATTENTION IDENTIFIER: PADDING IS PART OF THE VALUE

    /**
     * Pins the padding of the five-character attention identifier, which is data rather than
     * formatting.
     *
     * <p>The underlying field is five characters wide, so each of the sixteen condition-name literals
     * declared beneath it is exactly five characters long. Two of them are mnemonics of three
     * characters and are therefore <em>padded in the copybook literal itself</em> with two trailing
     * spaces; the twelve program-function identifiers zero-pad their numeric suffix to two digits for
     * the same reason. Both kinds of padding occupy real positions in a fixed-width area, so trimming
     * either one produces a value that no longer matches the field it came from and that the legacy
     * lookup would not have recognised.
     *
     * <p>The lookup is the sharpest available evidence that the padding is load-bearing: it matches
     * exactly, so the trimmed mnemonic and the unpadded function-key form both fail to resolve. Those
     * negative cases are asserted alongside the positive ones, because a lookup that quietly accepted
     * the shortened forms would be tolerating exactly the normalisation this contract forbids.
     */
    @Nested
    @DisplayName("the attention identifier, whose padding is part of the value")
    class AttentionIdentifierPadding {

        @Test
        @DisplayName("the two program-attention identifiers keep their two trailing spaces and are not "
                + "trimmed")
        void theTwoProgramAttentionIdentifiersKeepTheirTrailingSpaces() {
            assertThat(withKey(KeyAction.PA1).attentionIdText()).contains("PA1  ");
            assertThat(withKey(KeyAction.PA2).attentionIdText()).contains("PA2  ");

            assertThat(withKey(KeyAction.PA1).attentionIdText().orElseThrow())
                    .isEqualTo("PA1  ")
                    .hasSize(ScreenWorkArea.ATTENTION_ID_LENGTH)
                    .endsWith("  ");
            assertThat(withKey(KeyAction.PA2).attentionIdText().orElseThrow())
                    .isEqualTo("PA2  ")
                    .hasSize(ScreenWorkArea.ATTENTION_ID_LENGTH)
                    .endsWith("  ");
        }

        @Test
        @DisplayName("a trimmed program-attention mnemonic resolves to nothing, which is what proves the "
                + "two trailing spaces are load-bearing rather than incidental")
        void aTrimmedProgramAttentionMnemonicResolvesToNothing() {
            assertThat(KeyAction.fromAid("PA1")).isEmpty();
            assertThat(KeyAction.fromAid("PA2")).isEmpty();

            assertThat(KeyAction.fromAid("PA1  ")).contains(KeyAction.PA1);
            assertThat(KeyAction.fromAid("PA2  ")).contains(KeyAction.PA2);
        }

        @Test
        @DisplayName("the first program-function identifier is zero-padded to two digits rather than left "
                + "at one")
        void theFirstProgramFunctionIdentifierIsZeroPadded() {
            assertThat(withKey(KeyAction.PFK01).attentionIdText()).contains("PFK01");

            assertThat(withKey(KeyAction.PFK01).attentionIdText().orElseThrow())
                    .isEqualTo("PFK01")
                    .isNotEqualTo("PFK1")
                    .hasSize(ScreenWorkArea.ATTENTION_ID_LENGTH);
            assertThat(KeyAction.fromAid("PFK1")).isEmpty();
            assertThat(KeyAction.fromAid("PFK01")).contains(KeyAction.PFK01);
        }

        @Test
        @DisplayName("every single-digit program-function identifier is zero-padded, and none of the "
                + "unpadded forms resolves")
        void everySingleDigitProgramFunctionIdentifierIsZeroPadded() {
            for (int key = 1; key <= 9; key++) {
                final String padded = "PFK0" + key;
                final String unpadded = "PFK" + key;

                assertThat(KeyAction.fromAid(padded)).as("padded %s", padded).isPresent();
                assertThat(KeyAction.fromAid(unpadded)).as("unpadded %s", unpadded).isEmpty();
            }
        }

        @Test
        @DisplayName("the two-digit program-function identifiers need no padding and fill the field on "
                + "their own")
        void theTwoDigitProgramFunctionIdentifiersNeedNoPadding() {
            for (final String identifier : List.of("PFK10", "PFK11", "PFK12")) {
                assertThat(KeyAction.fromAid(identifier)).as("identifier %s", identifier).isPresent();
                assertThat(identifier).hasSize(ScreenWorkArea.ATTENTION_ID_LENGTH);
            }
        }

        @Test
        @DisplayName("an unrecognised identifier resolves to nothing without throwing, because the legacy "
                + "mapping has no catch-all clause and leaves the held value intact")
        void anUnrecognisedIdentifierResolvesToNothingWithoutThrowing() {
            for (final String unrecognised : List.of("PFK99", "     ", "enter", "CLEA", "CLEARX")) {
                assertThatCode(() -> KeyAction.fromAid(unrecognised)).doesNotThrowAnyException();
                assertThat(KeyAction.fromAid(unrecognised)).as("identifier %s", unrecognised).isEmpty();
            }

            assertThatCode(() -> KeyAction.fromAid(null)).doesNotThrowAnyException();
            assertThat(KeyAction.fromAid(null)).isEmpty();
        }

        @Test
        @DisplayName("no identifier resolves to a substitute, so there is no constant standing for "
                + "unrecognised, none, other, invalid, unmapped or default")
        void noIdentifierResolvesToASubstitute() {
            for (final String absentName : List.of(
                    "UNKNOWN", "NONE", "OTHER", "INVALID", "UNMAPPED", "DEFAULT")) {
                assertThat(KeyAction.fromAid(absentName)).as("name %s", absentName).isEmpty();
            }

            final List<String> declaredNames = new ArrayList<>();
            for (final KeyAction key : KeyAction.values()) {
                declaredNames.add(key.name());
            }
            assertThat(declaredNames)
                    .hasSize(16)
                    .containsExactly(
                            "ENTER", "CLEAR", "PA1", "PA2",
                            "PFK01", "PFK02", "PFK03", "PFK04", "PFK05", "PFK06",
                            "PFK07", "PFK08", "PFK09", "PFK10", "PFK11", "PFK12");
        }

        @Test
        @DisplayName("the raised key survives a round trip and still reports its padded five-character "
                + "identifier afterwards")
        void theRaisedKeySurvivesARoundTripAndStillReportsItsPaddedIdentifier()
                throws JsonProcessingException {
            for (final KeyAction key : KeyAction.values()) {
                final ScreenWorkArea returned = roundTrip(withKey(key));

                assertThat(returned.keyAction()).as("key %s", key).isEqualTo(key);
                assertThat(returned.attentionIdText())
                        .as("key %s", key)
                        .contains(key.getAid());
                assertThat(returned.attentionIdText().orElseThrow())
                        .as("key %s", key)
                        .hasSize(ScreenWorkArea.ATTENTION_ID_LENGTH);
            }
        }

        @Test
        @DisplayName("the twelve program-function keys are distinguished from the four others, and keys "
                + "beyond the twelfth simply do not exist")
        void theTwelveProgramFunctionKeysAreDistinguishedFromTheFourOthers() {
            final List<KeyAction> functionKeys = new ArrayList<>();
            final List<KeyAction> others = new ArrayList<>();
            for (final KeyAction key : KeyAction.values()) {
                if (key.isProgramFunctionKey()) {
                    functionKeys.add(key);
                } else {
                    others.add(key);
                }
            }

            assertThat(functionKeys).hasSize(12);
            assertThat(others).containsExactly(
                    KeyAction.ENTER, KeyAction.CLEAR, KeyAction.PA1, KeyAction.PA2);

            for (int key = 13; key <= 24; key++) {
                final String beyondTheTwelfth = "PFK" + key;

                assertThat(KeyAction.fromAid(beyondTheTwelfth))
                        .as("identifier %s", beyondTheTwelfth)
                        .isEmpty();
            }
        }
    }

    // THE ATTENTION KEY

    /**
     * Verifies the attention identifier the terminal last raised.
     */
    @Nested
    @DisplayName("the attention key")
    class AttentionKey {

        @Test
        @DisplayName("a present key is reported together with its five-character identifier")
        void aPresentKeyIsReportedWithItsIdentifier() {
            final ScreenWorkArea area = withKey(KeyAction.PFK03);

            assertThat(area.attentionKey()).contains(KeyAction.PFK03);
            assertThat(area.attentionIdText()).contains(KeyAction.PFK03.getAid());
        }

        @Test
        @DisplayName("an absent key reports absence for both the key and its identifier, rather than a "
                + "blank identifier")
        void anAbsentKeyReportsAbsenceForBoth() {
            final ScreenWorkArea area = withKey(null);

            assertThat(area.keyAction()).isNull();
            assertThat(area.attentionKey()).isEmpty();
            assertThat(area.attentionIdText()).isEmpty();
        }

        @Test
        @DisplayName("every declared key reports an identifier of exactly the copybook width")
        void everyDeclaredKeyReportsAnIdentifierOfTheCopybookWidth() {
            for (final KeyAction key : KeyAction.values()) {
                assertThat(withKey(key).attentionIdText()).as("key %s", key).isPresent();
                assertThat(withKey(key).attentionIdText().orElseThrow())
                        .as("key %s", key)
                        .hasSize(ScreenWorkArea.ATTENTION_ID_LENGTH);
            }
        }

        @Test
        @DisplayName("each of the sixteen declared keys carries exactly the five-character identifier "
                + "the copybook gives it, trailing spaces included")
        void eachDeclaredKeyCarriesExactlyTheCopybookIdentifier() {
            final Map<KeyAction, String> expected = new LinkedHashMap<>();
            expected.put(KeyAction.ENTER, "ENTER");
            expected.put(KeyAction.CLEAR, "CLEAR");
            expected.put(KeyAction.PA1, "PA1  ");
            expected.put(KeyAction.PA2, "PA2  ");
            expected.put(KeyAction.PFK01, "PFK01");
            expected.put(KeyAction.PFK02, "PFK02");
            expected.put(KeyAction.PFK03, "PFK03");
            expected.put(KeyAction.PFK04, "PFK04");
            expected.put(KeyAction.PFK05, "PFK05");
            expected.put(KeyAction.PFK06, "PFK06");
            expected.put(KeyAction.PFK07, "PFK07");
            expected.put(KeyAction.PFK08, "PFK08");
            expected.put(KeyAction.PFK09, "PFK09");
            expected.put(KeyAction.PFK10, "PFK10");
            expected.put(KeyAction.PFK11, "PFK11");
            expected.put(KeyAction.PFK12, "PFK12");

            assertThat(expected.keySet())
                    .as("the table names every declared key and no other")
                    .containsExactly(KeyAction.values());

            for (final Map.Entry<KeyAction, String> entry : expected.entrySet()) {
                final KeyAction key = entry.getKey();
                final String identifier = entry.getValue();

                assertThat(identifier)
                        .as("declared width of %s", key)
                        .hasSize(ScreenWorkArea.ATTENTION_ID_LENGTH);
                assertThat(key.getAid())
                        .as("identifier carried by %s", key)
                        .isEqualTo(identifier);
                assertThat(KeyAction.fromAid(identifier))
                        .as("lookup of the identifier carried by %s", key)
                        .contains(key);
                assertThat(withKey(key).attentionIdText())
                        .as("work area holding %s", key)
                        .contains(identifier);
            }
        }

        @Test
        @DisplayName("the reported identifier round-trips back to the same key for every declared key")
        void theReportedIdentifierRoundTripsBackToTheSameKey() {
            for (final KeyAction key : KeyAction.values()) {
                final String identifier = withKey(key).attentionIdText().orElseThrow();

                assertThat(KeyAction.fromAid(identifier)).as("key %s", key).contains(key);
            }
        }

        @Test
        @DisplayName("the reported identifiers are all distinct, so the raised key is recoverable from the "
                + "text alone")
        void theReportedIdentifiersAreAllDistinct() {
            final List<String> identifiers = new ArrayList<>();
            for (final KeyAction key : KeyAction.values()) {
                identifiers.add(withKey(key).attentionIdText().orElseThrow());
            }

            assertThat(identifiers)
                    .hasSize(KeyAction.values().length)
                    .doesNotHaveDuplicates();
        }
    }

    // THE TWO SEVENTY-FIVE CHARACTER MESSAGES

    /**
     * Pins the two message lines as independent seventy-five character fields.
     *
     * <p>The copybook declares the error message at line 28 and the return message at line 29, both
     * seventy-five characters wide, and gives the return message its own condition name at line 30
     * reading an unset field as off. The two widths coinciding is a fact about the legacy structure
     * rather than a reason to model them as one field: they are populated at different moments by
     * different code paths, one to report a failed edit and one to carry text back to the program
     * being returned to, so conflating them would put a validation failure where an operator expected
     * a confirmation.
     *
     * <p>Seventy-five is also deliberately <em>not</em> the width of the map-level error fields, which
     * are wider. The narrower copybook width is reproduced exactly and is never rounded up to match
     * the maps, because message text is an external contract that operators and downstream tooling
     * match on.
     *
     * <p>Message text is space padded to the field width, and that padding is carried rather than
     * stripped, so a value already at full width comes back at full width. Every assertion below
     * therefore compares the padded value untrimmed.
     */
    @Nested
    @DisplayName("the two seventy-five character messages")
    class SeventyFiveCharacterMessages {

        @Test
        @DisplayName("a message padded to the full declared width is carried untrimmed in both fields")
        void aMessagePaddedToTheFullWidthIsCarriedUntrimmed() {
            final String paddedError = padded("Account not found", ScreenWorkArea.ERROR_MESSAGE_LENGTH);
            final String paddedReturn =
                    padded("Update successful", ScreenWorkArea.RETURN_MESSAGE_LENGTH);

            final ScreenWorkArea area = new ScreenWorkArea(
                    KeyAction.ENTER, null, null, null, paddedError, paddedReturn, null, null, null);

            assertThat(area.errorMessage())
                    .isEqualTo(paddedError)
                    .hasSize(ScreenWorkArea.ERROR_MESSAGE_LENGTH)
                    .endsWith(" ");
            assertThat(area.returnMessage())
                    .isEqualTo(paddedReturn)
                    .hasSize(ScreenWorkArea.RETURN_MESSAGE_LENGTH)
                    .endsWith(" ");
        }

        @Test
        @DisplayName("a padded message survives a round trip with its trailing spaces intact")
        void aPaddedMessageSurvivesARoundTripWithItsTrailingSpacesIntact()
                throws JsonProcessingException {
            final String paddedError = padded("Did not find this account", 75);
            final String paddedReturn = padded("Returning to the previous screen", 75);

            final ScreenWorkArea returned = roundTrip(new ScreenWorkArea(
                    null, null, null, null, paddedError, paddedReturn, null, null, null));

            assertThat(returned.errorMessage()).isEqualTo(paddedError).hasSize(75);
            assertThat(returned.returnMessage()).isEqualTo(paddedReturn).hasSize(75);
        }

        @Test
        @DisplayName("the two messages are never conflated, so a value placed in one is absent from the "
                + "other")
        void theTwoMessagesAreNeverConflated() {
            final ScreenWorkArea onlyError = new ScreenWorkArea(
                    null, null, null, null, "an error and nothing else", null, null, null, null);
            final ScreenWorkArea onlyReturn = new ScreenWorkArea(
                    null, null, null, null, null, "a return message and nothing else",
                    null, null, null);

            assertThat(onlyError.errorMessage()).isEqualTo("an error and nothing else");
            assertThat(onlyError.returnMessage()).isNull();
            assertThat(onlyReturn.returnMessage()).isEqualTo("a return message and nothing else");
            assertThat(onlyReturn.errorMessage()).isNull();
            assertThat(onlyError).isNotEqualTo(onlyReturn);
        }

        @Test
        @DisplayName("each message keeps its own property on the wire, so neither can arrive as the other")
        void eachMessageKeepsItsOwnPropertyOnTheWire() throws JsonProcessingException {
            final ScreenWorkArea area = new ScreenWorkArea(
                    null, null, null, null, "the error text", "the return text", null, null, null);

            assertThat(wireProperties(area))
                    .containsEntry("errorMessage", "the error text")
                    .containsEntry("returnMessage", "the return text");
            assertThat(roundTrip(area).errorMessage()).isEqualTo("the error text");
            assertThat(roundTrip(area).returnMessage()).isEqualTo("the return text");
        }

        @Test
        @DisplayName("neither width is normalised up to a wider map-level error field")
        void neitherWidthIsNormalisedToAWiderMapLevelField() {
            assertThat(ScreenWorkArea.ERROR_MESSAGE_LENGTH).isEqualTo(75).isNotIn(78, 80);
            assertThat(ScreenWorkArea.RETURN_MESSAGE_LENGTH).isEqualTo(75).isNotIn(78, 80);
        }

        @Test
        @DisplayName("an absent message is absent rather than blank, which is how the off state of the "
                + "return message is expressed")
        void anAbsentMessageIsAbsentRatherThanBlank() throws JsonProcessingException {
            final ScreenWorkArea area = new ScreenWorkArea(
                    KeyAction.ENTER, null, null, null, null, null, null, null, null);

            assertThat(area.errorMessage()).isNull();
            assertThat(area.returnMessage()).isNull();
            assertThat(wireProperties(area).keySet()).containsExactly("keyAction");
        }

        /**
         * Pads a message to a field width with trailing spaces, the way a fixed-width field holds it.
         *
         * @param text the message text
         * @param width the declared field width, which must not be narrower than the text
         * @return the text followed by enough spaces to fill the field exactly
         */
        private String padded(final String text, final int width) {
            return text + " ".repeat(width - text.length());
        }
    }

    // THE NUMERIC REDEFINITIONS

    /**
     * Verifies the three numeric views over the three character identifier fields.
     *
     * <p>Each view is derived on every call from the stored text rather than held as a second field,
     * which is what a redefinition guarantees: two ways of reading one storage area cannot disagree.
     * Every view is total - it reports absence for a missing, empty, blank or otherwise non-digit
     * value and never raises - because all three fields are blank-initialised in the copybook, so a
     * blank identifier is an ordinary state rather than an error.
     */
    @Nested
    @DisplayName("the numeric redefinitions")
    class NumericRedefinitions {

        @Test
        @DisplayName("an all-digit value is reported as its own magnitude by all three views")
        void anAllDigitValueIsReportedAsItsMagnitude() {
            final ScreenWorkArea area = withIdentifiers("123456789");

            assertThat(area.accountIdNumeric()).contains(new BigInteger("123456789"));
            assertThat(area.cardNumberNumeric()).contains(new BigInteger("123456789"));
            assertThat(area.customerIdNumeric()).contains(new BigInteger("123456789"));
        }

        @Test
        @DisplayName("each view reads only its own component")
        void eachViewReadsOnlyItsOwnComponent() {
            final ScreenWorkArea area = new ScreenWorkArea(
                    KeyAction.ENTER, null, null, null, null, null,
                    "00000000011", "4111111111111111", "000000021");

            assertThat(area.accountIdNumeric()).contains(BigInteger.valueOf(11L));
            assertThat(area.cardNumberNumeric()).contains(new BigInteger("4111111111111111"));
            assertThat(area.customerIdNumeric()).contains(BigInteger.valueOf(21L));
        }

        @Test
        @DisplayName("leading zeros are absorbed by the magnitude but never change it")
        void leadingZerosAreAbsorbedButNeverChangeTheMagnitude() {
            for (final String padded : List.of("11", "011", "0011", "00000000011")) {
                assertThat(withIdentifiers(padded).accountIdNumeric())
                        .as("value %s", padded)
                        .contains(BigInteger.valueOf(11L));
            }
        }

        @Test
        @DisplayName("an all-zero value reports zero, which a blank value must not")
        void anAllZeroValueReportsZero() {
            assertThat(withIdentifiers("00000000000").accountIdNumeric())
                    .contains(BigInteger.ZERO);
            assertThat(withIdentifiers("           ").accountIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("a blank-initialised field reports absence at every width the copybook declares, "
                + "without throwing")
        void aBlankInitialisedFieldReportsAbsence() {
            final ScreenWorkArea area = new ScreenWorkArea(
                    KeyAction.ENTER, null, null, null, null, null,
                    " ".repeat(ScreenWorkArea.ACCOUNT_ID_LENGTH),
                    " ".repeat(ScreenWorkArea.CARD_NUMBER_LENGTH),
                    " ".repeat(ScreenWorkArea.CUSTOMER_ID_LENGTH));

            assertThatCode(area::accountIdNumeric).doesNotThrowAnyException();
            assertThatCode(area::cardNumberNumeric).doesNotThrowAnyException();
            assertThatCode(area::customerIdNumeric).doesNotThrowAnyException();

            assertThat(area.accountIdNumeric()).isEmpty();
            assertThat(area.cardNumberNumeric()).isEmpty();
            assertThat(area.customerIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("an absent component reports absence without throwing")
        void anAbsentComponentReportsAbsence() {
            final ScreenWorkArea area = withIdentifiers(null);

            assertThatCode(area::accountIdNumeric).doesNotThrowAnyException();
            assertThatCode(area::cardNumberNumeric).doesNotThrowAnyException();
            assertThatCode(area::customerIdNumeric).doesNotThrowAnyException();

            assertThat(area.accountIdNumeric()).isEmpty();
            assertThat(area.cardNumberNumeric()).isEmpty();
            assertThat(area.customerIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("an empty component reports absence without throwing")
        void anEmptyComponentReportsAbsence() {
            final ScreenWorkArea area = withIdentifiers("");

            assertThatCode(area::accountIdNumeric).doesNotThrowAnyException();
            assertThatCode(area::cardNumberNumeric).doesNotThrowAnyException();
            assertThatCode(area::customerIdNumeric).doesNotThrowAnyException();

            assertThat(area.accountIdNumeric()).isEmpty();
            assertThat(area.cardNumberNumeric()).isEmpty();
            assertThat(area.customerIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("a single non-digit anywhere in the value defeats the numeric view, wherever it sits, "
                + "and still does not throw")
        void aSingleNonDigitAnywhereDefeatsTheNumericView() {
            for (final String value : List.of("X23456789", "1234X6789", "12345678X")) {
                final ScreenWorkArea area = withIdentifiers(value);

                assertThatCode(area::accountIdNumeric).as("value %s", value)
                        .doesNotThrowAnyException();
                assertThat(area.accountIdNumeric()).as("value %s", value).isEmpty();
            }
        }

        @Test
        @DisplayName("an embedded blank defeats the numeric view even when every other byte is a digit")
        void anEmbeddedBlankDefeatsTheNumericView() {
            assertThat(withIdentifiers("1234 6789").accountIdNumeric()).isEmpty();
            assertThat(withIdentifiers("     6789").accountIdNumeric()).isEmpty();
            assertThat(withIdentifiers("1234     ").accountIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("a sign or a decimal point defeats the numeric view, because the copybook field is "
                + "unsigned and has no decimal places")
        void aSignOrDecimalPointDefeatsTheNumericView() {
            for (final String value : List.of("-123456789", "+123456789", "1234.6789", "1234,6789")) {
                assertThat(withIdentifiers(value).accountIdNumeric())
                        .as("value %s", value)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("a value at the widest declared width is reported exactly, with no loss")
        void aValueAtTheWidestWidthIsReportedExactly() {
            final String widest = "9".repeat(ScreenWorkArea.CARD_NUMBER_LENGTH);

            assertThat(withIdentifiers(widest).cardNumberNumeric())
                    .contains(new BigInteger(widest));
            assertThat(withIdentifiers(widest).cardNumberNumeric().orElseThrow().toString())
                    .hasSize(ScreenWorkArea.CARD_NUMBER_LENGTH);
        }

        @Test
        @DisplayName("a non-ASCII digit is not accepted, so only the characters the copybook permits count")
        void aNonAsciiDigitIsNotAccepted() {
            final String arabicIndicDigits = "\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669";

            assertThat(withIdentifiers(arabicIndicDigits).accountIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("the numeric view is computed on every call rather than held in a second field, which "
                + "is what makes it impossible for the text and the number to disagree")
        void theNumericViewIsComputedOnEveryCallRatherThanStored() {
            final ScreenWorkArea area = withIdentifiers("000000123");

            assertThat(area.accountIdNumeric())
                    .isEqualTo(area.accountIdNumeric())
                    .isNotSameAs(area.accountIdNumeric());
            assertThat(area.cardNumberNumeric())
                    .isEqualTo(area.cardNumberNumeric())
                    .isNotSameAs(area.cardNumberNumeric());
            assertThat(area.customerIdNumeric())
                    .isEqualTo(area.customerIdNumeric())
                    .isNotSameAs(area.customerIdNumeric());

            assertThat(area.accountIdNumeric())
                    .isEqualTo(area.cardNumberNumeric())
                    .isEqualTo(area.customerIdNumeric());
            assertThat(area.accountId()).isSameAs(area.accountId());
        }

        @Test
        @DisplayName("the three views agree with the one text they are each derived from, so a value "
                + "readable as a number is readable identically however often it is read")
        void theThreeViewsAgreeWithTheTextTheyAreDerivedFrom() {
            final ScreenWorkArea area = new ScreenWorkArea(
                    null, null, null, null, null, null,
                    "00000000500", "0000000000000600", "000000700");

            assertThat(area.accountIdNumeric()).contains(new BigInteger(area.accountId()));
            assertThat(area.cardNumberNumeric()).contains(new BigInteger(area.cardNumber()));
            assertThat(area.customerIdNumeric()).contains(new BigInteger(area.customerId()));
        }
    }

    // NULLABLE TOLERANCE AND IMMUTABILITY

    /**
     * Pins that every field may legitimately be absent and that nothing is defaulted, normalised or
     * rejected on the way in.
     *
     * <p>A freshly entered legacy work area held blanks in its three identifiers, no message text and
     * an attention identifier that the key mapping may not have recognised. That state has to be
     * representable, so no field carries a presence requirement and no field is rewritten. The record
     * has no compact constructor at all: a blank value stays blank, a padded value stays padded, and
     * an absent value stays absent.
     *
     * <p>The consequence asserted below is deliberately the negative one. Constructing an entirely
     * empty work area succeeds and every accessor reports absence, which is the behaviour a presence
     * or non-blank requirement on any field would make impossible. It also shows why the only bound
     * the type carries is an upper length bound: a bound measures and reports without trimming,
     * padding or rewriting, which is the only safe constraint on a fixed-width field whose leading and
     * trailing spaces are real data.
     */
    @Nested
    @DisplayName("nullable tolerance and immutability")
    class NullableToleranceAndImmutability {

        @Test
        @DisplayName("an entirely empty work area is constructible and every field reports absence, which "
                + "no presence requirement would allow")
        void anEntirelyEmptyWorkAreaIsConstructible() {
            assertThatCode(() -> new ScreenWorkArea(
                    null, null, null, null, null, null, null, null, null))
                    .doesNotThrowAnyException();

            final ScreenWorkArea empty =
                    new ScreenWorkArea(null, null, null, null, null, null, null, null, null);

            assertThat(empty.keyAction()).isNull();
            assertThat(empty.nextProgram()).isNull();
            assertThat(empty.nextMapset()).isNull();
            assertThat(empty.nextMap()).isNull();
            assertThat(empty.errorMessage()).isNull();
            assertThat(empty.returnMessage()).isNull();
            assertThat(empty.accountId()).isNull();
            assertThat(empty.cardNumber()).isNull();
            assertThat(empty.customerId()).isNull();
        }

        @Test
        @DisplayName("a blank value is stored exactly as supplied rather than defaulted away, because no "
                + "field is required to be non-blank")
        void aBlankValueIsStoredExactlyAsSupplied() {
            final ScreenWorkArea blanks = new ScreenWorkArea(
                    null, "        ", "       ", "       ",
                    " ".repeat(75), " ".repeat(75),
                    " ".repeat(11), " ".repeat(16), " ".repeat(9));

            assertThat(blanks.nextProgram()).isEqualTo("        ").hasSize(8);
            assertThat(blanks.nextMapset()).isEqualTo("       ").hasSize(7);
            assertThat(blanks.nextMap()).isEqualTo("       ").hasSize(7);
            assertThat(blanks.errorMessage()).isEqualTo(" ".repeat(75)).hasSize(75);
            assertThat(blanks.returnMessage()).isEqualTo(" ".repeat(75)).hasSize(75);
            assertThat(blanks.accountId()).isEqualTo(" ".repeat(11)).hasSize(11);
            assertThat(blanks.cardNumber()).isEqualTo(" ".repeat(16)).hasSize(16);
            assertThat(blanks.customerId()).isEqualTo(" ".repeat(9)).hasSize(9);
        }

        @Test
        @DisplayName("an empty work area is written as an empty object and read back as an empty work area, "
                + "so absence survives the wire in both directions")
        void anEmptyWorkAreaSurvivesTheWireInBothDirections() throws JsonProcessingException {
            final ScreenWorkArea empty =
                    new ScreenWorkArea(null, null, null, null, null, null, null, null, null);

            assertThat(WIRE_MAPPER.writeValueAsString(empty)).isEqualTo("{}");
            assertThat(WIRE_MAPPER.readValue("{}", ScreenWorkArea.class)).isEqualTo(empty);
            assertThat(roundTrip(empty)).isEqualTo(empty);
        }

        @Test
        @DisplayName("an empty work area renders without throwing, so a diagnostic on a first entry is "
                + "still safe to emit")
        void anEmptyWorkAreaRendersWithoutThrowing() {
            final ScreenWorkArea empty =
                    new ScreenWorkArea(null, null, null, null, null, null, null, null, null);

            assertThatCode(empty::toString).doesNotThrowAnyException();
            assertThat(empty.toString()).startsWith("ScreenWorkArea[").endsWith("]");
        }

        @Test
        @DisplayName("every field is read back from the same instance unchanged however many times it is "
                + "asked, because the type holds no mutable state")
        void everyFieldIsReadBackUnchangedHoweverManyTimesItIsAsked() {
            final ScreenWorkArea area = withLeadingZeroIdentifiers();

            assertThat(area.accountId()).isSameAs(area.accountId());
            assertThat(area.cardNumber()).isSameAs(area.cardNumber());
            assertThat(area.customerId()).isSameAs(area.customerId());
            assertThat(area.nextProgram()).isSameAs(area.nextProgram());
            assertThat(area.nextMapset()).isSameAs(area.nextMapset());
            assertThat(area.nextMap()).isSameAs(area.nextMap());
            assertThat(area.keyAction()).isSameAs(area.keyAction());
        }

        @Test
        @DisplayName("a second work area built from the values read off the first is equal to it, which is "
                + "the only way this type can be modified")
        void aSecondWorkAreaBuiltFromTheFirstIsEqualToIt() {
            final ScreenWorkArea original = withLeadingZeroIdentifiers();

            final ScreenWorkArea copy = new ScreenWorkArea(
                    original.keyAction(), original.nextProgram(), original.nextMapset(),
                    original.nextMap(), original.errorMessage(), original.returnMessage(),
                    original.accountId(), original.cardNumber(), original.customerId());

            assertThat(copy).isEqualTo(original).hasSameHashCodeAs(original);

            final ScreenWorkArea changed = new ScreenWorkArea(
                    KeyAction.PFK03, original.nextProgram(), original.nextMapset(),
                    original.nextMap(), original.errorMessage(), original.returnMessage(),
                    original.accountId(), original.cardNumber(), original.customerId());

            assertThat(changed).isNotEqualTo(original);
            assertThat(original.keyAction()).isEqualTo(KeyAction.ENTER);
        }
    }

    // VALUE SEMANTICS

    /**
     * Verifies that the work area behaves as a value.
     */
    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("two work areas with the same components are equal")
        void twoWorkAreasWithTheSameComponentsAreEqual() {
            assertThat(withIdentifiers("123456789"))
                    .isEqualTo(withIdentifiers("123456789"))
                    .hasSameHashCodeAs(withIdentifiers("123456789"));
        }

        @Test
        @DisplayName("a difference in the raised key alone makes two work areas unequal")
        void aDifferenceInTheRaisedKeyMakesWorkAreasUnequal() {
            assertThat(withKey(KeyAction.ENTER)).isNotEqualTo(withKey(KeyAction.PFK03));
            assertThat(withKey(KeyAction.ENTER)).isNotEqualTo(withKey(null));
        }

        @Test
        @DisplayName("a work area holding the same identifier text is equal even though the three views "
                + "report three different components")
        void identicalTextIsEqualAcrossTheThreeComponents() {
            final ScreenWorkArea first = withIdentifiers("000000011");
            final ScreenWorkArea second = withIdentifiers("000000011");

            assertThat(first).isEqualTo(second);
            assertThat(first.accountIdNumeric()).isEqualTo(second.customerIdNumeric());
        }

        @Test
        @DisplayName("a work area is not equal to null and not equal to a foreign type")
        void aWorkAreaIsNotEqualToNullOrToAForeignType() {
            final ScreenWorkArea area = withIdentifiers("123456789");

            assertThat(area).isNotEqualTo(null);
            assertThat(area).isNotEqualTo("ScreenWorkArea");
        }

        @Test
        @DisplayName("the rendered form names the record and carries the routing components, withholding the "
                + "three identifiers and the two message slots")
        void theRenderedFormNamesTheRecordAndItsComponents() {
            assertThat(withIdentifiers("123456789").toString())
                    .startsWith("ScreenWorkArea[")
                    .endsWith("]")
                    .contains("COACTUPC")
                    .contains("CACTUPA")
                    .doesNotContain("Account not found")
                    .doesNotContain("Update successful")
                    .doesNotContain("123456789");
        }
    }
}
