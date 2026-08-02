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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.carddemo.domain.enums.DateFormat;
import com.carddemo.util.CobolStringUtils;

/**
 * Shared date validation, translated from the two legacy date authorities of the estate and exposed
 * through <strong>two deliberately separate entry points</strong> that take different input, produce
 * different output and serve different callers, and are never conflated. {@link #validateCcyymmddDate}
 * reproduces the field-level, message-accumulating cascade of {@code [app/cpy/CSUTLDPY.cpy]} that the
 * account-update program drives four times; {@link #validateDateOfBirth} reproduces the separate range
 * driven after it; {@link #validateDate} reproduces a static invocation of
 * {@code [app/cbl/CSUTLDTC.cbl]} and returns the eighty-character result block its callers read.
 *
 * <p><strong>The cascade is the most dangerous translation in the online tier.</strong> Its head
 * paragraph, {@code [app/cpy/CSUTLDPY.cpy:L18]}, has a body of one statement that sets the flag group to
 * all-invalid and validates nothing; every check lives in the eleven paragraphs the invocation range
 * falls through. A translation mapping only the head would compile, would look correct, and would
 * validate nothing. The five real stages are therefore invoked in source order &mdash; year, month, day,
 * combined day/month/year, Language Environment &mdash; each keeping its own early exit, so a failing
 * stage short-circuits exactly where the legacy short-circuits.
 *
 * <p><strong>No overall "is valid" verdict is returned.</strong> The only statement declaring a date
 * good, {@code [app/cpy/CSUTLDPY.cpy:L327]}, sits behind the guard at
 * {@code [app/cpy/CSUTLDPY.cpy:L274]} that the migration analysis records as never satisfied. The guard
 * is reproduced exactly as written rather than resolved either way; see {@link #editDateLe}.
 *
 * <p>The service composes no operator message and owns no screen: every message here is the bare suffix
 * the copybook declares, because the label prefix, the message field and the input-error flag are all
 * the caller's own work fields. It sets no attribute, positions no cursor, sends no map, reads no
 * repository and performs no arithmetic on money.
 *
 * <p><strong>Every width is a byte width.</strong> The cascade input field, the two linkage parameters
 * and the result area are byte reservations, so every check and truncation is performed on the
 * {@link java.nio.charset.StandardCharsets#US_ASCII} encoded image and never on a character count. A
 * value carrying an unrepresentable character is <strong>rejected before it is encoded</strong>: it
 * would encode wider than its character count and overrun the eighty-byte area both callers overlay as
 * four plus eleven plus four plus sixty-one, silently shifting the severity code and the message number
 * the acceptance test reads. Only representability is gated &mdash; control bytes are deliberately
 * admitted, because the cascade tests its input for an all-null image at
 * {@code [app/cpy/CSUTLDPY.cpy:L30]} and must still be able to observe that state.
 *
 * <p>The diagnostics record only values this module owns: the outcome, the mask identifier, the severity
 * and its own message number. None records the candidate, the subject, or a third-party parser's
 * message, which quotes the offending text back verbatim. The candidate is external fixed-width text
 * that may carry a line separator, so a record built by appending it could be split into what looks like
 * a second record.
 *
 * <p>Stateless singleton; every flag, parsed field and accumulated message lives in a per-invocation
 * state object.
 */
@Service
public final class DateValidationService {

    private static final Logger LOG = LoggerFactory.getLogger(DateValidationService.class);

    /* The two-level acceptance test, reproduced from [app/cbl/CORPT00C.cbl:L396-L406, L416-L426] and
     * mirrored at [app/cbl/COTRN02C.cbl:L397, L417]. Both comparisons are on four-character text. */

    /**
     * Severity code the callers accept outright. The comparison is textual rather than numeric because the
     * caller's overlay reads the field as four characters.
     */
    private static final String ACCEPTED_SEVERITY_CODE = "0000";

    /**
     * Message number the callers accept even when the severity is non-zero: the unsupported-range condition
     * at {@code [app/cbl/CSUTLDTC.cbl:L66]}, which both program-level callers silently tolerate.
     */
    private static final String TOLERATED_MESSAGE_NUMBER = "2513";

    /* Widths of the legacy fields this service reads and writes. Every one is a byte reservation. */

    private static final int CCYYMMDD_WIDTH = 8;

    private static final int LINKAGE_TEXT_WIDTH = 10;

    private static final String LS_DATE_FIELD = "LS-DATE";

    private static final String LS_DATE_FORMAT_FIELD = "LS-DATE-FORMAT";

    private static final int CODE_WIDTH = 4;

    private static final int RESULT_TEXT_WIDTH = 15;

    private static final int RESULT_BLOCK_WIDTH = 80;

    /** Width of the caller's trailing message view. Four plus eleven plus four plus sixty-one is
     * eighty, so caller and callee agree on the block. */
    private static final int MESSAGE_SEGMENT_WIDTH = 61;

    private static final int YEAR_WIDTH = 4;

    private static final int MONTH_DAY_WIDTH = 2;

    /**
     * Highest code unit the single-byte character set of the legacy fields can represent. A value
     * carrying anything above it is refused before any encode, because such a character occupies more
     * than one byte and would overrun the fixed-width field it is being moved into, shifting every field
     * that follows it in the block. Only representability is gated: control bytes are legitimate, since
     * the cascade must still be able to observe an all-null input field.
     */
    private static final char MAX_SINGLE_BYTE_CHARACTER = 0x7F;

    /* Result-block label fillers. Each carries a literal narrower than its field, so the trailing
     * padding is part of the field and occupies block positions. */

    private static final String MESSAGE_CODE_LABEL = "Mesg Code: ";

    private static final String TESTED_DATE_LABEL = "TstDate: ";

    /** Ten-byte filler carrying exactly ten characters, so this one has no padding,
     * {@code [app/cbl/CSUTLDTC.cbl:L54]}. */
    private static final String MASK_USED_LABEL = "Mask used:";

    private static final String SINGLE_SPACE = " ";

    private static final String TRAILING_FILLER = "   ";

    /* The ten outcome texts of the subprogram's selection at [app/cbl/CSUTLDTC.cbl:L128-L149],
     * reproduced character for character including the internal and trailing padding the source
     * literals carry: the receiving field is fifteen characters and several literals are written short
     * of it, so the padding is externally observable and must not be trimmed or regularised. */

    /**
     * Text for the all-zero feedback token.
     *
     * <p><strong>Source anomaly.</strong> The condition name attached to the all-zero token at
     * {@code [app/cbl/CSUTLDTC.cbl:L62]} reads backwards &mdash; an all-zero feedback token signals
     * <em>success</em>. The name is preserved in the citation and the behaviour follows the text the
     * clause actually moves, not the name.
     */
    private static final String TEXT_DATE_IS_VALID = "Date is valid";

    private static final String TEXT_INSUFFICIENT = "Insufficient";

    private static final String TEXT_DATEVALUE_ERROR = "Datevalue error";

    private static final String TEXT_INVALID_ERA = "Invalid Era    ";

    private static final String TEXT_UNSUPPORTED_RANGE = "Unsupp. Range  ";

    private static final String TEXT_INVALID_MONTH = "Invalid month  ";

    private static final String TEXT_BAD_PICTURE_STRING = "Bad Pic String ";

    private static final String TEXT_NON_NUMERIC_DATA = "Nonnumeric data";

    private static final String TEXT_YEAR_IN_ERA_ZERO = "YearInEra is 0 ";

    private static final String TEXT_DATE_IS_INVALID = "Date is invalid";

    /* The thirteen cascade message suffixes, reproduced byte for byte. Each is the tail of a
     * concatenating store whose head is the trimmed field label, so the leading punctuation and spacing
     * below belong to the suffix and not to the label. The spacing is irregular across the set and every
     * irregularity is deliberate: these are externally observable operator texts, so nothing here may be
     * regularised, spaced consistently or tidied. */

    private static final String MESSAGE_YEAR_NOT_SUPPLIED = " : Year must be supplied.";

    /** Uniquely among the thirteen, this suffix carries <strong>no colon</strong>: it reads as a
     * continuation of the field label rather than as an annotation on it.
     * {@code [app/cpy/CSUTLDPY.cpy:L54]} */
    private static final String MESSAGE_YEAR_NOT_FOUR_DIGITS = " must be 4 digit number.";

    private static final String MESSAGE_CENTURY_NOT_VALID = " : Century is not valid.";

    private static final String MESSAGE_MONTH_NOT_SUPPLIED = " : Month must be supplied.";

