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
 * Shared date validation, translated from the two legacy date authorities of the CardDemo estate and
 * exposed through <strong>two deliberately separate entry points</strong> that are never conflated:
 * they take different input, produce different output and serve different callers.
 *
 * <p><strong>The copybook cascade.</strong> {@code validateCcyymmddDate} reproduces
 * {@code PERFORM EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT}, the field-level,
 * message-accumulating validator that the account-update program performs four times - open date at
 * {@code [app/cbl/COACTUPC.cbl:L1480]}, expiry date at {@code [app/cbl/COACTUPC.cbl:L1492]}, reissue
 * date at {@code [app/cbl/COACTUPC.cbl:L1505]} and date of birth at
 * {@code [app/cbl/COACTUPC.cbl:L1536]}. Its companion {@code validateDateOfBirth} reproduces the
 * separate range performed at {@code [app/cbl/COACTUPC.cbl:L1540]}.
 *
 * <h2>Two entry points, two different callers</h2>
 * <ol>
 *   <li><strong>The copybook cascade.</strong> {@code validateCcyymmddDate} reproduces the paragraph
 *       range from {@code EDIT-DATE-CCYYMMDD} through to its own exit paragraph: the field-level,
 *       message-accumulating validator that the account-update program performs four times &mdash; open
 *       date at {@code [app/cbl/COACTUPC.cbl:L1480]}, expiry date at
 *       {@code [app/cbl/COACTUPC.cbl:L1492]}, reissue date at {@code [app/cbl/COACTUPC.cbl:L1505]} and
 *       date of birth at {@code [app/cbl/COACTUPC.cbl:L1536]}. Its companion
 *       {@code validateDateOfBirth} reproduces the separate range performed at
 *       {@code [app/cbl/COACTUPC.cbl:L1540]}.</li>
 *   <li><strong>The subprogram contract.</strong> {@code validateDate} reproduces a static invocation
 *       of the date-validation subprogram {@code CSUTLDTC} and returns the 80-character result block.
 *       The four genuine call sites
 *       are {@code [app/cbl/COTRN02C.cbl:L393]}, {@code [app/cbl/COTRN02C.cbl:L413]},
 *       {@code [app/cbl/CORPT00C.cbl:L392]} and {@code [app/cbl/CORPT00C.cbl:L412]}, which become the
 *       transaction-add and report-request services.</li>
 * </ol>
 * The two are never conflated: they take different input, they produce different output and they serve
 * different callers.
 *
 * <p><strong>The cascade is the most dangerous translation in the online tier.</strong> The head
 * paragraph {@code EDIT-DATE-CCYYMMDD} at {@code [app/cpy/CSUTLDPY.cpy:L18]} has a body of exactly one
 * statement, at {@code [app/cpy/CSUTLDPY.cpy:L19]}: it sets the three-byte flag group to the
 * all-invalid value and performs no validation of any kind. Every check the cascade actually applies
 * lives in the eleven paragraphs the {@code THRU} range falls through, so a translation that mapped
 * only the head paragraph would compile, would look correct, and would validate nothing. The cascade
 * below therefore invokes the five real stages in source order - year, month, day, combined
 * day/month/year, then the Language-Environment stage - each retaining its own early exit, so a
 * failing stage short-circuits exactly where the legacy short-circuits.
 *
 * <h2>Why no overall "is valid" verdict is returned</h2>
 * The only statement in the whole range that declares a date good sits at
 * {@code [app/cpy/CSUTLDPY.cpy:L327]}, in the tail of the Language-Environment stage's exit paragraph,
 * and reaching it requires passing the guard at {@code [app/cpy/CSUTLDPY.cpy:L274]}. That guard admits
 * the stage only while the whole three-byte flag group still reads as valid and the jump at
 * {@code [app/cpy/CSUTLDPY.cpy:L277]} leaves the range otherwise, and the migration analysis records the
 * guard as never satisfied in the shipped estate &mdash; which on that reading makes the embedded
 * invocation of {@code CSUTLDTC} at {@code [app/cpy/CSUTLDPY.cpy:L293]} dead code and the valid-marking
 * statement unreachable with it. The guard is reproduced exactly as written rather than resolved either
 * way here, so this translation neither asserts nor contradicts that finding; see {@code editDateLe}.
 *
 * <p><strong>What this service deliberately does not do.</strong> It does not prefix the field label and
 * does not compose the final operator message; every message below is the bare suffix the copybook
 * declares, because the legacy prefix is {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} - a 25-character
 * work field at {@code [app/cbl/COACTUPC.cbl:L53]} owned by the account-update program, not by the
 * copybook. The 75-character message field at {@code [app/cbl/COACTUPC.cbl:L479]} and the input-error
 * flag at {@code [app/cbl/COACTUPC.cbl:L173]} are likewise the caller's own fields.
 *
 * <h2>What this service deliberately does not do</h2>
 * It does not prefix the field label and it does not compose the final operator message. Every message
 * below is returned as the bare suffix the copybook declares, because the legacy prefix is
 * {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} &mdash; a 25-character work field at
 * {@code [app/cbl/COACTUPC.cbl:L53]} owned by the account-update program, not by the copybook. The
 * 75-character message field at {@code [app/cbl/COACTUPC.cbl:L479]} and the input-error flag at
 * {@code [app/cbl/COACTUPC.cbl:L173]} are likewise the caller's own fields. It sets no screen
 * attribute, positions no cursor, sends no map, touches no repository and performs no monetary
 * arithmetic.
 *
 * <h2>Every width is a byte width</h2>
 * The eight-character cascade input field, the two ten-character linkage parameters and the
 * eighty-character result area are all {@code PIC X(n)} declarations, and a {@code PIC X(n)} field
 * reserves <em>n bytes</em>. So every width check and every truncation in this class is performed on the
 * value's {@code StandardCharsets.US_ASCII} encoded image, never on a {@code String} character count. A
 * value carrying a character that the single-byte character set cannot represent is <strong>rejected
 * before it is encoded</strong> with an {@code IllegalArgumentException}, because such a character
 * encodes to more than one byte: a ten-character value would then be eleven bytes wide and would overrun
 * the eighty-byte area that both callers overlay as four plus eleven plus four plus sixty-one, silently
 * shifting the severity code and the message number that the two-level acceptance test reads. Rejecting
 * before the encode is what makes the refusal exact, since {@code String.getBytes} would otherwise
 * substitute a question mark and leave a field of the right width holding the wrong content.
 *
 * <p>Only representability is gated. Control bytes are deliberately not rejected, because the cascade
 * tests its input field against {@code LOW-VALUES} at {@code [app/cpy/CSUTLDPY.cpy:L30]}, so a
 * null-filled field is a legitimate legacy state the cascade must still be able to observe. Once a value
 * has passed the gate it carries exactly one byte per character, which is what makes the character-index
 * slicing of the year, month and day positions further down byte-faithful rather than merely plausible.
 *
 * <h2>Thread safety</h2>
 * Stateless singleton. Every flag, every parsed field and every accumulated message lives in a
 * per-invocation state object, so concurrent requests cannot observe each other's validation state.
 */
@Service
public final class DateValidationService {

    /** Logger for this service; the legacy diagnostic channel was {@code DISPLAY}. */
    private static final Logger LOG = LoggerFactory.getLogger(DateValidationService.class);

    // The two-level acceptance test. Both comparisons are against four-character literals.
    // [app/cbl/CORPT00C.cbl:L396] to [app/cbl/CORPT00C.cbl:L406] and [app/cbl/CORPT00C.cbl:L416] to
    // [app/cbl/CORPT00C.cbl:L426]; mirrored at [app/cbl/COTRN02C.cbl:L397] and
    // [app/cbl/COTRN02C.cbl:L417].

    /**
     * Severity code that the callers accept outright: the four characters {@code 0000}.
     *
     * <p>This is a character comparison, not a numeric one. The severity field is
     * {@code WS-SEVERITY PIC X(04)} at {@code [app/cbl/CSUTLDTC.cbl:L43]}, redefined as
     * {@code PIC 9(4)} on the next line, and the caller's overlay at
     * {@code [app/cbl/CORPT00C.cbl:L133]} addresses the character view.
     */
    private static final String ACCEPTED_SEVERITY_CODE = "0000";

    /**
     * Message number that the callers accept even when the severity is non-zero: the four characters
     * {@code 2513}.
     *
     * <p>This exemption is not arbitrary. The feedback token bound to the condition name
     * {@code FC-UNSUPP-RANGE} at {@code [app/cbl/CSUTLDTC.cbl:L66]} carries {@code 0x09D1} in its
     * message-number halfword, and {@code 0x09D1} is decimal 2513. The exempted condition is therefore
     * exactly "the date lies outside the range the Language Environment date services support", which
     * both callers chose to tolerate. Dropping the exemption would reject dates the legacy accepts.
     */
    private static final String TOLERATED_MESSAGE_NUMBER = "2513";

    // Field widths, all read from the record layouts rather than assumed.

    /** Width of the cascade input field {@code WS-EDIT-DATE-CCYYMMDD}, {@code [app/cpy/CSUTLDWY.cpy:L4]}. */
    private static final int CCYYMMDD_WIDTH = 8;

    /** Width of {@code LS-DATE} and {@code LS-DATE-FORMAT}, {@code [app/cbl/CSUTLDTC.cbl:L84]}. */
    private static final int LINKAGE_TEXT_WIDTH = 10;

    /** Name of the first linkage parameter {@code LS-DATE}, {@code [app/cbl/CSUTLDTC.cbl:L84]}. */
    private static final String LS_DATE_FIELD = "LS-DATE";

    /** Name of the second linkage parameter {@code LS-DATE-FORMAT}, {@code [app/cbl/CSUTLDTC.cbl:L85]}. */
    private static final String LS_DATE_FORMAT_FIELD = "LS-DATE-FORMAT";

    /** Width of {@code WS-SEVERITY} and of {@code WS-MSG-NO}, {@code [app/cbl/CSUTLDTC.cbl:L43]}. */
    private static final int CODE_WIDTH = 4;

    /** Width of {@code WS-RESULT}, {@code [app/cbl/CSUTLDTC.cbl:L49]}. */
    private static final int RESULT_TEXT_WIDTH = 15;

    /** Width of {@code LS-RESULT}, {@code [app/cbl/CSUTLDTC.cbl:L86]}. */
    private static final int RESULT_BLOCK_WIDTH = 80;

    /**
     * Width of the caller's trailing message view {@code CSUTLDTC-RESULT-MSG},
     * {@code [app/cbl/CORPT00C.cbl:L136]}. Four plus eleven plus four plus sixty-one is eighty, so
     * caller and callee agree on the block.
     */
    private static final int MESSAGE_SEGMENT_WIDTH = 61;

    /** Width of the four-character year slice {@code WS-EDIT-DATE-CCYY}, {@code [app/cpy/CSUTLDWY.cpy:L5]}. */
    private static final int YEAR_WIDTH = 4;

    /** Width of the two-character month and day slices, {@code [app/cpy/CSUTLDWY.cpy:L16]}. */
    private static final int MONTH_DAY_WIDTH = 2;

