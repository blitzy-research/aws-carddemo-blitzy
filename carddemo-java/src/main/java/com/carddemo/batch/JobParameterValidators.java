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
package com.carddemo.batch;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.stereotype.Component;

import com.carddemo.exception.ValidationException;
import com.carddemo.service.BatchJobCatalog;
import com.carddemo.service.DateValidationService;

/**
 * The single supplier of every job-parameter validator the CardDemo batch tier needs, holding the four
 * launch-time parameter contracts the legacy job streams actually carried.
 *
 * <p>This component is the foundational member of its package: nothing here depends on a job
 * configuration, and the job configurations depend on this. Keeping the four contracts in one place is
 * what stops nine job configurations from each inventing their own spelling of a parameter key, their own
 * width check and their own idea of whether a range bound is inclusive.</p>
 *
 * <h2>The four measured contracts</h2>
 *
 * <ol>
 *   <li><strong>The interest-calculation parameter.</strong> The interest program declares a linkage
 *       group of a signed four-digit binary length field followed by a ten-character date field, at
 *       {@code [app/cbl/CBACT04C.cbl:L176]} through {@code [app/cbl/CBACT04C.cbl:L178]}, and receives
 *       that group into its procedure division at {@code [app/cbl/CBACT04C.cbl:L180]}. The job stream
 *       supplies it as a ten-character all-digit literal with <em>no separators</em> at
 *       {@code [app/jcl/INTCALC.jcl:L22]}. See {@link #interestParmDateValidator()}.</li>
 *   <li><strong>The transaction-report date range.</strong> Two sort-symbol character constants, each
 *       exactly ten characters in hyphenated ISO form, declared at
 *       {@code [app/jcl/TRANREPT.jcl:L43]} and {@code [app/jcl/TRANREPT.jcl:L44]} and identically at
 *       {@code [app/proc/TRANREPT.prc:L41]} and {@code [app/proc/TRANREPT.prc:L42]}. Both bounds are
 *       inclusive. See {@link #reportDateRangeValidator()}.</li>
 *   <li><strong>The date-parameter record.</strong> Twenty-one significant bytes carried inside an
 *       eighty-byte record image, declared at {@code [app/cbl/CBTRN03C.cbl:L122]} through
 *       {@code [app/cbl/CBTRN03C.cbl:L125]} over the record area at
 *       {@code [app/cbl/CBTRN03C.cbl:L88]}. See {@link #parseDateParmRecord(String)}.</li>
 *   <li><strong>The file-probe mode.</strong> A Java-side parameterisation with no legacy antecedent
 *       value: one job replaces four separate sequential-read verification job streams. See
 *       {@link #fileProbeModeValidator(Collection)}.</li>
 * </ol>
 *
 * <h2>Two data paths, one date window</h2>
 *
 * <p>The report date window reaches the legacy estate by two different routes, and the distinction is
 * measured rather than assumed. The sort step receives the window as <em>sort symbols</em> — the two
 * character constants cited above, consumed by the range filter at
 * {@code [app/jcl/TRANREPT.jcl:L47]} and {@code [app/jcl/TRANREPT.jcl:L48]}. The report program does
 * <em>not</em> receive a parameter at all: it declares a sequential file assigned to the date-parameter
 * data definition at {@code [app/cbl/CBTRN03C.cbl:L55]} through {@code [app/cbl/CBTRN03C.cbl:L57]},
 * pointed at a catalogued dataset with shared disposition at {@code [app/jcl/TRANREPT.jcl:L73]}, and
 * <em>reads</em> its window from that file. Two routes, one window, and therefore one validation
 * cascade shared by {@link #reportDateRangeValidator()} and {@link #parseDateParmRecord(String)}.</p>
 *
 * <h2>Why the two date formats are not interchangeable</h2>
 *
 * <p>The interest parameter is ten digits with no separators; the report window is ten characters with
 * hyphens. Neither may be reformatted into the other, for two independent reasons.</p>
 *
 * <p>The interest parameter's ten characters are reused verbatim as the literal prefix of the
 * sixteen-character synthesized interest-transaction identifier — ten characters of parameter date
 * followed by the six-digit incrementing suffix declared at {@code [app/cbl/CBACT04C.cbl:L173]}.
 * Reformatting the parameter would silently change every generated transaction identifier.</p>
 *
 * <p>The report window's hyphens are load bearing because the legacy filter is a <em>character</em>
 * comparison, not a date comparison. The sort declares the record's processing-date field as a
 * character field at {@code [app/jcl/TRANREPT.jcl:L42]} and filters it with greater-or-equal against
 * the start value and less-or-equal against the end value; the report program applies the same
 * inclusive character comparison to the leading positions of the processing timestamp at
 * {@code [app/cbl/CBTRN03C.cbl:L173]} and {@code [app/cbl/CBTRN03C.cbl:L174]}. Character ordering of
 * hyphenated ISO dates coincides with chronological ordering, which is precisely why the format has
 * hyphens, and it is why the ordering test in this class is a character comparison too.</p>
 *
 * <h2>Every width is a byte width</h2>
 *
 * <p>Each width this class enforces comes from a picture clause that reserves <em>bytes</em>, so every
 * width assertion is made on the value's {@link StandardCharsets#US_ASCII} encoded image and never on a
 * character count. Representability is gated before the encode, in that order, because encoding first
 * would substitute a replacement byte and leave a value of the correct width holding the wrong content.
 * That ordering also guarantees that the delegated calendar validation can never raise an
 * {@code IllegalArgumentException} back through a validator, since the delegate rejects
 * non-representable input the same way.</p>
 *
 * <h2>Why the width is checked here and not left to the delegate</h2>
 *
 * <p>{@link DateValidationService} moves its input into a fixed-width linkage field, and a
 * fixed-width alphanumeric move discards the <em>rightmost</em> excess of an over-long sender. An
 * eleven-character value delegated without a prior width check would therefore arrive as its own first
 * ten characters and be accepted. Every cascade in this class asserts the exact encoded width before it
 * delegates anything.</p>
 *
 * <h2>Strict calendar resolution is delegated, never re-implemented</h2>
 *
 * <p>No calendar arithmetic and no date parser lives in this class. Every calendar decision is taken by
 * the injected {@link DateValidationService}, which reproduces the legacy date-edit cascade and resolves
 * strictly, so an impossible calendar date such as a thirtieth of February is rejected rather than
 * normalised onto a nearby valid date. Acceptance is decided by that service's own two-level test rather
 * than by a rule invented here, so this class inherits the legacy acceptance policy exactly, including
 * its one tolerated feedback condition.</p>
 *
 * <h2>Which exception a failure raises</h2>
 *
 * <p>The framework contract is narrow: a validator reports a bad parameter set with a
 * {@link JobParametersInvalidException}, whose only constructor takes a message. Because that exception
 * cannot carry a cause, the shared cascades below <em>return</em> a completed diagnostic rather than
 * throwing one, and each public entry point raises the exception its own contract requires. Nothing in
 * this class uses an exception as control flow. Failures raised outside the validator contract — that
 * is, from {@link #parseDateParmRecord(String)}, which parses a record rather than validating a
 * parameter set — raise {@link ValidationException} instead.</p>
 *
 * <h2>What a diagnostic is allowed to say about a value</h2>
 *
 * <p>Every diagnostic names the offending parameter, and names the offending value too whenever that
 * value is renderable, because a batch failure is read from a log after the fact and a message that
 * omits both is not actionable. Renderability is the limit. A value carrying any character outside
 * printable US-ASCII is replaced, in every message and every log record this class produces, by a
 * substitute that names the offending character's zero-based position and code point and never the
 * character itself.</p>
 *
 * <p>That is a correctness requirement rather than a stylistic preference. Parameter substitution
 * escapes nothing, so a launch value carrying a carriage return or a line feed would otherwise forge
 * additional log records, and a log record is a sink a human or a tool later reads exactly as a
 * rejection message is. The substitution is therefore applied at a single point,
 * {@link #describeValue(String)}, through which every value this class writes into a message or a log
 * record passes — including the values it writes on the <em>accepting</em> path — so that adding a
 * diagnostic here later cannot reopen the hole. The one value bound from configuration rather than from
 * a launch, the probe-mode enumeration, is refused outright at the boundary where it is supplied
 * instead, because a configuration mistake is better reported where it is made.</p>
 *
 * <p>None of the four parameters is a credential, so the substitution protects the integrity of the log
 * rather than the confidentiality of the value.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Immutable and stateless. The single field is final, every returned validator is either a method
 * reference or a lambda closing over an unmodifiable copy of its caller's input, and no validation state
 * outlives a call. Safe to register as a singleton and to share across concurrent job launches.</p>
 *
 * <h2>Source anomalies observed, recorded and deliberately not propagated</h2>
 *
 * <ul>
 *   <li>The report procedure declares its internal procedure label at
 *       {@code [app/proc/TRANREPT.prc:L1]} as {@code REPROC}, which is also the label declared by the
 *       separate {@code REPROC} member of the same procedure library: two members of one library
 *       claiming one name. The member name is what resolves, so the internal label is dead. Recorded
 *       here for the migration decision log; nothing in this class depends on either label.</li>
 *   <li>The report job stream names two <em>different</em> steps identically, at
 *       {@code [app/jcl/TRANREPT.jcl:L23]} and {@code [app/jcl/TRANREPT.jcl:L37]}, whereas the
 *       procedure form of the same pipeline uses three distinct names at
 *       {@code [app/proc/TRANREPT.prc:L21]}, {@code [app/proc/TRANREPT.prc:L35]} and
 *       {@code [app/proc/TRANREPT.prc:L57]}. The duplicate is a source defect. The report job
 *       configuration must generate distinct step names; the defect is recorded here because this class
 *       owns the parameter contract that both forms share.</li>
 *   <li>The statement job stream carries no parameter on any of its five steps — verified across
 *       {@code [app/jcl/CREASTMT.JCL:L22]}, {@code [app/jcl/CREASTMT.JCL:L44]},
 *       {@code [app/jcl/CREASTMT.JCL:L56]}, {@code [app/jcl/CREASTMT.JCL:L66]} and
 *       {@code [app/jcl/CREASTMT.JCL:L79]}. Its contribution to this class is contextual only, and this
 *       class deliberately declares <strong>no</strong> date parameter for the statement job, because
 *       inventing one would add a launch-time contract the legacy pipeline never had.</li>
 * </ul>
 *
 * <h2>Provenance</h2>
 *
 * <p>The legacy antecedents of this class are the linkage section of {@code app/cbl/CBACT04C.cbl},
 * the parameter literal of {@code app/jcl/INTCALC.jcl}, the sort symbols and date-parameter data
 * definitions of {@code app/jcl/TRANREPT.jcl} and {@code app/proc/TRANREPT.prc}, the date-parameter
 * file of {@code app/cbl/CBTRN03C.cbl}, and — for the negative finding above — {@code
 * app/jcl/CREASTMT.JCL}. No COBOL, JCL, cataloged-procedure or utility control text is transcribed
 * here: the legacy source is cited by member, field, data-definition name and line number, and its
 * statements are described rather than quoted. Traceability is by citation only, and nothing in
 * this class reads the legacy tree at runtime.</p>
 */