    /** Emitted by both of the month stage's failure branches with the same flag settings, which is why
     * the order of those two branches is not observable.
     * {@code [app/cpy/CSUTLDPY.cpy:L119, L136]} */
    private static final String MESSAGE_MONTH_OUT_OF_RANGE = ": Month must be a number between 1 and 12.";

    private static final String MESSAGE_DAY_NOT_SUPPLIED = " : Day must be supplied.";

    /** Two source oddities preserved: "day" is lower case where the month equivalent capitalises
     * "Month", and there is no space after the colon. {@code [app/cpy/CSUTLDPY.cpy:L180, L195]} */
    private static final String MESSAGE_DAY_OUT_OF_RANGE = ":day must be a number between 1 and 31.";

    private static final String MESSAGE_CANNOT_HAVE_31_DAYS = ":Cannot have 31 days in this month.";

    private static final String MESSAGE_CANNOT_HAVE_30_DAYS = ":Cannot have 30 days in this month.";

    /** <strong>There is no space after the first period</strong> &mdash; two sentences run together in
     * the source literal. That is externally observable text, so inserting the missing space would
     * change the message. {@code [app/cpy/CSUTLDPY.cpy:L266]} */
    private static final String MESSAGE_NOT_A_LEAP_YEAR =
            ":Not a leap year.Cannot have 29 days in this month.";

    private static final String MESSAGE_LE_SEVERITY_PREFIX = " validation error Sev code: ";

    private static final String MESSAGE_LE_MESSAGE_CODE_PREFIX = " Message code: ";

    /** The trailing space is in the source literal and is retained.
     * {@code [app/cpy/CSUTLDPY.cpy:L363]} */
    private static final String MESSAGE_DATE_IN_FUTURE = ":cannot be in the future ";

    private static final String NO_RETURN_MESSAGE = "";

    /* Numeric bounds and divisors, taken from the condition names the copybook declares:
     * [app/cpy/CSUTLDWY.cpy:L19] months one to twelve, [app/cpy/CSUTLDWY.cpy:L28] days one to
     * thirty-one. Only the two centuries below are accepted. */

    private static final int THIS_CENTURY = 20;

    private static final int LAST_CENTURY = 19;

    private static final int FIRST_MONTH = 1;

    private static final int LAST_MONTH = 12;

    private static final int FEBRUARY = 2;

    private static final int FIRST_DAY = 1;

    private static final int DAY_31 = 31;

    private static final int DAY_30 = 30;

    private static final int DAY_29 = 29;

    /** Divisor chosen when the two-digit year within the century is zero,
     * {@code [app/cpy/CSUTLDPY.cpy:L246]}. */
    private static final int CENTURY_LEAP_DIVISOR = 400;

    private static final int ORDINARY_LEAP_DIVISOR = 4;

    private static final int NOT_NUMERIC = -1;

    /** The per-flag-byte image of the valid state: the null byte. Three of them side by side are the
     * all-valid group image. */
    private static final char LOW_VALUE = '\u0000';

    private static final char SPACE = ' ';

    /** The pad byte for a short sender. Padding is applied to the encoded image rather than to the
     * character sequence, so the field is filled to its declared byte count. */
    private static final byte SPACE_BYTE = (byte) SPACE;

    /* The Language-Environment substitution. The date-service call at [app/cbl/CSUTLDTC.cbl:L116] is
     * replaced by strict java.time parsing, which is behaviour preserving only because STRICT resolution
     * refuses to normalise: a 30th of February fails instead of rolling forward into March. The year
     * field is the era-independent form, because strict resolution would otherwise demand an era and
     * neither legacy mask carries one. */

    private static final String HYPHENATED_PATTERN = "uuuu-MM-dd";

    private static final String COMPACT_PATTERN = "uuuuMMdd";

