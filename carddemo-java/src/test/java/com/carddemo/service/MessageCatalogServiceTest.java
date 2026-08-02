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

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for the shared common-message and screen-title catalog.
 *
 * <p>The class under test is the migrated form of two legacy copybooks: {@code app/cpy/CSMSG01Y.cpy},
 * declaring the common-message group as two 50-character items, and {@code app/cpy/COTTL01Y.cpy},
 * declaring the screen-title group as three 40-character items. Each was textually included by all 17
 * online COBOL programs, so the migration collapses 34 textual inclusions into one injected
 * singleton.</p>
 *
 * <p>Every published value is a fixed-width external contract rather than incidental whitespace: the
 * two common messages reach a REST response body by way of the online services, so shortening a value
 * or normalising its padding would change the wire contract. These tests therefore assert the padding
 * as deliberately as the visible text. In {@code app/cbl/COSGN00C.cbl} (transaction {@code CC00}) the
 * attention-key decision places the thank-you message into the screen message field on the exit-key
 * arm and sends plain text without raising the error flag, whereas its default arm raises the error
 * flag first and only then places the invalid-key message. This class covers the text alone; flag
 * state, cursor placement and routing belong to the services that own them.</p>
 *
 * <p>Every expected value is a literal declared in this test class, so the oracle is independent of
 * the code it judges: no expected value is produced by calling the class under test or any production
 * formatter, codec, template holder or record mapper. Padding is written as an explicit repeat count
 * rather than as trailing whitespace, so the count is visible to a reviewer and cannot be stripped by
 * an editor. Every width assertion measures encoded bytes, never character count, because these are
 * byte-width contracts. No fixed-width value is ever trimmed before comparison; trimming appears only
 * where the assertion is explicitly about visible text, such as the trailing full-stop run.</p>
 */
@DisplayName("Common message catalog: the shared screen text keeps its legacy fixed widths")
class MessageCatalogServiceTest {

    /**
     * Visible text of the 50-character thank-you common message. The three trailing full stops are part
     * of the text rather than padding.
     */
    private static final String MSG_THANK_YOU_VISIBLE = "Thank you for using CardDemo application...";

    /**
     * Visible text of the 50-character invalid-key common message. The full stop after the first word
     * group, the single space that follows it and the three trailing full stops are all part of the
     * text.
     */
    private static final String MSG_INVALID_KEY_VISIBLE = "Invalid key pressed. Please see below...";

    private static final String TITLE01_VISIBLE = "AWS Mainframe Modernization";

    /**
     * Visible text of the active value of the second screen title. The copybook also carries a
     * commented-out alternative on the line immediately above it; the alternative is inactive in the
     * legacy source and must stay inactive here.
     */
    private static final String TITLE02_VISIBLE = "CardDemo";

    /**
     * Visible text of the 40-character screen-title thank-you line. Note the product token and the
     * width: this is a different literal from the 50-character common message serving the same purpose.
     */
    private static final String TITLE_THANK_YOU_VISIBLE = "Thank you for using CCDA application...";

    private static final int EXPECTED_COMMON_MESSAGE_WIDTH = 50;

    private static final int EXPECTED_SCREEN_TITLE_WIDTH = 40;

    private static final int MSG_THANK_YOU_VISIBLE_WIDTH = 43;

    private static final int MSG_INVALID_KEY_VISIBLE_WIDTH = 40;

    private static final int TITLE01_VISIBLE_WIDTH = 27;

    private static final int TITLE02_VISIBLE_WIDTH = 8;

    private static final int TITLE_THANK_YOU_VISIBLE_WIDTH = 39;

    private static final int TITLE01_LEADING_SPACES = 6;

    private static final int TITLE02_LEADING_SPACES = 14;

    private static final int EXPECTED_FULL_STOP_RUN = 3;

    private static final int EXPECTED_COMMON_MESSAGE_COUNT = 2;

    private static final int EXPECTED_SCREEN_TITLE_COUNT = 3;

    private static final String EXPECTED_MSG_THANK_YOU = MSG_THANK_YOU_VISIBLE + " ".repeat(7);