    /**
     * Highest code unit the single-byte character set of the legacy fields can represent.
     *
     * <p>Every width above is a {@code PIC X(n)} <em>byte</em> reservation, not a character count, so
     * every width check and every truncation in this class is performed on the
     * {@code StandardCharsets.US_ASCII} encoded image. A value carrying a code unit above this bound
     * cannot occupy the byte count its field reserves &mdash; a single such character encodes to more
     * than one byte, so a ten-character value can be eleven bytes wide and would overrun the
     * eighty-byte result area that both callers overlay.
     *
     * <p>Such a value is therefore rejected <em>before</em> it is encoded, and that ordering is the
     * point: {@code String.getBytes} substitutes a question mark for anything the charset cannot
     * represent, so encoding first and measuring afterwards would silently produce a field of the
     * right width holding the wrong content, and the substituted byte would be indistinguishable from
     * a question mark that was genuinely present.
     *
     * <p>Only representability is gated here. Control bytes are <strong>not</strong> rejected, because
     * the cascade tests its input field against {@code LOW-VALUES} at
     * {@code [app/cpy/CSUTLDPY.cpy:L30]}, so a null-filled field is a legitimate legacy state that the
     * cascade must still be able to observe.
     */
    private static final char MAX_SINGLE_BYTE_CHARACTER = 0x7F;

    // =================================================================================================
    // Literal fillers of the 80-character result block. Each carries the padding its declared width
    // implies, because that padding occupies result-block positions.
    // =================================================================================================

    /**
     * The message-code label of the result block, an unnamed filler eleven bytes wide carrying a
     * ten-character literal, at {@code [app/cbl/CSUTLDTC.cbl:L45]}: ten characters in eleven, so one
     * trailing space belongs to the field.
     */
    private static final String MESSAGE_CODE_LABEL = "Mesg Code: ";

    /**
     * The tested-date label of the result block, an unnamed filler nine bytes wide carrying an
     * eight-character literal, at {@code [app/cbl/CSUTLDTC.cbl:L51]}: eight characters in nine, so one
     * trailing space belongs to the field.
     */
    private static final String TESTED_DATE_LABEL = "TstDate: ";

    /**
     * The mask-used label of the result block, an unnamed filler ten bytes wide carrying a
     * ten-character literal, at {@code [app/cbl/CSUTLDTC.cbl:L54]}: exactly ten characters, so the
     * field carries no padding.
     */
    private static final String MASK_USED_LABEL = "Mask used:";

    /** The single-space fillers at {@code [app/cbl/CSUTLDTC.cbl:L48]}, {@code L50}, {@code L53} and {@code L56}. */
    private static final String SINGLE_SPACE = " ";

    /** The three-space trailing filler at {@code [app/cbl/CSUTLDTC.cbl:L57]}. */
    private static final String TRAILING_FILLER = "   ";

    // The ten result texts of the outcome selection at [app/cbl/CSUTLDTC.cbl:L128] to [L149].
    // Reproduced character for character, including the internal and trailing padding that the source
    // literals carry: the comment at [app/cbl/CSUTLDTC.cbl:L126] states that the receiving field is
    // fifteen characters, and several literals are written out to that full width.

    /**
     * Text for the all-zero feedback token, {@code [app/cbl/CSUTLDTC.cbl:L130]}.
     *
     * <p><strong>Source anomaly.</strong> The condition name attached to the all-zero token at
     * {@code [app/cbl/CSUTLDTC.cbl:L62]} is {@code FC-INVALID-DATE}, which reads backwards: an all-zero
     * Language-Environment feedback token signals <em>success</em>. The token semantics govern and the
     * text is correct, so this constant and the outcome that selects it are named for success. The
     * original condition name is recorded here so the mapping stays findable under it.
     */
    private static final String TEXT_DATE_IS_VALID = "Date is valid";

    /** Text at {@code [app/cbl/CSUTLDTC.cbl:L132]}, twelve characters in a fifteen-character field. */
    private static final String TEXT_INSUFFICIENT = "Insufficient";

    /** Text at {@code [app/cbl/CSUTLDTC.cbl:L134]}. */
    private static final String TEXT_DATEVALUE_ERROR = "Datevalue error";

    /** Text at {@code [app/cbl/CSUTLDTC.cbl:L136]}, written to the full width with four trailing spaces. */
    private static final String TEXT_INVALID_ERA = "Invalid Era    ";

    /** Text at {@code [app/cbl/CSUTLDTC.cbl:L138]}, written to the full width with two trailing spaces. */
    private static final String TEXT_UNSUPPORTED_RANGE = "Unsupp. Range  ";

    /** Text at {@code [app/cbl/CSUTLDTC.cbl:L140]}, written to the full width with two trailing spaces. */
    private static final String TEXT_INVALID_MONTH = "Invalid month  ";

    /** Text at {@code [app/cbl/CSUTLDTC.cbl:L142]}, written to the full width with one trailing space. */
    private static final String TEXT_BAD_PICTURE_STRING = "Bad Pic String ";

    /** Text at {@code [app/cbl/CSUTLDTC.cbl:L144]}. */
    private static final String TEXT_NON_NUMERIC_DATA = "Nonnumeric data";

    /** Text at {@code [app/cbl/CSUTLDTC.cbl:L146]}, written to the full width with one trailing space. */
    private static final String TEXT_YEAR_IN_ERA_ZERO = "YearInEra is 0 ";

    /** Text of the tenth clause, {@code WHEN OTHER}, at {@code [app/cbl/CSUTLDTC.cbl:L148]}. */
    private static final String TEXT_DATE_IS_INVALID = "Date is invalid";

    // The thirteen cascade message suffixes, reproduced byte for byte.
    //
    // Every one of them is the tail of a STRING statement whose head is the trimmed field label, so the
    // leading punctuation and spacing below belong to the suffix and not to the label. The spacing is
    // irregular across the set - three suffixes open with " : ", two with ": ", four with ":" alone, one
    // with no colon at all and one closes with a trailing space. That irregularity is the source's, it
    // is externally observable on the account-update screen, and it is reproduced rather than tidied.

    /** Suffix at {@code [app/cpy/CSUTLDPY.cpy:L37]}, emitted when the year slice is blank. */
    private static final String MESSAGE_YEAR_NOT_SUPPLIED = " : Year must be supplied.";

    /**
     * Suffix at {@code [app/cpy/CSUTLDPY.cpy:L54]}, emitted when the year slice is not four digits.
     *
     * <p>Uniquely among the thirteen, this suffix carries <strong>no colon</strong>: it reads as a
     * continuation of the field label rather than as an annotation on it.
     */
    private static final String MESSAGE_YEAR_NOT_FOUR_DIGITS = " must be 4 digit number.";

    /** Suffix at {@code [app/cpy/CSUTLDPY.cpy:L79]}, emitted when the century is neither 19 nor 20. */
    private static final String MESSAGE_CENTURY_NOT_VALID = " : Century is not valid.";

    /** Suffix at {@code [app/cpy/CSUTLDPY.cpy:L101]}, emitted when the month slice is blank. */
    private static final String MESSAGE_MONTH_NOT_SUPPLIED = " : Month must be supplied.";

    /**
     * Suffix at {@code [app/cpy/CSUTLDPY.cpy:L119]} and, identically, at
     * {@code [app/cpy/CSUTLDPY.cpy:L136]}.
     *
     * <p>The month stage has two distinct failure branches &mdash; the range test and the numeric test
     * &mdash; and both emit this same text with the same flag settings, which is why the order of those
     * two branches is not observable.
     */
    private static final String MESSAGE_MONTH_OUT_OF_RANGE = ": Month must be a number between 1 and 12.";

    /** Suffix at {@code [app/cpy/CSUTLDPY.cpy:L161]}, emitted when the day slice is blank. */
    private static final String MESSAGE_DAY_NOT_SUPPLIED = " : Day must be supplied.";

    /**
     * Suffix at {@code [app/cpy/CSUTLDPY.cpy:L180]} and, identically, at
     * {@code [app/cpy/CSUTLDPY.cpy:L195]}.
     *
     * <p>Two source oddities are preserved: the word "day" is lower case where the month equivalent
     * capitalises "Month", and there is no space after the colon.
     */
    private static final String MESSAGE_DAY_OUT_OF_RANGE = ":day must be a number between 1 and 31.";

    /** Suffix at {@code [app/cpy/CSUTLDPY.cpy:L221]}, emitted for a 31st day in a shorter month. */
    private static final String MESSAGE_CANNOT_HAVE_31_DAYS = ":Cannot have 31 days in this month.";

    /** Suffix at {@code [app/cpy/CSUTLDPY.cpy:L236]}, emitted for a 30th of February. */
    private static final String MESSAGE_CANNOT_HAVE_30_DAYS = ":Cannot have 30 days in this month.";

    /**
     * Suffix at {@code [app/cpy/CSUTLDPY.cpy:L266]}, emitted for a 29th of February in a common year.
     *
     * <p><strong>There is no space after the first period.</strong> Two sentences are run together in
     * the source literal. That is externally observable text and it is reproduced exactly; inserting
     * the missing space would change the message a screen displays today.
     */
    private static final String MESSAGE_NOT_A_LEAP_YEAR =
            ":Not a leap year.Cannot have 29 days in this month.";

    /** First segment of the suffix built at {@code [app/cpy/CSUTLDPY.cpy:L308]}. */
    private static final String MESSAGE_LE_SEVERITY_PREFIX = " validation error Sev code: ";

    /** Second segment of the same suffix, at {@code [app/cpy/CSUTLDPY.cpy:L310]}. */
    private static final String MESSAGE_LE_MESSAGE_CODE_PREFIX = " Message code: ";

    /**
     * Suffix at {@code [app/cpy/CSUTLDPY.cpy:L363]}, emitted when a date of birth is not in the past.
     *
     * <p>The trailing space is in the source literal and is retained.
     */
    private static final String MESSAGE_DATE_IN_FUTURE = ":cannot be in the future ";

    /** The empty accumulated message, standing for the all-spaces state of the caller's message field. */
    private static final String NO_RETURN_MESSAGE = "";

    // Calendar constants, every one of them a condition-name value read from
    // app/cpy/CSUTLDWY.cpy rather than from general knowledge.

    /** Value of the condition name {@code THIS-CENTURY}, which is 20, {@code [app/cpy/CSUTLDWY.cpy:L9]}. */
    private static final int THIS_CENTURY = 20;

    /** Value of the condition name {@code LAST-CENTURY}, which is 19, {@code [app/cpy/CSUTLDWY.cpy:L10]}. */
    private static final int LAST_CENTURY = 19;

    /**
     * Lower bound of the condition name {@code WS-VALID-MONTH}, which spans 1 through 12,
     * {@code [app/cpy/CSUTLDWY.cpy:L19]}.
     */
    private static final int FIRST_MONTH = 1;

    /** Upper bound of the same condition name. */
    private static final int LAST_MONTH = 12;

    /** Value of the condition name {@code WS-FEBRUARY}, which is 2, {@code [app/cpy/CSUTLDWY.cpy:L24]}. */
    private static final int FEBRUARY = 2;

    /**
     * Lower bound of the condition name {@code WS-VALID-DAY}, which spans 1 through 31,
     * {@code [app/cpy/CSUTLDWY.cpy:L28]}.
     */
    private static final int FIRST_DAY = 1;

    /** Upper bound of the same condition name, and the value of the condition name {@code WS-DAY-31}. */
    private static final int DAY_31 = 31;

    /** Value of the condition name {@code WS-DAY-30}, which is 30, {@code [app/cpy/CSUTLDWY.cpy:L31]}. */
    private static final int DAY_30 = 30;

    /** Value of the condition name {@code WS-DAY-29}, which is 29, {@code [app/cpy/CSUTLDWY.cpy:L32]}. */
    private static final int DAY_29 = 29;