    private static final DateTimeFormatter HYPHENATED_FORMATTER =
            DateTimeFormatter.ofPattern(HYPHENATED_PATTERN, Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    private static final DateTimeFormatter COMPACT_FORMATTER =
            DateTimeFormatter.ofPattern(COMPACT_PATTERN, Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    /**
     * First date the legacy Lilian date services support, the count beginning on 15 October 1582. A date
     * before that boundary is outside the supported range, which is the condition reported at
     * {@code [app/cbl/CSUTLDTC.cbl:L66]} and the one both program-level callers tolerate.
     */
    private static final LocalDate LILIAN_RANGE_START = LocalDate.of(1582, 10, 15);

    /**
     * Creates the service. Written out explicitly, taking no collaborator, because this is a tier-zero
     * component that injects nothing and holds no state.
     */
    public DateValidationService() {
    }

    /**
     * State of one of the three date-field flags the copybook declares at
     * {@code [app/cpy/CSUTLDWY.cpy:L43-L57]}: a group of three one-character flags for year, month and
     * day, each carrying the same three states. The one-character image is retained because the group is
     * read as a whole against its group-level condition, so the three images side by side are what that
     * comparison sees.
     */
    public enum DateEditFlag {

        /** The field passed its stage; its image is the null byte. */
        VALID(LOW_VALUE),

        /** The field was supplied but failed. Three of these side by side spell the all-invalid group
         * image the head paragraph writes. */
        NOT_OK('0'),

        BLANK('B');

        private final char image;

        DateEditFlag(final char image) {
            this.image = image;
        }

        /** @return the legacy flag byte, which is the null character for the valid state */
        public char getImage() {
            return image;
        }
    }

    /**
     * Outcome of the cascade: the three per-stage flags, the input-error indicator and the accumulated
     * return message.
     *
     * <p><strong>There is deliberately no overall verdict here.</strong> The cascade has no reachable
     * statement that declares a date good, so a synthesised boolean would report a state the legacy
     * cannot produce. A caller that needs a verdict reads the flags, exactly as the account-update
     * program does.
     */
    public record DateEditResult(boolean inputError,
                                 DateEditFlag yearFlag,
                                 DateEditFlag monthFlag,
                                 DateEditFlag dayFlag,
                                 String returnMessage) {

        public DateEditResult {
            Objects.requireNonNull(yearFlag, "yearFlag must not be null");
            Objects.requireNonNull(monthFlag, "monthFlag must not be null");
            Objects.requireNonNull(dayFlag, "dayFlag must not be null");
            Objects.requireNonNull(returnMessage, "returnMessage must not be null; use an empty string");
        }

        /**
         * Renders the three flags as the three-character group image the caller copies out. The valid
         * state contributes a null character, so an all-valid group is three null characters &mdash; the
         * image the group-level condition at {@code [app/cpy/CSUTLDWY.cpy:L44]} compares against.
         *
         * @return the three-character group image
         */
        public String flagsImage() {
            return new String(new char[] {yearFlag.getImage(), monthFlag.getImage(), dayFlag.getImage()});
        }
    }

    /**
     * The ten outcomes of the subprogram's feedback selection, in the order the source declares them.
     * Declaration order is preserved because the legacy selection is evaluated top down and stops at the
     * first match. {@code [app/cbl/CSUTLDTC.cbl:L62-L70, L147]}
     */
    public enum DateFeedback {

        /**
         * The date is good. The condition name on the all-zero token at
         * {@code [app/cbl/CSUTLDTC.cbl:L62]} reads backwards &mdash; an all-zero token means success
         * &mdash; and the behaviour follows the text the clause moves rather than the name.
         */
        DATE_IS_VALID(0, 0),

        INSUFFICIENT_DATA(3, 2507),

        BAD_DATE_VALUE(3, 2508),

        /** Not producible by either mask this estate transmits, because neither carries an era field.
         * The constant exists because the clause exists and every clause must map to a named value. */
        INVALID_ERA(3, 2509),

        /** <strong>The condition both program-level callers silently tolerate</strong> despite its
         * non-zero severity. */
        UNSUPPORTED_RANGE(3, 2513),

        INVALID_MONTH(3, 2517),

        /** Reported when the supplied format mask is not one the estate transmits. */
        BAD_PICTURE_STRING(3, 2518),

        NON_NUMERIC_DATA(3, 2520),

        YEAR_IN_ERA_ZERO(3, 2521),

        /**
         * The fallback arm at {@code [app/cbl/CSUTLDTC.cbl:L147]}, firing precisely when none of the nine
         * matched. Error severity with message number zero, so it is neither the accepted severity nor the
         * tolerated message number and the acceptance test rejects it.
         */
        UNRECOGNISED_FEEDBACK(3, 0);

        private final int severity;

        private final int messageNumber;

        DateFeedback(final int severity, final int messageNumber) {
            this.severity = severity;
            this.messageNumber = messageNumber;
        }

        /** @return zero for success, three for every failure condition the source declares */
        public int getSeverity() {
            return severity;
        }

        /** @return zero for success and for the unrecognised-feedback tail, otherwise the decoded
         * message number */
        public int getMessageNumber() {
            return messageNumber;
        }
    }

    /**
     * Typed form of the eighty-character result block the subprogram returns through its third linkage
     * parameter, declared at {@code [app/cbl/CSUTLDTC.cbl:L42-L57]} as thirteen elementary items whose
     * widths sum to exactly eighty. The callers overlay that same block as four plus eleven plus four
     * plus sixty-one, which is why the label fillers and their padding are modelled rather than dropped:
     * they occupy block positions the caller's overlay counts on.
     */
    public record SubprogramResult(DateFeedback feedback,
                                   String severityCode,
                                   String messageNumber,
                                   String resultText,
                                   String testedDate,
                                   String maskUsed) {

        /**
         * Fail-fast invariants on this migration's own rendering rather than validation of caller input: no
         * caller constructs this record directly, so a failure here can only mean the renderer drifted from
         * the layout.
         *
         * @throws NullPointerException     if any component is {@code null}
         * @throws IllegalArgumentException if any component is not its declared byte width
         */
        public SubprogramResult {
            Objects.requireNonNull(feedback, "feedback must not be null");
            requireWidth(severityCode, CODE_WIDTH, "severityCode");
            requireWidth(messageNumber, CODE_WIDTH, "messageNumber");
            requireWidth(resultText, RESULT_TEXT_WIDTH, "resultText");
            requireWidth(testedDate, LINKAGE_TEXT_WIDTH, "testedDate");
            requireWidth(maskUsed, LINKAGE_TEXT_WIDTH, "maskUsed");
        }

        /**
         * Returns the numeric severity behind the four-character view, reproducing the numeric
         * redefinition the subprogram also moves to the return code at
         * {@code [app/cbl/CSUTLDTC.cbl:L98]} and the copybook stage compares against zero at
         * {@code [app/cpy/CSUTLDPY.cpy:L298]}.
         *
         * @return the numeric severity
         */
        public int numericSeverity() {
            return feedback.getSeverity();
        }

        /**
         * Renders the whole block at exactly eighty encoded bytes, in declaration order and with every
         * literal filler, so the returned value is what the legacy third parameter carries. The label
         * fillers keep their declared padding, since that padding occupies block positions.
         *
         * <p>The width guarantee is a byte guarantee, not a character-count guarantee: every component
         * has already been width-checked on its encoded image, so the assembled block cannot be
         * eighty characters and more than eighty bytes.
         *
         * @return the eighty-byte result block
         */
        public String render() {
            final String block = severityCode
                    + MESSAGE_CODE_LABEL
                    + messageNumber
                    + SINGLE_SPACE
                    + resultText
                    + SINGLE_SPACE
                    + TESTED_DATE_LABEL
                    + testedDate
                    + SINGLE_SPACE
                    + MASK_USED_LABEL
                    + maskUsed
                    + SINGLE_SPACE
                    + TRAILING_FILLER;
            final int encodedWidth = encodedByteWidth(block);
            if (encodedWidth != RESULT_BLOCK_WIDTH) {
                throw new IllegalStateException("the rendered result block must be exactly "
                        + RESULT_BLOCK_WIDTH + " encoded bytes to fill LS-RESULT, but measures "
                        + encodedWidth);
            }
            return block;
        }

        /**
         * Returns the sixty-one character tail of the block the callers overlay as the message. It starts
         * at block position twenty, immediately after the message number, so it opens with the
         * single-space filler and then carries the outcome text &mdash; the caller sees that leading
         * space, and trimming it here would change what the caller displays.
         *
         * @return the message tail, exactly sixty-one encoded bytes
         */
        public String messageSegment() {
            final byte[] block = render().getBytes(StandardCharsets.US_ASCII);
            return new String(block, RESULT_BLOCK_WIDTH - MESSAGE_SEGMENT_WIDTH,
                    MESSAGE_SEGMENT_WIDTH, StandardCharsets.US_ASCII);
        }

        /**
         * Enforces one component width, measured in encoded bytes because the declared width is a byte
         * reservation. Representability is gated first, since a value the charset cannot carry has no byte
         * width its field can hold; the diagnostic names the component and both widths and never the value.
         *
         * @param  value the component
         * @param  width the declared byte width
         * @param  field the component name, for the diagnostic
         * @throws IllegalArgumentException if the encoded image is not exactly {@code width} bytes
         */
        private static void requireWidth(final String value, final int width, final String name) {
            Objects.requireNonNull(value, name + " must not be null");
            requireSingleByteRepresentable(value, name);
            final int encodedWidth = encodedByteWidth(value);
            if (encodedWidth != width) {
                throw new IllegalArgumentException(name + " must be exactly " + width
                        + " encoded bytes to fill its result-block positions, but measures "
                        + encodedWidth);
            }
        }
    }

    /**
     * Which of the two jump targets a cascade paragraph took. Most jumps go to the immediately following
     * exit paragraph of the same stage and are an early return from one method. The combined
     * day/month/year paragraph is different: three of its jumps, and its closing guard, leave the
     * <em>whole</em> range, skipping the Language-Environment stage entirely. That distinction is the
     * reason this enum exists rather than a boolean.
     */
    private enum CascadeFlow {

        FALL_THROUGH,

        /** A jump straight to the exit of the whole range was taken,
         * {@code [app/cpy/CSUTLDPY.cpy:L225, L240, L270, L277]}. */
        GO_TO_RANGE_EXIT
    }

    /**
     * Per-invocation working storage for one run of the cascade. The legacy equivalent lives in the
     * calling program's working storage and is reused across every date the program edits; holding it
     * here, created fresh on entry and discarded on exit, is what makes this service a safe stateless
     * singleton while still reproducing the accumulation the legacy performs within one run.
     */
    private static final class CascadeState {

        private final String ccyymmddImage;

        private final String centuryField;

        private final String yearOfCenturyField;

        private final String yearField;

        private final String monthField;

        private final String dayField;

        private DateEditFlag yearFlag;

        private DateEditFlag monthFlag;

        private DateEditFlag dayFlag;

        private boolean inputError;

        private String returnMessage;

        CascadeState(final String ccyymmddImage,
                     final String currentReturnMessage,
                     final DateEditFlag initialFlag) {
            this.ccyymmddImage = ccyymmddImage;
            this.yearField = ccyymmddImage.substring(0, YEAR_WIDTH);
            this.centuryField = ccyymmddImage.substring(0, MONTH_DAY_WIDTH);
            this.yearOfCenturyField = ccyymmddImage.substring(MONTH_DAY_WIDTH, YEAR_WIDTH);
            this.monthField = ccyymmddImage.substring(YEAR_WIDTH, YEAR_WIDTH + MONTH_DAY_WIDTH);
            this.dayField = ccyymmddImage.substring(YEAR_WIDTH + MONTH_DAY_WIDTH, CCYYMMDD_WIDTH);
            this.yearFlag = initialFlag;
            this.monthFlag = initialFlag;
            this.dayFlag = initialFlag;
            this.inputError = false;
            this.returnMessage = currentReturnMessage;
        }

        /**
         * Tests the group-level valid condition at {@code [app/cpy/CSUTLDWY.cpy:L44]}, which compares the
         * whole three-byte group at once and therefore holds only when all three flags are individually
         * valid. This is the guard the combined stage evaluates before admitting the
         * Language-Environment stage.
         *
         * @return whether all three flags read as valid
         */
        boolean editDateFlagsAreLowValues() {
            return yearFlag == DateEditFlag.VALID
                    && monthFlag == DateEditFlag.VALID
                    && dayFlag == DateEditFlag.VALID;
        }

        DateEditResult toResult() {
            return new DateEditResult(inputError, yearFlag, monthFlag, dayFlag, returnMessage);
        }
    }

    /* Entry point one: the copybook cascade, [app/cpy/CSUTLDPY.cpy]. */

    /**
     * Validates a date through the copybook cascade, starting from a blank accumulated message.
     * Convenience form for the common case in which no earlier field has already claimed the caller's
     * message field.
     *
     * @param  candidateDate the candidate date; moved to the eight-character input field
     * @return the flags, the input-error indicator and the accumulated message
     * @throws NullPointerException     if {@code candidateDate} is {@code null}
     * @throws IllegalArgumentException if it carries a character the single-byte character set cannot
     *                                  represent, since such a value cannot occupy the reserved width
     */
    public DateEditResult validateCcyymmddDate(final String candidateDate) {
        return validateCcyymmddDate(candidateDate, NO_RETURN_MESSAGE);
    }

    /**
     * Validates a date through the copybook cascade, {@code [app/cpy/CSUTLDPY.cpy:L18-L329]}, as driven at
     * {@code [app/cbl/COACTUPC.cbl:L1480, L1492, L1505, L1536]}.
     *
     * <p>An ordered cascade, not five independent checks. A failing stage does <em>not</em> abandon the
     * range: the year stage's early exit lands on the month stage, which is why a blank year and a bad
     * month are both reported on one pass. The message follows the source's <strong>first-wins</strong>
     * rule &mdash; each stage writes its suffix only while the message is still blank &mdash; which is why
     * the caller's message field is passed in rather than assumed empty; in the account-update program it
     * is shared by every field on the screen.
     *
     * @param  currentReturnMessage the caller's accumulated message on entry; empty stands for the blank
     *                              state that lets a stage claim the message
     * @throws IllegalArgumentException if {@code candidateDate} carries a character the single-byte
     *                                  character set cannot represent
     */
    public DateEditResult validateCcyymmddDate(final String candidateDate,
                                               final String currentReturnMessage) {
        Objects.requireNonNull(candidateDate,
                "candidateDate must not be null: an absent field is not a blank field");
        Objects.requireNonNull(currentReturnMessage,
                "currentReturnMessage must not be null; pass an empty string for the blank state");

        final CascadeState state = new CascadeState(moveToCcyymmddField(candidateDate),
                currentReturnMessage, DateEditFlag.NOT_OK);

        editDateCcyymmdd(state);
        editYearCcyy(state);
        editYearCcyyExit();
        editMonth(state);
        editMonthExit();
        editDay(state);
        editDayExit();
        if (editDayMonthYear(state) == CascadeFlow.FALL_THROUGH) {
            editDayMonthYearExit();
            editDateLe(state);
            editDateLeExit(state);
        }
        editDateCcyymmddExit();

        final DateEditResult result = state.toResult();
        LOG.debug("CCYYMMDD cascade finished: year={} month={} day={} inputError={} message=[{}]",
                result.yearFlag(), result.monthFlag(), result.dayFlag(), result.inputError(),
                authoredHere(currentReturnMessage, result.returnMessage()));
        return result;
    }

    /**
     * Applies the date-of-birth reasonableness check, starting from a blank accumulated message.
     *
     * @param  candidateDate the candidate date of birth as an eight-character image
     * @param  currentDate   the current date the check compares against
     * @return the flags, the input-error indicator and the accumulated message
     * @throws NullPointerException if either argument is {@code null}
     */
    public DateEditResult validateDateOfBirth(final String candidateDate, final LocalDate currentDate) {
        return validateDateOfBirth(candidateDate, currentDate, NO_RETURN_MESSAGE);
    }

    /**
     * Applies the date-of-birth reasonableness check, {@code [app/cpy/CSUTLDPY.cpy:L341-L370]}, as driven
     * at {@code [app/cbl/COACTUPC.cbl:L1540]}. The range sits outside the main cascade, which ends at
     * {@code [app/cpy/CSUTLDPY.cpy:L329]}, and is driven in its own right, so this is a separate entry
     * point rather than a sixth stage.
     *
     * <p>Entry precondition: the account-update program drives it only after the main cascade returns and
     * only while the flag group still reads valid, {@code [app/cbl/COACTUPC.cbl:L1538-L1539]}. The three
     * flags therefore start valid and the candidate is expected to be a resolvable calendar date, the
     * legacy conversion having no defined result for one the main cascade would have rejected. The current
     * date is supplied by the caller rather than read from a clock, keeping the service pure.
     *
     * @throws IllegalArgumentException if the candidate is not a resolvable calendar date, meaning the
     *                                  entry precondition was not met, or is not single-byte representable
     */
    public DateEditResult validateDateOfBirth(final String candidateDate,
                                              final LocalDate currentDate,
                                              final String currentReturnMessage) {
        Objects.requireNonNull(candidateDate,
                "candidateDate must not be null: an absent field is not a blank field");
        Objects.requireNonNull(currentDate, "currentDate must not be null");
        Objects.requireNonNull(currentReturnMessage,
                "currentReturnMessage must not be null; pass an empty string for the blank state");

        final CascadeState state = new CascadeState(moveToCcyymmddField(candidateDate),
                currentReturnMessage, DateEditFlag.VALID);

        editDateOfBirth(state, currentDate);
        editDateOfBirthExit();

        final DateEditResult result = state.toResult();
        LOG.debug("Date-of-birth edit finished: inputError={} message=[{}]",
                result.inputError(), authoredHere(currentReturnMessage, result.returnMessage()));
        return result;
    }

    /* Entry point two: the subprogram contract, [app/cbl/CSUTLDTC.cbl]. */

    /**
     * Validates a date against a format mask, reproducing a static invocation of the date-validation
     * subprogram and the whole of its procedure division, {@code [app/cbl/CSUTLDTC.cbl:L88-L102]}. The four
     * genuine call sites are {@code [app/cbl/COTRN02C.cbl:L393, L413]} and
     * {@code [app/cbl/CORPT00C.cbl:L392, L412]}.
     *
     * <p>The legacy third parameter is an output area the caller blanks before the call; here it is a
     * return value, typed, with a renderer that reproduces the eighty-character form.
     *
     * @param candidateDate moved to the ten-character first linkage parameter, so a shorter value is space
     *                      padded on the right
     */
    public SubprogramResult validateDate(final String candidateDate, final DateFormat dateFormat) {
        Objects.requireNonNull(candidateDate,
                "candidateDate must not be null: an absent field is not a blank field");
        Objects.requireNonNull(dateFormat, "dateFormat must not be null");

        final String testedDate = moveToLinkageTextField(candidateDate, LS_DATE_FIELD);
        final String maskUsed = dateFormat.getValue();

        final DateFeedback feedback = a000Main(testedDate, dateFormat);
        a000MainExit();

        final SubprogramResult result = buildResult(feedback, testedDate, maskUsed);
        // The candidate is deliberately absent from this record: it is external fixed-width text that may
        // carry a line separator, so writing it would let a caller append a line of its own choosing and
        // have it read as a record this service emitted.
        LOG.debug("Subprogram date validation: mask=[{}] severity=[{}] messageNumber=[{}] result=[{}]",
                maskUsed, result.severityCode(), result.messageNumber(), result.resultText());
        return result;
    }

    /**
     * Validates a date against a raw format mask, resolving the mask before delegating. Offered because
     * both callers hold the mask in a ten-character work field rather than as a typed value,
     * {@code [app/cbl/CORPT00C.cbl:L72]} and identically {@code [app/cbl/COTRN02C.cbl:L60]}.
     *
     * <p>A mask the estate never transmits is reported as a bad picture string rather than guessed at:
     * substituting one of the two real masks would validate against the wrong picture and return a
     * confidently wrong verdict. The unresolved case therefore yields a non-zero severity with a
     * non-tolerated message number, which the acceptance test rejects.
     */
    public SubprogramResult validateDate(final String candidateDate, final String formatMask) {
        Objects.requireNonNull(candidateDate,
                "candidateDate must not be null: an absent field is not a blank field");
        Objects.requireNonNull(formatMask, "formatMask must not be null");

        final String maskUsed = moveToLinkageTextField(formatMask, LS_DATE_FORMAT_FIELD);
        final Optional<DateFormat> resolved = DateFormat.fromValue(maskUsed);
        if (resolved.isEmpty()) {
            final String testedDate = moveToLinkageTextField(candidateDate, LS_DATE_FIELD);
            // The mask is reported by width rather than by value, and for the same reason the candidate is
            // never reported: on this branch the mask did not resolve, so it is arbitrary external text,
            // whereas a resolved mask would have been the enum's own literal.
            LOG.warn("Unrecognised date-format mask of {} characters; reporting a bad picture string",
                    maskUsed.strip().length());
            return buildResult(DateFeedback.BAD_PICTURE_STRING, testedDate, maskUsed);
        }
        return validateDate(candidateDate, resolved.get());
    }

    /**
     * The two-level acceptance test the four genuine call sites apply to the result block,
     * {@code [app/cbl/CORPT00C.cbl:L396-L406, L416-L426]} and mirrored at
     * {@code [app/cbl/COTRN02C.cbl:L397, L417]}. All four are identical, so it lives here once.
     *
     * <p>Both levels must be kept: the accepted severity passes outright; otherwise a message number that
     * is <em>not</em> the tolerated one is rejected; otherwise the result is accepted silently, so
     * <strong>a non-zero severity carrying the tolerated message number is accepted</strong>. Both
     * comparisons are textual, so neither field is parsed, and the levels do not collapse into one because
     * acceptance can arrive by either route.
     */
    public boolean isDateAcceptable(final SubprogramResult result) {
        Objects.requireNonNull(result, "result must not be null");

        if (ACCEPTED_SEVERITY_CODE.equals(result.severityCode())) {
            return true;
        }
        if (!TOLERATED_MESSAGE_NUMBER.equals(result.messageNumber())) {
            return false;
        }
        // The tested date is not written here either. This branch accepts a value the subprogram flagged,
        // which makes it the record most worth reading and the one an attacker would most want to control;
        // the message number and severity identify the case exactly, and both are this module's own codes.
        LOG.debug("Accepting a date on the tolerated message number [{}] despite severity [{}]",
                result.messageNumber(), result.severityCode());
        return true;
    }

    /* The subprogram's own paragraphs, [app/cbl/CSUTLDTC.cbl], in source order. */

    /**
     * The subprogram's main paragraph, {@code [app/cbl/CSUTLDTC.cbl:L103]}: copy the linkage values across,
     * zero the day-number output, call the date service, decode the severity and message-number halfwords
     * out of the returned feedback token, and select the result text with the ten-arm evaluation at
     * {@code [app/cbl/CSUTLDTC.cbl:L128]}.
     *
     * <p>The date-service call is the one construct that cannot be carried across, so it is replaced by
     * strict {@code java.time} parsing. That substitution is behaviour preserving only because strict
     * resolution refuses to normalise: a 30th of February is rejected rather than rolled into March. The
     * parser reports a single failure rather than a token, so its distinguishable failures are classified
     * onto the matching feedback conditions in a fixed order, each annotated below.
     */
    private static DateFeedback a000Main(final String testedDate, final DateFormat dateFormat) {
        final String pattern = patternFor(dateFormat);
        final int maskLength = pattern.length();

        // A defensive invariant rather than a live branch. The legacy always passes the full ten
        // characters and both masks describe ten positions or fewer, so a mask can never overrun; but
        // insufficient data is the correct outcome if it ever did. Compared in encoded bytes, like every
        // other width comparison here.
        if (encodedByteWidth(testedDate) < maskLength) {
            return DateFeedback.INSUFFICIENT_DATA;
        }
        final String subject = testedDate.substring(0, maskLength);

        // Nothing supplied. "Insufficient data" is the condition for a value too short to satisfy the
        // picture, and an all-blank or all-low-value slot supplies no digits at all.
        if (isLowValuesOrSpaces(subject)) {
            return DateFeedback.INSUFFICIENT_DATA;
        }

        // A non-digit where the picture requires a digit is reported as non-numeric data. Separator
        // positions are deliberately not checked here; a wrong separator is a bad date value and the
        // parser below reports it as one.
        if (!hasDigitsWherePatternRequires(subject, pattern)) {
            return DateFeedback.NON_NUMERIC_DATA;
        }

        // A zero year has to be tested before parsing, because ISO proleptic parsing accepts year zero
        // and would return a date the date service would have refused.
        if (numericView(sliceForPatternField(subject, pattern, 'u', YEAR_WIDTH)) == 0) {
            return DateFeedback.YEAR_IN_ERA_ZERO;
        }

        // A month outside one to twelve has its own condition, which is more specific than the general
        // bad-date-value condition the parser would otherwise produce.
        final int month = numericView(sliceForPatternField(subject, pattern, 'M', MONTH_DAY_WIDTH));
        if (month < FIRST_MONTH || month > LAST_MONTH) {
            return DateFeedback.INVALID_MONTH;
        }

        final LocalDate parsed;
        try {
            parsed = LocalDate.parse(subject, formatterFor(dateFormat));
        } catch (final DateTimeParseException tooBadToResolve) {
            // A well-formed but non-existent calendar date is a bad date value. Neither the value nor the
            // parser's own message is written: the message quotes the offending text back verbatim and
            // would reintroduce the candidate by a route that reads as a library detail.
            LOG.debug("Strict parse rejected a candidate against pattern [{}]; reporting a bad date value",
                    pattern);
            return DateFeedback.BAD_DATE_VALUE;
        }

        // Resolvable, but before the first day the Lilian date services cover.
        if (parsed.isBefore(LILIAN_RANGE_START)) {
            return DateFeedback.UNSUPPORTED_RANGE;
        }
        return DateFeedback.DATE_IS_VALID;
    }

    /**
     * The subprogram's exit paragraph, {@code [app/cbl/CSUTLDTC.cbl:L152]}. Empty and still invoked, so the
     * paragraph-level mapping resolves to a named method rather than disappearing from the record.
     */
    private static void a000MainExit() {
    }

    /* The fourteen cascade paragraphs of [app/cpy/CSUTLDPY.cpy], in source order. Instance methods because
     * the Language-Environment stage re-enters the subprogram entry point on this same component. Where a
     * source condition has an explicit no-operation branch the Java branch is left empty and marked, so an
     * empty branch below is faithful rather than an oversight. */

    /**
     * The head paragraph, {@code [app/cpy/CSUTLDPY.cpy:L18]}. <strong>Its entire body is the single
     * statement at {@code [app/cpy/CSUTLDPY.cpy:L19]}</strong>, which sets the group-level all-invalid
     * condition &mdash; declared on the whole three-byte group, so one statement writes all three flags at
     * once. It performs no validation of any kind, which is exactly why translating the head paragraph
     * alone would produce a validator that validates nothing.
     *
     * @param state the per-invocation working storage
     */
    private void editDateCcyymmdd(final CascadeState state) {
        state.yearFlag = DateEditFlag.NOT_OK;
        state.monthFlag = DateEditFlag.NOT_OK;
        state.dayFlag = DateEditFlag.NOT_OK;
    }

    /**
     * Cascade stage one, {@code [app/cpy/CSUTLDPY.cpy:L25]}: three checks in source order, each with its
     * own early exit &mdash; the year slice must be supplied, it must be four digits, and its century must
     * be one of the only two the source accepts. The order matters because each branch emits a different
     * message. The stage opens by setting the year flag not-valid, so the flag is cleared only by reaching
     * the end at {@code [app/cpy/CSUTLDPY.cpy:L86]}.
     */
    private void editYearCcyy(final CascadeState state) {
        state.yearFlag = DateEditFlag.NOT_OK;

        if (isLowValuesOrSpaces(state.yearField)) {
            state.inputError = true;
            state.yearFlag = DateEditFlag.BLANK;
            setReturnMessage(state, MESSAGE_YEAR_NOT_SUPPLIED);
            return;
        }

        if (!isAllAsciiDigits(state.yearField)) {
            state.inputError = true;
            state.yearFlag = DateEditFlag.NOT_OK;
            setReturnMessage(state, MESSAGE_YEAR_NOT_FOUR_DIGITS);
            return;
        }

        final int century = numericView(state.centuryField);
        if (century == THIS_CENTURY || century == LAST_CENTURY) {
            // Faithful no-op branch.
        } else {
            state.inputError = true;
            state.yearFlag = DateEditFlag.NOT_OK;
            setReturnMessage(state, MESSAGE_CENTURY_NOT_VALID);
            return;
        }

        state.yearFlag = DateEditFlag.VALID;
    }

    /**
     * Stage-one exit, {@code [app/cpy/CSUTLDPY.cpy:L88]}. The target of the year stage's three jumps, and
     * the exit of that <em>stage</em> rather than of the range &mdash; so control continues into the month
     * stage, which is why a blank year and a bad month are both reported on one pass.
     */
    private void editYearCcyyExit() {
    }

    /**
     * Cascade stage two, {@code [app/cpy/CSUTLDPY.cpy:L91]}: three checks in source order, each exiting to
     * {@code [app/cpy/CSUTLDPY.cpy:L145]} &mdash; supplied, then within one to twelve, then numeric.
     * <strong>The range test precedes the numeric test</strong>, which looks inverted but is not
     * observable: both failures emit the same text with the same flag settings, so the order cannot be
     * detected from outside. It is kept as written rather than tidied.
     *
     * @param state the per-invocation working storage
     */
    private void editMonth(final CascadeState state) {
        state.monthFlag = DateEditFlag.NOT_OK;

        if (isLowValuesOrSpaces(state.monthField)) {
            state.inputError = true;
            state.monthFlag = DateEditFlag.BLANK;
            setReturnMessage(state, MESSAGE_MONTH_NOT_SUPPLIED);
            return;
        }

        final int month = numericView(state.monthField);
        if (month >= FIRST_MONTH && month <= LAST_MONTH) {
            // Faithful no-op branch.
        } else {
            state.inputError = true;
            state.monthFlag = DateEditFlag.NOT_OK;
            setReturnMessage(state, MESSAGE_MONTH_OUT_OF_RANGE);
            return;
        }

        if (isAllAsciiDigits(state.monthField)) {
            // The legacy assignment reads the numeric redefinition from the very bytes it redefines, so it
        // preserves the value; the test that guards it is what has observable effect.
        } else {
            state.inputError = true;
            state.monthFlag = DateEditFlag.NOT_OK;
            setReturnMessage(state, MESSAGE_MONTH_OUT_OF_RANGE);
            return;
        }

        state.monthFlag = DateEditFlag.VALID;
    }

    /** Stage-two exit, {@code [app/cpy/CSUTLDPY.cpy:L145]}. A no-operation body; control falls through to
     * the day stage. */
    private void editMonthExit() {
    }

    /**
     * Cascade stage three, {@code [app/cpy/CSUTLDPY.cpy:L150]}: three checks in source order, each exiting
     * to {@code [app/cpy/CSUTLDPY.cpy:L205]} &mdash; supplied, then numeric, then within one to
     * thirty-one. Note that the numeric test precedes the range test here, the opposite of the month
     * stage; both orders are reproduced as written because neither is observable.
     *
     * @param state the per-invocation working storage
     */
    private void editDay(final CascadeState state) {
        state.dayFlag = DateEditFlag.VALID;

        if (isLowValuesOrSpaces(state.dayField)) {
            state.inputError = true;
            state.dayFlag = DateEditFlag.BLANK;
            setReturnMessage(state, MESSAGE_DAY_NOT_SUPPLIED);
            return;
        }

        if (isAllAsciiDigits(state.dayField)) {
            // Faithful no-op branch. As in the month stage, the redefinition read preserves the value.
        } else {
            state.inputError = true;
            state.dayFlag = DateEditFlag.NOT_OK;
            setReturnMessage(state, MESSAGE_DAY_OUT_OF_RANGE);
            return;
        }

        final int day = numericView(state.dayField);
        if (day >= FIRST_DAY && day <= DAY_31) {
            // Faithful no-op branch.
        } else {
            state.inputError = true;
            state.dayFlag = DateEditFlag.NOT_OK;
            setReturnMessage(state, MESSAGE_DAY_OUT_OF_RANGE);
            return;
        }

        state.dayFlag = DateEditFlag.VALID;
    }

    /** Stage-three exit, {@code [app/cpy/CSUTLDPY.cpy:L205]}. A no-operation body; control falls through
     * to the combined day, month and year stage. */
    private void editDayExit() {
    }

    /**
     * Cascade stage four, {@code [app/cpy/CSUTLDPY.cpy:L209]} &mdash; the only stage that judges the
     * combination, so it is where a 31st of April and a 29th of February in a common year are caught. Its
     * three failure branches jump to the exit of the <strong>whole range</strong> rather than to a stage
     * exit, so a combination failure skips the Language-Environment stage entirely.
     *
     * <p>The leap-year decision keeps the source's two-branch shape: a divisor is chosen first &mdash; four
     * hundred when the year within the century is zero, four otherwise &mdash; and only then is the
     * remainder tested. A library predicate would agree on every year this estate accepts and is still not
     * substituted, because the branch structure is what the paragraph-level mapping records and what branch
     * coverage measures. The closing guard at {@code [app/cpy/CSUTLDPY.cpy:L274]} is reproduced exactly as
     * written; see {@link #editDateLe}.
     *
     * @return whether a range-level jump was taken
     */
    private CascadeFlow editDayMonthYear(final CascadeState state) {
        final int month = numericView(state.monthField);
        final int day = numericView(state.dayField);

        if (!isThirtyOneDayMonth(month) && day == DAY_31) {
            state.inputError = true;
            state.dayFlag = DateEditFlag.NOT_OK;
            state.monthFlag = DateEditFlag.NOT_OK;
            setReturnMessage(state, MESSAGE_CANNOT_HAVE_31_DAYS);
            return CascadeFlow.GO_TO_RANGE_EXIT;
        }

        if (month == FEBRUARY && day == DAY_30) {
            state.inputError = true;
            state.dayFlag = DateEditFlag.NOT_OK;
            state.monthFlag = DateEditFlag.NOT_OK;
            setReturnMessage(state, MESSAGE_CANNOT_HAVE_30_DAYS);
            return CascadeFlow.GO_TO_RANGE_EXIT;
        }

        if (month == FEBRUARY && day == DAY_29) {
            final int divisor;
            if (numericView(state.yearOfCenturyField) == 0) {
                divisor = CENTURY_LEAP_DIVISOR;
            } else {
                divisor = ORDINARY_LEAP_DIVISOR;
            }

            final int year = numericView(state.yearField);
            // The legacy division also produces a quotient no later statement reads. A year slice that is
            // not four digits reaches here only when the year stage already failed and jumped to its own
            // exit; the legacy would then divide on non-numeric storage, which has no defined result.
            final int remainder = year == NOT_NUMERIC ? NOT_NUMERIC : year % divisor;
            if (remainder == 0) {
                // Faithful no-op branch.
            } else {
                state.inputError = true;
                state.dayFlag = DateEditFlag.NOT_OK;
                state.monthFlag = DateEditFlag.NOT_OK;
                state.yearFlag = DateEditFlag.NOT_OK;
                setReturnMessage(state, MESSAGE_NOT_A_LEAP_YEAR);
                return CascadeFlow.GO_TO_RANGE_EXIT;
            }
        }

        if (state.editDateFlagsAreLowValues()) {
            // Faithful no-op branch.
        } else {
            return CascadeFlow.GO_TO_RANGE_EXIT;
        }
        return CascadeFlow.FALL_THROUGH;
    }

    /**
     * Stage-four exit, {@code [app/cpy/CSUTLDPY.cpy:L280]}. Nothing jumps here &mdash; the combined stage's
     * jumps all leave the range &mdash; so it is reached only by falling out of the stage, and control then
     * falls through to the Language-Environment stage.
     */
    private void editDayMonthYearExit() {
    }

    /**
     * Cascade stage five, {@code [app/cpy/CSUTLDPY.cpy:L284]} &mdash; a last resort for a bad date that
     * slipped past every edit above: it clears the result block, moves the compact mask, invokes the
     * subprogram, and on a non-zero severity sets the input-error condition, clears all three flags and
     * builds a message quoting both the severity and the message number.
     *
     * <p><strong>Anomaly: this stage is unreachable in the shipped estate, and that shapes the whole public
     * interface.</strong> Entry is gated at {@code [app/cpy/CSUTLDPY.cpy:L274]}, which admits the stage only
     * while the flag group still reads all-valid; the head paragraph writes the all-invalid value into that
     * group before any stage runs, and the migration analysis records the guard as never satisfied. Two
     * load-bearing consequences follow: the embedded subprogram invocation at
     * {@code [app/cpy/CSUTLDPY.cpy:L293]} never executes, so the cascade never invokes the subprogram; and
     * the valid-marking statement at {@code [app/cpy/CSUTLDPY.cpy:L327]} is unreachable with it, so the
     * cascade can only accumulate failures. That is why {@link DateEditResult} reports flags and a message
     * rather than a verdict.
     *
     * <p>The method is kept, implemented in full and invoked in its source position, because every legacy
     * paragraph maps to a named method. The guard is evaluated as written rather than hard-wired to either
     * answer, so Java reachability equals COBOL reachability under every flag state.
     */
    private void editDateLe(final CascadeState state) {
        final SubprogramResult leResult = validateDate(state.ccyymmddImage, DateFormat.YYYYMMDD);

        if (leResult.numericSeverity() == 0) {
            // Faithful no-op branch.
        } else {
            state.inputError = true;
            state.dayFlag = DateEditFlag.NOT_OK;
            state.monthFlag = DateEditFlag.NOT_OK;
            state.yearFlag = DateEditFlag.NOT_OK;
            setReturnMessage(state, MESSAGE_LE_SEVERITY_PREFIX + leResult.severityCode()
                    + MESSAGE_LE_MESSAGE_CODE_PREFIX + leResult.messageNumber());
            return;
        }

        if (!state.inputError) {
            state.dayFlag = DateEditFlag.VALID;
        }
    }

    /**
     * Stage-five exit, {@code [app/cpy/CSUTLDPY.cpy:L323]}. <strong>Not empty:</strong> it carries a
     * no-operation and then a second sentence at {@code [app/cpy/CSUTLDPY.cpy:L327]} that writes all three
     * flags back to the valid image in one statement.
     *
     * <p><strong>Anomaly reproduced, not corrected.</strong> The source comment at
     * {@code [app/cpy/CSUTLDPY.cpy:L326]} asserts that arriving here means every edit cleared, which is
     * false on one path: the severity failure jumps <em>to</em> this paragraph, so a failed
     * Language-Environment check would still have its three flags overwritten as valid while the
     * input-error condition stayed set. Never observed, for the same reason the stage is never entered;
     * carried as written because a reader who "fixes" the guard needs to know it is there.
     */
    private void editDateLeExit(final CascadeState state) {
        state.yearFlag = DateEditFlag.VALID;
        state.monthFlag = DateEditFlag.VALID;
        state.dayFlag = DateEditFlag.VALID;
    }

    /** Range exit, {@code [app/cpy/CSUTLDPY.cpy:L329]}. A no-operation body. This is the end of the
     * performed range and the target of every range-level jump the combined stage takes. */
    private void editDateCcyymmddExit() {
    }

    /**
     * The date-of-birth paragraph, {@code [app/cpy/CSUTLDPY.cpy:L341]} &mdash; a reasonableness check rather
     * than a format check. A date of birth in the future is refused, and the comparison at
     * {@code [app/cpy/CSUTLDPY.cpy:L350]} is strict, so <strong>the current date is itself refused</strong>.
     * The commented-out duration-based alternative at {@code [app/cpy/CSUTLDPY.cpy:L351]} stays inactive, as
     * the source leaves it. On failure all three flags are cleared even though the date is well formed and
     * merely unreasonable, because the screen depends on that to highlight the whole field group.
     */
    private void editDateOfBirth(final CascadeState state, final LocalDate currentDate) {
        final LocalDate dateOfBirth = toCalendarDate(state.ccyymmddImage);

        if (currentDate.isAfter(dateOfBirth)) {
            // Faithful no-op branch.
        } else {
            state.inputError = true;
            state.dayFlag = DateEditFlag.NOT_OK;
            state.monthFlag = DateEditFlag.NOT_OK;
            state.yearFlag = DateEditFlag.NOT_OK;
            setReturnMessage(state, MESSAGE_DATE_IN_FUTURE);
        }
    }

    /** Date-of-birth exit, {@code [app/cpy/CSUTLDPY.cpy:L370]}. A no-operation body, and the end of that
     * range. */
    private void editDateOfBirthExit() {
    }

    /* Private helpers. Each reproduces a single legacy primitive: a fixed-width move, a class or condition
     * test, a redefinition read, or the outcome-text selection. */

    /**
     * Selects the outcome text, reproducing the ten-arm evaluation at
     * {@code [app/cbl/CSUTLDTC.cbl:L128-L149]}. <strong>Arm order is contractual</strong> &mdash; the
     * legacy evaluates top down and stops at the first match &mdash; so the nine explicit arms appear in
     * the source's order and the tenth is the fallback.
     *
     * @param  feedback the outcome
     * @return the text the matching arm moves, padded exactly as the source literal is written
     */
    private static String resultText(final DateFeedback feedback) {
        return switch (feedback) {
            case DATE_IS_VALID -> TEXT_DATE_IS_VALID;
            case INSUFFICIENT_DATA -> TEXT_INSUFFICIENT;
            case BAD_DATE_VALUE -> TEXT_DATEVALUE_ERROR;
            case INVALID_ERA -> TEXT_INVALID_ERA;
            case UNSUPPORTED_RANGE -> TEXT_UNSUPPORTED_RANGE;
            case INVALID_MONTH -> TEXT_INVALID_MONTH;
            case BAD_PICTURE_STRING -> TEXT_BAD_PICTURE_STRING;
            case NON_NUMERIC_DATA -> TEXT_NON_NUMERIC_DATA;
            case YEAR_IN_ERA_ZERO -> TEXT_YEAR_IN_ERA_ZERO;
            default -> TEXT_DATE_IS_INVALID;
        };
    }

    /**
     * Assembles the typed result block. The two four-character code fields are rendered the way a numeric
     * receiving field renders a numeric move: right justified and zero filled, losing high-order digits
     * rather than low-order ones if a value were ever too wide. The shared string utility provides exactly
     * that primitive, so it is used rather than a format string, which would pad differently and would
     * silently accept a five-digit value.
     */
    private static SubprogramResult buildResult(final DateFeedback feedback,
                                                final String testedDate,
                                                final String maskUsed) {
        final String severityCode = CobolStringUtils.rightJustifyZeroFill(
                Integer.toString(feedback.getSeverity()), CODE_WIDTH);
        final String messageNumber = CobolStringUtils.rightJustifyZeroFill(
                Integer.toString(feedback.getMessageNumber()), CODE_WIDTH);
        return new SubprogramResult(feedback,
                severityCode,
                messageNumber,
                padOrTruncate(resultText(feedback), RESULT_TEXT_WIDTH, "WS-RESULT"),
                testedDate,
                maskUsed);
    }

    /**
     * Returns the pattern that stands in for a legacy mask. Both patterns use the era-independent year
     * field, because strict resolution would demand an era from the alternative and neither legacy mask
     * carries one. The switch is exhaustive over the two masks the estate transmits and deliberately has
     * no default arm: a third mask would fail compilation here, forcing its pattern to be decided against
     * the source rather than defaulted.
     *
     * @param  dateFormat the legacy mask
     * @return the equivalent pattern
     */
    private static String patternFor(final DateFormat dateFormat) {
        return switch (dateFormat) {
            case YYYY_MM_DD -> HYPHENATED_PATTERN;
            case YYYYMMDD -> COMPACT_PATTERN;
        };
    }

    /** @return the strict formatter for the given mask, built once and shared */
    private static DateTimeFormatter formatterFor(final DateFormat dateFormat) {
        return switch (dateFormat) {
            case YYYY_MM_DD -> HYPHENATED_FORMATTER;
            case YYYYMMDD -> COMPACT_FORMATTER;
        };
    }

    /**
     * Tests whether every position the pattern describes as a date field holds an ASCII digit. Separator
     * positions are skipped, because a wrong separator is a bad date value rather than non-numeric data and
     * the parser reports it as such.
     *
     * @param  subject the candidate text, already cut to the pattern's length
     * @param  pattern the pattern
     * @return whether every date-field position holds a digit
     */
    private static boolean hasDigitsWherePatternRequires(final String subject, final String pattern) {
        for (int position = 0; position < pattern.length(); position++) {
            final char patternCharacter = pattern.charAt(position);
            if (patternCharacter == 'u' || patternCharacter == 'M' || patternCharacter == 'd') {
                if (!isAsciiDigit(subject.charAt(position))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Cuts out the positions a pattern assigns to one date field. Both patterns place the year first, so
     * the field letter's first occurrence is the field's first position in the subject as well.
     *
     * @param  subject     the candidate text, already cut to the pattern's length
     * @param  pattern     the pattern
     * @param  fieldLetter the pattern letter of the field
     * @param  width       the field's width
     * @return the slice
     */
    private static String sliceForPatternField(final String subject,
                                               final String pattern,
                                               final char fieldLetter,
                                               final int width) {
        final int start = pattern.indexOf(fieldLetter);
        return subject.substring(start, start + width);
    }

    /**
     * Reproduces a move into the eight-character cascade input field.
     *
     * @param  value the sending value
     * @return exactly eight encoded bytes
     * @throws IllegalArgumentException if the sender carries a character the single-byte charset cannot
     *                                  represent
     */
    private static String moveToCcyymmddField(final String value) {
        return padOrTruncate(value, CCYYMMDD_WIDTH, "WS-EDIT-DATE-CCYYMMDD");
    }

    /**
     * Reproduces a move into one of the two ten-character linkage text fields,
     * {@code [app/cbl/CSUTLDTC.cbl:L84-L85]}. The field name is supplied by the caller because the two
     * parameters are distinct fields at the same width, and a rejection has to say which of them was
     * overrun.
     *
     * @param  value     the sending value
     * @param  fieldName the legacy field name, for the diagnostic
     * @return exactly ten encoded bytes
     * @throws IllegalArgumentException if the sender carries a character the single-byte charset cannot
     *                                  represent
     */
    private static String moveToLinkageTextField(final String value, final String fieldName) {
        return padOrTruncate(value, LINKAGE_TEXT_WIDTH, fieldName);
    }

    /**
     * Reproduces an alphanumeric move into a fixed-width field. Such a receiver is left justified: a shorter
     * sender is padded with spaces on the right and a longer one loses its <em>rightmost</em> excess &mdash;
     * the opposite of the right-justified receiver the menu programs use, so the two must not be confused.
     *
     * <p>The move is performed on the encoded image, not on the character sequence. A receiving field
     * declares a byte count, so measuring by character count would leave a field whose byte width is wrong
     * for any sender outside the single-byte range, and the eighty-byte result area both callers overlay
     * would overrun. Representability is gated first, so the value cannot be transcoded silently and the
     * result re-encodes to exactly {@code width} bytes &mdash; which is what makes the character slicing
     * performed downstream byte-faithful.
     */
    private static String padOrTruncate(final String value, final int width, final String fieldName) {
        requireSingleByteRepresentable(value, fieldName);
        final byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        if (encoded.length == width) {
            return value;
        }
        final byte[] field = new byte[width];
        final int copied = Math.min(encoded.length, width);
        System.arraycopy(encoded, 0, field, 0, copied);
        for (int position = copied; position < width; position++) {
            field[position] = SPACE_BYTE;
        }
        return new String(field, StandardCharsets.US_ASCII);
    }

    /**
     * Refuses a value the single-byte character set of the legacy fields cannot carry. The scan runs before
     * any encode, and that ordering is the whole point: the encoder substitutes a question mark for an
     * unmappable character, so encoding first cannot tell a substituted byte from a question mark that was
     * genuinely sent. The loop bound is a character count used purely to walk the value; the width authority
     * is always the encoded length. The diagnostic reports the position and the numeric code unit and never
     * the character, so a rejected value cannot place its own content into a message a caller may log.
     */
    private static void requireSingleByteRepresentable(final String value, final String fieldName) {
        for (int index = 0; index < value.length(); index++) {
            final char candidate = value.charAt(index);
            if (candidate > MAX_SINGLE_BYTE_CHARACTER) {
                throw new IllegalArgumentException(fieldName
                        + " carries a character the single-byte character set of the legacy fields"
                        + " cannot represent, at position " + index + " (code unit "
                        + (int) candidate + "); a fixed-width field reserves bytes, so such a value"
                        + " cannot occupy its declared width and must never be transcoded silently");
            }
        }
    }

    /**
     * Measures a value as encoded bytes in the single-byte character set of the legacy fields. Every width
     * assertion here goes through this method, so no width is ever asserted in {@code String} characters or
     * against the platform default charset. The value must already have passed the representability gate,
     * otherwise the measurement would count substituted bytes.
     *
     * @param  value the value to measure
     * @return the encoded byte width
     */
    private static int encodedByteWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Reproduces the paired all-null-or-all-spaces test as written at
     * {@code [app/cpy/CSUTLDPY.cpy:L30, L94, L154]}. Each half compares the <em>whole</em> field against one
     * figurative value, so a slice that mixes null characters and spaces satisfies neither half and is not
     * treated as unsupplied &mdash; which is the legacy behaviour and is easy to lose by testing character
     * by character.
     *
     * @param  slice the field slice
     * @return whether the slice is entirely null characters or entirely spaces
     */
    private static boolean isLowValuesOrSpaces(final String slice) {
        return isEntirely(slice, LOW_VALUE) || isEntirely(slice, SPACE);
    }

    /**
     * Reproduces the caller's message-blank condition, {@code [app/cbl/COACTUPC.cbl:L480]}, which compares
     * the message field against spaces. An empty string counts as blank, because it is this migration's
     * stand-in for the all-spaces state of a fixed-width field. Only the space character qualifies: any
     * other content, including a null-filled field, means the message has been claimed.
     *
     * @param  message the caller's message field
     * @return whether the field is still blank and can therefore be claimed
     */
    private static boolean isReturnMessageOff(final String message) {
        return isEntirely(message, SPACE);
    }

    /**
     * Reproduces a concatenating store into the return-message field guarded by the message-blank
     * condition &mdash; the shape every one of the cascade's message branches uses, for example at
     * {@code [app/cpy/CSUTLDPY.cpy:L34-L40]}. The guard is what makes the message first-wins, so the
     * earliest failing edit keeps the field even though later stages carry on setting their flags.
     *
     * @param state    the per-invocation working storage
     * @param suffix   the message suffix this edit contributes
     */
    private static void setReturnMessage(final CascadeState state, final String suffix) {
        if (isReturnMessageOff(state.returnMessage)) {
            state.returnMessage = suffix;
        }
    }

    /**
     * Returns an accumulated message in a form that can be written to a log record.
     *
     * <p>The message field is first-wins: {@link #setReturnMessage} is its only writer and stores nothing
     * unless the field was still blank, so the text is this module's own literal <em>exactly when</em> the
     * caller's field was blank on entry. When it was not, the value is the caller's own text, outside this
     * service's control &mdash; possibly assembled from something a person typed, and possibly carrying a
     * line separator that would split one log record into two.
     *
     * <p>So the entry condition decides. A message this module authored is written in full, because it names
     * which edit claimed the field. One the caller brought is described rather than repeated, which loses
     * nothing the caller does not already know. See {@code docs/decision-log.md} DL-100.
     */
    private static String authoredHere(final String currentReturnMessage,
                                       final String accumulatedMessage) {
        return isReturnMessageOff(currentReturnMessage)
                ? accumulatedMessage
                : "unchanged caller message of " + accumulatedMessage.length() + " characters";
    }

    /**
     * Tests whether a value consists entirely of one character. An empty value satisfies the test, matching
     * the vacuous truth of comparing a zero-length field against a figurative value.
     *
     * @param  value    the value
     * @param  expected the only character the value may contain
     * @return whether every character equals {@code expected}
     */
    private static boolean isEntirely(final String value, final char expected) {
        for (int position = 0; position < value.length(); position++) {
            if (value.charAt(position) != expected) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reproduces the numeric class condition on an alphanumeric field, as tested at
     * {@code [app/cpy/CSUTLDPY.cpy:L48, L126, L170]}. Membership is <strong>strict ASCII</strong>: the
     * library digit predicate is Unicode aware and accepts digits from other scripts, which the legacy
     * display field cannot hold and the legacy condition would reject.
     *
     * @param  slice the field slice
     * @return whether every character is an ASCII digit
     */
    private static boolean isAllAsciiDigits(final String slice) {
        if (slice.isEmpty()) {
            return false;
        }
        for (int position = 0; position < slice.length(); position++) {
            if (!isAsciiDigit(slice.charAt(position))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiDigit(final char candidate) {
        return candidate >= '0' && candidate <= '9';
    }

    /**
     * Reads a field slice through its numeric redefinition, as the range conditions at
     * {@code [app/cpy/CSUTLDWY.cpy:L17, L26]} do. Accumulated digit by digit rather than delegated to a
     * library parser, because a parser would also accept a leading sign and non-ASCII digits, neither of
     * which the redefined display field can hold.
     *
     * @param  slice the field slice
     * @return the value the redefinition holds, or the not-numeric sentinel when the slice is not composed
     *         of ASCII digits and therefore cannot equal any declared bound
     */
    private static int numericView(final String slice) {
        if (!isAllAsciiDigits(slice)) {
            return NOT_NUMERIC;
        }
        int value = 0;
        for (int position = 0; position < slice.length(); position++) {
            value = value * 10 + (slice.charAt(position) - '0');
        }
        return value;
    }

    /**
     * Reproduces the thirty-one-day-month condition, {@code [app/cpy/CSUTLDWY.cpy:L21-L23]}. The seven
     * values are written out as that condition writes them rather than derived from a calendar library, so
     * the enumeration a reader must check against the source is visible here.
     *
     * @param  month the month
     * @return whether that month has a thirty-first day
     */
    private static boolean isThirtyOneDayMonth(final int month) {
        return switch (month) {
            case 1, 3, 5, 7, 8, 10, 12 -> true;
            default -> false;
        };
    }

    /**
     * Resolves an eight-character image to a calendar date for the date-of-birth comparison, standing in
     * for the legacy day-number conversion at {@code [app/cpy/CSUTLDPY.cpy:L346]}. Resolution is strict, so
     * the conversion refuses a value that is not a real calendar date instead of normalising it.
     *
     * @param  ccyymmddImage the eight-character image
     * @return the calendar date
     * @throws IllegalArgumentException if the image is not a resolvable calendar date, which means the
     *                                  caller entered the range without the documented precondition
     */
    private static LocalDate toCalendarDate(final String ccyymmddImage) {
        try {
            return LocalDate.parse(ccyymmddImage, COMPACT_FORMATTER);
        } catch (final DateTimeParseException notACalendarDate) {
            throw new IllegalArgumentException("[" + ccyymmddImage + "] is not a resolvable CCYYMMDD"
                    + " calendar date; the date-of-birth range is entered only once the main cascade has"
                    + " left all three field flags valid", notACalendarDate);
        }
    }
}
