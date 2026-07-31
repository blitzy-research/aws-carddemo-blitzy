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
package com.carddemo.exception;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AbendException}, the single Java type that replaces the entire abend
 * surface of the legacy CardDemo estate.
 *
 * <h2>What is under test</h2>
 *
 * <p>Thirteen legacy sites terminate abnormally and all thirteen map onto this one type. Nine sit
 * on the batch tier, one per batch program, each a static call to the Language Environment abort
 * routine {@code CEE3ABD} inside a paragraph named {@code 9999-ABEND-PROGRAM} that first sets a
 * numeric abend code of 999: {@code CBACT01C}, {@code CBACT02C}, {@code CBACT03C},
 * {@code CBACT04C}, {@code CBCUS01C}, {@code CBTRN01C}, {@code CBTRN02C}, {@code CBTRN03C} and
 * {@code CBSTM03A}. Four sit on the online tier as CICS abend commands carrying the
 * four-character code 9999, each immediately preceded by the CICS abend-handler deregistration:
 * {@code COACTUPC}, {@code COACTVWC}, {@code COCRDSLC} and {@code COCRDUPC}. Those four are
 * confined to the five-program family {@code COACTUPC}, {@code COACTVWC}, {@code COCRDLIC},
 * {@code COCRDSLC} and {@code COCRDUPC}; the fifth member, {@code COCRDLIC}, belongs to the
 * family stylistically but has its inclusion of the abend work area commented out and therefore
 * carries no abend context of its own. Nine plus four is the complete surface, which is why one
 * exception type suffices and no second one is introduced. These counts are recorded here as
 * documentation only and are deliberately not asserted: no legacy source is on the test
 * classpath, and none may be reproduced in this module.
 *
 * <h2>The 134-character context</h2>
 *
 * <p>The legacy abend work area is a group of exactly four character fields, every one of them
 * initialised to spaces, and the tests below treat their widths as the contract:
 *
 * <ul>
 *   <li>{@code ABEND-CODE}, four characters, exposed by {@link AbendException#code()}</li>
 *   <li>{@code ABEND-CULPRIT}, eight characters, exposed by {@link AbendException#culprit()}</li>
 *   <li>{@code ABEND-REASON}, fifty characters, exposed by {@link AbendException#reason()}</li>
 *   <li>{@code ABEND-MSG}, seventy-two characters, exposed by the inherited
 *       {@link Throwable#getMessage()}</li>
 * </ul>
 *
 * <p>Four plus eight plus fifty plus seventy-two is 134, so
 * {@link AbendException#CONTEXT_LENGTH} is asserted both against that total and against the
 * arithmetic sum of the four width constants. Asserting the sum as well as the literal is the
 * strongest single check in this class: it means a later edit to any one width cannot silently
 * desynchronise the total.
 *
 * <p>The fourth component is intentionally not duplicated into a field of its own. It is carried
 * by the inherited {@code Throwable} message, so these tests read it with
 * {@link Throwable#getMessage()} and never look for an accessor that does not exist.
 *
 * <h2>Faithful over idiomatic</h2>
 *
 * <p>Several behaviours here would look wrong to a reader who expected idiomatic Java, and each
 * is deliberate. The default operator message keeps its trailing full stop and stays in upper
 * case because that is how the legacy literal reads. The batch abend code is three characters
 * while the online abend code is four, because the first comes from a numeric move and the second
 * from a four-character command literal; the asymmetry is asserted on purpose so that nobody
 * "normalises" the two to match. Values are left-justified and padded on the right, never on the
 * left, because that is how a legacy character field holds a short value. An over-length value is
 * rejected rather than truncated, which is the one place this translation is deliberately
 * stricter than the mainframe: a legacy move would have truncated in silence, and a truncated
 * abend context is a lost diagnostic.
 *
 * <h2>Documented source anomaly</h2>
 *
 * <p>The copybook that declares the abend work area carries an internal banner comment naming the
 * file {@code CABENDD.CPY}, which disagrees with the member name that actually exists,
 * {@code CSMSG02Y.cpy}. The copybook is also written in the older sequence-numbered layout, with
 * numbers occupying the first six columns. Both observations are recorded, neither is corrected:
 * the legacy tree is the parity baseline and stays byte-identical.
 *
 * <h2>Provenance</h2>
 *
 * <p>Behaviour verified against the CardDemo mainframe estate at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. That stamp is cited here purely as
 * provenance for this class as a whole; it is not universal across the estate and is never
 * asserted, per member or otherwise.
 */
@DisplayName("AbendException: the 134-character legacy abend context")
class AbendExceptionTest {

    /** A four-character abend code, exactly filling the legacy code field. */
    private static final String CODE_IN_RANGE = "0001";

    /** An eight-character program name, exactly filling the legacy culprit field. */
    private static final String CULPRIT_IN_RANGE = "COACTUPC";

    /** A reason comfortably inside the legacy reason field. */
    private static final String REASON_IN_RANGE = "ACCOUNT UPDATE VALIDATION FAILED";

    /** An operator message comfortably inside the legacy message field. */
    private static final String MESSAGE_IN_RANGE = "UNEXPECTED DATA SCENARIO";

    /** Offset at which the rendered context begins the code field. */
    private static final int CODE_START = 0;

    /** Offset at which the rendered context begins the culprit field. */
    private static final int CULPRIT_START = CODE_START + AbendException.CODE_LENGTH;

    /** Offset at which the rendered context begins the reason field. */
    private static final int REASON_START = CULPRIT_START + AbendException.CULPRIT_LENGTH;

    /** Offset at which the rendered context begins the message field. */
    private static final int MESSAGE_START = REASON_START + AbendException.REASON_LENGTH;

    /**
     * Builds the expectation for one rendered slice: the value, then spaces out to the legacy
     * width. Written independently of the production padding helper on purpose, so that the two
     * cannot agree on the same mistake.
     *
     * @param value the value the slice is expected to carry
     * @param width the legacy field width the slice is expected to occupy
     * @return the value followed by enough spaces to reach {@code width}
     */
    private static String rightPaddedTo(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Serializes and then deserializes an abend, proving the whole context survives a real object
     * stream rather than only a field-by-field copy.
     *
     * @param original the abend to write and read back
     * @return the deserialized abend
     * @throws IOException            if the in-memory streams fail
     * @throws ClassNotFoundException if the deserialized type cannot be resolved
     */
    private static AbendException serializeAndBack(AbendException original)
            throws IOException, ClassNotFoundException {
        ByteArrayOutputStream serialised = new ByteArrayOutputStream();
        try (ObjectOutputStream writer = new ObjectOutputStream(serialised)) {
            writer.writeObject(original);
        }
        try (ObjectInputStream reader =
                new ObjectInputStream(new ByteArrayInputStream(serialised.toByteArray()))) {
            return (AbendException) reader.readObject();
        }
    }

    @Nested
    @DisplayName("Legacy field widths")
    class LayoutConstants {

        @Test
        @DisplayName("each width constant matches the width of the legacy field it stands for")
        void widthConstantsMatchTheLegacyFieldWidths() {
            assertThat(AbendException.CODE_LENGTH)
                    .as("the legacy ABEND-CODE field is four characters wide")
                    .isEqualTo(4);
            assertThat(AbendException.CULPRIT_LENGTH)
                    .as("the legacy ABEND-CULPRIT field is eight characters wide, which is the"
                            + " longest a legacy program name can be")
                    .isEqualTo(8);
            assertThat(AbendException.REASON_LENGTH)
                    .as("the legacy ABEND-REASON field is fifty characters wide")
                    .isEqualTo(50);
            assertThat(AbendException.MESSAGE_LENGTH)
                    .as("the legacy ABEND-MSG field is seventy-two characters wide")
                    .isEqualTo(72);
        }

        @Test
        @DisplayName("the total context width is 134 and is the arithmetic sum of the four widths")
        void contextWidthIsBoth134AndTheSumOfItsParts() {
            int sumOfFieldWidths = AbendException.CODE_LENGTH
                    + AbendException.CULPRIT_LENGTH
                    + AbendException.REASON_LENGTH
                    + AbendException.MESSAGE_LENGTH;

            assertThat(AbendException.CONTEXT_LENGTH)
                    .as("the legacy abend work area is 134 characters in total")
                    .isEqualTo(134);
            assertThat(AbendException.CONTEXT_LENGTH)
                    .as("the total must remain the sum of its four parts, so that changing one"
                            + " field width can never silently leave the total behind")
                    .isEqualTo(sumOfFieldWidths);
        }
    }

    @Nested
    @DisplayName("The three legacy literals")
    class LiteralConstants {

        @Test
        @DisplayName("the default operator message is reproduced verbatim, in upper case, with its"
                + " trailing full stop")
        void defaultMessageIsReproducedVerbatim() {
            assertThat(AbendException.DEFAULT_MESSAGE)
                    .as("the online abend routine substitutes this exact text when no operator"
                            + " message was supplied")
                    .isEqualTo("UNEXPECTED ABEND OCCURRED.");
            assertThat(AbendException.DEFAULT_MESSAGE.length())
                    .as("the substituted text is twenty-six characters long")
                    .isEqualTo(26);
            assertThat(AbendException.DEFAULT_MESSAGE)
                    .as("the trailing full stop is part of the legacy literal and is not tidied"
                            + " away")
                    .endsWith(".");
            assertThat(AbendException.DEFAULT_MESSAGE)
                    .as("the legacy literal is upper case throughout, so folding it must be a"
                            + " no-op")
                    .isEqualTo(AbendException.DEFAULT_MESSAGE.toUpperCase(Locale.ROOT));
            assertThat(AbendException.DEFAULT_MESSAGE.length())
                    .as("the substituted text has to fit the legacy message field")
                    .isLessThanOrEqualTo(AbendException.MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("the online abend code is four characters and fills the legacy code field"
                + " exactly")
        void onlineAbendCodeFillsTheCodeFieldExactly() {
            assertThat(AbendException.ONLINE_ABEND_CODE)
                    .as("the online tier issues this four-character abend code")
                    .isEqualTo("9999");
            assertThat(AbendException.ONLINE_ABEND_CODE.length())
                    .as("the online code is exactly as wide as the legacy code field, which is"
                            + " precisely why that field is four characters wide")
                    .isEqualTo(AbendException.CODE_LENGTH);

            AbendException online = new AbendException(AbendException.ONLINE_ABEND_CODE,
                    CULPRIT_IN_RANGE, REASON_IN_RANGE, MESSAGE_IN_RANGE);

            assertThat(online.toFixedWidthContext().substring(CODE_START, CULPRIT_START))
                    .as("a four-character code fills its slice with no padding at all")
                    .isEqualTo("9999");
        }

        @Test
        @DisplayName("the batch abend code is three characters, one short of the field, and stays"
                + " that way")
        void batchAbendCodeIsDeliberatelyOneCharacterShorter() {
            assertThat(AbendException.BATCH_ABEND_CODE)
                    .as("the batch tier sets this abend code before calling the Language"
                            + " Environment abort routine")
                    .isEqualTo("999");
            assertThat(AbendException.BATCH_ABEND_CODE.length())
                    .as("the batch code is three characters because it originates as a numeric"
                            + " value rather than as a four-character command literal")
                    .isEqualTo(3);
            assertThat(AbendException.BATCH_ABEND_CODE.length())
                    .as("the two abend codes are genuinely different widths and must never be"
                            + " normalised to match one another")
                    .isLessThan(AbendException.CODE_LENGTH);

            AbendException batch = new AbendException(AbendException.BATCH_ABEND_CODE,
                    CULPRIT_IN_RANGE, REASON_IN_RANGE, MESSAGE_IN_RANGE);

            assertThat(batch.toFixedWidthContext().substring(CODE_START, CULPRIT_START))
                    .as("a three-character code occupies the four-character slice followed by a"
                            + " single space, exactly as the legacy field holds it")
                    .isEqualTo("999 ");
        }
    }

    @Nested
    @DisplayName("All four context components")
    class ContextComponents {

        @Test
        @DisplayName("all four context components are preserved and retrievable after"
                + " construction")
        void allFourContextComponentsArePreservedAndRetrievable() {
            AbendException abend = new AbendException(CODE_IN_RANGE, CULPRIT_IN_RANGE,
                    REASON_IN_RANGE, MESSAGE_IN_RANGE);

            assertThat(abend.code())
                    .as("the first context component, the abend code, is returned unchanged")
                    .isEqualTo(CODE_IN_RANGE);
            assertThat(abend.culprit())
                    .as("the second context component, the originating program, is returned"
                            + " unchanged")
                    .isEqualTo(CULPRIT_IN_RANGE);
            assertThat(abend.reason())
                    .as("the third context component, the failure reason, is returned unchanged")
                    .isEqualTo(REASON_IN_RANGE);
            assertThat(abend.getMessage())
                    .as("the fourth context component is carried by the inherited throwable"
                            + " message rather than duplicated into a field of its own")
                    .isEqualTo(MESSAGE_IN_RANGE);
        }

        @Test
        @DisplayName("the stored values are the unpadded originals, while only the rendered"
                + " context is padded")
        void storedValuesStayUnpaddedWhileOnlyTheRenderingPads() {
            AbendException abend = new AbendException(AbendException.BATCH_ABEND_CODE,
                    CULPRIT_IN_RANGE, REASON_IN_RANGE, MESSAGE_IN_RANGE);

            assertThat(abend.code())
                    .as("the accessor is the store and returns exactly what was supplied, with no"
                            + " padding introduced")
                    .isEqualTo("999")
                    .hasSize(3);
            assertThat(abend.reason())
                    .as("a short reason is stored as supplied and is not widened to the legacy"
                            + " field width")
                    .hasSize(REASON_IN_RANGE.length());
            assertThat(abend.toFixedWidthContext())
                    .as("padding belongs to the rendered legacy image, not to the stored values")
                    .hasSize(AbendException.CONTEXT_LENGTH);
        }

        @Test
        @DisplayName("an eight-character program name fills the culprit field exactly")
        void eightCharacterProgramNameFillsTheCulpritField() {
            AbendException abend = new AbendException(CODE_IN_RANGE, CULPRIT_IN_RANGE,
                    REASON_IN_RANGE, MESSAGE_IN_RANGE);

            assertThat(CULPRIT_IN_RANGE.length())
                    .as("a legacy program name is at most eight characters, which is why the"
                            + " culprit field is eight characters wide")
                    .isEqualTo(AbendException.CULPRIT_LENGTH);
            assertThat(abend.culprit())
                    .as("the program name is stored whole, with nothing trimmed and nothing added")
                    .hasSize(AbendException.CULPRIT_LENGTH);
            assertThat(abend.toFixedWidthContext().substring(CULPRIT_START, REASON_START))
                    .as("a culprit of exactly the field width renders with no padding at all")
                    .isEqualTo(CULPRIT_IN_RANGE);
        }
    }


    @Nested
    @DisplayName("Rendering the 134-character context")
    class FixedWidthContextRendering {

        @Test
        @DisplayName("the rendered context is exactly 134 characters for full, empty and mixed"
                + " values alike")
        void renderedContextIsAlwaysExactly134Characters() {
            AbendException fullWidth = new AbendException(
                    AbendException.ONLINE_ABEND_CODE,
                    CULPRIT_IN_RANGE,
                    "R".repeat(AbendException.REASON_LENGTH),
                    "M".repeat(AbendException.MESSAGE_LENGTH));
            AbendException nothingSupplied = new AbendException(null, null, null, null);
            AbendException mixedWidths = new AbendException(AbendException.BATCH_ABEND_CODE, "CB",
                    REASON_IN_RANGE, "M".repeat(AbendException.MESSAGE_LENGTH));

            assertThat(fullWidth.toFixedWidthContext())
                    .as("every field at its full legal width still renders exactly 134 characters")
                    .hasSize(AbendException.CONTEXT_LENGTH);
            assertThat(nothingSupplied.toFixedWidthContext())
                    .as("supplying nothing at all still renders exactly 134 characters, because"
                            + " the width is the contract rather than the content")
                    .hasSize(AbendException.CONTEXT_LENGTH);
            assertThat(mixedWidths.toFixedWidthContext())
                    .as("a mixture of short and full-width values still renders exactly 134"
                            + " characters")
                    .hasSize(AbendException.CONTEXT_LENGTH);
        }

        @Test
        @DisplayName("each of the four slices carries its own field, left justified and padded on"
                + " the right")
        void eachSliceCarriesItsFieldLeftJustifiedAndRightPadded() {
            AbendException abend = new AbendException(AbendException.BATCH_ABEND_CODE, "CBACT01C",
                    REASON_IN_RANGE, MESSAGE_IN_RANGE);
            String rendered = abend.toFixedWidthContext();

            assertThat(rendered.substring(CODE_START, CULPRIT_START))
                    .as("the first four characters carry the abend code")
                    .isEqualTo(rightPaddedTo("999", AbendException.CODE_LENGTH));
            assertThat(rendered.substring(CULPRIT_START, REASON_START))
                    .as("the next eight characters carry the originating program")
                    .isEqualTo(rightPaddedTo("CBACT01C", AbendException.CULPRIT_LENGTH));
            assertThat(rendered.substring(REASON_START, MESSAGE_START))
                    .as("the next fifty characters carry the failure reason")
                    .isEqualTo(rightPaddedTo(REASON_IN_RANGE, AbendException.REASON_LENGTH));
            assertThat(rendered.substring(MESSAGE_START, AbendException.CONTEXT_LENGTH))
                    .as("the final seventy-two characters carry the operator message")
                    .isEqualTo(rightPaddedTo(MESSAGE_IN_RANGE, AbendException.MESSAGE_LENGTH));
        }

        @Test
        @DisplayName("short values are padded on the right and never on the left")
        void shortValuesArePaddedOnTheRightAndNeverOnTheLeft() {
            AbendException abend = new AbendException("1", "CB", "SHORT", "SHORT MESSAGE");
            String rendered = abend.toFixedWidthContext();
            String codeSlice = rendered.substring(CODE_START, CULPRIT_START);
            String reasonSlice = rendered.substring(REASON_START, MESSAGE_START);

            assertThat(codeSlice)
                    .as("a one-character code begins its slice, so the padding cannot be on the"
                            + " left")
                    .startsWith("1");
            assertThat(codeSlice.substring(1))
                    .as("everything after a short code is space padding")
                    .isBlank();
            assertThat(reasonSlice)
                    .as("a short reason begins its slice, so the padding cannot be on the left")
                    .startsWith("SHORT");
            assertThat(reasonSlice.substring("SHORT".length()))
                    .as("everything after a short reason is space padding")
                    .isBlank();
            assertThat(reasonSlice)
                    .as("padding is spaces, never zeroes and never any other filler")
                    .isEqualTo(rightPaddedTo("SHORT", AbendException.REASON_LENGTH));
        }

        @Test
        @DisplayName("full-width values render with no padding whatsoever")
        void fullWidthValuesRenderWithNoPaddingWhatsoever() {
            String reasonAtFullWidth = "R".repeat(AbendException.REASON_LENGTH);
            String messageAtFullWidth = "M".repeat(AbendException.MESSAGE_LENGTH);
            AbendException abend = new AbendException(AbendException.ONLINE_ABEND_CODE,
                    CULPRIT_IN_RANGE, reasonAtFullWidth, messageAtFullWidth);
            String rendered = abend.toFixedWidthContext();

            assertThat(rendered.substring(CODE_START, CULPRIT_START))
                    .as("a code of exactly four characters is accepted and rendered whole")
                    .isEqualTo(AbendException.ONLINE_ABEND_CODE);
            assertThat(rendered.substring(CULPRIT_START, REASON_START))
                    .as("a culprit of exactly eight characters is accepted and rendered whole")
                    .isEqualTo(CULPRIT_IN_RANGE);
            assertThat(rendered.substring(REASON_START, MESSAGE_START))
                    .as("a reason of exactly fifty characters is accepted and rendered whole")
                    .isEqualTo(reasonAtFullWidth);
            assertThat(rendered.substring(MESSAGE_START, AbendException.CONTEXT_LENGTH))
                    .as("a message of exactly seventy-two characters is accepted and rendered"
                            + " whole")
                    .isEqualTo(messageAtFullWidth);
            assertThat(rendered)
                    .as("at full width there is nothing left to pad, so the image holds no spaces"
                            + " at all")
                    .doesNotContain(" ");
        }

        @Test
        @DisplayName("the three space-initialised legacy fields render as spaces when nothing was"
                + " supplied")
        void unsuppliedFieldsRenderAsSpaces() {
            AbendException abend = new AbendException(null, null, null, null);
            String rendered = abend.toFixedWidthContext();

            assertThat(rendered)
                    .as("the width of the image never varies with its content")
                    .hasSize(AbendException.CONTEXT_LENGTH);
            assertThat(rendered.substring(CODE_START, MESSAGE_START))
                    .as("the code, culprit and reason fields are initialised to spaces in the"
                            + " legacy work area, so an unsupplied value renders as spaces")
                    .isBlank();
            assertThat(rendered.substring(CODE_START, MESSAGE_START))
                    .as("those three fields together occupy the first sixty-two characters")
                    .hasSize(62);
            // The message slice is deliberately NOT blank here. The online abend routine
            // substitutes its standard operator text when no message was supplied, so an abend
            // never presents an operator with an empty message. Faithfulness to that behaviour is
            // why the whole 134 characters can never be blank, and asserting otherwise would
            // assert a defect.
            assertThat(rendered.substring(MESSAGE_START, AbendException.CONTEXT_LENGTH))
                    .as("the message slice carries the substituted standard text, padded to the"
                            + " legacy field width")
                    .isEqualTo(rightPaddedTo(AbendException.DEFAULT_MESSAGE,
                            AbendException.MESSAGE_LENGTH));
        }

        @Test
        @DisplayName("rendering is a pure derivation, so calling it twice yields the same image")
        void renderingIsPureAndRepeatable() {
            AbendException abend = new AbendException(CODE_IN_RANGE, CULPRIT_IN_RANGE,
                    REASON_IN_RANGE, MESSAGE_IN_RANGE);

            String firstCall = abend.toFixedWidthContext();
            String secondCall = abend.toFixedWidthContext();

            assertThat(secondCall)
                    .as("the image is derived from the stored values on every call and is never"
                            + " itself the store, so repeated calls cannot drift")
                    .isEqualTo(firstCall);
            assertThat(abend.code())
                    .as("rendering has no side effect on the stored code")
                    .isEqualTo(CODE_IN_RANGE);
            assertThat(abend.culprit())
                    .as("rendering has no side effect on the stored culprit")
                    .isEqualTo(CULPRIT_IN_RANGE);
            assertThat(abend.reason())
                    .as("rendering has no side effect on the stored reason")
                    .isEqualTo(REASON_IN_RANGE);
            assertThat(abend.getMessage())
                    .as("rendering has no side effect on the inherited message")
                    .isEqualTo(MESSAGE_IN_RANGE);
        }
    }


    @Nested
    @DisplayName("Over-length values are refused, never truncated")
    class OverLengthRejection {

        @Test
        @DisplayName("a code one character too long is refused, naming the field, its width and"
                + " the offending length")
        void codeLongerThanTheLegacyFieldIsRefused() {
            String codeTooLong = "9".repeat(AbendException.CODE_LENGTH + 1);

            assertThatThrownBy(() -> new AbendException(codeTooLong, CULPRIT_IN_RANGE,
                    REASON_IN_RANGE, MESSAGE_IN_RANGE))
                    .as("a five-character code cannot be held by a four-character field")
                    .isInstanceOf(IllegalArgumentException.class)
                    .message()
                    .as("the failure has to name the legacy field so a reader can find it")
                    .containsIgnoringCase("ABEND-CODE")
                    .as("the failure has to state the legal width")
                    .contains(String.valueOf(AbendException.CODE_LENGTH))
                    .as("the failure has to state the offending length")
                    .contains(String.valueOf(codeTooLong.length()));
        }

        @Test
        @DisplayName("a culprit one character too long is refused, naming the field, its width and"
                + " the offending length")
        void culpritLongerThanTheLegacyFieldIsRefused() {
            String culpritTooLong = "C".repeat(AbendException.CULPRIT_LENGTH + 1);

            assertThatThrownBy(() -> new AbendException(CODE_IN_RANGE, culpritTooLong,
                    REASON_IN_RANGE, MESSAGE_IN_RANGE))
                    .as("a nine-character culprit cannot be held by an eight-character field")
                    .isInstanceOf(IllegalArgumentException.class)
                    .message()
                    .as("the failure has to name the legacy field so a reader can find it")
                    .containsIgnoringCase("ABEND-CULPRIT")
                    .as("the failure has to state the legal width")
                    .contains(String.valueOf(AbendException.CULPRIT_LENGTH))
                    .as("the failure has to state the offending length")
                    .contains(String.valueOf(culpritTooLong.length()));
        }

        @Test
        @DisplayName("a reason one character too long is refused, naming the field, its width and"
                + " the offending length")
        void reasonLongerThanTheLegacyFieldIsRefused() {
            String reasonTooLong = "R".repeat(AbendException.REASON_LENGTH + 1);

            assertThatThrownBy(() -> new AbendException(CODE_IN_RANGE, CULPRIT_IN_RANGE,
                    reasonTooLong, MESSAGE_IN_RANGE))
                    .as("a fifty-one character reason cannot be held by a fifty-character field")
                    .isInstanceOf(IllegalArgumentException.class)
                    .message()
                    .as("the failure has to name the legacy field so a reader can find it")
                    .containsIgnoringCase("ABEND-REASON")
                    .as("the failure has to state the legal width")
                    .contains(String.valueOf(AbendException.REASON_LENGTH))
                    .as("the failure has to state the offending length")
                    .contains(String.valueOf(reasonTooLong.length()));
        }

        @Test
        @DisplayName("a message one character too long is refused, naming the field, its width and"
                + " the offending length")
        void messageLongerThanTheLegacyFieldIsRefused() {
            String messageTooLong = "M".repeat(AbendException.MESSAGE_LENGTH + 1);

            assertThatThrownBy(() -> new AbendException(CODE_IN_RANGE, CULPRIT_IN_RANGE,
                    REASON_IN_RANGE, messageTooLong))
                    .as("a seventy-three character message cannot be held by a seventy-two"
                            + " character field")
                    .isInstanceOf(IllegalArgumentException.class)
                    .message()
                    .as("the failure has to name the legacy field so a reader can find it")
                    .containsIgnoringCase("ABEND-MSG")
                    .as("the failure has to name the rejected value on this class as well")
                    .containsIgnoringCase("message")
                    .as("the failure has to state the legal width")
                    .contains(String.valueOf(AbendException.MESSAGE_LENGTH))
                    .as("the failure has to state the offending length")
                    .contains(String.valueOf(messageTooLong.length()));
        }

        @Test
        @DisplayName("construction fails outright instead of trimming the value down to fit")
        void constructionFailsInsteadOfTruncating() {
            String reasonOneTooLong = "R".repeat(AbendException.REASON_LENGTH + 1);

            // Faithful over idiomatic, inverted deliberately and for one reason only. A legacy
            // move into a shorter field truncates in silence; this translation refuses instead,
            // because a truncated abend context is a lost diagnostic and losing a diagnostic at
            // the moment of termination is the one legacy behaviour worth improving upon. The
            // refusal is therefore a documented divergence, not an oversight.
            assertThatThrownBy(() -> new AbendException(CODE_IN_RANGE, CULPRIT_IN_RANGE,
                    reasonOneTooLong, MESSAGE_IN_RANGE))
                    .as("no instance is produced at all, so no caller can ever observe a silently"
                            + " shortened reason")
                    .isInstanceOf(IllegalArgumentException.class);

            AbendException atTheBoundary = new AbendException(CODE_IN_RANGE, CULPRIT_IN_RANGE,
                    "R".repeat(AbendException.REASON_LENGTH), MESSAGE_IN_RANGE);

            assertThat(atTheBoundary.reason())
                    .as("one character shorter is legal and is kept whole, which shows the refusal"
                            + " is a width check rather than an aversion to long values")
                    .hasSize(AbendException.REASON_LENGTH)
                    .isEqualTo("R".repeat(AbendException.REASON_LENGTH));
        }
    }

    @Nested
    @DisplayName("Absent values are normalised, never stringified")
    class AbsentValueNormalisation {

        @Test
        @DisplayName("an absent code, culprit or reason becomes the empty string and never the"
                + " text null")
        void absentCodeCulpritAndReasonBecomeEmptyStrings() {
            AbendException abend = new AbendException(null, null, null, MESSAGE_IN_RANGE);

            assertThat(abend.code())
                    .as("the legacy code field is initialised to spaces and so is never absent")
                    .isEmpty();
            assertThat(abend.code())
                    .as("an absent code never degrades into the four-character text null")
                    .isNotEqualTo("null");
            assertThat(abend.culprit())
                    .as("the legacy culprit field is initialised to spaces, so it is never absent")
                    .isEmpty();
            assertThat(abend.culprit())
                    .as("an absent culprit never degrades into the four-character text null")
                    .isNotEqualTo("null");
            assertThat(abend.reason())
                    .as("the legacy reason field is initialised to spaces and so is never absent")
                    .isEmpty();
            assertThat(abend.reason())
                    .as("an absent reason never degrades into the four-character text null")
                    .isNotEqualTo("null");
            assertThat(abend.toFixedWidthContext())
                    .as("no rendered image may ever leak the four-character text null into a"
                            + " field an operator reads")
                    .doesNotContain("null");
        }

        @Test
        @DisplayName("an absent message is replaced by the standard operator text")
        void absentMessageIsReplacedByTheStandardOperatorText() {
            AbendException abend = new AbendException(CODE_IN_RANGE, CULPRIT_IN_RANGE,
                    REASON_IN_RANGE, null);

            assertThat(abend.getMessage())
                    .as("the online abend routine substitutes its standard text when no message"
                            + " was supplied, so an operator is never shown an empty message")
                    .isEqualTo(AbendException.DEFAULT_MESSAGE);
            assertThat(abend.toFixedWidthContext())
                    .as("the rendered image carries the substituted text and never the word null")
                    .doesNotContain("null")
                    .contains(AbendException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("a blank message is treated exactly like an absent one")
        void blankMessageIsTreatedLikeAnAbsentOne() {
            AbendException fromEmptyText = new AbendException(CODE_IN_RANGE, CULPRIT_IN_RANGE,
                    REASON_IN_RANGE, "");
            AbendException fromSpaces = new AbendException(CODE_IN_RANGE, CULPRIT_IN_RANGE,
                    REASON_IN_RANGE, "    ");

            assertThat(fromEmptyText.getMessage())
                    .as("an empty message is no message, so the standard text is substituted")
                    .isEqualTo(AbendException.DEFAULT_MESSAGE);
            assertThat(fromSpaces.getMessage())
                    .as("the legacy field holds spaces when it holds nothing, so a message of"
                            + " spaces is treated exactly like an absent one")
                    .isEqualTo(AbendException.DEFAULT_MESSAGE);
        }
    }

    @Nested
    @DisplayName("Chained cause")
    class CausePropagation {

        @Test
        @DisplayName("a supplied cause is retained by identity and leaves the context untouched")
        void suppliedCauseIsRetainedByIdentity() {
            IOException underlying = new IOException("io failure");

            AbendException abend = new AbendException(CODE_IN_RANGE, CULPRIT_IN_RANGE,
                    REASON_IN_RANGE, MESSAGE_IN_RANGE, underlying);

            assertThat(abend.getCause())
                    .as("the original failure stays reachable, so the diagnostic trail is never"
                            + " broken at the point of termination")
                    .isSameAs(underlying);
            assertThat(abend.code())
                    .as("chaining a cause does not disturb the abend code")
                    .isEqualTo(CODE_IN_RANGE);
            assertThat(abend.culprit())
                    .as("chaining a cause does not disturb the culprit")
                    .isEqualTo(CULPRIT_IN_RANGE);
            assertThat(abend.reason())
                    .as("chaining a cause does not disturb the reason")
                    .isEqualTo(REASON_IN_RANGE);
            assertThat(abend.getMessage())
                    .as("chaining a cause does not disturb the operator message")
                    .isEqualTo(MESSAGE_IN_RANGE);
        }

        @Test
        @DisplayName("an abend raised without a cause has none")
        void abendWithoutACauseHasNone() {
            AbendException abend = new AbendException(CODE_IN_RANGE, CULPRIT_IN_RANGE,
                    REASON_IN_RANGE, MESSAGE_IN_RANGE);

            assertThat(abend.getCause())
                    .as("the four-argument form chains nothing, matching the legacy sites that"
                            + " abend on a status check rather than on a caught failure")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("Batch convenience form")
    class BatchConvenienceConstructor {

        @Test
        @DisplayName("the two-argument form applies the batch abend code and the standard operator"
                + " text")
        void twoArgumentFormAppliesBatchCodeAndStandardText() {
            AbendException abend = new AbendException("CBTRN02C", REASON_IN_RANGE);

            assertThat(abend.code())
                    .as("the batch tier always abends with the same code, so the convenience form"
                            + " supplies it")
                    .isEqualTo(AbendException.BATCH_ABEND_CODE);
            assertThat(abend.culprit())
                    .as("the supplied program name is kept intact")
                    .isEqualTo("CBTRN02C");
            assertThat(abend.reason())
                    .as("the supplied reason is kept intact")
                    .isEqualTo(REASON_IN_RANGE);
            assertThat(abend.getMessage())
                    .as("no message is supplied by this form, so the standard operator text"
                            + " applies")
                    .isEqualTo(AbendException.DEFAULT_MESSAGE);
            assertThat(abend.getCause())
                    .as("the convenience form chains nothing")
                    .isNull();
            assertThat(abend.toFixedWidthContext())
                    .as("the convenience form renders the same fixed-width image as every other"
                            + " form")
                    .hasSize(AbendException.CONTEXT_LENGTH)
                    .startsWith(rightPaddedTo(AbendException.BATCH_ABEND_CODE,
                            AbendException.CODE_LENGTH));
        }
    }

    @Nested
    @DisplayName("Type identity and serialization")
    class TypeIdentityAndSerialization {

        @Test
        @DisplayName("the exception is unchecked, so no translated call site is forced to declare"
                + " it")
        void theExceptionIsUnchecked() {
            AbendException abend = new AbendException(CODE_IN_RANGE, CULPRIT_IN_RANGE,
                    REASON_IN_RANGE, MESSAGE_IN_RANGE);

            assertThat(abend)
                    .as("an abend is terminal and unrecoverable, so making callers declare it"
                            + " would add ceremony the legacy never had")
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("the exception extends RuntimeException directly, with no intermediate base"
                + " class")
        void theExceptionExtendsRuntimeExceptionDirectly() {
            assertThat(AbendException.class.getSuperclass())
                    .as("there is no intermediate base class between this type and"
                            + " RuntimeException, so the hierarchy stays as shallow as the single"
                            + " legacy abend surface it represents")
                    .isEqualTo(RuntimeException.class);
        }

        @Test
        @DisplayName("the serialization identity is the explicitly declared value one")
        void serializationIdentityIsTheExplicitlyDeclaredOne() {
            ObjectStreamClass descriptor = ObjectStreamClass.lookup(AbendException.class);

            assertThat(descriptor)
                    .as("the type is serializable because every throwable is")
                    .isNotNull();
            assertThat(descriptor.getSerialVersionUID())
                    .as("an explicitly declared identity of one, which a compiler-generated hash"
                            + " could never be, so the identity is stable across builds")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("a serialization round trip preserves all four context components and the"
                + " rendered image")
        void serializationRoundTripPreservesTheWholeContext()
                throws IOException, ClassNotFoundException {
            IOException underlying = new IOException("io failure");
            AbendException original = new AbendException(AbendException.ONLINE_ABEND_CODE,
                    CULPRIT_IN_RANGE, REASON_IN_RANGE, MESSAGE_IN_RANGE, underlying);

            AbendException restored = serializeAndBack(original);

            assertThat(restored)
                    .as("the round trip really did produce a new object rather than the original")
                    .isNotSameAs(original);
            assertThat(restored.code())
                    .as("the abend code survives serialization")
                    .isEqualTo(original.code());
            assertThat(restored.culprit())
                    .as("the culprit survives serialization")
                    .isEqualTo(original.culprit());
            assertThat(restored.reason())
                    .as("the reason survives serialization")
                    .isEqualTo(original.reason());
            assertThat(restored.getMessage())
                    .as("the operator message survives serialization, which it must, because it"
                            + " is the fourth context component and lives in the superclass")
                    .isEqualTo(original.getMessage());
            assertThat(restored.toFixedWidthContext())
                    .as("the rendered legacy image is identical after the round trip")
                    .isEqualTo(original.toFixedWidthContext())
                    .hasSize(AbendException.CONTEXT_LENGTH);
            assertThat(restored.getCause())
                    .as("the chained cause survives serialization as an equivalent failure")
                    .isInstanceOf(IOException.class)
                    .hasMessage("io failure");
        }
    }
}