    private static final String EXPECTED_MSG_INVALID_KEY = MSG_INVALID_KEY_VISIBLE + " ".repeat(10);

    private static final String EXPECTED_TITLE01 = " ".repeat(6) + TITLE01_VISIBLE + " ".repeat(7);

    private static final String EXPECTED_TITLE02 = " ".repeat(14) + TITLE02_VISIBLE + " ".repeat(18);

    /**
     * The screen-title thank-you line at its contractual width, padded by a single trailing space
     * written as a repeat count because a lone trailing space is the easiest character here to lose.
     */
    private static final String EXPECTED_TITLE_THANK_YOU = TITLE_THANK_YOU_VISIBLE + " ".repeat(1);

    private static final String EXPECTED_KEY_MSG_THANK_YOU = "CCDA-MSG-THANK-YOU";

    private static final String EXPECTED_KEY_MSG_INVALID_KEY = "CCDA-MSG-INVALID-KEY";

    private static final String EXPECTED_KEY_TITLE01 = "CCDA-TITLE01";

    private static final String EXPECTED_KEY_TITLE02 = "CCDA-TITLE02";

    private static final String EXPECTED_KEY_TITLE_THANK_YOU = "CCDA-THANK-YOU";

    /**
     * Product token carried by the 50-character common message and by no screen-title thank-you line.
     */
    private static final String CARDDEMO_TOKEN = "CardDemo";

    /**
     * Product token carried by the 40-character screen-title thank-you line and by no common message.
     */
    private static final String CCDA_TOKEN = "CCDA";

    /**
     * Token appearing only in the commented-out alternative second screen title. The catalog must not
     * publish it: activating an inactive legacy literal would be a behaviour change.
     */
    private static final String INACTIVE_ALTERNATIVE_TITLE_TOKEN = "Credit Card Demo Application";

    /**
     * The catalog under test, reconstructed before each test so no test can depend on another having run
     * and so the public no-argument constructor is exercised every time.
     */
    private MessageCatalogService catalog;

    /**
     * Builds a fresh catalog. It has no collaborators, so there is nothing to mock and no container to
     * start; direct construction is the strongest statement that this test needs no framework to run.
     */
    @BeforeEach
    void createCatalog() {
        catalog = new MessageCatalogService();
    }

    /**
     * Measures a value the way the fixed-width contract defines it, in encoded bytes rather than
     * characters: a character count would agree for well-formed catalog text and disagree the moment a
     * non-ASCII character was introduced, which is exactly the regression these tests exist to catch.
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Counts the run of full stops terminating a value, over encoded bytes for consistency with the width
     * measurement. Counting the run rejects both a shortened and a lengthened run in one assertion,
     * which a suffix comparison alone cannot do.
     */
    private static int trailingFullStops(final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int run = 0;
        while (run < bytes.length && bytes[bytes.length - 1 - run] == '.') {
            run++;
        }
        return run;
    }