@Component
public final class JobParameterValidators {

    /**
     * Logger for this component. The legacy diagnostic channel was the console display verb; every
     * diagnostic here goes through the logging facade instead.
     */
    private static final Logger LOG = LoggerFactory.getLogger(JobParameterValidators.class);

    // =================================================================================================
    // The launch surface. These key names are shared with the job configurations of this package and
    // with the batch launch endpoint, so that one spelling exists in one place. They are part of the
    // published launch contract and are therefore stable.
    // =================================================================================================

    /**
     * Key of the interest-calculation parameter date: the ten-character all-digit value the interest job
     * stream passed as a program parameter at {@code [app/jcl/INTCALC.jcl:L22]}.
     */
    public static final String INTEREST_PARM_DATE_KEY =
            BatchJobCatalog.INTEREST_PARM_DATE_PARAMETER;

    /**
     * Key of the inclusive lower bound of the transaction-report window, corresponding to the sort
     * symbol {@code PARM-START-DATE} at {@code [app/jcl/TRANREPT.jcl:L43]}.
     */
    public static final String REPORT_START_DATE_KEY = BatchJobCatalog.REPORT_START_DATE_PARAMETER;

    /**
     * Key of the inclusive upper bound of the transaction-report window, corresponding to the sort
     * symbol {@code PARM-END-DATE} at {@code [app/jcl/TRANREPT.jcl:L44]}.
     */
    public static final String REPORT_END_DATE_KEY = BatchJobCatalog.REPORT_END_DATE_PARAMETER;

    /**
     * Key of the file-probe mode, which selects which of the collapsed sequential-read verification
     * jobs a launch is asking for.
     */
    public static final String FILE_PROBE_MODE_KEY = BatchJobCatalog.FILE_PROBE_MODE_PARAMETER;

    // =================================================================================================
    // Measured widths and offsets. Every figure below was read from a picture clause or a sort symbol
    // declaration; none is assumed, and none is a tuning figure.
    // =================================================================================================

    /**
     * Width of the interest parameter's date field {@code PARM-DATE}, declared in
     * {@code [app/cbl/CBACT04C.cbl]} and matching the exact length of the literal supplied at
     * {@code [app/jcl/INTCALC.jcl]}.
     */
    private static final int INTEREST_PARM_DATE_WIDTH = 10;

    /**
     * How many of the interest parameter's ten positions carry the calendar date. The supplied literal
     * is an eight-digit calendar date followed by two further digits, so the calendar portion is the
     * leading eight positions and the trailing two are not part of it.
     */
    private static final int INTEREST_PARM_CALENDAR_WIDTH = 8;

    /**
     * Width of a hyphenated ISO date. It is the declared length of both report sort symbols, and also
     * the declared length of the record's processing-date sort field at
     * {@code [app/jcl/TRANREPT.jcl:L42]} and of the two date fields of the date-parameter record at
     * {@code [app/cbl/CBTRN03C.cbl:L123]} and {@code [app/cbl/CBTRN03C.cbl:L125]}.
     */
    private static final int ISO_DATE_WIDTH = 10;

    /**
     * Significant width of the date-parameter record: ten characters, one separator, ten characters,
     * from {@code [app/cbl/CBTRN03C.cbl:L122]} through {@code [app/cbl/CBTRN03C.cbl:L125]}. The record
     * <em>image</em> is wider — an eighty-byte area at {@code [app/cbl/CBTRN03C.cbl:L88]} — but only
     * these positions carry content.
     *
     * <p>This is therefore the width of the <em>receiving group</em> of the legacy read, which is what
     * makes it both the minimum a supplied record must measure and the exact extent that is read: the
     * move fills the group from the leading bytes of the record area and discards the rest.</p>
     */
    private static final int DATEPARM_SIGNIFICANT_WIDTH = 21;

    /**
     * Zero-based index of the date-parameter record's separator, which is the single-character filler
     * declared between the two date fields at {@code [app/cbl/CBTRN03C.cbl:L124]}.
     */
    private static final int DATEPARM_SEPARATOR_INDEX = 10;

    /**
     * Zero-based index at which the date-parameter record's end date begins, immediately after the
     * single-character separator.
     */
    private static final int DATEPARM_END_DATE_INDEX = 11;

    // =================================================================================================
    // Format masks. Both are values the date-validation contract already recognises, so neither is a
    // new mask invented here; they are passed as text because that is how the legacy callers held them.
    // =================================================================================================