    /**
     * Divisor chosen when the two-digit year within the century is zero,
     * {@code [app/cpy/CSUTLDPY.cpy:L246]}.
     */
    private static final int CENTURY_LEAP_DIVISOR = 400;

    /** Divisor chosen otherwise, {@code [app/cpy/CSUTLDPY.cpy:L248]}. */
    private static final int ORDINARY_LEAP_DIVISOR = 4;

    /** Sentinel returned by the numeric-view reader when a slice is not composed of ASCII digits. */
    private static final int NOT_NUMERIC = -1;

    /**
     * The single value of the condition name {@code WS-EDIT-DATE-IS-VALID}, expressed per flag byte:
     * the low-value byte.
     */
    private static final char LOW_VALUE = '\u0000';

    /** The space character, used for the {@code SPACES} comparisons and for {@code PIC X} padding. */
    private static final char SPACE = ' ';

    /**
     * The same space as a single encoded byte, used to pad a short sender into a {@code PIC X(n)}
     * receiving field. Padding is applied to the encoded image rather than to the character sequence,
     * so the field is filled to its declared byte count.
     */
    private static final byte SPACE_BYTE = (byte) SPACE;

    // =================================================================================================
    // The Language Environment substitution. CALL "CEEDAYS" at [app/cbl/CSUTLDTC.cbl:L116] is replaced
    // by strict java.time parsing, which is behaviour preserving only because STRICT resolution refuses
    // to normalise: a 30th of February fails instead of rolling forward into March.
    //
    // The year field is the era-independent uuuu rather than yyyy. Under STRICT resolution a yyyy
    // pattern demands an era, which the legacy masks do not carry, so yyyy would fail on every input.
    // The locale is pinned to ROOT so that no ambient default can alter digit or separator handling.

    /** Pattern for {@code DateFormat#YYYY_MM_DD}, the hyphenated ten-character mask. */
    private static final String HYPHENATED_PATTERN = "uuuu-MM-dd";

    /** Pattern for {@code DateFormat#YYYYMMDD}, the compact eight-character mask. */
    private static final String COMPACT_PATTERN = "uuuuMMdd";

