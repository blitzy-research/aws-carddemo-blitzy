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
package com.carddemo.util;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Builds the seventeen fixed eighty-byte job-submission card images that the legacy transaction
 * report screen wrote, card by card, to the CICS transient data queue in order to trigger the
 * daily transaction report batch job.
 *
 * <p>This is an <strong>external interface contract</strong>, not an internal helper. Every card,
 * its byte width, its position in the sequence, the four date substitution slots and the
 * terminating sentinel are contractual, and they are verified end to end by draining a real
 * queue. The card images are the batch-trigger half of that contract; nothing here may be
 * "modernised away".
 *
 * <p>Source of truth: transaction {@code CR00}, program {@code CORPT00C}. The card group is
 * declared as {@code JOB-DATA-1} and its seventeen eighty-byte entries occupy
 * {@code [app/cbl/CORPT00C.cbl:L82-L127]}; the submission driver and its loop occupy
 * {@code [app/cbl/CORPT00C.cbl:L462-L510]}; the queue-write paragraph is at
 * {@code [app/cbl/CORPT00C.cbl:L515]}. The single-card write buffer is a {@code PIC X(80)} field
 * at {@code [app/cbl/CORPT00C.cbl:L79]}, which is why one message carries exactly one card.
 *
 * <h2>The seventeen cards, in order</h2>
 *
 * <p>Each row shows the card image before space padding, the declaring source lines, and the
 * composition of the card. Cards 11, 12 and 15 are the only composed cards; the other fourteen
 * are fixed literals.
 *
 * <pre>{@code
 *  #   Card image (before space padding)                  Source      Composition
 * ---  ------------------------------------------------   ---------   ----------------------------
 *   1  //TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,   L83-L84     literal + padding = 80
 *   2  // NOTIFY=&SYSUID                                  L85-L86     literal + padding = 80
 *   3  //*                                                L87-L88     comment card
 *   4  //JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')     L89-L90     procedure-library card
 *   5  //*                                                L91-L92     comment card
 *   6  //STEP10 EXEC PROC=TRANREPT                        L93-L94     invokes the cataloged proc
 *   7  //*                                                L95-L96     comment card
 *   8  //STEP05R.SYMNAMES DD *                            L97-L98     in-stream SYMNAMES override
 *   9  TRAN-CARD-NUM,263,16,ZD                            L99-L100    symbol: at 263, len 16, ZD
 *  10  TRAN-PROC-DT,305,10,CH                             L101-L102   symbol: at 305, len 10, CH
 *  11  PARM-START-DATE,C'<startDate>'                     L103-L107   18 + 10 + 52 = 80
 *  12  PARM-END-DATE,C'<endDate>'                         L108-L112   16 + 10 + 54 = 80
 *  13  /*                                                 L113-L114   in-stream data terminator
 *  14  //STEP10R.DATEPARM DD *                            L115-L116   in-stream DATEPARM override
 *  15  <startDate> <endDate>                              L117-L121   10 + 1 + 10 + 59 = 80
 *  16  /*                                                 L122-L123   in-stream data terminator
 *  17  /*EOF                                              L124-L125   sentinel - IS transmitted
 * }</pre>
 *
 * <p>Every card is emitted at exactly eighty <em>encoded bytes</em>, left justified and padded
 * with the ASCII space. Padding is never a zero, never a null and never a tab. Seventeen cards of
 * eighty bytes give a total image width of {@code 17 x 80 = 1360} bytes.
 *
 * <p>The composition arithmetic of the three composed cards is taken from the declared component
 * widths rather than inferred from the finished string:
 *
 * <ul>
 *   <li>Card 11 is an eighteen-byte leading literal, then the ten-byte start-date slot, then a
 *       fifty-two-byte trailing field whose first byte is a literal apostrophe and whose
 *       remaining fifty-one bytes are spaces. {@code 18 + 10 + 52 = 80}.</li>
 *   <li>Card 12 is a sixteen-byte leading literal, then the ten-byte end-date slot, then a
 *       fifty-four-byte trailing field whose first byte is a literal apostrophe and whose
 *       remaining fifty-three bytes are spaces. {@code 16 + 10 + 54 = 80}.</li>
 *   <li>Card 15 is the ten-byte start-date slot, then a separator declared as a bare
 *       {@code PIC X} and therefore <strong>exactly one byte</strong> rather than a defaulted
 *       width, then the ten-byte end-date slot, then fifty-nine spaces.
 *       {@code 10 + 1 + 10 + 59 = 80}. Card 15 is the only card built entirely from substituted
 *       values plus padding, with no leading literal at all.</li>
 * </ul>
 *
 * <p>The apostrophes on cards 11 and 12 are real output characters, not quoting artefacts. They
 * close the character constants that the sort step compares against, so they may not be moved,
 * omitted or repositioned.
 *
 * <h2>Four substitution slots carry two values</h2>
 *
 * <p>There are exactly four substitution slots, each declared {@code PIC X(10)}, and they carry
 * only two distinct values:
 *
 * <ul>
 *   <li>{@code PARM-START-DATE-1} on card 11 receives the start date.</li>
 *   <li>{@code PARM-END-DATE-1} on card 12 receives the end date.</li>
 *   <li>{@code PARM-START-DATE-2} on card 15 receives the <em>same</em> start date.</li>
 *   <li>{@code PARM-END-DATE-2} on card 15 receives the <em>same</em> end date.</li>
 * </ul>
 *
 * <p>So the builder's input is two dates and its output fills four slots: the start date appears
 * on cards 11 and 15, and the end date appears on cards 12 and 15. Cards 11 and 12 carry the
 * dates as sort-filter values inside apostrophes; card 15 carries them as the report program's
 * date parameters. The two occurrences of a date are produced from the same validated argument
 * and are therefore never allowed to diverge.
 *
 * <p>Each date is exactly ten characters. The format the legacy screen used is the literal
 * {@code YYYY-MM-DD} held in a {@code PIC X(10)} work field at
 * {@code [app/cbl/CORPT00C.cbl:L72]}. Because the surrounding frame is a fixed eighty columns, a
 * date of any other encoded byte length would shift the closing apostrophe on cards 11 and 12 or
 * overflow the card, so each slot argument is validated as exactly ten encoded bytes. That check
 * is a <strong>frame-integrity</strong> check, not a calendar check: calendar validity, leap-year
 * handling, range ordering and the multi-paragraph date-edit cascade all belong to the date
 * validation service, and this class neither parses nor reformats a date.
 *
 * <h2>There is no report-name substitution slot</h2>
 *
 * <p>A {@code PIC X(10)} report-name work field exists in the program at
 * {@code [app/cbl/CORPT00C.cbl:L58]}, but it is used only to compose screen messages. No card
 * contains a report-name placeholder, and the job name on card 1 is a fixed literal. The job name
 * is therefore never derived, never parameterised and never templatised.
 *
 * <h2>The sentinel card is transmitted, not merely held</h2>
 *
 * <p>This is the single most likely defect in a naive translation, so the submission loop at
 * {@code [app/cbl/CORPT00C.cbl:L496-L508]} was traced statement by statement. The driver clears
 * its end-of-loop flag, then enters a {@code PERFORM VARYING ... UNTIL} loop whose terminating
 * condition is evaluated at the <em>top</em> of each iteration. Inside the body the current card
 * is moved to the write buffer and, when that card is the sentinel, the terminating flag is set.
 * The queue write is then performed at {@code [app/cbl/CORPT00C.cbl:L507]}, which is
 * <em>after</em> the flag has been set and still inside the same iteration. Because the loop tests
 * before it iterates rather than after, the flag set during the seventeenth iteration cannot
 * suppress the write that follows it in that same iteration.
 *
 * <p>Therefore the seventeenth card reaches the queue. This builder always emits all seventeen
 * cards with the sentinel seventeenth and last. It is never dropped, never filtered, never
 * treated as a loop terminator and never conditional or optional.
 *
 * <p>The same loop is bounded by the literal one thousand, which is the identical bound discussed
 * under the oversized redefine below.
 *
 * <h2>Card details that are easy to "correct" by mistake</h2>
 *
 * <ul>
 *   <li>On card 1 the message class is the digit <strong>zero</strong>, not the letter O.</li>
 *   <li>Card 1 ends with a <strong>trailing comma</strong>. That comma is a JCL continuation
 *       marker joining the job card to the notify card, so it is content and is retained.</li>
 *   <li>Card 2 spells the system-user symbol correctly. A different member of the estate carries a
 *       transposed spelling of that symbol; that typo belongs to the other member and must never
 *       be imported here.</li>
 * </ul>
 *
 * <h2>The oversized redefine</h2>
 *
 * <p>The card group is redefined as a table of one thousand eighty-byte lines at
 * {@code [app/cbl/CORPT00C.cbl:L126-L127]}. One thousand entries of eighty bytes claim eighty
 * thousand bytes over a group that is only one thousand three hundred and sixty bytes long, which
 * makes the redefine oversized by a factor of roughly fifty-nine. It is a recorded source
 * anomaly, and the submission loop genuinely iterates against that same bound.
 *
 * <p>The bound is honoured rather than propagated: it is published as the named constant
 * {@link #OVERSIZED_REDEFINE_CARD_BOUND} and the produced card count is asserted not to exceed
 * it. Nothing is allocated to one thousand entries, no output is padded to one thousand cards,
 * and no API is offered that would let a caller add cards up to the bound.
 *
 * <h2>The onward queue contract, implemented elsewhere</h2>
 *
 * <p>The target queue is defined at {@code [app/csd/CARDDEMO.CSD]} as an extra-partition,
 * output-only, initially-opened queue with four attributes that bind the transport. Those four
 * attributes map onto the target as follows, and all four are implemented by
 * {@code com.carddemo.service.JobSubmissionService}, never here:
 *
 * <ul>
 *   <li>A fixed record size of eighty becomes an eighty-character fixed-width payload per
 *       message, which is exactly one card per message.</li>
 *   <li>A fixed record format becomes the invariant that no message is trimmed, wrapped or
 *       newline-terminated.</li>
 *   <li>A modify disposition becomes append semantics, published as one message per card in the
 *       order this builder returns them, preserved by message-group ordering.</li>
 *   <li>An ignore error option becomes a non-blocking publish whose failure path logs and
 *       continues rather than aborting the caller.</li>
 * </ul>
 *
 * <p>Accordingly this class has no queue client, no messaging or cloud dependency, no publish
 * method, no retry, no failure-message text, no confirmation gate and no reporting-period logic.
 * It receives two dates and asks no questions.
 *
 * <h2>Cross references</h2>
 *
 * <ul>
 *   <li>Cards 9 and 10 restate the sort-symbol specification that the cataloged procedure
 *       declares at {@code [app/proc/TRANREPT.prc]}; cards 8 through 12 override that
 *       procedure's symbol-names input and cards 14 and 15 override its date-parameter input, so
 *       the step names on cards 8 and 14 must match the procedure's step names exactly.</li>
 *   <li>The same sixteen bytes at one-based offset 263 are typed as zoned decimal here and as
 *       character data by the statement job at {@code [app/jcl/CREASTMT.JCL]}. The typing is
 *       therefore per job, which is why the batch tier carries one comparator per job rather than
 *       one shared comparator.</li>
 *   <li>The submitting job stream at {@code [app/jcl/TRANREPT.jcl]} contains a duplicate step
 *       name, a recorded source anomaly. The card images reference the step by name and are
 *       unaffected by the duplication; the target generates distinct step names.</li>
 *   <li>The queue-write paragraph of the legacy program is spelled {@code WIRTE-JOBSUB-TDQ} at
 *       {@code [app/cbl/CORPT00C.cbl:L515]}, a transposition of "write". The Java naming is
 *       corrected wherever that behaviour is implemented, and the legacy spelling is cited here
 *       and in the traceability matrix so the mapping from Java back to the COBOL paragraph stays
 *       findable by search.</li>
 * </ul>
 *
 * <h2>Why there is no templating engine here</h2>
 *
 * <p>Byte-identical output requires the same literals at the same offsets. A templating engine or
 * a general-purpose format-string abstraction introduces whitespace, ordering and locale
 * variability that a byte-level comparison immediately fails, and it also makes the eighty-column
 * frame implicit rather than asserted. Every card is therefore assembled from an explicit literal
 * plus explicit padding computed from declared component widths, and every finished card is
 * asserted at eighty encoded bytes so a mistake in either the literal or the padding is caught
 * rather than shipped.
 *
 * <p>All widths are measured as encoded bytes in {@link StandardCharsets#US_ASCII}, never as
 * {@code char} counts, so a multi-byte character cannot silently break the eighty-column frame.
 *
 * <p>This class is stateless, pure and side-effect free. It performs no input or output, consults
 * no clock, environment or random source, holds no mutable state, logs nothing and is safe for
 * concurrent use.
 *
 * <p>Provenance: legacy checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
public final class JclCardImageBuilder {

    /**
     * Width in bytes of a single job-submission card image, from the {@code PIC X(80)} write
     * buffer at {@code [app/cbl/CORPT00C.cbl:L79]} and the fixed eighty-byte record size the
     * queue definition declares at {@code [app/csd/CARDDEMO.CSD]}.
     */
    public static final int CARD_IMAGE_WIDTH = 80;

    /**
     * Number of cards in the job-submission image, from the seventeen eighty-byte entries of the
     * card group at {@code [app/cbl/CORPT00C.cbl:L83-L125]}. Fourteen are fixed literals and
     * three carry substituted dates.
     */
    public static final int CARD_COUNT = 17;

    /**
     * Width in bytes of each date substitution slot, from the four {@code PIC X(10)} slot fields.
     * The corresponding format literal is {@code YYYY-MM-DD}, held at
     * {@code [app/cbl/CORPT00C.cbl:L72]}.
     */
    public static final int DATE_SLOT_WIDTH = 10;

    /**
     * The one-thousand-entry bound of the oversized redefine at
     * {@code [app/cbl/CORPT00C.cbl:L126-L127]}, where a table of one thousand eighty-byte lines
     * redefines a group of only {@value #TOTAL_IMAGE_WIDTH} bytes and therefore claims eighty
     * thousand bytes it does not have. The submission loop iterates against this same bound.
     *
     * <p>The constant exists so the bound can be asserted, not allocated: the produced card count
     * is checked against it, and nothing is ever sized or padded to it.
     */
    public static final int OVERSIZED_REDEFINE_CARD_BOUND = 1000;

    /**
     * Total width in bytes of the concatenated job-submission image, being
     * {@value #CARD_COUNT} cards of {@value #CARD_IMAGE_WIDTH} bytes, that is 1360 bytes.
     */
    public static final int TOTAL_IMAGE_WIDTH = CARD_COUNT * CARD_IMAGE_WIDTH;

    /**
     * Name of the start-date sort symbol, used to identify the slot in validation failures. The
     * same value names both start-date slots, {@code PARM-START-DATE-1} on card 11 and
     * {@code PARM-START-DATE-2} on card 15.
     */
    public static final String SLOT_PARM_START_DATE = "PARM-START-DATE";

    /**
     * Name of the end-date sort symbol, used to identify the slot in validation failures. The
     * same value names both end-date slots, {@code PARM-END-DATE-1} on card 12 and
     * {@code PARM-END-DATE-2} on card 15.
     */
    public static final String SLOT_PARM_END_DATE = "PARM-END-DATE";

    /**
     * Card 1, the job card, from {@code [app/cbl/CORPT00C.cbl:L83-L84]}. The message class is the
     * digit zero and the trailing comma is a JCL continuation marker onto card 2; both are
     * content.
     */
    public static final String JOB_CARD = "//TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,";

    /**
     * Card 2, the notify card continued from card 1, from
     * {@code [app/cbl/CORPT00C.cbl:L85-L86]}. The system-user symbol is spelled correctly here.
     */
    public static final String NOTIFY_CARD = "// NOTIFY=&SYSUID";

    /**
     * Cards 3, 5 and 7, the comment cards, from {@code [app/cbl/CORPT00C.cbl:L87-L88]},
     * {@code [app/cbl/CORPT00C.cbl:L91-L92]} and {@code [app/cbl/CORPT00C.cbl:L95-L96]}. All
     * three hold the same literal and all three are emitted.
     */
    public static final String COMMENT_CARD = "//*";

    /**
     * Card 4, the procedure-library card naming the library that holds the cataloged procedure,
     * from {@code [app/cbl/CORPT00C.cbl:L89-L90]}.
     */
    public static final String JOBLIB_CARD = "//JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')";

    /**
     * Card 6, the step card that invokes the cataloged transaction-report procedure, from
     * {@code [app/cbl/CORPT00C.cbl:L93-L94]}.
     */
    public static final String EXEC_PROC_CARD = "//STEP10 EXEC PROC=TRANREPT";

    /**
     * Card 8, the in-stream override of the sort step's symbol-names input, from
     * {@code [app/cbl/CORPT00C.cbl:L97-L98]}. The qualifying step name matches the sort step of
     * the cataloged procedure at {@code [app/proc/TRANREPT.prc]}.
     */
    public static final String SYMNAMES_DD_CARD = "//STEP05R.SYMNAMES DD *";

    /**
     * Card 9, the card-number sort symbol, from {@code [app/cbl/CORPT00C.cbl:L99-L100]}. It
     * declares sixteen bytes at one-based offset 263 as zoned decimal. The statement job types the
     * same bytes as character data, which is why comparators are per job.
     */
    public static final String SORT_SYMBOL_CARD_NUM_CARD = "TRAN-CARD-NUM,263,16,ZD";

    /**
     * Card 10, the processing-date sort symbol, from {@code [app/cbl/CORPT00C.cbl:L101-L102]}. It
     * declares ten bytes at one-based offset 305 as character data, and it is the field the
     * report's inclusive date-range filter compares against the two substituted dates.
     */
    public static final String SORT_SYMBOL_PROC_DT_CARD = "TRAN-PROC-DT,305,10,CH";

    /**
     * The eighteen-byte leading literal of card 11, from
     * {@code [app/cbl/CORPT00C.cbl:L103-L105]}. It opens the character constant that the
     * ten-byte {@code PARM-START-DATE-1} slot fills, and the constant is closed by the apostrophe
     * that begins the card's fifty-two-byte trailing field.
     */
    public static final String SORT_SYMBOL_START_DATE_LEAD = "PARM-START-DATE,C'";

    /**
     * The sixteen-byte leading literal of card 12, from
     * {@code [app/cbl/CORPT00C.cbl:L109-L110]}. It opens the character constant that the ten-byte
     * {@code PARM-END-DATE-1} slot fills, and the constant is closed by the apostrophe that
     * begins the card's fifty-four-byte trailing field.
     */
    public static final String SORT_SYMBOL_END_DATE_LEAD = "PARM-END-DATE,C'";

    /**
     * Cards 13 and 16, the in-stream data terminators, from
     * {@code [app/cbl/CORPT00C.cbl:L113-L114]} and {@code [app/cbl/CORPT00C.cbl:L122-L123]}.
     * Card 13 closes the symbol-names data and card 16 closes the date-parameter data.
     */
    public static final String IN_STREAM_TERMINATOR_CARD = "/*";

    /**
     * Card 14, the in-stream override of the report step's date-parameter input, from
     * {@code [app/cbl/CORPT00C.cbl:L115-L116]}. The qualifying step name matches the report step
     * of the cataloged procedure at {@code [app/proc/TRANREPT.prc]}.
     */
    public static final String DATEPARM_DD_CARD = "//STEP10R.DATEPARM DD *";

    /**
     * Card 17, the terminating sentinel, from {@code [app/cbl/CORPT00C.cbl:L124-L125]}. The
     * legacy submission loop writes this card before it stops, so it is part of the transmitted
     * contract and is always emitted last.
     */
    public static final String EOF_SENTINEL_CARD = "/*EOF";

    /** The single ASCII space that is the only padding character any card may use. */
    private static final String PAD_CHARACTER = " ";

    /** The literal apostrophe that closes the character constants on cards 11 and 12. */
    private static final String CLOSING_APOSTROPHE = "'";

    /** Width of the closing apostrophe, being the first byte of the cards 11 and 12 trailers. */
    private static final int CLOSING_APOSTROPHE_WIDTH = 1;

    /** Declared width of the card 11 leading literal, from its {@code PIC X(18)} field. */
    private static final int START_DATE_LEAD_WIDTH = 18;

    /** Declared width of the card 11 trailing field, from its {@code PIC X(52)} field. */
    private static final int START_DATE_TRAILER_WIDTH = 52;

    /** Declared width of the card 12 leading literal, from its {@code PIC X(16)} field. */
    private static final int END_DATE_LEAD_WIDTH = 16;

    /** Declared width of the card 12 trailing field, from its {@code PIC X(54)} field. */
    private static final int END_DATE_TRAILER_WIDTH = 54;

    /**
     * Declared width of the card 15 separator. The field is a bare {@code PIC X} holding a single
     * space, so it is exactly one byte rather than a defaulted width.
     */
    private static final int DATE_PARAMETER_SEPARATOR_WIDTH = 1;

    /** Declared width of the card 15 trailing field, from its {@code PIC X(59)} field. */
    private static final int DATE_PARAMETER_TRAILER_WIDTH = 59;

    /** Not instantiable: every member of this contract is static. */
    private JclCardImageBuilder() {
        throw new AssertionError("JclCardImageBuilder is a static contract and is not instantiable");
    }

    /**
     * Builds the complete job-submission card image for one transaction-report request.
     *
     * <p>The returned list holds exactly {@value #CARD_COUNT} card images in the order the legacy
     * card group declares them, each exactly {@value #CARD_IMAGE_WIDTH} encoded bytes, left
     * justified and padded with the ASCII space. The list is unmodifiable and no mutable
     * structure escapes.
     *
     * <p>The two arguments fill four slots: the start date is placed on card 11 as a sort-filter
     * character constant and again on card 15 as a report parameter, and the end date is placed on
     * card 12 and again on card 15. Because both occurrences of each date come from the same
     * validated argument, they cannot diverge.
     *
     * <p>The seventeenth card is the sentinel, and it is always present and always last. The
     * legacy submission loop writes it before it stops, so dropping it would break the
     * batch-trigger contract.
     *
     * <p>Each argument is a raw ten-character date slot, conventionally formatted
     * {@code YYYY-MM-DD}. It is validated for encoded byte width only, because the eighty-column
     * frame depends on that width; it is not parsed, reformatted or checked for calendar validity.
     *
     * @param startDate the ten-byte value for the {@code PARM-START-DATE-1} slot on card 11 and
     *                  the {@code PARM-START-DATE-2} slot on card 15; must not be {@code null}
     * @param endDate   the ten-byte value for the {@code PARM-END-DATE-1} slot on card 12 and the
     *                  {@code PARM-END-DATE-2} slot on card 15; must not be {@code null}
     * @return an unmodifiable, ordered list of exactly {@value #CARD_COUNT} card images, each
     *         exactly {@value #CARD_IMAGE_WIDTH} encoded bytes
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if either argument is not exactly
     *                                  {@value #DATE_SLOT_WIDTH} encoded bytes, or contains a
     *                                  character that is not representable as a single
     *                                  US-ASCII byte
     */
    public static List<String> build(final String startDate, final String endDate) {
        final String start = requireDateSlot(startDate, SLOT_PARM_START_DATE);
        final String end = requireDateSlot(endDate, SLOT_PARM_END_DATE);

        // Explicit literal plus explicit padding, one entry per card, in declaration order.
        // Deliberately not driven by a template or a format string: the eighty-column frame must
        // stay visible at the call site and asserted, card by card.
        final List<String> cardImages = List.of(
                padToCardImageWidth(JOB_CARD),                   //  1  job card
                padToCardImageWidth(NOTIFY_CARD),                //  2  notify continuation
                padToCardImageWidth(COMMENT_CARD),               //  3  comment
                padToCardImageWidth(JOBLIB_CARD),                //  4  procedure library
                padToCardImageWidth(COMMENT_CARD),               //  5  comment
                padToCardImageWidth(EXEC_PROC_CARD),             //  6  invoke cataloged procedure
                padToCardImageWidth(COMMENT_CARD),               //  7  comment
                padToCardImageWidth(SYMNAMES_DD_CARD),           //  8  symbol-names override
                padToCardImageWidth(SORT_SYMBOL_CARD_NUM_CARD),  //  9  card-number symbol
                padToCardImageWidth(SORT_SYMBOL_PROC_DT_CARD),   // 10  processing-date symbol
                startDateSortSymbolCard(start),                  // 11  PARM-START-DATE-1
                endDateSortSymbolCard(end),                      // 12  PARM-END-DATE-1
                padToCardImageWidth(IN_STREAM_TERMINATOR_CARD),  // 13  terminator
                padToCardImageWidth(DATEPARM_DD_CARD),           // 14  date-parameter override
                dateParameterCard(start, end),                   // 15  PARM-START/END-DATE-2
                padToCardImageWidth(IN_STREAM_TERMINATOR_CARD),  // 16  terminator
                padToCardImageWidth(EOF_SENTINEL_CARD));         // 17  sentinel - transmitted

        return requireWellFormedSequence(cardImages);
    }

    /**
     * Builds the concatenated form of the job-submission image for callers that need the whole
     * block as one value.
     *
     * <p>The result is the plain concatenation of the {@value #CARD_COUNT} card images returned by
     * {@link #build(String, String)} with no separator, no line terminator and no trailing
     * newline, giving exactly {@value #TOTAL_IMAGE_WIDTH} encoded bytes. The cards are
     * fixed-width records, not text lines, which is why nothing is inserted between them.
     *
     * @param startDate the ten-byte start-date slot value; must not be {@code null}
     * @param endDate   the ten-byte end-date slot value; must not be {@code null}
     * @return the concatenated image, exactly {@value #TOTAL_IMAGE_WIDTH} encoded bytes
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if either argument is not exactly
     *                                  {@value #DATE_SLOT_WIDTH} encoded bytes, or contains a
     *                                  character that is not representable as a single
     *                                  US-ASCII byte
     */
    public static String buildConcatenatedImage(final String startDate, final String endDate) {
        final List<String> cardImages = build(startDate, endDate);

        // The capacity is the exact, known width of the finished image, not a tuning figure.
        final StringBuilder image = new StringBuilder(TOTAL_IMAGE_WIDTH);
        for (final String cardImage : cardImages) {
            image.append(cardImage);
        }

        final String concatenated = image.toString();
        final int width = encodedByteLength(concatenated);
        if (width != TOTAL_IMAGE_WIDTH) {
            throw new IllegalStateException("concatenated job-submission image must be exactly "
                    + TOTAL_IMAGE_WIDTH + " encoded bytes but was " + width);
        }
        return concatenated;
    }

    /**
     * Assembles card 11, the start-date sort symbol, from its declared component widths:
     * an eighteen-byte leading literal, the ten-byte start-date slot, and a fifty-two-byte
     * trailing field whose first byte is the closing apostrophe and whose remaining fifty-one
     * bytes are spaces. {@code 18 + 10 + 52 = 80}.
     *
     * @param startDate the already validated ten-byte start-date slot value
     * @return the card 11 image at exactly {@value #CARD_IMAGE_WIDTH} encoded bytes
     */
    private static String startDateSortSymbolCard(final String startDate) {
        final String lead = requireComponentWidth(SORT_SYMBOL_START_DATE_LEAD,
                START_DATE_LEAD_WIDTH, "card 11 leading literal");
        final String trailer = requireComponentWidth(
                CLOSING_APOSTROPHE + pad(START_DATE_TRAILER_WIDTH - CLOSING_APOSTROPHE_WIDTH),
                START_DATE_TRAILER_WIDTH, "card 11 trailing field");
        return requireCardImageWidth(lead + startDate + trailer);
    }

    /**
     * Assembles card 12, the end-date sort symbol, from its declared component widths:
     * a sixteen-byte leading literal, the ten-byte end-date slot, and a fifty-four-byte trailing
     * field whose first byte is the closing apostrophe and whose remaining fifty-three bytes are
     * spaces. {@code 16 + 10 + 54 = 80}.
     *
     * @param endDate the already validated ten-byte end-date slot value
     * @return the card 12 image at exactly {@value #CARD_IMAGE_WIDTH} encoded bytes
     */
    private static String endDateSortSymbolCard(final String endDate) {
        final String lead = requireComponentWidth(SORT_SYMBOL_END_DATE_LEAD,
                END_DATE_LEAD_WIDTH, "card 12 leading literal");
        final String trailer = requireComponentWidth(
                CLOSING_APOSTROPHE + pad(END_DATE_TRAILER_WIDTH - CLOSING_APOSTROPHE_WIDTH),
                END_DATE_TRAILER_WIDTH, "card 12 trailing field");
        return requireCardImageWidth(lead + endDate + trailer);
    }

    /**
     * Assembles card 15, the report date-parameter card, from its declared component widths:
     * the ten-byte start-date slot, a one-byte separator, the ten-byte end-date slot, and
     * fifty-nine trailing spaces. {@code 10 + 1 + 10 + 59 = 80}.
     *
     * <p>The separator is declared as a bare {@code PIC X} holding a space, so it is exactly one
     * byte wide rather than a defaulted width. Card 15 carries no leading literal at all, which
     * makes it the only card built entirely from substituted values plus padding.
     *
     * @param startDate the already validated ten-byte start-date slot value
     * @param endDate   the already validated ten-byte end-date slot value
     * @return the card 15 image at exactly {@value #CARD_IMAGE_WIDTH} encoded bytes
     */
    private static String dateParameterCard(final String startDate, final String endDate) {
        final String separator = pad(DATE_PARAMETER_SEPARATOR_WIDTH);
        final String trailer = pad(DATE_PARAMETER_TRAILER_WIDTH);
        return requireCardImageWidth(startDate + separator + endDate + trailer);
    }

    /**
     * Verifies the assembled sequence before it is published.
     *
     * <p>Three properties are checked: the produced count does not exceed the one-thousand-entry
     * bound of the legacy oversized redefine, the produced count is exactly
     * {@value #CARD_COUNT}, and every card is exactly {@value #CARD_IMAGE_WIDTH} encoded bytes.
     * The bound is asserted rather than allocated, so nothing here is ever sized to one thousand.
     *
     * @param cardImages the assembled, already unmodifiable card sequence
     * @return the same sequence, once verified
     */
    private static List<String> requireWellFormedSequence(final List<String> cardImages) {
        final int producedCount = cardImages.size();
        if (producedCount > OVERSIZED_REDEFINE_CARD_BOUND) {
            throw new IllegalStateException("job-submission image produced " + producedCount
                    + " cards, which exceeds the legacy card-table bound of "
                    + OVERSIZED_REDEFINE_CARD_BOUND);
        }
        if (producedCount != CARD_COUNT) {
            throw new IllegalStateException("job-submission image must contain exactly "
                    + CARD_COUNT + " cards but contained " + producedCount);
        }
        for (final String cardImage : cardImages) {
            requireCardImageWidth(cardImage);
        }
        return cardImages;
    }

    /**
     * Left justifies a fixed card literal in the {@value #CARD_IMAGE_WIDTH}-byte card frame and
     * pads the remainder with ASCII spaces.
     *
     * @param cardContent the card literal, no wider than the card frame
     * @return the padded card image at exactly {@value #CARD_IMAGE_WIDTH} encoded bytes
     */
    private static String padToCardImageWidth(final String cardContent) {
        final int contentWidth = encodedByteLength(cardContent);
        if (contentWidth > CARD_IMAGE_WIDTH) {
            throw new IllegalStateException("card literal does not fit the " + CARD_IMAGE_WIDTH
                    + "-byte card frame: it is " + contentWidth + " encoded bytes");
        }
        return requireCardImageWidth(cardContent + pad(CARD_IMAGE_WIDTH - contentWidth));
    }

    /**
     * Asserts that a finished card image occupies the full card frame.
     *
     * <p>The width is measured as encoded bytes rather than as a character count, so a multi-byte
     * character cannot silently break the eighty-column frame.
     *
     * @param cardImage the finished card image
     * @return the same image, once verified
     */
    private static String requireCardImageWidth(final String cardImage) {
        final int width = encodedByteLength(cardImage);
        if (width != CARD_IMAGE_WIDTH) {
            throw new IllegalStateException("assembled card image must be exactly "
                    + CARD_IMAGE_WIDTH + " encoded bytes but was " + width);
        }
        return cardImage;
    }

    /**
     * Asserts that one component of a composed card occupies its declared width, so that a
     * mistake in a leading literal or in a trailing field is caught at its own offset rather than
     * only as a total-width discrepancy.
     *
     * @param component     the component value
     * @param expectedWidth the width the legacy field declares for it
     * @param componentName the component's name, for the failure message
     * @return the same component, once verified
     */
    private static String requireComponentWidth(final String component, final int expectedWidth,
            final String componentName) {
        final int width = encodedByteLength(component);
        if (width != expectedWidth) {
            throw new IllegalStateException(componentName + " must be exactly " + expectedWidth
                    + " encoded bytes but was " + width);
        }
        return component;
    }

    /**
     * Validates one date substitution slot for frame integrity.
     *
     * <p>The slot must be exactly {@value #DATE_SLOT_WIDTH} encoded bytes and must be
     * representable in single-byte US-ASCII, because the surrounding frame is a fixed
     * {@value #CARD_IMAGE_WIDTH} columns: a wider value would overflow the card and a narrower
     * one would shift the closing apostrophe on cards 11 and 12.
     *
     * <p>This guard is a deliberate divergence from the legacy behaviour. A COBOL move into a
     * ten-byte field pads or truncates silently, so the legacy program had no equivalent check and
     * relied on the screen field being exactly ten characters wide. Reproducing a silent
     * corruption would defeat the byte-level contract, so a malformed slot raises here instead.
     * Nothing is ever silently padded or truncated, no malformed card is emitted, and no
     * {@code null} is returned. The failure is an unchecked argument failure rather than a domain
     * exception, because "the caller handed me the wrong number of bytes" is a frame-integrity
     * violation and not a business outcome.
     *
     * @param slotValue the caller-supplied slot value
     * @param slotName  the legacy sort-symbol name of the slot, used in the failure message
     * @return the same value, once verified
     */
    private static String requireDateSlot(final String slotValue, final String slotName) {
        Objects.requireNonNull(slotValue, "date slot " + slotName + " must not be null");

        // A fresh encoder per call: CharsetEncoder is stateful, so it is never held statically.
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(slotValue)) {
            throw new IllegalArgumentException("date slot " + slotName + " must be exactly "
                    + DATE_SLOT_WIDTH + " encoded bytes and must contain only characters that are"
                    + " representable as single US-ASCII bytes, so the " + CARD_IMAGE_WIDTH
                    + "-column card frame is preserved");
        }

        final int actualWidth = encodedByteLength(slotValue);
        if (actualWidth != DATE_SLOT_WIDTH) {
            throw new IllegalArgumentException("date slot " + slotName + " must be exactly "
                    + DATE_SLOT_WIDTH + " encoded bytes but was " + actualWidth);
        }
        return slotValue;
    }

    /**
     * Measures a value in encoded bytes using the single-byte encoding the card frame is defined
     * in. Every width decision in this class goes through here, so no width is ever taken from a
     * character count.
     *
     * @param value the value to measure
     * @return the value's length in encoded bytes
     */
    private static int encodedByteLength(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Produces a run of ASCII spaces. Padding is always the space character, never a zero, never a
     * null and never a tab.
     *
     * @param width the number of spaces, never negative
     * @return the padding run
     */
    private static String pad(final int width) {
        return PAD_CHARACTER.repeat(width);
    }
}