    /**
     * The compact mask, selecting an eight-position calendar interpretation. Passing it alongside the
     * full ten-character parameter reproduces the legacy arrangement exactly: the linkage date field is
     * ten characters wide and the mask governs how many of those positions are interpreted, so no
     * slicing of the parameter is needed or performed.
     */
    private static final String COMPACT_DATE_MASK = "YYYYMMDD";

    /**
     * The hyphenated mask, selecting a ten-position calendar interpretation. It is the value both
     * legacy online callers held in their ten-character format work fields, and it is the form the
     * report window is declared in.
     */
    private static final String ISO_DATE_MASK = "YYYY-MM-DD";

    // =================================================================================================
    // Character constants.
    // =================================================================================================

    /** The separator the date-parameter record carries between its two date fields. */
    private static final char DATEPARM_SEPARATOR = ' ';

    /**
     * Highest code unit the single-byte character set of the legacy fields can represent.
     *
     * <p>Every width this class enforces is a byte reservation, so a value carrying a code unit above
     * this bound cannot occupy the byte count its field reserves: one such character encodes to more
     * than one byte. Such a value is rejected before it is encoded, because encoding first would
     * substitute a replacement byte and leave a value of the right width holding the wrong content.</p>
     */
    private static final char MAX_SINGLE_BYTE_CHARACTER = 0x7F;

    /**
     * Lowest character this class will place inside a diagnostic: the ASCII space.
     *
     * <p>Everything below it is a C0 control code, and every one of those is a log-forging primitive
     * when it reaches a record this class composes. A line feed ends the record, so one rejected value
     * becomes two apparent log entries and an operator reading the batch log cannot tell which of them
     * the job actually emitted. A carriage return returns the cursor so the remainder of the record
     * overwrites what preceded it. An escape reaching a terminal-backed viewer can reposition the
     * cursor and overwrite records already written, forging history without emitting a terminator at
     * all. A NUL truncates the record for any consumer that reads a C string.</p>
     *
     * <p>The bound is deliberately the same one {@link com.carddemo.util.FixedWidthFieldReader} and
     * {@code JobSubmissionService} apply to the fragments they do not author, so that "printable" has
     * one definition across the module rather than one per class.</p>
     */
    private static final char FIRST_PRINTABLE_US_ASCII = 0x20;

    /**
     * Highest character this class will place inside a diagnostic: the tilde.
     *
     * <p>{@link #MAX_SINGLE_BYTE_CHARACTER} is retained separately because it answers a different
     * question - whether a character can occupy the byte count a legacy field reserves - and the two
     * limits are reported through different diagnostics. The single code point between this bound and
     * that one is the delete control, which is refused here for the same reason as the C0 range.</p>
     */
    private static final char LAST_PRINTABLE_US_ASCII = 0x7E;

    /**
     * What a diagnostic says in place of a value that was never supplied.
     *
     * <p>Rendered in parentheses rather than in the brackets a reproduced value gets, so that a reader
     * can tell an absent value from a value that was supplied and happened to be empty without the
     * message having to say which it was. Only {@link #describeValue(String)} uses it: the message
     * composer renders an absent fragment as the literal {@code null} instead, because a message that
     * names a parameter and then says {@code (absent)} where the value should be reads as though the
     * parameter itself were optional.</p>
     */
    private static final String ABSENT_VALUE_SUBSTITUTE = "(absent)";

    /** Lowest and highest characters a legacy numeric-picture position accepts. */
    private static final char FIRST_DIGIT = '0';

    /** Companion of {@link #FIRST_DIGIT}. */
    private static final char LAST_DIGIT = '9';

    // =================================================================================================
    // Field labels used in the diagnostics of the record parser, whose failures are not attributable to
    // a job parameter key. They name the record and its two date positions.
    // =================================================================================================

    /** Label of the date-parameter record as a whole. */
    private static final String DATEPARM_RECORD_LABEL = "DATEPARM record";

    /** Label of the date-parameter record's start-date position. */
    private static final String DATEPARM_START_DATE_LABEL = "DATEPARM record start date";

    /** Label of the date-parameter record's end-date position. */
    private static final String DATEPARM_END_DATE_LABEL = "DATEPARM record end date";

    /**
     * The strict calendar authority. Every calendar decision this class reaches is taken by this
     * collaborator; nothing here parses or arithmetically manipulates a date.
     */
    private final DateValidationService dateValidationService;

    /**
     * Creates the validator supplier.
     *
     * <p>Constructor injection only, so the collaborator is mandatory and the instance is fully formed
     * and immutable once constructed.</p>
     *
     * @param dateValidationService the strict calendar authority; must not be {@code null}
     * @throws NullPointerException if {@code dateValidationService} is {@code null}
     */
    public JobParameterValidators(final DateValidationService dateValidationService) {
        this.dateValidationService = Objects.requireNonNull(dateValidationService,
                "dateValidationService must not be null: every calendar decision is delegated to it and"
                        + " none is taken here");
    }

    // =================================================================================================
    // Public accessors, one per contract. A job configuration attaches the returned instance through the
    // job builder's validator hook.
    // =================================================================================================

    /**
     * Returns the validator for the interest-calculation parameter date.
     *
     * <p>The returned validator requires {@link #INTEREST_PARM_DATE_KEY} and accepts it only when it is
     * exactly ten encoded bytes, every one of those ten is a digit, and its leading eight positions form
     * a strictly valid calendar date. The measured literal the job stream supplied at
     * {@code [app/jcl/INTCALC.jcl:L22]} is an eight-digit calendar date followed by two further digits
     * filling the remaining positions of the ten-character linkage date field declared at
     * {@code [app/cbl/CBACT04C.cbl:L178]}; it carries no separators and is <strong>not</strong> a
     * hyphenated ISO date.</p>
     *
     * <p><strong>The accepted value must be consumed verbatim and must never be reformatted.</strong>
     * Its ten characters are reused as the literal prefix of the sixteen-character synthesized
     * interest-transaction identifier: ten characters of parameter date followed by the six-digit
     * incrementing suffix declared at {@code [app/cbl/CBACT04C.cbl:L173]}. Normalising the parameter into
     * any other date form — inserting separators, stripping the trailing two positions, or round-tripping
     * it through a date type — would silently change every transaction identifier the interest run
     * generates, and would do so without failing any check. The validator therefore neither rewrites nor
     * returns a transformed value: it accepts or it rejects, and the job configuration reads the original
     * parameter back out under {@link #INTEREST_PARM_DATE_KEY}.</p>
     *
     * <p>The length field that precedes the date in the linkage group, declared at
     * {@code [app/cbl/CBACT04C.cbl:L177]}, is a signed four-digit binary field. Its range comfortably
     * exceeds the ten characters it describes, so the only enforceable part of its contract is that the
     * supplied value occupies exactly those ten positions. No numeric range check on the length itself is
     * applied, because the source imposes none and inventing one would reject nothing the source
     * rejects.</p>
     *
     * @return a stateless validator, never {@code null}
     */
    public JobParametersValidator interestParmDateValidator() {
        return this::validateInterestParmDate;
    }

    /**
     * Returns the validator for the transaction-report date range.
     *
     * <p>The returned validator requires both {@link #REPORT_START_DATE_KEY} and
     * {@link #REPORT_END_DATE_KEY}, accepts each only as exactly ten encoded bytes in hyphenated ISO
     * form denoting a strictly valid calendar date, and then requires the start not to follow the end.</p>
     *
     * <p><strong>Both bounds are inclusive.</strong> That is measured, not chosen: the sort filter tests
     * the record's processing-date field with greater-or-equal against the start value and less-or-equal
     * against the end value at {@code [app/jcl/TRANREPT.jcl:L47]} and {@code [app/jcl/TRANREPT.jcl:L48]},
     * and the report program applies the same pair of inclusive comparisons at
     * {@code [app/cbl/CBTRN03C.cbl:L173]} and {@code [app/cbl/CBTRN03C.cbl:L174]}. A window whose start
     * equals its end therefore selects that one day rather than nothing, and the report job must not
     * drift to an exclusive bound. {@link ReportDateWindow#includes(String)} encodes the inclusive test
     * once so that no caller has to restate it.</p>
     *
     * <p>The ordering test is a <em>character</em> comparison, matching the legacy exactly. The sort
     * declares the processing-date field as a character field at {@code [app/jcl/TRANREPT.jcl:L42]}, so
     * the legacy filter never converts anything to a date; character ordering of hyphenated ISO dates
     * coincides with chronological ordering, which is why the format carries hyphens.</p>
     *
     * @return a stateless validator, never {@code null}
     */
    public JobParametersValidator reportDateRangeValidator() {
        return this::validateReportDateRange;
    }