    /**
     * Supplies every published value twice, once by way of its public constant and once by way of its
     * accessor, with the width its legacy declaration gives it. Adding a message without extending this
     * table leaves the new message unasserted, which is the point: the table is meant to have to grow.
     */
    private static Stream<Arguments> publishedValues() {
        final MessageCatalogService catalogUnderTest = new MessageCatalogService();
        return Stream.of(
                Arguments.of("CCDA-MSG-THANK-YOU as a constant",
                        MessageCatalogService.CCDA_MSG_THANK_YOU,
                        EXPECTED_MSG_THANK_YOU, EXPECTED_COMMON_MESSAGE_WIDTH),
                Arguments.of("CCDA-MSG-THANK-YOU through its accessor",
                        catalogUnderTest.thankYouMessage(),
                        EXPECTED_MSG_THANK_YOU, EXPECTED_COMMON_MESSAGE_WIDTH),
                Arguments.of("CCDA-MSG-INVALID-KEY as a constant",
                        MessageCatalogService.CCDA_MSG_INVALID_KEY,
                        EXPECTED_MSG_INVALID_KEY, EXPECTED_COMMON_MESSAGE_WIDTH),
                Arguments.of("CCDA-MSG-INVALID-KEY through its accessor",
                        catalogUnderTest.invalidKeyMessage(),
                        EXPECTED_MSG_INVALID_KEY, EXPECTED_COMMON_MESSAGE_WIDTH),
                Arguments.of("CCDA-TITLE01 as a constant",
                        MessageCatalogService.CCDA_TITLE01,
                        EXPECTED_TITLE01, EXPECTED_SCREEN_TITLE_WIDTH),
                Arguments.of("CCDA-TITLE01 through its accessor",
                        catalogUnderTest.screenTitle01(),
                        EXPECTED_TITLE01, EXPECTED_SCREEN_TITLE_WIDTH),
                Arguments.of("CCDA-TITLE02 as a constant",
                        MessageCatalogService.CCDA_TITLE02,
                        EXPECTED_TITLE02, EXPECTED_SCREEN_TITLE_WIDTH),
                Arguments.of("CCDA-TITLE02 through its accessor",
                        catalogUnderTest.screenTitle02(),
                        EXPECTED_TITLE02, EXPECTED_SCREEN_TITLE_WIDTH),
                Arguments.of("CCDA-THANK-YOU as a constant",
                        MessageCatalogService.CCDA_THANK_YOU,
                        EXPECTED_TITLE_THANK_YOU, EXPECTED_SCREEN_TITLE_WIDTH),
                Arguments.of("CCDA-THANK-YOU through its accessor",
                        catalogUnderTest.screenTitleThankYou(),
                        EXPECTED_TITLE_THANK_YOU, EXPECTED_SCREEN_TITLE_WIDTH));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publishedValues")
    @DisplayName("every published value reproduces its legacy literal byte for byte at the width its picture "
            + "clause declares")
    void everyPublishedValueReproducesItsLegacyLiteral(final String member, final String published,
            final String expected, final int declaredWidth) {

        assertThat(published).as("%s must never be null", member).isNotNull();
        assertThat(published).as("%s text", member).isEqualTo(expected);
        assertThat(published.getBytes(StandardCharsets.US_ASCII))
                .as("%s encoded bytes", member)
                .isEqualTo(expected.getBytes(StandardCharsets.US_ASCII));
        assertThat(encodedWidth(published)).as("%s encoded width", member).isEqualTo(declaredWidth);
    }

    /**
     * The two 50-character items of the common-message group.
     */
    @Nested
    @DisplayName("The two common messages the legacy estate shared across all seventeen online programs")
    class CommonMessages {

        @Test
        @DisplayName("the thank-you message occupies exactly fifty encoded bytes, the width its picture clause "
                + "declares")
        void thankYouMessageOccupiesFiftyEncodedBytes() {
            assertThat(encodedWidth(catalog.thankYouMessage())).isEqualTo(EXPECTED_COMMON_MESSAGE_WIDTH);
            assertThat(encodedWidth(EXPECTED_MSG_THANK_YOU)).isEqualTo(EXPECTED_COMMON_MESSAGE_WIDTH);
            assertThat(encodedWidth(MSG_THANK_YOU_VISIBLE)).isEqualTo(MSG_THANK_YOU_VISIBLE_WIDTH);
        }

        @Test
        @DisplayName("the thank-you message keeps all seven of its trailing spaces, because the padding is part "
                + "of the screen contract")
        void thankYouMessageKeepsAllSevenTrailingSpaces() {
            final String published = catalog.thankYouMessage();

            assertThat(published).isEqualTo(EXPECTED_MSG_THANK_YOU);
            assertThat(published).startsWith(MSG_THANK_YOU_VISIBLE);
            assertThat(published.substring(0, MSG_THANK_YOU_VISIBLE_WIDTH)
                    .getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo(MSG_THANK_YOU_VISIBLE.getBytes(StandardCharsets.US_ASCII));
            assertThat(published.substring(MSG_THANK_YOU_VISIBLE_WIDTH)).isEqualTo(" ".repeat(7));
            assertThat(published).endsWith(" ".repeat(7));
            assertThat(published).isNotEqualTo(published.strip()).isNotEqualTo(published.trim());
        }

        @Test
        @DisplayName("the invalid-key message occupies exactly fifty encoded bytes, the width its picture "
                + "clause declares")
        void invalidKeyMessageOccupiesFiftyEncodedBytes() {
            assertThat(encodedWidth(catalog.invalidKeyMessage())).isEqualTo(EXPECTED_COMMON_MESSAGE_WIDTH);
            assertThat(encodedWidth(EXPECTED_MSG_INVALID_KEY)).isEqualTo(EXPECTED_COMMON_MESSAGE_WIDTH);
            assertThat(encodedWidth(MSG_INVALID_KEY_VISIBLE)).isEqualTo(MSG_INVALID_KEY_VISIBLE_WIDTH);
        }

        @Test
        @DisplayName("the invalid-key message keeps all ten of its trailing spaces, because the padding is part "
                + "of the screen contract")
        void invalidKeyMessageKeepsAllTenTrailingSpaces() {
            final String published = catalog.invalidKeyMessage();

            assertThat(published).isEqualTo(EXPECTED_MSG_INVALID_KEY);
            assertThat(published).startsWith(MSG_INVALID_KEY_VISIBLE);
            assertThat(published.substring(0, MSG_INVALID_KEY_VISIBLE_WIDTH)
                    .getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo(MSG_INVALID_KEY_VISIBLE.getBytes(StandardCharsets.US_ASCII));
            assertThat(published.substring(MSG_INVALID_KEY_VISIBLE_WIDTH)).isEqualTo(" ".repeat(10));
            assertThat(published).endsWith(" ".repeat(10));
            assertThat(published).isNotEqualTo(published.strip()).isNotEqualTo(published.trim());
        }

        @Test
        @DisplayName("the thank-you message ends in exactly three full stops, so neither a shortened nor a "
                + "lengthened run can slip in")
        void thankYouMessageEndsInExactlyThreeFullStops() {
            final String visible = catalog.thankYouMessage().strip();

            assertThat(trailingFullStops(visible)).isEqualTo(EXPECTED_FULL_STOP_RUN);
            assertThat(visible).endsWith("...").doesNotEndWith("....");
            assertThat(visible)
                    .isNotEqualTo(MSG_THANK_YOU_VISIBLE + ".")
                    .isNotEqualTo(MSG_THANK_YOU_VISIBLE.substring(0, MSG_THANK_YOU_VISIBLE_WIDTH - 1));
        }

        @Test
        @DisplayName("the invalid-key message ends in exactly three full stops, and its mid-text full stop is "
                + "not mistaken for the run")
        void invalidKeyMessageEndsInExactlyThreeFullStops() {
            final String visible = catalog.invalidKeyMessage().strip();

            assertThat(trailingFullStops(visible)).isEqualTo(EXPECTED_FULL_STOP_RUN);
            assertThat(visible).endsWith("...").doesNotEndWith("....");
            assertThat(visible)
                    .isNotEqualTo(MSG_INVALID_KEY_VISIBLE + ".")
                    .isNotEqualTo(MSG_INVALID_KEY_VISIBLE.substring(0, MSG_INVALID_KEY_VISIBLE_WIDTH - 1));
        }
    }

    /**
     * The three 40-character items of the screen-title group.
     */
    @Nested
    @DisplayName("The three screen-title lines, which are forty bytes wide and not fifty")
    class ScreenTitles {

        @Test
        @DisplayName("the first title keeps both its six leading and its seven trailing spaces, because "
                + "together they centre it in the field")
        void firstTitleKeepsItsLeadingAndTrailingPadding() {
            final String published = catalog.screenTitle01();

            assertThat(published).isEqualTo(EXPECTED_TITLE01);
            assertThat(published.substring(0, TITLE01_LEADING_SPACES)).isEqualTo(" ".repeat(6));
            assertThat(published.substring(TITLE01_LEADING_SPACES,
                    TITLE01_LEADING_SPACES + TITLE01_VISIBLE_WIDTH)
                    .getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo(TITLE01_VISIBLE.getBytes(StandardCharsets.US_ASCII));
            assertThat(published.substring(TITLE01_LEADING_SPACES + TITLE01_VISIBLE_WIDTH))
                    .isEqualTo(" ".repeat(7));
            assertThat(published).isNotEqualTo(published.strip()).isNotEqualTo(published.trim());
            assertThat(encodedWidth(published)).isEqualTo(EXPECTED_SCREEN_TITLE_WIDTH);
            assertThat(encodedWidth(TITLE01_VISIBLE)).isEqualTo(TITLE01_VISIBLE_WIDTH);
        }

        @Test
        @DisplayName("the second title carries only the value active in the legacy copybook, so its "
                + "commented-out alternative stays inactive")
        void secondTitleCarriesOnlyTheActiveLegacyValue() {
            final String published = catalog.screenTitle02();

            assertThat(published).isEqualTo(EXPECTED_TITLE02);
            assertThat(published.substring(0, TITLE02_LEADING_SPACES)).isEqualTo(" ".repeat(14));
            assertThat(published.substring(TITLE02_LEADING_SPACES,
                    TITLE02_LEADING_SPACES + TITLE02_VISIBLE_WIDTH)
                    .getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo(TITLE02_VISIBLE.getBytes(StandardCharsets.US_ASCII));
            assertThat(published.substring(TITLE02_LEADING_SPACES + TITLE02_VISIBLE_WIDTH))
                    .isEqualTo(" ".repeat(18));
            assertThat(published).doesNotContain(INACTIVE_ALTERNATIVE_TITLE_TOKEN);
            assertThat(published).isNotEqualTo(published.strip()).isNotEqualTo(published.trim());
            assertThat(encodedWidth(published)).isEqualTo(EXPECTED_SCREEN_TITLE_WIDTH);
            assertThat(encodedWidth(TITLE02_VISIBLE)).isEqualTo(TITLE02_VISIBLE_WIDTH);
        }

        @Test
        @DisplayName("the screen-title thank-you line keeps the single trailing space that pads it to forty bytes")
        void screenTitleThankYouKeepsItsSingleTrailingSpace() {
            final String published = catalog.screenTitleThankYou();

            assertThat(published).isEqualTo(EXPECTED_TITLE_THANK_YOU);
            assertThat(published).startsWith(TITLE_THANK_YOU_VISIBLE);
            assertThat(published.substring(0, TITLE_THANK_YOU_VISIBLE_WIDTH)
                    .getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo(TITLE_THANK_YOU_VISIBLE.getBytes(StandardCharsets.US_ASCII));
            assertThat(published.substring(TITLE_THANK_YOU_VISIBLE_WIDTH)).isEqualTo(" ".repeat(1));
            assertThat(published).endsWith(" ".repeat(1));
            assertThat(published).isNotEqualTo(published.strip()).isNotEqualTo(published.trim());
            assertThat(encodedWidth(published)).isEqualTo(EXPECTED_SCREEN_TITLE_WIDTH);
            assertThat(encodedWidth(TITLE_THANK_YOU_VISIBLE)).isEqualTo(TITLE_THANK_YOU_VISIBLE_WIDTH);
        }

        @Test
        @DisplayName("the screen-title thank-you line also ends in exactly three full stops")
        void screenTitleThankYouEndsInExactlyThreeFullStops() {
            final String visible = catalog.screenTitleThankYou().strip();

            assertThat(trailingFullStops(visible)).isEqualTo(EXPECTED_FULL_STOP_RUN);
            assertThat(visible).endsWith("...").doesNotEndWith("....");
            assertThat(visible)
                    .isNotEqualTo(TITLE_THANK_YOU_VISIBLE + ".")
                    .isNotEqualTo(TITLE_THANK_YOU_VISIBLE.substring(0, TITLE_THANK_YOU_VISIBLE_WIDTH - 1));
        }
    }

    /**
     * The guard against the single most tempting wrong simplification in this catalog.
     */
    @Nested
    @DisplayName("The two thank-you literals, which serve the same purpose and are not the same contract")
    class ThankYouVariants {

        @Test
        @DisplayName("the fifty-byte CardDemo common message and the forty-byte CCDA screen title are two "
                + "different legacy literals that must never be unified")
        void theTwoThankYouLiteralsAreNeverUnified() {
            final String commonMessage = catalog.thankYouMessage();
            final String screenTitle = catalog.screenTitleThankYou();

            assertThat(commonMessage).isNotEqualTo(screenTitle);
            assertThat(commonMessage.strip()).isNotEqualTo(screenTitle.strip());
            assertThat(encodedWidth(commonMessage)).isNotEqualTo(encodedWidth(screenTitle));
            assertThat(encodedWidth(commonMessage)).isEqualTo(EXPECTED_COMMON_MESSAGE_WIDTH);
            assertThat(encodedWidth(screenTitle)).isEqualTo(EXPECTED_SCREEN_TITLE_WIDTH);
            assertThat(commonMessage).contains(CARDDEMO_TOKEN).doesNotContain(CCDA_TOKEN);
            assertThat(screenTitle).contains(CCDA_TOKEN).doesNotContain(CARDDEMO_TOKEN);
        }

        @Test
        @DisplayName("the two thank-you literals are published under different keys, so neither can be reached "
                + "by the other's name")
        void theTwoThankYouLiteralsAreKeyedSeparately() {
            assertThat(EXPECTED_KEY_MSG_THANK_YOU).isNotEqualTo(EXPECTED_KEY_TITLE_THANK_YOU);
            assertThat(catalog.commonMessages()).doesNotContainKey(EXPECTED_KEY_TITLE_THANK_YOU);
            assertThat(catalog.screenTitles()).doesNotContainKey(EXPECTED_KEY_MSG_THANK_YOU);
            assertThat(catalog.commonMessages().values()).doesNotContain(EXPECTED_TITLE_THANK_YOU);
            assertThat(catalog.screenTitles().values()).doesNotContain(EXPECTED_MSG_THANK_YOU);
        }
    }

    /**
     * The declared widths and the legacy field names the catalog publishes alongside the text.
     */
    @Nested
    @DisplayName("The declared field widths and the legacy field names used as catalog keys")
    class DeclaredWidthsAndKeys {

        @Test
        @DisplayName("the published widths are fifty for a common message and forty for a screen title, and the "
                + "two are not interchangeable")
        void thePublishedWidthsMatchTheLegacyPictureClauses() {
            assertThat(MessageCatalogService.COMMON_MESSAGE_WIDTH)
                    .isEqualTo(EXPECTED_COMMON_MESSAGE_WIDTH);
            assertThat(MessageCatalogService.SCREEN_TITLE_WIDTH)
                    .isEqualTo(EXPECTED_SCREEN_TITLE_WIDTH);
            assertThat(MessageCatalogService.COMMON_MESSAGE_WIDTH)
                    .isNotEqualTo(MessageCatalogService.SCREEN_TITLE_WIDTH);
        }

        @Test
        @DisplayName("each catalog key is the legacy copybook field name, so a published value can be traced "
                + "back to its declaration")
        void eachCatalogKeyIsTheLegacyFieldName() {
            assertThat(MessageCatalogService.KEY_MSG_THANK_YOU).isEqualTo(EXPECTED_KEY_MSG_THANK_YOU);
            assertThat(MessageCatalogService.KEY_MSG_INVALID_KEY)
                    .isEqualTo(EXPECTED_KEY_MSG_INVALID_KEY);
            assertThat(MessageCatalogService.KEY_TITLE01).isEqualTo(EXPECTED_KEY_TITLE01);
            assertThat(MessageCatalogService.KEY_TITLE02).isEqualTo(EXPECTED_KEY_TITLE02);
            assertThat(MessageCatalogService.KEY_TITLE_THANK_YOU)
                    .isEqualTo(EXPECTED_KEY_TITLE_THANK_YOU);
        }

        @Test
        @DisplayName("each catalog key matches its legacy field name byte for byte, so a hyphen or a letter "
                + "case cannot drift")
        void eachCatalogKeyMatchesItsLegacyFieldNameByteForByte() {
            assertThat(MessageCatalogService.KEY_MSG_THANK_YOU.getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo(EXPECTED_KEY_MSG_THANK_YOU.getBytes(StandardCharsets.US_ASCII));
            assertThat(MessageCatalogService.KEY_MSG_INVALID_KEY.getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo(EXPECTED_KEY_MSG_INVALID_KEY.getBytes(StandardCharsets.US_ASCII));
            assertThat(MessageCatalogService.KEY_TITLE01.getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo(EXPECTED_KEY_TITLE01.getBytes(StandardCharsets.US_ASCII));
            assertThat(MessageCatalogService.KEY_TITLE02.getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo(EXPECTED_KEY_TITLE02.getBytes(StandardCharsets.US_ASCII));
            assertThat(MessageCatalogService.KEY_TITLE_THANK_YOU.getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo(EXPECTED_KEY_TITLE_THANK_YOU.getBytes(StandardCharsets.US_ASCII));
        }
    }

    /**
     * The two copybook groups republished as maps keyed by legacy field name.
     */
    @Nested
    @DisplayName("The two copybook groups, republished whole and closed to modification")
    class GroupMaps {

        @Test
        @DisplayName("the common-message group holds exactly the two legacy entries, each at its fifty-byte width")
        void theCommonMessageGroupHoldsExactlyTheTwoLegacyEntries() {
            final Map<String, String> group = catalog.commonMessages();

            assertThat(group)
                    .hasSize(EXPECTED_COMMON_MESSAGE_COUNT)
                    .containsOnlyKeys(EXPECTED_KEY_MSG_THANK_YOU, EXPECTED_KEY_MSG_INVALID_KEY)
                    .containsEntry(EXPECTED_KEY_MSG_THANK_YOU, EXPECTED_MSG_THANK_YOU)
                    .containsEntry(EXPECTED_KEY_MSG_INVALID_KEY, EXPECTED_MSG_INVALID_KEY);
            assertThat(group.values()).allSatisfy(value ->
                    assertThat(encodedWidth(value)).isEqualTo(EXPECTED_COMMON_MESSAGE_WIDTH));
        }

        @Test
        @DisplayName("the screen-title group holds exactly the three legacy entries, each at its forty-byte "
                + "width and none carrying the inactive alternative")
        void theScreenTitleGroupHoldsExactlyTheThreeLegacyEntries() {
            final Map<String, String> group = catalog.screenTitles();

            assertThat(group)
                    .hasSize(EXPECTED_SCREEN_TITLE_COUNT)
                    .containsOnlyKeys(EXPECTED_KEY_TITLE01, EXPECTED_KEY_TITLE02,
                            EXPECTED_KEY_TITLE_THANK_YOU)
                    .containsEntry(EXPECTED_KEY_TITLE01, EXPECTED_TITLE01)
                    .containsEntry(EXPECTED_KEY_TITLE02, EXPECTED_TITLE02)
                    .containsEntry(EXPECTED_KEY_TITLE_THANK_YOU, EXPECTED_TITLE_THANK_YOU);
            assertThat(group.values()).allSatisfy(value -> {
                assertThat(encodedWidth(value)).isEqualTo(EXPECTED_SCREEN_TITLE_WIDTH);
                assertThat(value).doesNotContain(INACTIVE_ALTERNATIVE_TITLE_TOKEN);
            });
        }

        @Test
        @DisplayName("the common-message group refuses every mutation, so no caller can rewrite the legacy text "
                + "at runtime")
        void theCommonMessageGroupRefusesEveryMutation() {
            final Map<String, String> group = catalog.commonMessages();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> group.put(EXPECTED_KEY_MSG_THANK_YOU, EXPECTED_MSG_INVALID_KEY));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> group.remove(EXPECTED_KEY_MSG_THANK_YOU));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(group::clear);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> group.keySet().add(EXPECTED_KEY_TITLE01));

            assertThat(catalog.commonMessages())
                    .containsEntry(EXPECTED_KEY_MSG_THANK_YOU, EXPECTED_MSG_THANK_YOU)
                    .containsEntry(EXPECTED_KEY_MSG_INVALID_KEY, EXPECTED_MSG_INVALID_KEY);
        }

        @Test
        @DisplayName("the screen-title group refuses every mutation, so no caller can activate the inactive "
                + "alternative title")
        void theScreenTitleGroupRefusesEveryMutation() {
            final Map<String, String> group = catalog.screenTitles();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> group.put(EXPECTED_KEY_TITLE02, EXPECTED_TITLE01));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> group.remove(EXPECTED_KEY_TITLE02));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(group::clear);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> group.keySet().add(EXPECTED_KEY_MSG_THANK_YOU));

            assertThat(catalog.screenTitles())
                    .containsEntry(EXPECTED_KEY_TITLE02, EXPECTED_TITLE02)
                    .hasSize(EXPECTED_SCREEN_TITLE_COUNT);
        }
    }

    /**
     * The accessor contract every collaborating service depends on.
     */
    @Nested
    @DisplayName("The accessor contract the twelve collaborating services depend on")
    class AccessorContract {

        @Test
        @DisplayName("no accessor ever returns null, because a null screen message would blank a field instead "
                + "of failing loudly")
        void noAccessorEverReturnsNull() {
            assertThat(catalog.thankYouMessage()).isNotNull();
            assertThat(catalog.invalidKeyMessage()).isNotNull();
            assertThat(catalog.screenTitle01()).isNotNull();
            assertThat(catalog.screenTitle02()).isNotNull();
            assertThat(catalog.screenTitleThankYou()).isNotNull();
            assertThat(catalog.commonMessages()).isNotNull();
            assertThat(catalog.screenTitles()).isNotNull();
        }

        @Test
        @DisplayName("every accessor returns the very same value on every invocation, so no caller can observe "
                + "a changing catalog")
        void everyAccessorReturnsTheSameValueOnEveryInvocation() {
            assertThat(catalog.thankYouMessage()).isSameAs(catalog.thankYouMessage());
            assertThat(catalog.invalidKeyMessage()).isSameAs(catalog.invalidKeyMessage());
            assertThat(catalog.screenTitle01()).isSameAs(catalog.screenTitle01());
            assertThat(catalog.screenTitle02()).isSameAs(catalog.screenTitle02());
            assertThat(catalog.screenTitleThankYou()).isSameAs(catalog.screenTitleThankYou());
            assertThat(catalog.commonMessages()).isSameAs(catalog.commonMessages());
            assertThat(catalog.screenTitles()).isSameAs(catalog.screenTitles());
        }

        @Test
        @DisplayName("every accessor publishes exactly the constant of the same name, so the two publication "
                + "routes cannot drift apart")
        void everyAccessorPublishesTheConstantOfTheSameName() {
            assertThat(catalog.thankYouMessage()).isSameAs(MessageCatalogService.CCDA_MSG_THANK_YOU);
            assertThat(catalog.invalidKeyMessage()).isSameAs(MessageCatalogService.CCDA_MSG_INVALID_KEY);
            assertThat(catalog.screenTitle01()).isSameAs(MessageCatalogService.CCDA_TITLE01);
            assertThat(catalog.screenTitle02()).isSameAs(MessageCatalogService.CCDA_TITLE02);
            assertThat(catalog.screenTitleThankYou()).isSameAs(MessageCatalogService.CCDA_THANK_YOU);
        }

        @Test
        @DisplayName("two separately constructed catalogs publish identical text, because the catalog holds no "
                + "instance state")
        void twoSeparatelyConstructedCatalogsPublishIdenticalText() {
            final MessageCatalogService other = new MessageCatalogService();

            assertThat(other.thankYouMessage()).isEqualTo(catalog.thankYouMessage());
            assertThat(other.invalidKeyMessage()).isEqualTo(catalog.invalidKeyMessage());
            assertThat(other.screenTitle01()).isEqualTo(catalog.screenTitle01());
            assertThat(other.screenTitle02()).isEqualTo(catalog.screenTitle02());
            assertThat(other.screenTitleThankYou()).isEqualTo(catalog.screenTitleThankYou());
            assertThat(other.commonMessages()).isEqualTo(catalog.commonMessages());
            assertThat(other.screenTitles()).isEqualTo(catalog.screenTitles());
        }
    }
}
