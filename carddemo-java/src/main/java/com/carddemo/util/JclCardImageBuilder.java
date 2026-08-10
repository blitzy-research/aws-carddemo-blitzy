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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Builds the seventeen fixed eighty-byte job-submission card images that the legacy transaction
 * report screen wrote, card by card, to the CICS transient data queue in order to trigger the daily
 * transaction report batch job.
 *
 * <p>This is an <strong>external interface contract</strong>, not an internal helper. Every card, its
 * byte width, its position in the sequence, the four date substitution slots and the terminating
 * sentinel are contractual, and they are verified end to end by draining a real queue. Nothing here
 * may be "modernised away".
 *
 * <p>Source of truth: transaction {@code CR00}, program {@code CORPT00C}. The card group is declared
 * as {@code JOB-DATA-1} and its seventeen eighty-byte entries occupy
 * {@code [app/cbl/CORPT00C.cbl:L82-L127]}; the submission driver and its loop occupy
 * {@code [app/cbl/CORPT00C.cbl:L462-L510]}; the queue-write paragraph is at
 * {@code [app/cbl/CORPT00C.cbl:L515]}. The single-card write buffer is a {@code PIC X(80)} field at
 * {@code [app/cbl/CORPT00C.cbl:L79]}, which is why one message carries exactly one card.
 *
 * <h2>The seventeen cards, in order</h2>
 *
 * <p>Each row names the card's role, the constant that holds its image, the declaring source
 * lines, and the composition of the card. Cards 11, 12 and 15 are the only composed cards; the
 * other fourteen are fixed literals.
 *
 * <p>The card images themselves are <strong>not repeated here</strong>. Each one is published
 * exactly once, as the named constant in the row below, and that constant is the single
 * authoritative copy: an image duplicated in prose is an image that can drift out of step with
 * the bytes actually emitted, which for a Gate 5 contract is the one failure mode that must be
 * impossible. Read the constant for the content; read this table for the order, the provenance
 * and the arithmetic.
 *
 * <pre>
 *  #   Role                              Image constant                 Source      Composition
 * ---  --------------------------------  -----------------------------  ---------   -----------------
 *   1  job card                          JOB_CARD                       L83-L84     literal + pad = 80
 *   2  notify card                       NOTIFY_CARD                    L85-L86     literal + pad = 80
 *   3  comment card                      COMMENT_CARD                   L87-L88     literal + pad = 80
 *   4  procedure-library card            JOBLIB_CARD                    L89-L90     literal + pad = 80
 *   5  comment card                      COMMENT_CARD                   L91-L92     literal + pad = 80
 *   6  cataloged-procedure invocation    EXEC_PROC_CARD                 L93-L94     literal + pad = 80
 *   7  comment card                      COMMENT_CARD                   L95-L96     literal + pad = 80
 *   8  in-stream sort-symbol override    SYMNAMES_DD_CARD               L97-L98     literal + pad = 80
 *   9  sort symbol: at 263, len 16, ZD   SORT_SYMBOL_CARD_NUM_CARD      L99-L100    literal + pad = 80
 *  10  sort symbol: at 305, len 10, CH   SORT_SYMBOL_PROC_DT_CARD       L101-L102   literal + pad = 80
 *  11  start-date sort filter            SORT_SYMBOL_START_DATE_LEAD    L103-L107   18 + 10 + 52 = 80
 *  12  end-date sort filter              SORT_SYMBOL_END_DATE_LEAD      L108-L112   16 + 10 + 54 = 80
 *  13  in-stream data terminator         IN_STREAM_TERMINATOR_CARD      L113-L114   literal + pad = 80
 *  14  in-stream date-parameter override DATEPARM_DD_CARD               L115-L116   literal + pad = 80
 *  15  the two date parameters           (composed from both slots)     L117-L121   10 + 1 + 10 + 59 = 80
 *  16  in-stream data terminator         IN_STREAM_TERMINATOR_CARD      L122-L123   literal + pad = 80
 *  17  end-of-file sentinel              EOF_SENTINEL_CARD              L124-L125   literal + pad = 80
 * </pre>
 *
 * <p>Card 15 is the only card with no leading literal at all: it is the start-date slot, one
 * space byte, the end-date slot, and padding. Card 17, the sentinel, <strong>is transmitted</strong>
 * &mdash; see the loop trace below.
 *
 * <p>Every card is emitted at exactly eighty <em>encoded bytes</em>, left justified and padded with
 * the ASCII space - never a zero, a null or a tab - so seventeen cards give a total image width of
 * {@code 17 x 80 = 1360} bytes. The composition arithmetic of the three composed cards is taken from
 * the declared component widths rather than inferred from the finished string. Card 11 is an
 * eighteen-byte leading literal, the ten-byte start-date slot, then a fifty-two-byte trailing field
 * whose first byte is a literal apostrophe and whose remaining fifty-one bytes are spaces. Card 12 is
 * a sixteen-byte leading literal, the ten-byte end-date slot, then a fifty-four-byte trailing field
 * beginning with the same apostrophe. Card 15 is the ten-byte start-date slot, a separator declared
 * as a bare {@code PIC X} and therefore <strong>exactly one byte</strong> rather than a defaulted
 * width, the ten-byte end-date slot, then fifty-nine spaces; it is the only card built entirely from
 * substituted values plus padding, with no leading literal. The apostrophes on cards 11 and 12 are
 * real output characters, not quoting artefacts: they close the character constants the sort step
 * compares against, so they may not be moved, omitted or repositioned.
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
 *   <li>Card 15 is the ten-byte start-date slot, then a separator declared as an unnamed filler of
 *       unqualified alphanumeric type and therefore <strong>exactly one byte</strong> rather than a
 *       defaulted width, then the ten-byte end-date slot, then fifty-nine spaces.
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
 * {@code [app/cbl/CORPT00C.cbl:L72]}. Because the frame is a fixed eighty columns, a date of any
 * other encoded byte length would shift the closing apostrophe on cards 11 and 12 or overflow the
 * card, so each slot argument is validated as exactly ten encoded bytes. That is a
 * <strong>frame-integrity</strong> check rather than the transaction's date edit: range ordering, the
 * screen's own field-level messages and the multi-paragraph date-edit cascade all belong to the date
 * validation service. This class does refuse a slot that names no real day, and it does so with a
 * strict resolver whose parsed value is discarded - the check exists to reject, never to convert, so an
 * accepted slot reaches its card byte for byte as the caller supplied it and no date is ever reformatted
 * here.
 *
 * <h2>The slot shape is part of the frame, not a calendar rule</h2>
 *
 * <p>Ten bytes on its own is not the whole of the frame. The legacy work fields the slots are
 * filled from are declared at {@code [app/cbl/CORPT00C.cbl:L60-L71]} as a four-byte year component,
 * a one-byte {@code FILLER} whose value is a literal hyphen, a two-byte month component, a second
 * one-byte hyphen {@code FILLER}, and a two-byte day component. The two hyphens are
 * <strong>constants of the group</strong>, not data: nothing is ever moved into them. Only the
 * three numeric components are moved into, from screen fields that the terminal restricts to
 * numeric entry, and the assembled ten-byte value is then handed to the date-validation subprogram
 * before it is substituted into the slots at {@code [app/cbl/CORPT00C.cbl:L429-L432]}. So
 * {@code YYYY-MM-DD} with digits in the eight numeric positions and a hyphen in the fifth and
 * eighth is the shape the legacy field could physically hold, and it is published here as
 * {@link #DATE_SLOT_FORMAT}.
 *
 * <p>Enforcing that shape is frame integrity for the same reason the width is. Cards 11 and 12
 * place the slot <em>inside</em> a character constant that the sort step parses, opened by the
 * leading literal and closed by the apostrophe in the trailing field, and card 15 places it in a
 * position-significant in-stream parameter record. A slot of the right width but the wrong shape
 * therefore still breaks the frame: an apostrophe inside the slot closes the constant early and
 * turns the remainder of the card into something the sort step reads as further specification, a
 * comma introduces a fresh operand, and a carriage return, line feed, tab, null or other control
 * byte splits or truncates a record that the queue definition declares as fixed and unblocked.
 * None of those values can arise from a legacy screen field, so admitting them would let a caller
 * inject job-control and sort-control text into a contract this class exists to hold invariant.
 *
 * <p>The shape check itself is <strong>structural only</strong>: it asks where digits and hyphens sit
 * and nothing else. On its own that admits {@code 9999-99-99}, {@code 2022-13-01} and
 * {@code 2023-02-29}, each of which would be embedded in the sort include-condition on cards 11 and 12
 * and in the report parameter on card 15 and would produce a job whose date window names no real
 * interval. A second check therefore follows it, {@link #requireRealCalendarDay(String, String)}, which
 * resolves the slot strictly and refuses it when it names no day that exists. That check does not
 * compare the two dates with each other and does not carry any of the screen's field-level messages;
 * ordering and messaging remain entirely with the date-validation service, exactly as before.
 *
 * <h2>There is no report-name substitution slot</h2>
 *
 * <p>A ten-byte report-name work field exists in the program at
 * {@code [app/cbl/CORPT00C.cbl:L58]}, but it is used only to compose screen messages. No card
 * contains a report-name placeholder, and the job name on card 1 is a fixed literal. The job name
 * is therefore never derived, never parameterised and never templatised.
 *
 * <p><strong>Card details that are easy to "correct" by mistake.</strong> On card 1 the message class
 * is the digit <strong>zero</strong>, not the letter O, and the card ends with a
 * <strong>trailing comma</strong> that is a JCL continuation marker joining the job card to the
 * notify card, so it is content and is retained. Card 2 spells the system-user symbol correctly; a
 * different member of the estate carries a transposed spelling of that symbol, and that typo belongs
 * to the other member and must never be imported here.
 *
 * <p>This is the single most likely defect in a naive translation, so the submission loop at
 * {@code [app/cbl/CORPT00C.cbl:L496-L508]} was traced statement by statement. The driver clears
 * its end-of-loop flag, then enters a subscript-varying loop whose terminating
 * condition is evaluated at the <em>top</em> of each iteration. Inside the body the current card
 * is moved to the write buffer and, when that card is the sentinel, the terminating flag is set.
 * The queue write is then performed at {@code [app/cbl/CORPT00C.cbl:L507]}, which is
 * <em>after</em> the flag has been set and still inside the same iteration. Because the loop tests
 * before it iterates rather than after, the flag set during the seventeenth iteration cannot
 * suppress the write that follows it in that same iteration.
 *
 * <p><strong>The onward queue contract, implemented elsewhere.</strong> The target queue is defined at
 * {@code [app/csd/CARDDEMO.CSD]} as an extra-partition, output-only, initially-opened queue with four
 * attributes that bind the transport, all four implemented by
 * {@code com.carddemo.service.JobSubmissionService} and never here: a fixed record size of eighty
 * becomes an eighty-character fixed-width payload per message, so exactly one card per message; a
 * fixed record format becomes the invariant that no message is trimmed, wrapped or
 * newline-terminated; a modify disposition becomes append semantics, one message per card in the
 * order this builder returns them, preserved by message-group ordering; and an ignore error option
 * becomes a non-blocking publish whose failure path logs and continues rather than aborting the
 * caller (decision D-36). Accordingly this class has no queue client, no messaging or cloud
 * dependency, no publish method, no retry, no failure-message text, no confirmation gate and no
 * reporting-period logic. It receives two dates and asks no questions.
 *
 * <p><strong>Cross references.</strong> Cards 9 and 10 restate the sort-symbol specification that the
 * cataloged procedure declares at {@code [app/proc/TRANREPT.prc]}; cards 8 through 12 override that
 * procedure's symbol-names input and cards 14 and 15 override its date-parameter input, so the step
 * names on cards 8 and 14 must match the procedure's step names exactly. The same sixteen bytes at
 * one-based offset 263 are typed as zoned decimal here and as character data by the statement job at
 * {@code [app/jcl/CREASTMT.JCL]}, so the typing is per job, which is why the batch tier carries one
 * comparator per job rather than one shared comparator. The submitting job stream at
 * {@code [app/jcl/TRANREPT.jcl]} contains a duplicate step name, a recorded source anomaly; the card
 * images reference the step by name and are unaffected, and the target generates distinct step names.
 * The queue-write paragraph is spelled {@code WIRTE-JOBSUB-TDQ} at
 * {@code [app/cbl/CORPT00C.cbl:L515]}, a transposition of "write" carried as row 6 of the source
 * anomaly register; the Java naming is corrected wherever that behaviour is implemented and the
 * legacy spelling is cited here so the mapping back to the COBOL paragraph stays findable by search.
 *
 * <p><strong>Why there is no templating engine here.</strong> Byte-identical output requires the same
 * literals at the same offsets, and a templating engine or general-purpose format-string abstraction
 * introduces whitespace, ordering and locale variability that a byte-level comparison immediately
 * fails, while also making the eighty-column frame implicit rather than asserted (decision D-27).
 * Every card is assembled from an explicit literal plus explicit padding computed from declared
 * component widths, and every finished card is asserted at eighty encoded bytes so a mistake in
 * either the literal or the padding is caught rather than shipped. All widths are measured as encoded
 * bytes in {@link StandardCharsets#US_ASCII}, never as {@code char} counts, so a multi-byte character
 * cannot silently break the frame.
 *
 * <p>This class is stateless, pure and side-effect free. It performs no input or output, consults no
 * clock, environment or random source, holds no mutable state, logs nothing and is safe for
 * concurrent use.
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
     * The date-slot format literal, held in a {@code PIC X(10)} work field at
     * {@code [app/cbl/CORPT00C.cbl:L72]} and passed to the date-validation subprogram alongside
     * each assembled date. It records the shape every slot argument must take: four digits, a
     * hyphen, two digits, a hyphen, two digits, being {@value #DATE_SLOT_WIDTH} bytes in total.
     *
     * <p>The two hyphens are {@code FILLER} constants of the legacy group rather than data, so the
     * shape is fixed by the field declaration itself and is checked here as frame integrity. Shape is
     * all this literal records; whether the shaped value names a real day is the separate question
     * {@link #requireRealCalendarDay(String, String)} settles, and neither check reformats a date.
     */
    public static final String DATE_SLOT_FORMAT = "YYYY-MM-DD";

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

    /**
     * The separator character of the ten-column date slot, from the {@code YYYY-MM-DD} format
     * literal the legacy screen declares.
     *
     * <p>The same hyphen is carried by the legacy date group as a {@code FILLER} constant
     * between its numeric components at {@code [app/cbl/CORPT00C.cbl:L62]} and
     * {@code [app/cbl/CORPT00C.cbl:L64]}.</p>
     */
    private static final char DATE_SLOT_SEPARATOR = '-';

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

    /** Declared width of the year component of a date slot, from its {@code PIC X(04)} field. */
    private static final int DATE_SLOT_YEAR_WIDTH = 4;

    /** Declared width of the month component of a date slot, from its {@code PIC X(02)} field. */
    private static final int DATE_SLOT_MONTH_WIDTH = 2;

    /**
     * Declared width of each hyphen {@code FILLER} between the components of a date slot. The
     * field is a bare {@code PIC X(01)}, so it is exactly one byte.
     */
    private static final int DATE_SLOT_SEPARATOR_WIDTH = 1;

    /**
     * Zero-based offset of the first hyphen within a date slot, which is where the year component
     * ends. The day component's declared {@code PIC X(02)} width completes the arithmetic
     * {@code 4 + 1 + 2 + 1 + 2 = 10}, so the component widths account for the whole
     * {@value #DATE_SLOT_WIDTH}-byte slot with nothing unexplained.
     */
    private static final int DATE_SLOT_FIRST_SEPARATOR_OFFSET = DATE_SLOT_YEAR_WIDTH;

    /**
     * Zero-based offset of the second hyphen within a date slot, which is where the month
     * component ends.
     */
    private static final int DATE_SLOT_SECOND_SEPARATOR_OFFSET =
            DATE_SLOT_FIRST_SEPARATOR_OFFSET + DATE_SLOT_SEPARATOR_WIDTH + DATE_SLOT_MONTH_WIDTH;

    /** Lowest digit a numeric component of a date slot may hold. */
    private static final char LOWEST_DIGIT = '0';

    /** Highest digit a numeric component of a date slot may hold. */
    private static final char HIGHEST_DIGIT = '9';

    /** Offset-to-position adjustment, so a diagnostic names a one-based column of the slot. */
    private static final int FIRST_SLOT_POSITION = 1;

    /**
     * The one shape a date slot may take, written positionally: {@code N} marks a position that
     * must hold an ASCII digit and the two hyphens mark the two {@code FILLER} offsets. It is the
     * same shape {@link #DATE_SLOT_FORMAT} records in the legacy field's own notation, restated
     * here as an allowlist rather than as a date picture.
     *
     * <p>Published because it is part of the contract a caller must satisfy, and because it is the
     * boundary that keeps caller-supplied text out of the surrounding control language. Cards 11 and
     * 12 wrap the slot in a DFSORT character constant, {@code PARM-START-DATE,C'} … {@code '}, so a
     * single apostrophe inside the slot would close that constant early and the remainder of the
     * eighty-column card would be read by the sort utility as further control statements. An
     * allowlist of exactly ten positions, each restricted to one digit or one hyphen, forecloses that
     * by construction rather than by enumerating the characters that would be dangerous.
     */
    public static final String DATE_SLOT_PATTERN = "NNNN-NN-NN";

    /**
     * The strict formatter used to establish that a slot names a real day.
     *
     * <p>{@code uuuu} rather than {@code yyyy} because {@link ResolverStyle#STRICT} requires a
     * proleptic year: {@code yyyy} is the year-of-era and would demand an era field the slot does not
     * carry. Strict resolution is what makes {@code 2023-02-29} and {@code 9999-99-99} failures
     * rather than values that {@code SMART} resolution would quietly move to the nearest real day.
     * {@link DateTimeFormatter} is immutable and thread safe, so one instance is shared.
     *
     * <p>The parsed value is discarded. This formatter exists to reject, never to convert, so an
     * accepted slot reaches its card byte for byte as the caller supplied it.
     *
     * <p><strong>{@link Locale#ROOT} is supplied explicitly, and it is not decoration.</strong> The
     * single-argument factory resolves the formatting locale from ambient process state, so the
     * formatter a running application holds would depend on the host it was started on. That is
     * unacceptable in a fixed-column contract: the slot is ten US-ASCII bytes by construction
     * ({@value #DATE_SLOT_WIDTH} positions, each one digit or one hyphen per
     * {@value #DATE_SLOT_PATTERN}), and the decision this formatter makes about such a value must be
     * the same decision on every host, in every profile and under every locale the build is exercised
     * in - the continuous-integration definition deliberately re-runs the whole unit tier under two
     * hostile locales for exactly this reason. Naming the invariant locale makes the acceptance
     * decision a property of the value rather than of the environment, so no reader has to reason
     * about which locale characteristics happen not to influence the outcome today.
     */
    private static final DateTimeFormatter CALENDAR_DAY_FORMAT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

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
     * <p>Each argument is a raw ten-character date slot in the fixed {@value #DATE_SLOT_FORMAT}
     * shape, which {@value #DATE_SLOT_PATTERN} restates offset by offset as a positional allowlist.
     * Cards 11 and 12 embed the slot inside a DFSORT character constant and card 15 places it on a
     * {@code PARM} card, so the slot is caller-supplied text interpolated into a foreign control
     * language. It is therefore admitted only when it is representable in single-byte US-ASCII, is
     * exactly {@value #DATE_SLOT_WIDTH} encoded bytes, carries nothing but digits and the two
     * hyphens at their declared offsets, and names a day that actually exists; anything else is
     * refused rather than embedded. The accepted value is placed on the cards unchanged, byte for
     * byte, so nothing is reformatted and the eighty-column frame is preserved. Validation happens
     * before any card is composed, so a malformed slot never reaches a card image.
     *
     * @param startDate the ten-byte value for the {@code PARM-START-DATE-1} slot on card 11 and
     *                  the {@code PARM-START-DATE-2} slot on card 15; must not be {@code null}
     * @param endDate   the ten-byte value for the {@code PARM-END-DATE-1} slot on card 12 and the
     *                  {@code PARM-END-DATE-2} slot on card 15; must not be {@code null}
     * @return an unmodifiable, ordered list of exactly {@value #CARD_COUNT} card images, each
     *         exactly {@value #CARD_IMAGE_WIDTH} encoded bytes
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if either argument is not exactly
     *                                  {@value #DATE_SLOT_WIDTH} encoded bytes, contains a
     *                                  character that is not representable as a single US-ASCII
     *                                  byte, does not take the {@value #DATE_SLOT_FORMAT} shape of
     *                                  eight digits separated by hyphens at the fifth and eighth
     *                                  positions, or does not name a day that exists
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
     *                                  {@value #DATE_SLOT_WIDTH} encoded bytes, contains a
     *                                  character that is not representable as a single US-ASCII
     *                                  byte, does not take the {@value #DATE_SLOT_FORMAT} shape of
     *                                  eight digits separated by hyphens at the fifth and eighth
     *                                  positions, or does not name a day that exists
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
     * Validates one date substitution slot for frame integrity and for control-language safety.
     *
     * <p>Four properties are required, and they are checked in this order so that each failure is
     * reported against the narrowest cause.
     *
     * <ol>
     *   <li><strong>Single-byte representable.</strong> A multi-byte character would occupy more
     *       columns than it appears to and would be published as a substitution byte rather than as
     *       the caller's byte.</li>
     *   <li><strong>Exactly {@value #DATE_SLOT_WIDTH} encoded bytes.</strong> A wider value would
     *       overflow the card; a narrower one would shift the closing apostrophe on cards 11 and
     *       12.</li>
     *   <li><strong>The fixed {@value #DATE_SLOT_FORMAT} shape, stated positionally as
     *       {@value #DATE_SLOT_PATTERN}.</strong> A positive allowlist: each of the ten positions
     *       must hold an ASCII digit, except the fifth and the eighth which must hold the hyphen the
     *       legacy group carries as a {@code FILLER} constant. Nothing else is admitted.</li>
     *   <li><strong>A real calendar day.</strong> Resolved strictly, so an impossible day is refused
     *       rather than silently moved to a neighbouring one.</li>
     * </ol>
     *
     * <p>The first two properties hold the card at {@value #CARD_IMAGE_WIDTH} columns. The third
     * holds the <em>content</em> of the frame, and it is load bearing rather than cosmetic: cards 11
     * and 12 carry the slot inside a character constant that the sort step parses, and card 15
     * carries it in a position-significant in-stream parameter record. Width alone does not make that
     * safe. A right-width, right-encoding value such as {@code 2026-01-1} followed by an apostrophe
     * closes the character constant eight bytes early and hands the remaining columns to the sort
     * utility as further specification; a comma introduces a fresh operand; and a carriage return,
     * line feed, tab, null, escape, delete or any other control byte splits or truncates a record the
     * queue definition declares fixed and unblocked. Enumerating those characters as a denylist would
     * be an invitation to miss one, so the guard admits only the ten positions the contract actually
     * needs and refuses everything else, which makes the whole class of control-language injection
     * unreachable rather than merely unlikely.
     *
     * <p>The legacy field could hold none of those characters, because its two hyphens are
     * {@code FILLER} constants and only its three numeric components are moved into, from screen
     * fields the terminal restricts to numeric entry {@code [app/cbl/CORPT00C.cbl:L60-L71]}.
     * Requiring digits in the eight numeric positions and a hyphen in the fifth and eighth is
     * therefore reproducing the legacy field's own structure, not adding a new restriction.
     *
     * <p>The fourth property is why a shape check alone is not sufficient. A value such as
     * {@code 9999-99-99} passes the allowlist, cannot inject anything, and is still wrong: it would
     * be embedded in the sort include-condition and in the report parameter, producing a job whose
     * date window is meaningless. The legacy screen validated the calendar upstream through
     * {@code CSUTLDTC} before it ever built a card, so checking it here reproduces the legacy
     * pipeline's guarantee at the point where it can no longer be bypassed. Range ordering of the two
     * dates and the multi-paragraph date-edit cascade both stay with the date-validation service. The
     * value is validated and returned unchanged; it is never parsed for its value, reformatted or
     * normalised, so the ten bytes that reach the card are byte-identical to the ten the caller
     * supplied.
     *
     * <p>This guard is a deliberate divergence from the legacy assembly behaviour. A COBOL move into
     * a ten-byte field pads or truncates silently, so the legacy program had no equivalent check at
     * the point of assembly and relied on the screen field being exactly ten characters wide and
     * numerically shifted. Reproducing a silent corruption would defeat the byte-level contract, so a
     * malformed slot raises here instead. Nothing is ever silently padded, truncated, escaped, quoted
     * or sanitised, no malformed card is emitted, and no {@code null} is returned. The failure is an
     * unchecked argument failure rather than a domain exception, because a caller handing over bytes
     * the frame cannot hold is a frame-integrity violation and not a business outcome.
     *
     * <p>The diagnostics name the slot, the expected width or shape and the offending one-based
     * position, and they never echo the rejected value or the character found there, so a rejected
     * slot cannot carry its own text onward into a log record or a message.
     *
     * @param slotValue the caller-supplied slot value
     * @param slotName  the legacy sort-symbol name of the slot, used in the failure message
     * @return the same value, byte for byte, once verified
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

        requireDateSlotShape(slotValue, slotName);
        requireRealCalendarDay(slotValue, slotName);
        return slotValue;
    }

    /**
     * Requires that an already width-checked slot takes the fixed {@value #DATE_SLOT_FORMAT} shape,
     * stated positionally as {@value #DATE_SLOT_PATTERN}.
     *
     * <p>The walk is driven by the declared component widths of the legacy group rather than by a
     * pattern object, so the two hyphen positions in the check are the two {@code FILLER} offsets of
     * that group and the remaining eight positions are its three numeric components. Every position
     * is examined; none is sampled. That is why no character outside the digits and the two hyphens
     * can pass: the apostrophe that would close a sort character constant early, the comma that would
     * introduce a fresh operand, and every control byte and the delete byte are all rejected here
     * without being enumerated, because none of them is a digit and none sits at a hyphen offset.
     *
     * <p>The failure message names the slot, the required shape and the offending one-based position,
     * and deliberately does <em>not</em> echo the offending character, because a rejected value may
     * be reflected back to a caller or written to a log and an echoed apostrophe or control byte would
     * carry the same problem into that channel. For the same reason the message describes the
     * separator by name rather than quoting it: quoting anything here, even this class's own literal,
     * would put an apostrophe into a diagnostic and turn the guarantee &mdash; that no rejection
     * message this class raises can carry an injectable character &mdash; into a matter of reading
     * each message rather than a structural property a test can assert once and for all.
     *
     * @param slotValue the slot value, already known to be {@value #DATE_SLOT_WIDTH} single-byte
     *                  US-ASCII characters
     * @param slotName  the legacy sort-symbol name of the slot, used in the failure message
     * @throws IllegalArgumentException if any position holds a character the shape does not permit
     */
    private static void requireDateSlotShape(final String slotValue, final String slotName) {
        for (int offset = 0; offset < slotValue.length(); offset++) {
            final char character = slotValue.charAt(offset);
            final int position = offset + FIRST_SLOT_POSITION;
            if (offset == DATE_SLOT_FIRST_SEPARATOR_OFFSET
                    || offset == DATE_SLOT_SECOND_SEPARATOR_OFFSET) {
                if (character != DATE_SLOT_SEPARATOR) {
                    throw new IllegalArgumentException("date slot " + slotName + " must take the "
                            + DATE_SLOT_FORMAT + " shape the legacy work field fixes with its"
                            + " hyphen FILLER constants, so position " + position + " must be an"
                            + " ASCII hyphen-minus separator; the rejected value is not reproduced"
                            + " here");
                }
            } else if (character < LOWEST_DIGIT || character > HIGHEST_DIGIT) {
                throw new IllegalArgumentException("date slot " + slotName + " must take the "
                        + DATE_SLOT_FORMAT + " shape the legacy work field fixes with its hyphen"
                        + " FILLER constants, so position " + position + " must be a digit;"
                        + " the rejected value is not reproduced here");
            }
        }
    }

    /**
     * Requires that an already shape-checked slot names a day that actually exists.
     *
     * <p>The shape allowlist cannot see the calendar: {@code 9999-99-99}, {@code 2022-13-01} and
     * {@code 2023-02-29} all satisfy it. Each would nonetheless be embedded in the sort
     * include-condition on cards 11 and 12 and in the report parameter on card 15, producing a job
     * whose date window names no real interval. Strict resolution is what turns those into failures
     * rather than into values a lenient resolver would quietly move to a neighbouring real day.
     *
     * <p>The parsed value is discarded. This check exists to reject, never to convert, so an accepted
     * slot reaches its card byte for byte as the caller supplied it. The message names the slot and
     * the required shape and never echoes the rejected value.
     *
     * @param slotValue the slot value, already known to be shaped {@value #DATE_SLOT_PATTERN}
     * @param slotName  the legacy sort-symbol name of the slot, used in the failure message
     * @throws IllegalArgumentException if the value does not name a day that exists
     */
    private static void requireRealCalendarDay(final String slotValue, final String slotName) {
        try {
            LocalDate.parse(slotValue, CALENDAR_DAY_FORMAT);
        } catch (DateTimeParseException notARealDay) {
            throw new IllegalArgumentException("date slot " + slotName + " takes the "
                    + DATE_SLOT_FORMAT + " shape but does not name a day that exists; the report"
                    + " window and the sort include-condition are both built from it, so an"
                    + " impossible day is refused rather than embedded; the rejected value is not"
                    + " reproduced here", notARealDay);
        }
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