    /**
     * Returns the validator for the file-probe mode, rejecting any value outside the legal set the caller
     * supplies.
     *
     * <p>The probe job replaces four separate legacy job streams, each of which read one sequential file
     * to verify it, with a single parameterised job. The mode value therefore has no legacy antecedent
     * literal to reproduce — the legacy distinction was the identity of the job stream, not the content
     * of a parameter.</p>
     *
     * <p>The enumeration of legal modes belongs to the probe job configuration, which owns that concept,
     * so it is <strong>supplied to this method rather than declared here</strong>. This class validates
     * against the owner's enumeration and defines nothing of its own, which keeps the single definition
     * of "which files can be probed" in the single place that also knows how to probe them.</p>
     *
     * <p>Matching is exact and case sensitive, because the legal values are symbolic names rather than
     * free text and a near-miss is a launch error worth reporting rather than guessing at. The supplied
     * collection is copied defensively in declaration order, so the returned validator is unaffected by
     * later mutation of the caller's collection and its diagnostics list the legal values in a stable
     * order.</p>
     *
     * <p>Every legal mode name is reproduced verbatim inside the diagnostic of a rejected launch, so the
     * enumeration is admitted only in printable US-ASCII. That condition is enforced here, at the
     * configuration boundary, rather than each time a diagnostic is rendered: a mode name is supplied once
     * by the job configuration and read back into many messages, so guarding the boundary is what makes it
     * impossible for a later diagnostic to reintroduce an unescaped control character. Nothing is lost by
     * it — the mode value is a Java-side symbolic name with no legacy antecedent literal, so no legacy
     * value can fall outside the bound.</p>
     *
     * @param legalModeNames the exact set of acceptable mode values, in the order they should be
     *                       reported; must not be {@code null}, must not be empty, and must not contain
     *                       a {@code null} entry, a blank entry, or an entry carrying a character outside
     *                       printable US-ASCII
     * @return a stateless validator over an unmodifiable copy of {@code legalModeNames}, never
     *         {@code null}
     * @throws NullPointerException     if {@code legalModeNames} is {@code null}
     * @throws IllegalArgumentException if {@code legalModeNames} is empty, or contains a {@code null} or
     *                                  blank entry, either of which would make the validator reject a
     *                                  launch it was configured to accept; or contains an entry carrying a
     *                                  character outside printable US-ASCII, which could not be reproduced
     *                                  into a diagnostic safely
     */
    public JobParametersValidator fileProbeModeValidator(final Collection<String> legalModeNames) {
        final Set<String> legalModes = copyLegalModeNames(legalModeNames);
        return jobParameters -> validateFileProbeMode(jobParameters, legalModes);
    }

    /**
     * Validates one date-parameter record and returns the report window it carries.
     *
     * <p>This is not a job parameter and is deliberately not modelled as one. The report program does not
     * receive its date window as a program parameter: it declares a sequential file assigned to the
     * date-parameter data definition at {@code [app/cbl/CBTRN03C.cbl:L55]} through
     * {@code [app/cbl/CBTRN03C.cbl:L57]}, pointed at a catalogued dataset with shared disposition at
     * {@code [app/jcl/TRANREPT.jcl:L73]} and identically at {@code [app/proc/TRANREPT.prc:L71]}, and
     * reads the window from it. The sort step of the same pipeline receives the same window as sort
     * symbols instead. One window, two delivery routes, and this method is the file route.</p>
     *
     * <h4>The layout</h4>
     *
     * <p>The record area is eighty bytes wide, at {@code [app/cbl/CBTRN03C.cbl:L88]}, and the group the
     * program reads it into is twenty-one bytes: a ten-character start date at
     * {@code [app/cbl/CBTRN03C.cbl:L123]}, a single-character filler at
     * {@code [app/cbl/CBTRN03C.cbl:L124]} and a ten-character end date at
     * {@code [app/cbl/CBTRN03C.cbl:L125]}. Only those twenty-one bytes carry content; whatever follows in
     * the eighty-byte image is padding.</p>
     *
     * <h4>What is checked, in order</h4>
     *
     * <ol>
     *   <li>the record was supplied at all;</li>
     *   <li>every character is representable in the single-byte character set the record area reserves
     *       bytes for;</li>
     *   <li>the record carries at least the twenty-one encoded bytes the target group occupies, so there
     *       is something for every declared position to receive;</li>
     *   <li>the eleventh position is exactly one separator character;</li>
     *   <li>both extracted dates satisfy the same cascade the report date range validator applies, which
     *       includes the inclusive-ordering test between them.</li>
     * </ol>
     *
     * <p>Measurement is in encoded bytes rather than characters because the layout reserves bytes.</p>
     *
     * <h4>The eighty-to-twenty-one move, reproduced</h4>
     *
     * <p>The legacy read takes DATE-PARMS-FILE into WS-DATEPARM-RECORD at
     * {@code [app/cbl/CBTRN03C.cbl:L221]}: an eighty-byte record area moved into a twenty-one-byte group.
     * A COBOL group move of a longer sending item into a shorter receiving item keeps the leading bytes of
     * the sender and discards the remainder, silently and without diagnostic. This method reproduces that
     * exactly - it requires enough input to fill the twenty-one-byte group, reads the leading twenty-one
     * bytes, and ignores whatever follows them.</p>
     *
     * <p>So a record carrying content beyond position twenty-one is <em>not</em> refused, because the
     * legacy did not refuse it. REFUSING IT IS NOT AVAILABLE, and the reasoning that argues for it - a
     * control record running past its layout is a defect worth failing on - describes a system the estate
     * is not: it would reject input the mainframe accepted, which is a
     * behavioural regression whatever its merits as policy, and only an explicit authorisation could
     * license it. The withdrawal is reasoned in {@code docs/decision-log.md} DL-107. Input shorter than the
     * group is still refused - a sending item too small to fill the
     * receiving group has no legacy reading at all, since the record area is fixed at eighty bytes.</p>
     *
     * @param recordText the record as read, either the twenty-one bytes of the group or the full
     *                   eighty-byte image; anything beyond the leading twenty-one bytes is ignored, as the
     *                   legacy move ignores it; must not be {@code null}
     * @return the validated window, never {@code null}
     * @throws ValidationException if the record is absent, is not representable in a single-byte
     *                             character set, measures fewer than twenty-one encoded bytes, does not
     *                             carry the separator in the eleventh position, or carries
     *                             a date that fails the report window cascade. This is not the framework's
     *                             validator contract — no parameter set is being validated — so the
     *                             module's own validation failure is raised rather than the framework's
     */
    public ReportDateWindow parseDateParmRecord(final String recordText) {
        if (recordText == null) {
            throw new ValidationException(DATEPARM_RECORD_LABEL, null,
                    ValidationException.FieldState.MISSING,
                    DATEPARM_RECORD_LABEL + " was not supplied, so the report date window cannot be"
                            + " established");
        }
        if (!isSingleByteRepresentable(recordText)) {
            throw dateParmFailure(DATEPARM_RECORD_LABEL, recordText,
                    "carries a character outside the single-byte character set the record area reserves"
                            + " bytes for, so it cannot occupy the declared record positions");
        }

        final int suppliedWidth = encodedByteWidth(recordText);
        if (suppliedWidth < DATEPARM_SIGNIFICANT_WIDTH) {
            throw dateParmFailure(DATEPARM_RECORD_LABEL, recordText,
                    "must carry at least " + DATEPARM_SIGNIFICANT_WIDTH + " encoded bytes -"
                            + " two " + ISO_DATE_WIDTH + "-character dates either side of one separator -"
                            + " but measures " + suppliedWidth);
        }

        // The eighty-to-twenty-one group move: the leading bytes are what the receiving group gets, and
        // the remainder of the sending record area is discarded without diagnostic.
        final String group = recordText.substring(0, DATEPARM_SIGNIFICANT_WIDTH);
        if (group.charAt(DATEPARM_SEPARATOR_INDEX) != DATEPARM_SEPARATOR) {
            throw dateParmFailure(DATEPARM_RECORD_LABEL, group,
                    "must carry a single separator character in position "
                            + (DATEPARM_SEPARATOR_INDEX + 1) + ", between the two dates");
        }

        final String startDate = group.substring(0, ISO_DATE_WIDTH);
        final String endDate = group.substring(DATEPARM_END_DATE_INDEX, DATEPARM_SIGNIFICANT_WIDTH);

        final Optional<String> failure = checkReportWindow(DATEPARM_START_DATE_LABEL, startDate,
                DATEPARM_END_DATE_LABEL, endDate);
        if (failure.isPresent()) {
            throw new ValidationException(DATEPARM_RECORD_LABEL, null,
                    ValidationException.FieldState.INVALID, failure.get());
        }

        LOG.debug("Report date window read from the {}: {} through {}, both bounds inclusive",
                DATEPARM_RECORD_LABEL, describeValue(startDate), describeValue(endDate));
        return new ReportDateWindow(startDate, endDate);
    }