    /** Strict formatter for the hyphenated mask, built once. */
    private static final DateTimeFormatter HYPHENATED_FORMATTER =
            DateTimeFormatter.ofPattern(HYPHENATED_PATTERN, Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    /** Strict formatter for the compact mask, built once. */
    private static final DateTimeFormatter COMPACT_FORMATTER =
            DateTimeFormatter.ofPattern(COMPACT_PATTERN, Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    /**
     * First date the Language Environment Lilian date services support.
     *
     * <p>{@code CEEDAYS} converts to a Lilian day number, and the Lilian count begins on 15 October
     * 1582. A date before that boundary is outside the supported range, which is the condition the
     * feedback token at {@code [app/cbl/CSUTLDTC.cbl:L66]} reports and which both program-level callers
     * then tolerate through the {@code 2513} exemption. Strict {@code java.time} parsing accepts such a
     * date happily, so the boundary is tested explicitly rather than left to the parser.
     */
    private static final LocalDate LILIAN_RANGE_START = LocalDate.of(1582, 10, 15);

    /**
     * Creates the service.
     *
     * <p>Declared explicitly, and taking no collaborator, because this is a tier-zero component: it
     * injects no other service and holds no state. The constructor is written out rather than left
     * implicit so that the absence of dependencies is a visible decision.
     */
    public DateValidationService() {
        // No collaborator and no state: every value the service needs is a constant or an argument.
    }

    // NESTED CONTRACT TYPES

    /**
     * State of one of the three date-field flags of {@code WS-EDIT-DATE-FLGS},
     * {@code [app/cpy/CSUTLDWY.cpy:L43]} through {@code [app/cpy/CSUTLDWY.cpy:L57]}.
     *
     * <p>The legacy group is three one-character flags &mdash; year, month and day &mdash; each carrying
     * the same three condition names: valid, not valid, and blank. The one-character image of each state is
     * part of the contract because the account-update program copies the whole three-character group into
     * per-field save areas, for example at {@code [app/cbl/COACTUPC.cbl:L1482]}, and then tests the copy.
     * The two failure states are not interchangeable: a blank field and a present-but-wrong field are
     * reported differently, which is what lets the screen distinguish a missing entry from an invalid one.
     *
     */
    public enum DateEditFlag {

        /**
         * The field passed its stage. Condition name {@code FLG-YEAR-ISVALID} and its month and day
         * peers, whose value is the low-value byte, at {@code [app/cpy/CSUTLDWY.cpy:L47]}.
         */
        VALID(LOW_VALUE),

        /**
         * The field was supplied but failed. Condition name {@code FLG-YEAR-NOT-OK} and peers, whose
         * value is the single character {@code 0}, at {@code [app/cpy/CSUTLDWY.cpy:L48]}. Three of these
         * side by side spell the all-invalid group value that the head paragraph writes.
         */
        NOT_OK('0'),

        /**
         * The field was not supplied at all. Condition name {@code FLG-YEAR-BLANK} and peers, whose
         * value is the single character {@code B}, at {@code [app/cpy/CSUTLDWY.cpy:L49]}.
         */
        BLANK('B');

        /** The one-character image this state occupies in the three-character flag group. */
        private final char image;

        /**
         * Binds a state to its flag-group image.
         *
         * @param image the single character the legacy flag byte holds in this state
         */
        DateEditFlag(final char image) {
            this.image = image;
        }

        /**
         * Returns the one-character image of this state.
         *
         * @return the legacy flag byte, which is the null character for the valid state
         */
        public char getImage() {
            return image;
        }
    }

    /**
     * Outcome of the cascade: the three per-stage flags, the input-error indicator and the accumulated
     * return message.
     *
     * <p><strong>There is deliberately no overall verdict here.</strong> The cascade has no reachable
     * statement that declares a date good, so a synthesised boolean would report a state the legacy cannot
     * produce. A caller that needs the legacy group test performs it the way the account-update program
     * does &mdash; on the copied flag group, {@code [app/cbl/COACTUPC.cbl:L1538]} followed by
     * {@code [app/cbl/COACTUPC.cbl:L1539]} &mdash; by inspecting the three flags this record exposes.
     *
     * @param inputError    whether this edit signalled the caller's input-error condition, whose
     *                      condition name is {@code INPUT-ERROR} and whose value is the single
     *                      character {@code 1}, at {@code [app/cbl/COACTUPC.cbl:L173]}. The
     *                      flag itself belongs to the caller, which accumulates it across every field on
     *                      the screen; this component reports what this edit contributed
     * @param yearFlag      state of {@code WS-EDIT-YEAR-FLG}, {@code [app/cpy/CSUTLDWY.cpy:L46]}
     * @param monthFlag     state of {@code WS-EDIT-MONTH}, {@code [app/cpy/CSUTLDWY.cpy:L50]}
     * @param dayFlag       state of {@code WS-EDIT-DAY}, {@code [app/cpy/CSUTLDWY.cpy:L54]}
     * @param returnMessage the accumulated message: the value carried in, or the suffix this edit set
     *                      when the carried-in value was blank. Never {@code null}; empty stands for the
     *                      all-spaces state of {@code WS-RETURN-MSG}, whose condition name
     *                      {@code WS-RETURN-MSG-OFF} is declared at {@code [app/cbl/COACTUPC.cbl:L480]}.
     *                      The field label is not prefixed here
     */
    public record DateEditResult(boolean inputError,
                                 DateEditFlag yearFlag,
                                 DateEditFlag monthFlag,
                                 DateEditFlag dayFlag,
                                 String returnMessage) {

        /**
         * Validates the components.
         *
         * @throws NullPointerException if any component is {@code null}
         */
        public DateEditResult {
            Objects.requireNonNull(yearFlag, "yearFlag must not be null");
            Objects.requireNonNull(monthFlag, "monthFlag must not be null");
            Objects.requireNonNull(dayFlag, "dayFlag must not be null");
            Objects.requireNonNull(returnMessage, "returnMessage must not be null; use an empty string");
        }

        /**
         * Renders the three flags as the three-character group image the caller copies out.
         *
         * <p>This is {@code WS-EDIT-DATE-FLGS} itself. The valid state contributes a null character, so
         * an all-valid group is three null characters &mdash; the {@code LOW-VALUES} value that the
         * group-level condition name at {@code [app/cpy/CSUTLDWY.cpy:L44]} tests for &mdash; and a group
         * written by the head paragraph alone is the three characters {@code 000}, which is the value at
         * {@code [app/cpy/CSUTLDWY.cpy:L45]}.
         *
         * @return exactly three characters, in year, month, day order
         */
        public String flagsImage() {
            return new String(new char[] {yearFlag.getImage(), monthFlag.getImage(), dayFlag.getImage()});
        }
    }

    /**
     * The ten outcomes of the subprogram's feedback evaluation, in the order the source declares them.
     *
     * <p>The first nine correspond to the nine condition names at {@code [app/cbl/CSUTLDTC.cbl:L62]}
     * through {@code [app/cbl/CSUTLDTC.cbl:L70]}; the tenth stands for the {@code WHEN OTHER} clause at
     * {@code [app/cbl/CSUTLDTC.cbl:L147]}. Declaration order is the evaluation order, and evaluation order
     * is contractual because the source stops at the first matching clause.
     *
     * <p><strong>Severity and message number are decoded from the feedback tokens, not invented.</strong>
     * The token is subdivided at {@code [app/cbl/CSUTLDTC.cbl:L71]} through
     * {@code [app/cbl/CSUTLDTC.cbl:L73]} into a severity halfword followed by a message-number halfword,
     * and both are moved into the result block at {@code [app/cbl/CSUTLDTC.cbl:L123]} and
     * {@code [app/cbl/CSUTLDTC.cbl:L124]}. Reading the two halfwords out of each declared token yields
     * severity zero with message number zero for the success token and severity three with the message
     * numbers below for the eight failure tokens. Those numbers are what make the callers' {@code 2513}
     * exemption meaningful and testable, so they are carried rather than discarded.
     *
     */
    public enum DateFeedback {

        /**
         * First clause. The date is good.
         *
         * <p>Selected by the all-zero feedback token at {@code [app/cbl/CSUTLDTC.cbl:L62]}, whose COBOL
         * condition name is {@code FC-INVALID-DATE}. That name is the source defect: an all-zero
         * Language-Environment feedback token means success, and the text the clause moves is
         * {@code Date is valid}. The token semantics are followed and the constant is named accordingly.
         */
        DATE_IS_VALID(0, 0),

        /** Second clause, {@code FC-INSUFFICIENT-DATA} at {@code [app/cbl/CSUTLDTC.cbl:L63]}. */
        INSUFFICIENT_DATA(3, 2507),

        /** Third clause, {@code FC-BAD-DATE-VALUE} at {@code [app/cbl/CSUTLDTC.cbl:L64]}. */
        BAD_DATE_VALUE(3, 2508),

        /**
         * Fourth clause, {@code FC-INVALID-ERA} at {@code [app/cbl/CSUTLDTC.cbl:L65]}.
         *
         * <p>Not producible by either mask this estate transmits, because neither carries an era field.
         * The constant exists because the clause exists and every clause must map to a named value.
         */
        INVALID_ERA(3, 2509),

        /**
         * Fifth clause, {@code FC-UNSUPP-RANGE} at {@code [app/cbl/CSUTLDTC.cbl:L66]}.
         *
         * <p>Message number 2513, decoded from {@code 0x09D1}. <strong>This is the condition both
         * program-level callers silently tolerate</strong> despite its non-zero severity.
         */
        UNSUPPORTED_RANGE(3, 2513),

        /** Sixth clause, {@code FC-INVALID-MONTH} at {@code [app/cbl/CSUTLDTC.cbl:L67]}. */
        INVALID_MONTH(3, 2517),

        /**
         * Seventh clause, {@code FC-BAD-PIC-STRING} at {@code [app/cbl/CSUTLDTC.cbl:L68]}.
         *
         * <p>Reported when the format mask supplied is not one the estate transmits, which is the
         * closest equivalent of a picture string the date service cannot interpret.
         */
        BAD_PICTURE_STRING(3, 2518),

        /** Eighth clause, {@code FC-NON-NUMERIC-DATA} at {@code [app/cbl/CSUTLDTC.cbl:L69]}. */
        NON_NUMERIC_DATA(3, 2520),

        /** Ninth clause, {@code FC-YEAR-IN-ERA-ZERO} at {@code [app/cbl/CSUTLDTC.cbl:L70]}. */
        YEAR_IN_ERA_ZERO(3, 2521),

        /**
         * Tenth clause: {@code WHEN OTHER} at {@code [app/cbl/CSUTLDTC.cbl:L147]}.
         *
         * <p>No feedback token corresponds to it, because the clause fires precisely when none of the
         * nine tokens matched. It carries the error severity with message number zero, so it is neither
         * the accepted severity nor the tolerated message number and the two-level acceptance test
         * rejects it &mdash; which is how the legacy treats any feedback it does not recognise. The
         * substituted parser classifies every failure it can detect into one of the nine, so this
         * constant is the defensive tail of the chain rather than a routine outcome.
         */
        UNRECOGNISED_FEEDBACK(3, 0);

        /** Severity halfword of the feedback token. */
        private final int severity;

        /** Message-number halfword of the feedback token. */
        private final int messageNumber;

        /**
         * Binds an outcome to the two halfwords decoded from its feedback token.
         *
         * @param severity      the severity the subprogram moves to the result block and to the return code
         * @param messageNumber the message number the subprogram moves to the result block
         */
        DateFeedback(final int severity, final int messageNumber) {
            this.severity = severity;
            this.messageNumber = messageNumber;
        }

        /**
         * Returns the numeric severity.
         *
         * <p>This is the value the subprogram also moves to the return code at
         * {@code [app/cbl/CSUTLDTC.cbl:L98]}, and the value the copybook stage compares against zero at
         * {@code [app/cpy/CSUTLDPY.cpy:L298]}.
         *
         * @return zero for success, three for every failure condition the source declares
         */
        public int getSeverity() {
            return severity;
        }

        /**
         * Returns the numeric message number.
         *
         * @return zero for success and for the unrecognised-feedback tail, otherwise the decoded
         *         message number of the condition
         */
        public int getMessageNumber() {
            return messageNumber;
        }
    }

    /**
     * Typed form of the 80-character result block that the subprogram returns through its third linkage
     * parameter.
     *
     * <p>The block is declared at {@code [app/cbl/CSUTLDTC.cbl:L42]} through
     * {@code [app/cbl/CSUTLDTC.cbl:L57]} as thirteen elementary items whose widths sum to exactly eighty:
     * a four-character severity code, an eleven-character label, a four-character message number, a space,
     * the fifteen-character outcome text, a space, a nine-character label, the ten-character tested date,
     * a space, a ten-character label, the ten-character mask, a space and three trailing spaces.
     *
     * <p><strong>Severity and message number are individually addressable, because that is exactly what
     * the callers test.</strong> Both callers overlay the block as four plus eleven plus four plus
     * sixty-one and read the first and third of those, at {@code [app/cbl/CORPT00C.cbl:L133]} and
     * {@code [app/cbl/CORPT00C.cbl:L135]} and identically at {@code [app/cbl/COTRN02C.cbl:L66]} and
     * {@code [app/cbl/COTRN02C.cbl:L68]}.
     *
     * @param feedback      the outcome selected by the ten-clause evaluation
     * @param severityCode  the four-character severity view, zero filled from the right as a
     *                      {@code PIC 9(4)} receiving field is
     * @param messageNumber the four-character message-number view, zero filled the same way
     * @param resultText    the fifteen-character outcome-text field, the source literal right padded to
     *                      the declared width exactly as the legacy move pads it
     * @param testedDate    the ten-character date that was tested
     * @param maskUsed      the ten-character format mask that was used
     */
    public record SubprogramResult(DateFeedback feedback,
                                   String severityCode,
                                   String messageNumber,
                                   String resultText,
                                   String testedDate,
                                   String maskUsed) {

        /**
         * Validates the components against the widths the record layout declares.
         *
         * <p>These checks are fail-fast invariants on this migration's own rendering, not validation of
         * caller input: no caller constructs this record directly, so a failure here can only mean the
         * renderer drifted from the layout.
         *
         * @throws NullPointerException     if any component is {@code null}
         * @throws IllegalArgumentException if any component is not the width its {@code PIC} declares
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
         * Returns the numeric severity behind the four-character view.
         *
         * <p>Reproduces the numeric redefinition {@code WS-SEVERITY-N} at
         * {@code [app/cbl/CSUTLDTC.cbl:L44]}, which the subprogram also moves to the return code at
         * {@code [app/cbl/CSUTLDTC.cbl:L98]} and which the copybook stage compares against zero at
         * {@code [app/cpy/CSUTLDPY.cpy:L298]}. The value is taken from the outcome, never parsed back
         * out of the rendered characters.
         *
         * @return zero when the date validated, otherwise the failure severity
         */
        public int numericSeverity() {
            return feedback.getSeverity();
        }

        /**
         * Renders the whole block at exactly eighty encoded bytes.
         *
         * <p>Field order and every literal filler follow the declaration, so the returned value is what
         * the legacy third parameter carries. The label fillers keep the padding their {@code PIC}
         * declares, since that padding occupies block positions.
         *
         * <p>The width guarantee is a byte guarantee, because {@code LS-RESULT PIC X(80)} reserves
         * eighty bytes. Every component was gated for single-byte representability when this record was
         * constructed and every literal filler here is a single-byte constant, so the returned string
         * carries one byte per character: re-encoding it yields the same eighty bytes, and a character
         * index into it is also a byte offset.
         *
         * <p><strong>Source anomaly recorded here.</strong> The subprogram writes the tested date into
         * the block twice: once from the clean linkage parameter at {@code [app/cbl/CSUTLDTC.cbl:L108]},
         * and then again at {@code [app/cbl/CSUTLDTC.cbl:L122]} as a group move of the
         * Language-Environment variable-string structure, which begins with a two-byte binary length.
         * The shipped block therefore holds that binary length followed by the leading eight characters
         * of the date in those ten positions. This migration renders the tested date there, because that
         * is the documented layout, because no caller addresses those positions, and because a binary
         * length prefix has no meaning once the variable-string convention is gone. The anomaly is
         * recorded rather than reproduced.
         *
         * @return the result block, exactly eighty encoded bytes wide
         * @throws IllegalStateException if the assembled block is not exactly eighty encoded bytes,
         *                               which can only mean the renderer drifted from the record layout
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
         * Returns the sixty-one character tail of the block that the callers overlay as the message.
         *
         * <p>This is {@code CSUTLDTC-RESULT-MSG}, {@code [app/cbl/CORPT00C.cbl:L136]}: it starts at
         * block position twenty, immediately after the message number, so it opens with the single-space
         * filler and then carries the outcome text.
         *
         * <p>The caller's overlay is a byte overlay onto an eighty-byte area, so the slice is taken from
         * the encoded image at a byte offset rather than from the character sequence at a character
         * index. The two coincide for a block that has passed the representability gate, and taking the
         * slice in bytes is what keeps that coincidence a consequence of the contract rather than an
         * assumption the reader has to supply.
         *
         * @return exactly sixty-one bytes, the tail of the eighty-byte block
         */
        public String messageSegment() {
            final byte[] block = render().getBytes(StandardCharsets.US_ASCII);
            return new String(block, RESULT_BLOCK_WIDTH - MESSAGE_SEGMENT_WIDTH,
                    MESSAGE_SEGMENT_WIDTH, StandardCharsets.US_ASCII);
        }

        /**
         * Enforces one component width, measured in encoded bytes.
         *
         * <p>The width a {@code PIC X(n)} component declares is a byte reservation, so the check is made
         * on the encoded image and not on the character count. Representability is gated first, because
         * a value the charset cannot carry has no byte width its field can hold; the diagnostic for that
         * case names the position and the numeric code unit rather than echoing the character, so an
         * unexpected value cannot inject content into a downstream log record.
         *
         * @param value the component value
         * @param width the width the record layout declares, in bytes
         * @param name  the component name, for the failure message
         * @throws NullPointerException     if {@code value} is {@code null}
         * @throws IllegalArgumentException if {@code value} carries a character the single-byte charset
         *                                  cannot represent, or is not exactly {@code width} encoded
         *                                  bytes
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
     * Which of the two jump targets a cascade paragraph took.
     *
     * <p>The cascade contains two kinds of transfer. Most jumps go to the immediately following
     * {@code -EXIT} paragraph of the same stage and are therefore an early return from one method. The
     * combined day/month/year paragraph is different: three of its jumps, and its closing guard, target
     * the exit of the <em>whole</em> range at {@code [app/cpy/CSUTLDPY.cpy:L329]}, skipping the
     * Language-Environment stage entirely. That difference is load bearing, so it is modelled explicitly
     * instead of being folded into a boolean.
     */
    private enum CascadeFlow {

        /** No range-level jump was taken; the next paragraph in the range runs. */
        FALL_THROUGH,

        /**
         * A jump straight to the exit paragraph of the whole range, {@code EDIT-DATE-CCYYMMDD-EXIT},
         * was taken, at {@code [app/cpy/CSUTLDPY.cpy:L225]}, {@code [app/cpy/CSUTLDPY.cpy:L240]},
         * {@code [app/cpy/CSUTLDPY.cpy:L270]} or {@code [app/cpy/CSUTLDPY.cpy:L277]}.
         */
        GO_TO_RANGE_EXIT
    }

    /**
     * Per-invocation working storage for one run of the cascade.
     *
     * <p>The legacy equivalent is a group of fields in {@code app/cpy/CSUTLDWY.cpy} that lives in the
     * calling program's working storage and is reused across every date the program edits. Holding it
     * here, created fresh on entry and discarded on exit, is what makes this service a stateless
     * singleton: two concurrent requests cannot see each other's flags or message.
     *
     * <p>The six character slices are immutable because the cascade never writes to the input field. The
     * numeric views the level-88 range tests read are not held as fields at all: they are redefinitions of
     * the very same bytes ({@code [app/cpy/CSUTLDWY.cpy:L17]} and {@code [app/cpy/CSUTLDWY.cpy:L26]}), so
     * they are computed from the slice on demand. That also makes the two {@code COMPUTE} statements at
     * {@code [app/cpy/CSUTLDPY.cpy:L127]} and {@code [app/cpy/CSUTLDPY.cpy:L171]} what they actually are
     * &mdash; value-preserving assignments of a redefinition from its own storage &mdash; while the
     * numeric test guarding each of them, which does have observable effect, is reproduced in full.
     */
    private static final class CascadeState {

        /** {@code WS-EDIT-DATE-CCYYMMDD}, {@code [app/cpy/CSUTLDWY.cpy:L4]}: exactly eight characters. */
        private final String ccyymmddImage;

        /** {@code WS-EDIT-DATE-CC}, {@code [app/cpy/CSUTLDWY.cpy:L6]}: the century pair. */
        private final String centuryField;

        /** {@code WS-EDIT-DATE-YY}, {@code [app/cpy/CSUTLDWY.cpy:L11]}: the year within the century. */
        private final String yearOfCenturyField;

        /** {@code WS-EDIT-DATE-CCYY}, {@code [app/cpy/CSUTLDWY.cpy:L5]}: the four-character year. */
        private final String yearField;

        /** {@code WS-EDIT-DATE-MM}, {@code [app/cpy/CSUTLDWY.cpy:L16]}. */
        private final String monthField;

        /** {@code WS-EDIT-DATE-DD}, {@code [app/cpy/CSUTLDWY.cpy:L25]}. */
        private final String dayField;

        /** {@code WS-EDIT-YEAR-FLG}, {@code [app/cpy/CSUTLDWY.cpy:L46]}. */
        private DateEditFlag yearFlag;

        /** {@code WS-EDIT-MONTH}, {@code [app/cpy/CSUTLDWY.cpy:L50]}. */
        private DateEditFlag monthFlag;

        /** {@code WS-EDIT-DAY}, {@code [app/cpy/CSUTLDWY.cpy:L54]}. */
        private DateEditFlag dayFlag;

        /** The caller's {@code INPUT-ERROR} condition, {@code [app/cbl/COACTUPC.cbl:L173]}. */
        private boolean inputError;

        /** The caller's {@code WS-RETURN-MSG}, {@code [app/cbl/COACTUPC.cbl:L479]}. */
        private String returnMessage;

        /**
         * Slices the input field and seeds the flags.
         *
         * @param ccyymmddImage        the eight-character input image, already moved to width
         * @param currentReturnMessage the accumulated message carried in from the caller
         * @param initialFlag          the state of all three flags on entry to the range
         */
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
         * Tests the group-level condition name {@code WS-EDIT-DATE-IS-VALID},
         * {@code [app/cpy/CSUTLDWY.cpy:L44]}.
         *
         * <p>The condition compares the whole three-byte group against {@code LOW-VALUES}, which holds
         * exactly when all three flags are individually valid. This is the guard the combined stage
         * evaluates at {@code [app/cpy/CSUTLDPY.cpy:L274]}, and it is intentionally not exposed on the
         * public result: see the class documentation.
         *
         * @return whether the flag group currently reads as low values
         */
        boolean editDateFlagsAreLowValues() {
            return yearFlag == DateEditFlag.VALID
                    && monthFlag == DateEditFlag.VALID
                    && dayFlag == DateEditFlag.VALID;
        }

        /**
         * Freezes the mutable state into the immutable outcome the caller receives.
         *
         * @return the cascade result
         */
        DateEditResult toResult() {
            return new DateEditResult(inputError, yearFlag, monthFlag, dayFlag, returnMessage);
        }
    }

    // ENTRY POINT ONE: the copybook cascade.

    /**
     * Validates a {@code CCYYMMDD} date through the copybook cascade, starting from a blank accumulated
     * message.
     *
     * <p>Convenience form of {@code validateCcyymmddDate(String, String)} for the common case in which
     * no earlier field has already claimed the caller's message field.
     *
     * @param candidateDate the candidate date; moved to the eight-character input field exactly as the
     *                      legacy move does, so a shorter value is space padded on the right and a longer
     *                      one is truncated on the right. Must not be {@code null}
     * @return the flags, the input-error indicator and the message this edit produced
     * @throws NullPointerException     if {@code candidateDate} is {@code null}
     * @throws IllegalArgumentException if {@code candidateDate} carries a character the single-byte
     *                                  character set of the legacy fields cannot represent, since such
     *                                  a value cannot occupy the byte width the field reserves
     */
    public DateEditResult validateCcyymmddDate(final String candidateDate) {
        return validateCcyymmddDate(candidateDate, NO_RETURN_MESSAGE);
    }

    /**
     * Validates a {@code CCYYMMDD} date through the copybook cascade.
     *
     * <p>Reproduces the paragraph range from {@code EDIT-DATE-CCYYMMDD} through to its own exit
     * paragraph, spanning {@code [app/cpy/CSUTLDPY.cpy:L18]} to {@code [app/cpy/CSUTLDPY.cpy:L329]},
     * as performed at
     * {@code [app/cbl/COACTUPC.cbl:L1480]}, {@code [app/cbl/COACTUPC.cbl:L1492]},
     * {@code [app/cbl/COACTUPC.cbl:L1505]} and {@code [app/cbl/COACTUPC.cbl:L1536]}.
     *
     * <p><strong>An ordered cascade, not five independent checks.</strong> Each stage keeps its own early
     * exit, so a failure short-circuits where the legacy short-circuits - and a failing stage does
     * <em>not</em> abandon the range: the year stage's early exit lands on the month stage, which is why a
     * blank year and a bad month are both reported on the same pass. The accumulated message follows the
     * source's first-wins rule: each stage writes its suffix only while the message is still blank, so the
     * earliest failure keeps the message even though later stages continue to set their flags. That rule is
     * why the caller's message field is passed in rather than assumed empty - in the account-update program
     * it is shared by every field on the screen.
     *
     * @param candidateDate        the candidate date, moved to the eight-character input field; must not
     *                             be {@code null}
     * @param currentReturnMessage the caller's accumulated message on entry; empty stands for the blank
     *                             state that lets a stage claim the message. Must not be {@code null}
     * @return the flags, the input-error indicator and the accumulated message
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code candidateDate} carries a character the single-byte
     *                                  character set of the legacy fields cannot represent, since such
     *                                  a value cannot occupy the byte width the field reserves
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
                result.returnMessage());
        return result;
    }

    /**
     * Applies the date-of-birth reasonableness check, starting from a blank accumulated message.
     *
     * @param candidateDate the candidate date of birth as a {@code CCYYMMDD} image; must not be
     *                      {@code null}
     * @param currentDate   the current date the check compares against; must not be {@code null}
     * @return the flags, the input-error indicator and the message this edit produced
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if the candidate is not a resolvable calendar date, or carries a
     *                                  character the single-byte character set of the legacy fields
     *                                  cannot represent
     */
    public DateEditResult validateDateOfBirth(final String candidateDate, final LocalDate currentDate) {
        return validateDateOfBirth(candidateDate, currentDate, NO_RETURN_MESSAGE);
    }

    /**
     * Applies the date-of-birth reasonableness check.
     *
     * <p>Reproduces the paragraph range from {@code EDIT-DATE-OF-BIRTH} through to its own exit
     * paragraph, spanning {@code [app/cpy/CSUTLDPY.cpy:L341]} to {@code [app/cpy/CSUTLDPY.cpy:L370]},
     * as performed at
     * {@code [app/cbl/COACTUPC.cbl:L1540]}. <strong>This range sits outside the main cascade</strong>
     * &mdash; the span of the main range ends at
     * {@code [app/cpy/CSUTLDPY.cpy:L329]}, before this paragraph begins &mdash; and it is invoked in its
     * own right, so it is a separate entry point rather than a sixth cascade stage.
     *
     * <p><strong>Entry precondition.</strong> The account-update program performs this range only after
     * the main cascade returns and only while the copied flag group still reads as valid,
     * {@code [app/cbl/COACTUPC.cbl:L1538]} and {@code [app/cbl/COACTUPC.cbl:L1539]}. The three flags
     * therefore start valid and the candidate is expected to be a resolvable calendar date: the legacy
     * converts it with an integer-of-date function at {@code [app/cpy/CSUTLDPY.cpy:L346]}, which has no
     * defined result for a value the main cascade would have rejected. The current date replaces
     * {@code FUNCTION CURRENT-DATE} at {@code [app/cpy/CSUTLDPY.cpy:L343]} and is supplied by the caller
     * rather than read from a clock here, keeping this service pure and its date arithmetic reproducible.
     *
     * @param candidateDate        the candidate date of birth as a {@code CCYYMMDD} image; must not be
     *                             {@code null}
     * @param currentDate          the current date; must not be {@code null}
     * @param currentReturnMessage the caller's accumulated message on entry; must not be {@code null}
     * @return the flags, the input-error indicator and the accumulated message
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if the candidate is not a resolvable calendar date, which means
     *                                  the documented entry precondition was not met, or carries a
     *                                  character the single-byte character set of the legacy fields
     *                                  cannot represent
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
                result.inputError(), result.returnMessage());
        return result;
    }

    // ENTRY POINT TWO: the callable subprogram.

    /**
     * Validates a date against a format mask, reproducing a static invocation of the subprogram
     * {@code CSUTLDTC}.
     *
     * <p>The second and entirely separate entry point, reproducing the subprogram's procedure division,
     * {@code [app/cbl/CSUTLDTC.cbl:L88]} through {@code [app/cbl/CSUTLDTC.cbl:L102]}: clear the result
     * block, clear the tested-date field, perform the main paragraph through its exit, then move the
     * result block to the third linkage parameter. The four genuine call sites are
     * {@code [app/cbl/COTRN02C.cbl:L393]}, {@code [app/cbl/COTRN02C.cbl:L413]},
     * {@code [app/cbl/CORPT00C.cbl:L392]} and {@code [app/cbl/CORPT00C.cbl:L412]}. The legacy third
     * parameter is an output area the caller blanks before the call; in Java the equivalent is a return
     * value, so the block is returned as a typed object whose renderer reproduces the eighty-character
     * form when a caller needs the raw bytes.
     *
     * @param candidateDate the date to test; moved to the ten-character first linkage parameter, so a
     *                      shorter value is space padded on the right. Must not be {@code null}
     * @param dateFormat    the format mask, which is the second linkage parameter; must not be
     *                      {@code null}
     * @return the typed result block, with severity and message number individually addressable
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code candidateDate} carries a character the single-byte
     *                                  character set of the legacy fields cannot represent, since such
     *                                  a value cannot occupy the byte width the field reserves
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
        LOG.debug("Subprogram date validation: date=[{}] mask=[{}] severity=[{}] messageNumber=[{}]"
                        + " result=[{}]",
                testedDate, maskUsed, result.severityCode(), result.messageNumber(), result.resultText());
        return result;
    }

    /**
     * Validates a date against a raw format mask, resolving the mask before delegating.
     *
     * <p>Offered because both callers hold the mask in a ten-character work field rather than as a typed
     * value: {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} at {@code [app/cbl/CORPT00C.cbl:L72]} and
     * identically at {@code [app/cbl/COTRN02C.cbl:L60]}.
     *
     * <p>A mask the estate never transmits is reported as a bad picture string rather than guessed at.
     * Substituting one of the two real masks would validate against the wrong picture and return a
     * confidently wrong verdict, so the unresolved case yields the feedback condition meaning "the picture
     * string could not be used", whose non-zero severity and non-tolerated message number make the
     * two-level acceptance test reject it.
     *
     * @param candidateDate the date to test; must not be {@code null}
     * @param formatMask    the raw mask value, moved to the ten-character second linkage parameter; must
     *                      not be {@code null}
     * @return the typed result block
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code candidateDate} carries a character the single-byte
     *                                  character set of the legacy fields cannot represent, since such
     *                                  a value cannot occupy the byte width the field reserves
     *                                  The same applies to {@code formatMask}, which is moved to the
     *                                  second linkage parameter
     */
    public SubprogramResult validateDate(final String candidateDate, final String formatMask) {
        Objects.requireNonNull(candidateDate,
                "candidateDate must not be null: an absent field is not a blank field");
        Objects.requireNonNull(formatMask, "formatMask must not be null");

        final String maskUsed = moveToLinkageTextField(formatMask, LS_DATE_FORMAT_FIELD);
        final Optional<DateFormat> resolved = DateFormat.fromValue(maskUsed);
        if (resolved.isEmpty()) {
            final String testedDate = moveToLinkageTextField(candidateDate, LS_DATE_FIELD);
            LOG.warn("Unrecognised date-format mask [{}]; reporting a bad picture string", maskUsed);
            return buildResult(DateFeedback.BAD_PICTURE_STRING, testedDate, maskUsed);
        }
        return validateDate(candidateDate, resolved.get());
    }

    /**
     * The two-level acceptance test the four genuine call sites apply to the result block.
     *
     * <p>Reproduced from {@code [app/cbl/CORPT00C.cbl:L396]} through
     * {@code [app/cbl/CORPT00C.cbl:L406]} and {@code [app/cbl/CORPT00C.cbl:L416]} through
     * {@code [app/cbl/CORPT00C.cbl:L426]}, and mirrored at {@code [app/cbl/COTRN02C.cbl:L397]} and
     * {@code [app/cbl/COTRN02C.cbl:L417]}. All four sites are identical, so the test lives here once
     * instead of being written out in each calling service.
     *
     * <p>Both levels must be kept: severity {@code 0000} is accepted outright; otherwise a message number
     * that is <em>not</em> {@code 2513} is rejected; otherwise the result is accepted silently, so
     * <strong>a non-zero severity carrying message number {@code 2513} is accepted</strong>. Both
     * comparisons are against four-character strings, not integers, so neither field is parsed, and the
     * two levels do not collapse into one comparison because acceptance can arrive by either route. See
     * {@code TOLERATED_MESSAGE_NUMBER} for why the exemption is not dead weight.
     *
     * @param result the result block returned by either {@code validateDate} overload; must not be
     *               {@code null}
     * @return {@code true} when the callers would proceed, {@code false} when they would raise their
     *         "not a valid date" message
     * @throws NullPointerException if {@code result} is {@code null}
     */
    public boolean isDateAcceptable(final SubprogramResult result) {
        Objects.requireNonNull(result, "result must not be null");

        if (ACCEPTED_SEVERITY_CODE.equals(result.severityCode())) {
            return true;
        }
        if (!TOLERATED_MESSAGE_NUMBER.equals(result.messageNumber())) {
            return false;
        }
        LOG.debug("Accepting date [{}] on the tolerated message number [{}] despite severity [{}]",
                result.testedDate(), result.messageNumber(), result.severityCode());
        return true;
    }

    // app/cbl/CSUTLDTC.cbl - the subprogram's two paragraphs.

    /**
     * {@code A000-MAIN}, {@code [app/cbl/CSUTLDTC.cbl:L103]}.
     *
     * <p>The legacy paragraph copies the two linkage values into Language-Environment variable strings,
     * zeroes the Lilian output, calls the date service at {@code [app/cbl/CSUTLDTC.cbl:L116]}, moves the
     * severity and message-number halfwords out of the returned feedback token, and selects the result
     * text with the ten-clause evaluation at {@code [app/cbl/CSUTLDTC.cbl:L128]}.
     *
     * <p>The date service call is the one construct that cannot be carried across, so it is replaced by
     * strict {@code java.time} parsing. The substitution is behaviour preserving only because strict
     * resolution refuses to normalise: a 30th of February is rejected rather than rolled into March. The
     * substituted parser reports a single failure rather than a feedback token, so the failures it can
     * distinguish are classified onto the matching feedback conditions in a fixed order, each annotated
     * with why that condition is the right one.
     *
     * @param testedDate the ten-character date, already moved to the linkage width
     * @param dateFormat the mask, which selects the pattern and therefore the digit positions
     * @return the feedback condition the evaluation should select
     */
    private static DateFeedback a000Main(final String testedDate, final DateFormat dateFormat) {
        final String pattern = patternFor(dateFormat);
        final int maskLength = pattern.length();

        // The mask governs how much of the ten-character slot is interpreted. The compact mask covers
        // eight of the ten positions, and the date service examines only what the picture describes, so
        // the surplus positions are not inspected here either.
        //
        // This guard is a defensive invariant rather than a routine outcome, and the reason is worth
        // stating so that a reader does not mistake it for a live branch. The legacy sets the length of
        // the variable string it passes to the date service from LENGTH OF LS-DATE at
        // [app/cbl/CSUTLDTC.cbl:L105], so the service always receives the full ten characters; both
        // masks this estate transmits describe ten positions or fewer, so a mask can never overrun the
        // field. Insufficient data is nevertheless the correct outcome if it ever did, because that is
        // precisely the condition for a value too short to satisfy the picture.
        //
        // The comparison is made in encoded bytes, like every other width comparison in this class,
        // because the field it measures is a byte reservation. The value reached here through the move
        // into LS-DATE, so it has already passed the representability gate and its byte width and
        // character count coincide; measuring in bytes keeps that a consequence of the contract.
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
            // A well-formed but non-existent calendar date, such as a 31st of February or a 29th of
            // February in a common year, is a bad date value.
            LOG.debug("Strict parse rejected date [{}] against pattern [{}]: {}",
                    subject, pattern, tooBadToResolve.getMessage());
            return DateFeedback.BAD_DATE_VALUE;
        }

        // Resolvable, but before the first day the Lilian date services cover.
        if (parsed.isBefore(LILIAN_RANGE_START)) {
            return DateFeedback.UNSUPPORTED_RANGE;
        }
        return DateFeedback.DATE_IS_VALID;
    }

    /**
     * {@code A000-MAIN-EXIT}, {@code [app/cbl/CSUTLDTC.cbl:L152]}.
     *
     * <p>The paragraph's only statement is {@code EXIT}, which in COBOL is a documentary no-operation
     * marking the end of a performed range. It is translated as an empty method, and invoked, so that the
     * paragraph-level mapping resolves to a named class and method, and so that the performed range in
     * {@code validateDate} reads as the source reads. It is not a stub: there is nothing to implement.
     */
    private static void a000MainExit() {
        // EXIT is a no-operation. The range ends here.
    }

    // app/cpy/CSUTLDPY.cpy - the fourteen cascade paragraphs, in source order.
    //
    // These are instance methods because they are members of the range the instance entry points drive,
    // and because the Language-Environment stage re-enters the subprogram entry point on this same
    // component. Every one of them carries its verified source line so the mapping can cite it.

    /**
     * {@code EDIT-DATE-CCYYMMDD}, {@code [app/cpy/CSUTLDPY.cpy:L18]} &mdash; the head paragraph.
     *
     * <p><strong>Its entire body is the single statement at {@code [app/cpy/CSUTLDPY.cpy:L19]}</strong>,
     * which sets the group-level all-invalid condition. Because that condition is declared on the whole
     * three-byte group at {@code [app/cpy/CSUTLDWY.cpy:L45]}, one statement writes all three flags at
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
     * {@code EDIT-YEAR-CCYY}, {@code [app/cpy/CSUTLDPY.cpy:L25]} &mdash; cascade stage one.
     *
     * <p>Three checks in source order, each with its own early exit to
     * {@code [app/cpy/CSUTLDPY.cpy:L88]}: the year slice must be supplied, it must be four digits, and its
     * century must be one of the only two the source accepts. The order matters here because each branch
     * emits a different message. The comment at {@code [app/cpy/CSUTLDPY.cpy:L41]} acknowledges the
     * jump-based structure as an intentional departure from structured programming, and the comment block
     * at {@code [app/cpy/CSUTLDPY.cpy:L66]} explains the century restriction in its own words.
     *
     * <p>The stage opens by setting the year flag to not-valid, so the flag is only cleared by reaching the
     * end at {@code [app/cpy/CSUTLDPY.cpy:L86]}.
     *
     * @param state the per-invocation working storage
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
            // CONTINUE at [app/cpy/CSUTLDPY.cpy:L72]: an accepted century falls through to the flag set.
        } else {
            state.inputError = true;
            state.yearFlag = DateEditFlag.NOT_OK;
            setReturnMessage(state, MESSAGE_CENTURY_NOT_VALID);
            return;
        }

        state.yearFlag = DateEditFlag.VALID;
    }

    /**
     * {@code EDIT-YEAR-CCYY-EXIT}, {@code [app/cpy/CSUTLDPY.cpy:L88]}.
     *
     * <p>Body is {@code EXIT} alone. It is the target of the year stage's three jumps, and because it is
     * only the exit of that <em>stage</em> and not of the range, control continues into the month stage
     * &mdash; which is why a blank year and a bad month are both reported on one pass. Translated as an
     * empty method and invoked so the range reads as the source reads.
     */
    private void editYearCcyyExit() {
        // EXIT is a no-operation. Control falls through to the month stage.
    }

    /**
     * {@code EDIT-MONTH}, {@code [app/cpy/CSUTLDPY.cpy:L91]} &mdash; cascade stage two.
     *
     * <p>Three checks in source order, each exiting to {@code [app/cpy/CSUTLDPY.cpy:L145]}: supplied, then
     * within one to twelve, then numeric. <strong>The range test precedes the numeric test</strong>, which
     * looks inverted but is not observable: both failure branches, {@code [app/cpy/CSUTLDPY.cpy:L119]} and
     * {@code [app/cpy/CSUTLDPY.cpy:L136]}, emit the same message and set the same flag. The order is
     * preserved as written regardless. The range test reads the numeric redefinition of the same two
     * bytes, {@code [app/cpy/CSUTLDWY.cpy:L17]}, whose condition name enumerates one through twelve, so a
     * slice that is not two digits fails it and takes the first of the two identical branches.
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
            // CONTINUE at [app/cpy/CSUTLDPY.cpy:L112].
        } else {
            state.inputError = true;
            state.monthFlag = DateEditFlag.NOT_OK;
            setReturnMessage(state, MESSAGE_MONTH_OUT_OF_RANGE);
            return;
        }

        if (isAllAsciiDigits(state.monthField)) {
            // The COMPUTE at [app/cpy/CSUTLDPY.cpy:L127] assigns the numeric redefinition from the very
            // bytes it redefines, so it preserves the value and nothing is recomputed here. The test that
            // guards it, at [app/cpy/CSUTLDPY.cpy:L126], is what has observable effect and is reproduced.
        } else {
            state.inputError = true;
            state.monthFlag = DateEditFlag.NOT_OK;
            setReturnMessage(state, MESSAGE_MONTH_OUT_OF_RANGE);
            return;
        }

        state.monthFlag = DateEditFlag.VALID;
    }

    /**
     * {@code EDIT-MONTH-EXIT}, {@code [app/cpy/CSUTLDPY.cpy:L145]}.
     *
     * <p>Body is {@code EXIT} alone; control falls through to the day stage.
     */
    private void editMonthExit() {
        // EXIT is a no-operation. Control falls through to the day stage.
    }

    /**
     * {@code EDIT-DAY}, {@code [app/cpy/CSUTLDPY.cpy:L150]} &mdash; cascade stage three.
     *
     * <p>Three checks in source order, each exiting to {@code [app/cpy/CSUTLDPY.cpy:L205]}: supplied, then
     * numeric, then within one to thirty-one. Note that the numeric test precedes the range test here,
     * the opposite of the month stage; both orders are preserved as written.
     *
     * <p>Two source quirks are reproduced. The stage <em>opens</em> by setting the day flag to valid at
     * {@code [app/cpy/CSUTLDPY.cpy:L152]} rather than to not-valid, so a jump out of this stage leaves the
     * day flag valid unless the branch that jumped explicitly cleared it. And the paragraph is written as
     * two sentences, the first ending at {@code [app/cpy/CSUTLDPY.cpy:L201]}, so the redundant second flag
     * set at {@code [app/cpy/CSUTLDPY.cpy:L203]} is reached only by falling out of the first sentence.
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
            // As in the month stage, the COMPUTE at [app/cpy/CSUTLDPY.cpy:L171] assigns the numeric
            // redefinition from its own bytes and is value preserving; its guard is what matters.
        } else {
            state.inputError = true;
            state.dayFlag = DateEditFlag.NOT_OK;
            setReturnMessage(state, MESSAGE_DAY_OUT_OF_RANGE);
            return;
        }

        final int day = numericView(state.dayField);
        if (day >= FIRST_DAY && day <= DAY_31) {
            // CONTINUE at [app/cpy/CSUTLDPY.cpy:L188].
        } else {
            state.inputError = true;
            state.dayFlag = DateEditFlag.NOT_OK;
            setReturnMessage(state, MESSAGE_DAY_OUT_OF_RANGE);
            return;
        }

        state.dayFlag = DateEditFlag.VALID;
    }

    /**
     * {@code EDIT-DAY-EXIT}, {@code [app/cpy/CSUTLDPY.cpy:L205]}.
     *
     * <p>Body is {@code EXIT} alone; control falls through to the combined day, month and year stage.
     */
    private void editDayExit() {
        // EXIT is a no-operation. Control falls through to the combined stage.
    }

    /**
     * {@code EDIT-DAY-MONTH-YEAR}, {@code [app/cpy/CSUTLDPY.cpy:L209]} &mdash; cascade stage four.
     *
     * <p>The only stage that judges the combination, so it is where a 31st of April and a 29th of February
     * in a common year are caught. Its three failure branches jump to the exit of the <strong>whole
     * range</strong> at {@code [app/cpy/CSUTLDPY.cpy:L329]}, not to a stage exit, so a combination failure
     * skips the Language-Environment stage entirely.
     *
     * <p><strong>The leap-year decision keeps the source's two-branch shape.</strong> The source chooses a
     * divisor first &mdash; four hundred when the year within the century is zero, four otherwise,
     * {@code [app/cpy/CSUTLDPY.cpy:L245]} through {@code [app/cpy/CSUTLDPY.cpy:L249]} &mdash; then divides
     * and tests the remainder, {@code [app/cpy/CSUTLDPY.cpy:L251]} through
     * {@code [app/cpy/CSUTLDPY.cpy:L256]}. A library predicate would agree on every year this estate
     * accepts and is still not substituted: the branch structure is what the paragraph-level mapping
     * records and what branch coverage measures, and the divisor selection is the part a reader must be
     * able to find. The closing guard at {@code [app/cpy/CSUTLDPY.cpy:L274]} is reproduced verbatim; see
     * {@code editDateLe} for what it means for the stage it admits.
     *
     * @param state the per-invocation working storage
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
            // The legacy DIVIDE also produces a quotient, which no later statement reads. A year slice
            // that is not four digits reaches this point only when the year stage already failed and
            // jumped to its own stage exit; the legacy would then attempt a decimal divide on
            // non-numeric storage, which has no defined result, so the deterministic non-leap branch is
            // taken. The accumulated message is unaffected either way, because the year stage has
            // already claimed it.
            final int remainder = year == NOT_NUMERIC ? NOT_NUMERIC : year % divisor;
            if (remainder == 0) {
                // CONTINUE at [app/cpy/CSUTLDPY.cpy:L257]: a leap year accepts the 29th.
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
            // CONTINUE at [app/cpy/CSUTLDPY.cpy:L275].
        } else {
            return CascadeFlow.GO_TO_RANGE_EXIT;
        }
        return CascadeFlow.FALL_THROUGH;
    }

    /**
     * {@code EDIT-DAY-MONTH-YEAR-EXIT}, {@code [app/cpy/CSUTLDPY.cpy:L280]}.
     *
     * <p>Body is {@code EXIT} alone. Nothing jumps here: the combined stage's jumps all leave the range,
     * so this paragraph is reached only by falling out of the stage, and control then falls through to the
     * Language-Environment stage.
     */
    private void editDayMonthYearExit() {
        // EXIT is a no-operation. Control falls through to the Language-Environment stage.
    }

    /**
     * {@code EDIT-DATE-LE}, {@code [app/cpy/CSUTLDPY.cpy:L284]} &mdash; cascade stage five.
     *
     * <p>A last resort for a bad date that slipped past every edit above. It clears the result block,
     * moves the compact mask, invokes the subprogram at {@code [app/cpy/CSUTLDPY.cpy:L293]}, and on a
     * non-zero severity sets the input-error condition, clears all three flags and builds a message
     * quoting both the severity and the message number.
     *
     * <p><strong>Anomaly: this stage is unreachable in the shipped estate, and that shapes the whole
     * public interface.</strong> Entry is gated by the guard at {@code [app/cpy/CSUTLDPY.cpy:L274]}, which
     * admits the stage only while the three-byte flag group still reads as low values; the head paragraph
     * writes the all-invalid value into that group before any stage runs, and the migration analysis
     * records the guard as never satisfied, so the jump at {@code [app/cpy/CSUTLDPY.cpy:L277]} always
     * leaves the range. Two consequences follow and both are load bearing: the embedded invocation of
     * {@code CSUTLDTC} at {@code [app/cpy/CSUTLDPY.cpy:L293]} never executes, so the cascade never
     * actually invokes the subprogram; and the valid-marking statement at
     * {@code [app/cpy/CSUTLDPY.cpy:L327]} is unreachable with it, so the cascade can only accumulate
     * failures and never declares a date good. That is why {@code DateEditResult} reports flags and a
     * message rather than a verdict.
     *
     * <p>The method is kept, implemented in full and invoked in its source position, because every legacy
     * paragraph maps to a named method. The guard is evaluated as a test of the whole three-byte group,
     * exactly as written, rather than hard-wired to either answer, so Java reachability equals COBOL
     * reachability under every flag state. Forcing a conclusion into the control flow would have been the
     * one choice that could diverge.
     *
     * @param state the per-invocation working storage
     */
    private void editDateLe(final CascadeState state) {
        final SubprogramResult leResult = validateDate(state.ccyymmddImage, DateFormat.YYYYMMDD);

        if (leResult.numericSeverity() == 0) {
            // CONTINUE at [app/cpy/CSUTLDPY.cpy:L299].
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
     * {@code EDIT-DATE-LE-EXIT}, {@code [app/cpy/CSUTLDPY.cpy:L323]}.
     *
     * <p><strong>This exit paragraph is not empty.</strong> It carries the no-operation {@code EXIT} at
     * {@code [app/cpy/CSUTLDPY.cpy:L324]} and then a second sentence at
     * {@code [app/cpy/CSUTLDPY.cpy:L327]} that sets the group-level valid condition, writing all three
     * flags back to low values in one statement.
     *
     * <p><strong>Anomaly reproduced, not corrected.</strong> The comment at
     * {@code [app/cpy/CSUTLDPY.cpy:L326]} asserts that arriving here means every edit was cleared, which
     * is false on one path: the severity failure jumps <em>to</em> this paragraph at
     * {@code [app/cpy/CSUTLDPY.cpy:L315]}, so a failed Language-Environment check would still have its
     * three flags overwritten as valid while the input-error condition stayed set. Never observed, for the
     * same reason the stage is never entered; carried as written because a future reader who "fixes" the
     * guard needs to know it is there.
     *
     * @param state the per-invocation working storage
     */
    private void editDateLeExit(final CascadeState state) {
        state.yearFlag = DateEditFlag.VALID;
        state.monthFlag = DateEditFlag.VALID;
        state.dayFlag = DateEditFlag.VALID;
    }

    /**
     * {@code EDIT-DATE-CCYYMMDD-EXIT}, {@code [app/cpy/CSUTLDPY.cpy:L329]}.
     *
     * <p>Body is {@code EXIT} alone. This is the end of the performed range and the target of every
     * range-level jump the combined stage takes.
     */
    private void editDateCcyymmddExit() {
        // EXIT is a no-operation. The range ends here.
    }

    /**
     * {@code EDIT-DATE-OF-BIRTH}, {@code [app/cpy/CSUTLDPY.cpy:L341]}.
     *
     * <p>A reasonableness check rather than a format check: a date of birth in the future is refused, and
     * the comparison at {@code [app/cpy/CSUTLDPY.cpy:L350]} is strict, so <strong>today's date is itself
     * refused</strong>. The commented-out duration-based alternative at
     * {@code [app/cpy/CSUTLDPY.cpy:L351]} stays inactive, as the source leaves it.
     *
     * <p>On failure all three flags are cleared, {@code [app/cpy/CSUTLDPY.cpy:L357]} through
     * {@code [app/cpy/CSUTLDPY.cpy:L359]}, even though the date is well formed and merely unreasonable.
     * The screen depends on that to highlight the whole field group.
     *
     * @param state       the per-invocation working storage
     * @param currentDate the current date, standing in for the current-date intrinsic function
     * @throws IllegalArgumentException if the candidate is not a resolvable calendar date
     */
    private void editDateOfBirth(final CascadeState state, final LocalDate currentDate) {
        final LocalDate dateOfBirth = toCalendarDate(state.ccyymmddImage);

        if (currentDate.isAfter(dateOfBirth)) {
            // CONTINUE at [app/cpy/CSUTLDPY.cpy:L354]: strictly in the past, so acceptable.
        } else {
            state.inputError = true;
            state.dayFlag = DateEditFlag.NOT_OK;
            state.monthFlag = DateEditFlag.NOT_OK;
            state.yearFlag = DateEditFlag.NOT_OK;
            setReturnMessage(state, MESSAGE_DATE_IN_FUTURE);
        }
    }

    /**
     * {@code EDIT-DATE-OF-BIRTH-EXIT}, {@code [app/cpy/CSUTLDPY.cpy:L370]}.
     *
     * <p>Body is {@code EXIT} alone, and it is the end of the date-of-birth range.
     */
    private void editDateOfBirthExit() {
        // EXIT is a no-operation. The date-of-birth range ends here.
    }

    // PRIVATE HELPERS
    //
    // Each one reproduces a single COBOL primitive: a fixed-width move, a class or condition-name test,
    // a redefinition read, or the outcome-text selection. They are kept separate from the paragraph
    // methods so that a paragraph method reads as its paragraph reads.

    /**
     * Selects the outcome text, reproducing the ten-clause evaluation at
     * {@code [app/cbl/CSUTLDTC.cbl:L128]} through {@code [app/cbl/CSUTLDTC.cbl:L149]}.
     *
     * <p><strong>Clause order is contractual.</strong> COBOL evaluates the clauses top down and stops at
     * the first match, so the nine explicit arms below appear in the source's order and the tenth clause,
     * {@code WHEN OTHER}, is the default arm. Reordering them would change which text a caller sees for any
     * input that could satisfy more than one condition.
     *
     * @param feedback the outcome selected for the input
     * @return the source literal for that outcome, before it is padded to the receiving field's width
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
     * Assembles the typed result block.
     *
     * <p>The two four-character code fields are rendered the way a {@code PIC 9(4)} receiving field renders
     * a numeric move: right justified and zero filled, losing high-order digits rather than low-order ones
     * if the value were ever too wide. That is exactly the primitive the shared string utility provides, so
     * it is used rather than a format string, which would pad differently and would silently accept a
     * five-digit value. The outcome text is padded to its declared width the way the legacy move pads it.
     *
     * @param feedback   the outcome
     * @param testedDate the ten-character date that was tested
     * @param maskUsed   the ten-character mask that was used
     * @return the assembled result block
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
     * Returns the pattern that stands in for a legacy mask.
     *
     * <p>Both patterns use the era-independent year field, because strict resolution demands an era from
     * the alternative and neither legacy mask carries one. The switch is exhaustive over the two masks the
     * estate transmits and deliberately has no default arm: if a third mask were ever added to the enum,
     * this method should fail to compile rather than quietly pick a pattern.
     *
     * @param dateFormat the legacy mask
     * @return the equivalent pattern, whose length is also the number of positions the mask describes
     */
    private static String patternFor(final DateFormat dateFormat) {
        return switch (dateFormat) {
            case YYYY_MM_DD -> HYPHENATED_PATTERN;
            case YYYYMMDD -> COMPACT_PATTERN;
        };
    }

    /**
     * Returns the strict formatter for a legacy mask.
     *
     * @param dateFormat the legacy mask
     * @return the formatter, built once and shared, resolving strictly
     */
    private static DateTimeFormatter formatterFor(final DateFormat dateFormat) {
        return switch (dateFormat) {
            case YYYY_MM_DD -> HYPHENATED_FORMATTER;
            case YYYYMMDD -> COMPACT_FORMATTER;
        };
    }

    /**
     * Tests whether every position the pattern describes as a date field holds an ASCII digit.
     *
     * <p>Separator positions are skipped, because a wrong separator is a bad date value rather than
     * non-numeric data and the parser reports it as such.
     *
     * @param subject the candidate text, already cut to the pattern's length
     * @param pattern the pattern whose year, month and day letters mark the digit positions
     * @return whether all digit positions hold ASCII digits
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
     * Cuts out the positions a pattern assigns to one date field.
     *
     * <p>Both patterns place the year first, so the field letter's first occurrence is the field's first
     * position in the subject as well.
     *
     * @param subject     the candidate text, already cut to the pattern's length
     * @param pattern     the pattern
     * @param fieldLetter the pattern letter of the wanted field
     * @param width       how many positions that field occupies
     * @return the corresponding slice of the subject
     */
    private static String sliceForPatternField(final String subject,
                                               final String pattern,
                                               final char fieldLetter,
                                               final int width) {
        final int start = pattern.indexOf(fieldLetter);
        return subject.substring(start, start + width);
    }

    /**
     * Reproduces a move into the eight-character cascade input field,
     * {@code [app/cpy/CSUTLDWY.cpy:L4]}.
     *
     * @param value the sending value
     * @return exactly eight encoded bytes
     * @throws IllegalArgumentException if the sender carries a character the single-byte charset cannot
     *                                  represent
     */
    private static String moveToCcyymmddField(final String value) {
        return padOrTruncate(value, CCYYMMDD_WIDTH, "WS-EDIT-DATE-CCYYMMDD");
    }

    /**
     * Reproduces a move into one of the two ten-character linkage text fields,
     * {@code [app/cbl/CSUTLDTC.cbl:L84]} and {@code [app/cbl/CSUTLDTC.cbl:L85]}.
     *
     * <p>The field name is supplied by the caller because the two linkage parameters are distinct
     * fields at the same width, and a rejection has to say which of them was overrun.
     *
     * @param value     the sending value
     * @param fieldName the linkage field being filled, for the failure diagnostic
     * @return exactly ten encoded bytes
     * @throws IllegalArgumentException if the sender carries a character the single-byte charset cannot
     *                                  represent
     */
    private static String moveToLinkageTextField(final String value, final String fieldName) {
        return padOrTruncate(value, LINKAGE_TEXT_WIDTH, fieldName);
    }

    /**
     * Reproduces an alphanumeric move into a fixed-width field.
     *
     * <p>A plain {@code PIC X(n)} receiving field is left justified: a shorter sender is padded with
     * spaces on the right, and a longer one loses its <em>rightmost</em> excess. That is the opposite of
     * the right-justified receiver the menu programs use, so the two must not be confused.
     *
     * <p><strong>The move is performed on the encoded image, not on the character sequence.</strong> A
     * receiving field declares a byte count, so measuring and truncating by character count would leave
     * a field whose byte width is wrong for any sender outside the single-byte range &mdash; and the
     * eighty-byte result area that both callers overlay would then overrun. Representability is gated
     * first, so the value cannot be transcoded silently, and the returned string is pure single-byte
     * content: re-encoding it yields exactly {@code width} bytes, which is what makes the character
     * slicing performed downstream on these fields byte-faithful.
     *
     * @param value     the sending value
     * @param width     the receiving field's width in bytes
     * @param fieldName the legacy field name, for the failure diagnostic
     * @return exactly {@code width} encoded bytes
     * @throws IllegalArgumentException if the sender carries a character the single-byte charset cannot
     *                                  represent
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
     * Refuses a value the single-byte character set of the legacy fields cannot carry.
     *
     * <p>The scan runs before any encode, and that ordering is the whole point of the method:
     * {@code String.getBytes} substitutes a question mark for an unmappable character, so encoding first
     * and inspecting afterwards cannot tell a substituted byte from a question mark that was genuinely
     * sent. The loop bound is a character count used purely to walk the value; the width authority is
     * always the encoded length.
     *
     * <p>The diagnostic reports the position and the numeric code unit and never the character itself,
     * so a rejected value cannot place its own content into a message that a caller may log.
     *
     * @param value     the value to scan
     * @param fieldName the legacy field name, for the failure diagnostic
     * @throws IllegalArgumentException if any character lies above the single-byte bound
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
     * Measures a value as encoded bytes in the single-byte character set of the legacy fields.
     *
     * <p>Every width assertion in this class goes through here, so no width is ever asserted in
     * {@code String} characters or against the platform default charset. The value must already have
     * passed the representability gate, otherwise the measurement would count substituted bytes.
     *
     * @param value the value to measure
     * @return the number of bytes the value occupies when encoded
     */
    private static int encodedByteWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Reproduces the paired test {@code EQUAL LOW-VALUES OR EQUAL SPACES}, as written at
     * {@code [app/cpy/CSUTLDPY.cpy:L30]}, {@code [app/cpy/CSUTLDPY.cpy:L94]} and
     * {@code [app/cpy/CSUTLDPY.cpy:L154]}.
     *
     * <p>Each half of the test compares the <em>whole</em> field against a figurative constant, so a slice
     * that mixes null characters and spaces satisfies neither half and is not treated as blank. The two
     * halves are therefore kept as two whole-field tests rather than merged into a per-character check.
     *
     * @param slice the field slice
     * @return whether the slice is entirely null characters or entirely spaces
     */
    private static boolean isLowValuesOrSpaces(final String slice) {
        return isEntirely(slice, LOW_VALUE) || isEntirely(slice, SPACE);
    }

    /**
     * Reproduces the condition name {@code WS-RETURN-MSG-OFF}, {@code [app/cbl/COACTUPC.cbl:L480]}, which
     * compares the caller's message field against {@code SPACES}.
     *
     * <p>An empty string counts as blank, because it is this migration's stand-in for the all-spaces state
     * of a fixed-width field. Only the space character qualifies: any other whitespace would be a value the
     * legacy comparison would reject.
     *
     * @param message the accumulated message
     * @return whether the message field is still available to be claimed
     */
    private static boolean isReturnMessageOff(final String message) {
        return isEntirely(message, SPACE);
    }

    /**
     * Reproduces a concatenating store into the return-message field {@code WS-RETURN-MSG}, guarded by
     * the message-off condition: the shape
     * that every one of the cascade's message branches uses, for example at
     * {@code [app/cpy/CSUTLDPY.cpy:L34]} through {@code [app/cpy/CSUTLDPY.cpy:L40]}.
     *
     * <p>The guard is what makes the message first-wins: once any edit has claimed the field, later edits
     * still set their flags but leave the text alone. Only the suffix is stored, because the legacy head of
     * the concatenation is the trimmed field label, which belongs to the caller.
     *
     * @param state  the per-invocation working storage
     * @param suffix the message suffix this branch declares
     */
    private static void setReturnMessage(final CascadeState state, final String suffix) {
        if (isReturnMessageOff(state.returnMessage)) {
            state.returnMessage = suffix;
        }
    }

    /**
     * Tests whether a value consists entirely of one character.
     *
     * <p>An empty value satisfies the test, matching the vacuous truth of comparing a zero-length field
     * against a figurative constant.
     *
     * @param value    the value
     * @param expected the only character the value may contain
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
     * Reproduces the {@code NUMERIC} class condition on an alphanumeric field, as tested at
     * {@code [app/cpy/CSUTLDPY.cpy:L48]}, and the numeric-test intrinsic used at
     * {@code [app/cpy/CSUTLDPY.cpy:L126]} and {@code [app/cpy/CSUTLDPY.cpy:L170]}.
     *
     * <p>Membership is strict ASCII. The library digit predicate is Unicode aware and accepts digits from
     * other scripts, none of which a legacy single-byte field can hold, so it is not used.
     *
     * @param slice the field slice
     * @return whether the slice is non-empty and consists only of the characters zero to nine
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

    /**
     * Tests one character for strict ASCII digit membership.
     *
     * @param candidate the character
     * @return whether the character is between zero and nine inclusive
     */
    private static boolean isAsciiDigit(final char candidate) {
        return candidate >= '0' && candidate <= '9';
    }

    /**
     * Reads a field slice through its numeric redefinition, as the level-88 range tests do at
     * {@code [app/cpy/CSUTLDWY.cpy:L17]} and {@code [app/cpy/CSUTLDWY.cpy:L26]}.
     *
     * <p>Accumulated digit by digit rather than delegated to a library parser, because a parser would also
     * accept a leading sign and non-ASCII digits, neither of which the redefined display field can hold.
     *
     * @param slice the field slice
     * @return the value the redefinition holds, or the not-numeric sentinel when the slice is not composed
     *         of ASCII digits and therefore cannot equal any condition-name value
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
     * Reproduces the condition name {@code WS-31-DAY-MONTH}, {@code [app/cpy/CSUTLDWY.cpy:L21]} through
     * {@code [app/cpy/CSUTLDWY.cpy:L23]}, which enumerates the seven months that have a 31st day.
     *
     * <p>The seven values are written out as the condition name writes them, rather than derived from a
     * calendar library, so the enumeration a reader must check is the enumeration the source declares.
     *
     * @param month the month, read through its numeric redefinition
     * @return whether that month has a 31st day
     */
    private static boolean isThirtyOneDayMonth(final int month) {
        return switch (month) {
            case 1, 3, 5, 7, 8, 10, 12 -> true;
            default -> false;
        };
    }

    /**
     * Resolves an eight-character image to a calendar date for the date-of-birth comparison.
     *
     * <p>Stands in for the integer-of-date intrinsic at {@code [app/cpy/CSUTLDPY.cpy:L346]}, which converts
     * a date to a day number so that two dates can be compared as integers. Resolution is strict, so the
     * conversion refuses a value that is not a real calendar date instead of normalising it.
     *
     * @param ccyymmddImage the eight-character image
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