    // =================================================================================================
    // The framework-facing validators. Each is the target of one accessor above and honours the framework
    // contract by reporting a bad parameter set with the framework's own exception.
    // =================================================================================================

    /**
     * Validates the interest-calculation parameter date against its measured contract.
     *
     * @param jobParameters the parameter set the job was launched with
     * @throws JobParametersInvalidException if the parameter set is absent, or the parameter is missing or
     *                                       fails any stage of its cascade
     */
    private void validateInterestParmDate(final JobParameters jobParameters)
            throws JobParametersInvalidException {

        final JobParameters supplied = requireParameters(jobParameters);
        final String value = supplied.getString(INTEREST_PARM_DATE_KEY);

        final Optional<String> failure = checkInterestParmDate(value);
        if (failure.isPresent()) {
            throw new JobParametersInvalidException(failure.get());
        }

        LOG.debug("Interest parameter date accepted verbatim: {}", describeValue(value));
    }

    /**
     * Validates the transaction-report date range against its measured contract.
     *
     * @param jobParameters the parameter set the job was launched with
     * @throws JobParametersInvalidException if the parameter set is absent, either bound is missing or
     *                                       fails its cascade, or the start bound follows the end bound
     */
    private void validateReportDateRange(final JobParameters jobParameters)
            throws JobParametersInvalidException {

        final JobParameters supplied = requireParameters(jobParameters);
        final String startDate = supplied.getString(REPORT_START_DATE_KEY);
        final String endDate = supplied.getString(REPORT_END_DATE_KEY);

        final Optional<String> failure = checkReportWindow(REPORT_START_DATE_KEY, startDate,
                REPORT_END_DATE_KEY, endDate);
        if (failure.isPresent()) {
            throw new JobParametersInvalidException(failure.get());
        }

        LOG.debug("Report date window accepted: {} through {}, both bounds inclusive",
                describeValue(startDate), describeValue(endDate));
    }

    /**
     * Validates the file-probe mode against the legal set its owner supplied.
     *
     * <p>Static because it needs no instance state: the legal set arrives as an argument, closed over by
     * the lambda the accessor returned, and no calendar decision is involved.</p>
     *
     * @param jobParameters the parameter set the job was launched with
     * @param legalModes    the unmodifiable, non-empty set of acceptable values, in report order
     * @throws JobParametersInvalidException if the parameter set is absent, or the mode is missing, blank
     *                                       or outside {@code legalModes}
     */
    private static void validateFileProbeMode(final JobParameters jobParameters,
                                              final Set<String> legalModes)
            throws JobParametersInvalidException {

        final JobParameters supplied = requireParameters(jobParameters);
        final String value = supplied.getString(FILE_PROBE_MODE_KEY);

        if (value == null) {
            throw new JobParametersInvalidException("Job parameter [" + FILE_PROBE_MODE_KEY + "] is"
                    + " required but was not supplied; it selects which file the probe job reads, and the"
                    + " legal values are " + legalModes);
        }
        if (value.isBlank()) {
            throw new JobParametersInvalidException(describe(FILE_PROBE_MODE_KEY, value)
                    + "is blank; the legal values are " + legalModes);
        }
        // Representability is asserted explicitly rather than left to the membership test below. The
        // membership test would reject a control-bearing mode too, since no legal mode carries one, but
        // it would reject it as "not one of the legal probe modes" - which tells an operator to check a
        // spelling when the real defect is an invisible character, and is the least actionable message
        // this class could produce for the one input most likely to have been assembled by a script.
        // Naming the position and the code point is what turns that into a correctable report.
        if (firstNonPrintableIndex(value) >= 0) {
            // The offending position and code point are not re-derived here. describeValue already
            // renders exactly that, and a guard that spelled its own copy of the description would let
            // one machine-checked condition be reported two different ways inside one class depending on
            // which site happened to see it.
            throw new JobParametersInvalidException("Job parameter [" + FILE_PROBE_MODE_KEY + "] must"
                    + " not hold a character outside printable US-ASCII, because the mode is written to"
                    + " the batch log and no legal mode carries one; the supplied value is "
                    + describeValue(value));
        }
        if (!legalModes.contains(value)) {
            throw new JobParametersInvalidException(describe(FILE_PROBE_MODE_KEY, value)
                    + "is not one of the legal probe modes " + legalModes
                    + "; matching is exact and case sensitive");
        }

        LOG.debug("File-probe mode accepted: {}", describeValue(value));
    }


    // =================================================================================================
    // The shared cascades. Each returns a completed diagnostic rather than throwing, so that the record
    // parser and the framework validators can raise the exception their own contract requires from one
    // identical sequence of checks. The sequence itself is fixed: reordering it would change which
    // diagnostic a doubly-invalid value produces.
    // =================================================================================================

    /**
     * The interest parameter cascade: presence, representability, exact width, all digits, then strict
     * calendar validity of the leading calendar positions.
     *
     * <p>The width check precedes the delegation deliberately. The calendar authority moves its input into
     * a fixed-width linkage field, and such a move discards the rightmost excess of an over-long sender,
     * so an eleven-character value delegated first would arrive as its own leading ten characters and be
     * accepted.</p>
     *
     * <p>The digit test is an explicit range test rather than a general "is this a digit" query, because
     * the legacy numeric picture accepts the ten characters of the single-byte digit range and nothing
     * else; a general query would additionally admit digits from other scripts, which the legacy field
     * cannot hold.</p>
     *
     * <p>The full ten characters, not a slice of them, are handed to the calendar authority alongside the
     * compact mask. That reproduces the legacy arrangement exactly — a ten-character linkage date field
     * whose mask governs how many positions are interpreted — so the trailing two positions are outside
     * the calendar interpretation without this class having to cut them off.</p>
     *
     * @param value the parameter value as launched, or {@code null} when it was not supplied
     * @return the diagnostic when a stage fails, otherwise empty
     */
    private Optional<String> checkInterestParmDate(final String value) {
        if (value == null) {
            return Optional.of("Job parameter [" + INTEREST_PARM_DATE_KEY + "] is required but was not"
                    + " supplied; the interest calculation is driven by it");
        }
        if (!isSingleByteRepresentable(value)) {
            return Optional.of(describe(INTEREST_PARM_DATE_KEY, value)
                    + "carries a character outside the single-byte character set the parameter field"
                    + " reserves bytes for, so it cannot occupy the declared positions");
        }
        final int width = encodedByteWidth(value);
        if (width != INTEREST_PARM_DATE_WIDTH) {
            return Optional.of(describe(INTEREST_PARM_DATE_KEY, value) + "must be exactly "
                    + INTEREST_PARM_DATE_WIDTH + " encoded bytes to fill the parameter date field, but"
                    + " measures " + width);
        }
        if (!isAllDigits(value)) {
            return Optional.of(describe(INTEREST_PARM_DATE_KEY, value) + "must be "
                    + INTEREST_PARM_DATE_WIDTH + " digits with no separators; it is not a hyphenated ISO"
                    + " date");
        }
        if (!isAcceptableCalendarDate(value, COMPACT_DATE_MASK)) {
            return Optional.of(describe(INTEREST_PARM_DATE_KEY, value) + "does not carry a valid calendar"
                    + " date in its leading " + INTEREST_PARM_CALENDAR_WIDTH + " positions");
        }
        return Optional.empty();
    }

    /**
     * The single-date stage of the report window cascade: presence, representability, exact width, then
     * strict calendar validity in hyphenated ISO form.
     *
     * <p>No separator or shape test is written here. The calendar authority resolves strictly against the
     * hyphenated mask, which rejects a wrong separator, a missing separator and a non-digit in a digit
     * position, so restating any of those here would duplicate the authority and risk diverging from
     * it.</p>
     *
     * @param parameterName the name to attribute a failure to, which is a job parameter key on the
     *                      validator route and a record position label on the record route
     * @param value         the value to test, or {@code null} when it was not supplied
     * @return the diagnostic when a stage fails, otherwise empty
     */
    private Optional<String> checkIsoDateParameter(final String parameterName, final String value) {
        if (value == null) {
            return Optional.of("Job parameter [" + parameterName + "] is required but was not supplied;"
                    + " the report window needs both of its bounds");
        }
        if (!isSingleByteRepresentable(value)) {
            return Optional.of(describe(parameterName, value)
                    + "carries a character outside the single-byte character set the field reserves bytes"
                    + " for, so it cannot occupy the declared positions");
        }
        final int width = encodedByteWidth(value);
        if (width != ISO_DATE_WIDTH) {
            return Optional.of(describe(parameterName, value) + "must be exactly " + ISO_DATE_WIDTH
                    + " encoded bytes in the form YYYY-MM-DD, but measures " + width);
        }
        if (!isAcceptableCalendarDate(value, ISO_DATE_MASK)) {
            return Optional.of(describe(parameterName, value) + "is not a valid calendar date in the form"
                    + " YYYY-MM-DD");
        }
        return Optional.empty();
    }

    /**
     * The full report window cascade: the start bound, then the end bound, then the ordering relation
     * between them.
     *
     * <p>Stage order is fixed and reported one failure at a time, so a launch that supplies two bad bounds
     * is told about the start bound first. That matches how the legacy edits reported: the first failing
     * check short-circuits the rest.</p>
     *
     * <p>The ordering test is a character comparison, matching the legacy character filter, and it admits
     * equality because both bounds are inclusive — a window whose start equals its end selects that single
     * day.</p>
     *
     * @param startName  the name to attribute a start-bound failure to
     * @param startValue the start bound, or {@code null} when it was not supplied
     * @param endName    the name to attribute an end-bound failure to
     * @param endValue   the end bound, or {@code null} when it was not supplied
     * @return the diagnostic when a stage fails, otherwise empty
     */
    private Optional<String> checkReportWindow(final String startName, final String startValue,
                                               final String endName, final String endValue) {

        final Optional<String> startFailure = checkIsoDateParameter(startName, startValue);
        if (startFailure.isPresent()) {
            return startFailure;
        }
        final Optional<String> endFailure = checkIsoDateParameter(endName, endValue);
        if (endFailure.isPresent()) {
            return endFailure;
        }
        if (startValue.compareTo(endValue) > 0) {
            // Both bounds have already passed the ISO-date cascade, so both are printable by
            // construction and neither scan below can substitute anything. They are rendered through
            // the shared guard regardless, because this is the one diagnostic in the class that
            // composes a value without going through describe(), and a message whose safety depends on
            // a check performed by a different method further up is safe only until that method is
            // reordered. Guarding here makes this record's safety a property of this record.
            return Optional.of("Job parameter [" + renderSafely(startName) + "] value ["
                    + renderSafely(startValue) + "] follows [" + renderSafely(endName) + "] value ["
                    + renderSafely(endValue) + "]; the report window is filtered with an"
                    + " inclusive lower and an inclusive upper bound, so the start must not be later than"
                    + " the end");
        }
        return Optional.empty();
    }

    /**
     * Asks the calendar authority whether a value is an acceptable date under a mask, and reports its
     * verdict unchanged.
     *
     * <p>Both the validation and the acceptance decision are delegated. The authority reproduces the
     * legacy date-edit cascade with strict resolution, so an impossible calendar date is rejected rather
     * than normalised, and its acceptance test reproduces the legacy two-level test including the one
     * feedback condition the legacy callers chose to tolerate. Re-deriving acceptance here from the
     * severity code would drop that tolerance and reject dates the legacy accepts.</p>
     *
     * <p>The mask is passed as text because that is how the legacy callers held it — in a fixed-width
     * format work field — and because doing so keeps this class's dependencies to the authority itself. An
     * unrecognised mask is reported by the authority as an unusable picture string and is therefore
     * rejected rather than guessed at.</p>
     *
     * <p>This is reached only after representability and width have been established, which is why it
     * cannot raise the authority's own argument failures back through a validator.</p>
     *
     * @param candidate  the value to test, already proven representable and of the declared width
     * @param formatMask the mask selecting the calendar interpretation
     * @return {@code true} when the legacy callers would have proceeded
     */
    private boolean isAcceptableCalendarDate(final String candidate, final String formatMask) {
        final DateValidationService.SubprogramResult result =
                dateValidationService.validateDate(candidate, formatMask);
        final boolean acceptable = dateValidationService.isDateAcceptable(result);
        if (!acceptable) {
            LOG.debug("Calendar authority rejected {} under mask [{}]: severity [{}] messageNumber [{}]",
                    describeValue(candidate), formatMask, result.severityCode(), result.messageNumber());
        }
        return acceptable;
    }

    // =================================================================================================
    // Small shared primitives.
    // =================================================================================================

    /**
     * Guards against an absent parameter set, reporting it through the framework contract.
     *
     * <p>The framework always supplies a set, so this is a defensive invariant rather than a live branch;
     * reporting it as an invalid parameter set keeps the failure inside the declared contract instead of
     * surfacing an unchecked failure from inside a validator.</p>
     *
     * @param jobParameters the set to guard
     * @return {@code jobParameters}, never {@code null}
     * @throws JobParametersInvalidException if {@code jobParameters} is {@code null}
     */
    private static JobParameters requireParameters(final JobParameters jobParameters)
            throws JobParametersInvalidException {

        if (jobParameters == null) {
            throw new JobParametersInvalidException("No job parameters were supplied, so the launch-time"
                    + " contract of this job cannot be checked");
        }
        return jobParameters;
    }

    /**
     * Copies the caller's legal probe modes into an unmodifiable, order-preserving set, rejecting an input
     * that would make the validator unusable.
     *
     * <p>Order is preserved so that the diagnostic listing the legal values is stable across runs, which
     * matters when the diagnostic is read from a log. An empty set, or one carrying a blank entry, is
     * refused at configuration time rather than at launch time: a validator built from either would reject
     * launches it was configured to accept, and failing where the mistake was made is more useful than
     * failing where it is felt.</p>
     *
     * <p>An entry carrying a character outside printable US-ASCII is refused here too, and for a reason
     * that is not about matching. Each legal value is reproduced verbatim inside the diagnostic of every
     * rejected launch, and parameter substitution escapes nothing, so an entry carrying a line feed would
     * let a configuration mistake forge log records at every subsequent rejection. Guarding it at the one
     * point where the enumeration enters this class is what makes that impossible without every diagnostic
     * having to re-check it, and the guard names the offending character's position and code point rather
     * than repeating the entry, so the report of the problem cannot itself be the problem.</p>
     *
     * @param legalModeNames the caller's collection
     * @return an unmodifiable copy in declaration order, never empty
     * @throws NullPointerException     if {@code legalModeNames} is {@code null}
     * @throws IllegalArgumentException if the collection is empty, carries a {@code null} or blank entry,
     *                                  or carries an entry with a character outside printable US-ASCII;
     *                                  the message names the admissible range as well as the offending
     *                                  position and code point, because it is read by whoever declared
     *                                  the enumeration rather than by whoever launched the job
     */
    private static Set<String> copyLegalModeNames(final Collection<String> legalModeNames) {
        Objects.requireNonNull(legalModeNames, "legalModeNames must not be null: the probe job"
                + " configuration owns the mode enumeration and supplies it here");

        final Set<String> copy = new LinkedHashSet<>();
        for (final String modeName : legalModeNames) {
            if (modeName == null || modeName.isBlank()) {
                throw new IllegalArgumentException("legalModeNames must not carry a null or blank entry,"
                        + " because no launch could ever match one");
            }
            // A legal mode is the one value that reaches the batch log on the success path, and it is
            // also echoed into the membership-failure diagnostic as part of the legal-value set. A
            // control character in a configured mode name would therefore forge a record on a launch
            // that succeeded, which no amount of guarding at the launch-parameter boundary can catch,
            // because the value did not arrive as a launch parameter. It is rejected at construction
            // so the failure is felt where the mode enumeration is declared.
            final int offendingIndex = firstNonPrintableIndex(modeName);
            if (offendingIndex >= 0) {
                // This guard's reader is a developer correcting a declaration, not an operator
                // correcting a launch, so it states the admissible range as well as the offence. The
                // range is read from the same two constants the scan compares against, so it cannot
                // describe a bound the scan does not enforce.
                throw new IllegalArgumentException("legalModeNames must not carry an entry with a"
                        + " character outside printable US-ASCII, because an accepted mode is written to"
                        + " the batch log; an entry may carry printable US-ASCII only, that is code"
                        + " points " + (int) FIRST_PRINTABLE_US_ASCII + " to "
                        + (int) LAST_PRINTABLE_US_ASCII + ", and the entry's character at zero-based"
                        + " position " + offendingIndex + " is code point "
                        + (int) modeName.charAt(offendingIndex));
            }
            copy.add(modeName);
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("legalModeNames must not be empty, because a validator"
                    + " built from an empty set would reject every launch");
        }
        return Collections.unmodifiableSet(copy);
    }

    /**
     * Builds the record parser's failure, tagging it as a supplied-but-wrong value.
     *
     * @param label  the record or record-position label the failure is attributed to
     * @param value  the offending content
     * @param reason what is wrong with it, phrased to follow the label and value
     * @return the failure to raise
     */
    private static ValidationException dateParmFailure(final String label, final String value,
                                                       final String reason) {
        return new ValidationException(label, null, ValidationException.FieldState.INVALID,
                describe(label, value) + reason);
    }

    /**
     * Renders the common prefix of every diagnostic: the offending name and the offending value.
     *
     * <p>Both are always named. A batch diagnostic is read after the fact, so one that omits either the
     * parameter or the value is not actionable. None of the four parameters carries a credential, so
     * naming the value discloses nothing.</p>
     *
     * <p><strong>Disclosing nothing is not the same as being safe to print, and both fragments are
     * therefore rendered through {@link #renderSafely(String)} rather than interpolated.</strong> This
     * method is the single composer behind every diagnostic this class produces - the file-probe
     * rejections, the interest-parameter cascade, the ISO-date cascade and the {@code DATEPARM} record
     * parser all route through it - and the value it renders is launch-time input that no code in this
     * module authored. A value carrying a line feed would end the log record early and let the
     * remainder appear as a second, forged entry; the runtime proof of that defect was a probe mode of
     * {@code ACCT\nFORGED} producing a two-line exception message. Guarding here rather than at each of
     * the ten call sites is deliberate: a guard at the composer cannot be forgotten by the next
     * diagnostic added above it.</p>
     *
     * <p>The name is guarded as well as the value. Most callers pass one of this class's own constants,
     * which are printable by construction, but two do not - the ISO-date cascade takes a parameter name
     * from its caller and the record parser passes a positional label - and a fragment that is only
     * usually authored here still has to be checked. The cost of guarding a constant is one scan of a
     * short string on a path that has already failed.</p>
     *
     * @param name  the parameter key or record-position label
     * @param value the offending value
     * @return the prefix, ending in a space so a reason can follow directly
     */
    private static String describe(final String name, final String value) {
        return "Job parameter [" + renderSafely(name) + "] value [" + renderSafely(value) + "] ";
    }

    /**
     * Renders caller-supplied text for inclusion in a diagnostic, substituting a description of the
     * first offending character when the text is not printable US-ASCII.
     *
     * <p>Printable text renders unchanged, which keeps every legitimate diagnostic exactly as legible
     * as it was: a rejected probe mode still reads {@code [ACCTFILE]} and a rejected date still reads
     * {@code [2022-13-01]}, which is the most useful thing the message can say. Only text that could
     * reframe the record is replaced, and it is replaced by the two facts that make the defect
     * correctable - the zero-based position of the offending character and its code point - and by
     * nothing else. Neither fact can be used to forge a record, and together they identify exactly one
     * character.</p>
     *
     * <p>It degrades rather than throwing, following {@code FixedWidthFieldReader.describeField}. Every
     * caller is already on a failure path composing a message for a defect it has just detected;
     * throwing here would discard that message and report an unrelated fault, hiding the real one
     * behind a secondary one. A {@code null} renders as the literal {@code null} rather than raising,
     * for the same reason - a diagnostic must survive being handed nothing.</p>
     *
     * @param text the caller-supplied fragment, which may be {@code null}
     * @return the text unchanged when it is printable US-ASCII, the literal {@code "null"} when it is
     *         {@code null}, and a substitute fragment naming the offending position and code point
     *         otherwise
     */
    private static String renderSafely(final String text) {
        if (text == null) {
            return "null";
        }
        final int offendingIndex = firstNonPrintableIndex(text);
        if (offendingIndex >= 0) {
            return "value not printable US-ASCII: the character at zero-based position "
                    + offendingIndex + " is code point " + (int) text.charAt(offendingIndex);
        }
        return text;
    }

    /**
     * Reports the zero-based position of the first character outside printable US-ASCII.
     *
     * <p>The single scan behind both the degrading renderer and the throwing file-probe check, so the
     * two cannot disagree about what "printable" means. Returning the position rather than a boolean is
     * what lets both callers name the offending character precisely; a boolean would force each of them
     * to scan a second time to say anything actionable.</p>
     *
     * @param text the text to scan, never {@code null}
     * @return the zero-based position of the first offending character, or {@code -1} when every
     *         character is printable US-ASCII
     */
    private static int firstNonPrintableIndex(final String text) {
        for (int index = 0; index < text.length(); index++) {
            final char character = text.charAt(index);
            if (character < FIRST_PRINTABLE_US_ASCII || character > LAST_PRINTABLE_US_ASCII) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Renders a caller-supplied value, with its own delimiters, for a log record or for a message that
     * does not bracket the value itself.
     *
     * <p>Every log record this class emits routes through here, on the accepting path as well as on the
     * rejecting path. That is the half of the CWE-117 surface a message composer cannot reach: a
     * launch value that is <em>accepted</em> is written to the batch log verbatim, so a value carrying
     * a line feed would forge a second apparent log entry on a job that succeeded, where no rejection
     * message is ever produced and nothing else would have screened it. The probe mode and the interest
     * parameter are both logged on acceptance, and both arrive from a launch.
     *
     * <p>Three renderings exist, and the delimiters are what distinguish them at a glance:
     *
     * <ul>
     *   <li>a value that was never supplied becomes {@value #ABSENT_VALUE_SUBSTITUTE}, in parentheses,
     *       so a reader can tell it from a value that was supplied and happened to be empty;</li>
     *   <li>a value that is printable US-ASCII is reproduced verbatim inside square brackets, which is
     *       what keeps an ordinary batch failure actionable - a rejected probe mode still reads
     *       {@code [ACCTFILE]};</li>
     *   <li>any other value is replaced, in parentheses, by the zero-based position and the code point
     *       of its first offending character, and never by that character itself.</li>
     * </ul>
     *
     * <p>The scan is {@link #firstNonPrintableIndex(String)}, shared with {@link #renderSafely(String)}
     * and with the probe-mode configuration guard, so "printable" has exactly one definition in this
     * class and the three policies built on it cannot drift apart. The two renderers differ only in
     * presentation: this one supplies delimiters because its callers do not, and
     * {@code renderSafely} does not because its callers already wrote the brackets around it.
     *
     * <p>It degrades rather than throwing, for the same reason {@code renderSafely} does. Several
     * callers are already reporting a failure they have just detected, and throwing here would replace
     * that report with an unrelated one.
     *
     * @param value the value to render, or {@code null} when none was supplied
     * @return the rendering, never {@code null} and never carrying a character outside printable
     *         US-ASCII
     */
    private static String describeValue(final String value) {
        if (value == null) {
            return ABSENT_VALUE_SUBSTITUTE;
        }
        final int offendingIndex = firstNonPrintableIndex(value);
        if (offendingIndex >= 0) {
            return "(not printable US-ASCII: the character at zero-based position " + offendingIndex
                    + " is code point " + (int) value.charAt(offendingIndex) + ")";
        }
        return "[" + value + "]";
    }

    /**
     * Reports whether every character is one of the ten characters a legacy numeric picture position
     * accepts.
     *
     * <p>An explicit range test, not a general "is this a digit" query. The legacy field holds the digit
     * characters of a single-byte character set and nothing else, whereas a general query also admits
     * digits from other scripts — which would let a value through that the legacy field cannot represent
     * and that no downstream consumer could parse.</p>
     *
     * @param value the value to test
     * @return {@code true} when every character is in the accepted digit range
     */
    private static boolean isAllDigits(final String value) {
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character < FIRST_DIGIT || character > LAST_DIGIT) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether every character can be represented in the single-byte character set the legacy
     * fields reserve bytes for.
     *
     * <p>Checked before any encode, because encoding an unrepresentable character substitutes a
     * replacement byte: measuring afterwards would report the right width for the wrong content, and the
     * substituted byte would be indistinguishable from one that was genuinely supplied.</p>
     *
     * @param value the value to test
     * @return {@code true} when the value encodes to exactly one byte per character
     */
    private static boolean isSingleByteRepresentable(final String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > MAX_SINGLE_BYTE_CHARACTER) {
                return false;
            }
        }
        return true;
    }

    /**
     * Measures a value in encoded bytes, which is the unit every width in this class is declared in.
     *
     * <p>Callers gate representability first, so for every value this class measures the byte count and
     * the character count coincide; measuring in bytes keeps that a consequence of the contract rather
     * than an assumption about the input.</p>
     *
     * @param value the value to measure
     * @return the encoded width in bytes
     */
    private static int encodedByteWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * The validated transaction-report date window: two inclusive bounds, each a ten-character hyphenated
     * ISO date, held exactly as they were supplied.
     *
     * <p>The bounds are held as text rather than as a date type on purpose. The legacy filter is a
     * character comparison against the record's processing-date field, declared as a character field at
     * {@code [app/jcl/TRANREPT.jcl:L42]}, so the comparison the report performs is between characters and
     * never between dates. Converting to a date type and back would risk reintroducing a different
     * rendering of the same day, and would obscure the fact that the ordering the report relies on is
     * lexical. Both values have already been proven to be strictly valid calendar dates by the time this
     * record exists.</p>
     *
     * <p>Immutable, and both components are mandatory. This type is nested deliberately: the package
     * inventory is closed, and a two-component carrier that exists only to describe this class's own
     * result does not warrant a top-level type.</p>
     *
     * @param startDate the inclusive lower bound, exactly {@code YYYY-MM-DD}
     * @param endDate   the inclusive upper bound, exactly {@code YYYY-MM-DD}, not preceding
     *                  {@code startDate}
     */
    public record ReportDateWindow(String startDate, String endDate) {

        /**
         * Validates that both bounds are present.
         *
         * <p>A fail-fast invariant on this class's own construction rather than validation of caller
         * input: the only producer is {@link JobParameterValidators#parseDateParmRecord(String)}, which
         * has already run the full cascade, so a failure here could only mean the producer drifted.</p>
         *
         * @throws NullPointerException if either bound is {@code null}
         */
        public ReportDateWindow {
            Objects.requireNonNull(startDate, "startDate must not be null");
            Objects.requireNonNull(endDate, "endDate must not be null");
        }

        /**
         * Reports whether a processing date falls inside this window, reproducing the legacy filter
         * exactly.
         *
         * <p><strong>Both bounds are inclusive.</strong> The sort filter tests the processing-date field
         * with greater-or-equal against the start value and less-or-equal against the end value at
         * {@code [app/jcl/TRANREPT.jcl:L47]} and {@code [app/jcl/TRANREPT.jcl:L48]}, and the report
         * program applies the identical pair of comparisons at {@code [app/cbl/CBTRN03C.cbl:L173]} and
         * {@code [app/cbl/CBTRN03C.cbl:L174]}. This method exists so that the report job cannot drift to
         * an exclusive bound by restating the test in its own words.</p>
         *
         * <p>The comparison is lexical, because the legacy comparison is between characters. For
         * hyphenated ISO dates lexical order and chronological order coincide, which is why the legacy
         * could filter dates without ever converting one.</p>
         *
         * <p>The argument is the ten-character date portion of the record's processing timestamp, not the
         * whole timestamp. The legacy compares the leading positions of a wider timestamp field; taking
         * that leading portion is the caller's job, because knowledge of where a field sits inside a
         * record belongs to the fixed-width utilities and not here. Supplying the untrimmed timestamp
         * would be a width error and is rejected as one rather than silently compared.</p>
         *
         * @param processingDate the record's processing date, exactly {@code YYYY-MM-DD}; must not be
         *                       {@code null}
         * @return {@code true} when the date is on or after the start bound and on or before the end
         *         bound
         * @throws NullPointerException     if {@code processingDate} is {@code null}
         * @throws IllegalArgumentException if {@code processingDate} is not exactly the declared width in
         *                                  encoded bytes, since a comparison between operands of
         *                                  different widths is not the comparison the legacy performs
         */
        public boolean includes(final String processingDate) {
            Objects.requireNonNull(processingDate, "processingDate must not be null");
            if (!isSingleByteRepresentable(processingDate)
                    || encodedByteWidth(processingDate) != ISO_DATE_WIDTH) {
                throw new IllegalArgumentException("processingDate must be exactly " + ISO_DATE_WIDTH
                        + " encoded bytes to be compared against this window, but "
                        + describeValue(processingDate) + " is not");
            }
            return startDate.compareTo(processingDate) <= 0 && processingDate.compareTo(endDate) <= 0;
        }
    }
}
