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
package com.carddemo.batch.step;

import com.carddemo.domain.Account;
import com.carddemo.domain.Card;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.DailyTransaction;
import com.carddemo.domain.DisclosureGroup;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategory;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.TransactionType;
import com.carddemo.domain.UserSecurity;
import com.carddemo.exception.RecordParseException;
import com.carddemo.util.AccountRecordMapper;
import com.carddemo.util.CardRecordMapper;
import com.carddemo.util.CardXrefRecordMapper;
import com.carddemo.util.CustomerRecordMapper;
import com.carddemo.util.DailyTransactionRecordMapper;
import com.carddemo.util.DisclosureGroupRecordMapper;
import com.carddemo.util.FailureDiagnostics;
import com.carddemo.util.TranCatBalRecordMapper;
import com.carddemo.util.TranCatRecordMapper;
import com.carddemo.util.TranTypeRecordMapper;
import com.carddemo.util.TransactionRecordMapper;
import com.carddemo.util.UserSecurityRecordMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.file.BufferedReaderFactory;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.file.LineMapper;
import org.springframework.batch.item.file.separator.SimpleRecordSeparatorPolicy;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Builds one flat-file item reader per fixed-width record layout in the estate, binding a Spring
 * Batch reader to the hand-written record mapper that owns that layout.
 *
 * <p>This class is a <strong>composition layer and nothing else</strong>. It decides how a sequential
 * dataset is opened, decoded and split into logical records; it decides nothing whatsoever about what
 * a record <em>means</em>. Not one byte offset, field width, sign convention, key length or filler run
 * appears here. Those live in {@code com.carddemo.util.FixedWidthFieldReader} and the eleven record
 * mappers, which is the only place in the module where a position-derived width may be interpreted, so
 * this class deliberately imports neither that primitive nor a single width constant from any mapper.
 * The division is what keeps the layout knowledge in exactly one place: a mapper can be corrected
 * without touching a reader, and a reader can be re-configured without risking a layout.
 *
 * <h2>Provenance</h2>
 *
 * <p>That estate is a read-only reference: no COBOL, JCL or copybook text is reproduced anywhere in
 * this module, nothing under {@code app/} is read at run time, and the citations below name a
 * member only so a reviewer can find the authority for a width.
 *
 * <h2>The eleven layouts</h2>
 *
 * <p>Each row is a distinct typed factory method. The width column is the canonical encoded record
 * length in bytes, and it is stated here for orientation only - it is asserted by the mapper, never by
 * this class.
 *
 * <table border="1">
 *   <caption>Layout to reader to mapper</caption>
 *   <tr><th>Layout</th><th>Width</th><th>Reader</th><th>Mapper</th></tr>
 *   <tr><td>account</td><td>300</td><td>{@link #accountReader(Resource)}</td>
 *       <td>{@link AccountRecordMapper}</td></tr>
 *   <tr><td>card</td><td>150</td><td>{@link #cardReader(Resource)}</td>
 *       <td>{@link CardRecordMapper}</td></tr>
 *   <tr><td>card cross-reference</td><td>50 or 36</td>
 *       <td>{@link #cardCrossReferenceReader(Resource)}</td><td>{@link CardXrefRecordMapper}</td></tr>
 *   <tr><td>customer</td><td>500</td>
 *       <td>{@link #customerReader(Resource, UnaryOperator)}</td>
 *       <td>{@link CustomerRecordMapper}</td></tr>
 *   <tr><td>transaction</td><td>350</td><td>{@link #transactionReader(Resource)}</td>
 *       <td>{@link TransactionRecordMapper}</td></tr>
 *   <tr><td>daily transaction</td><td>350</td><td>{@link #dailyTransactionReader(Resource)}</td>
 *       <td>{@link DailyTransactionRecordMapper}</td></tr>
 *   <tr><td>transaction category balance</td><td>50</td>
 *       <td>{@link #transactionCategoryBalanceReader(Resource)}</td>
 *       <td>{@link TranCatBalRecordMapper}</td></tr>
 *   <tr><td>disclosure group</td><td>50</td><td>{@link #disclosureGroupReader(Resource)}</td>
 *       <td>{@link DisclosureGroupRecordMapper}</td></tr>
 *   <tr><td>transaction type</td><td>60</td><td>{@link #transactionTypeReader(Resource)}</td>
 *       <td>{@link TranTypeRecordMapper}</td></tr>
 *   <tr><td>transaction category</td><td>60</td><td>{@link #transactionCategoryReader(Resource)}</td>
 *       <td>{@link TranCatRecordMapper}</td></tr>
 *   <tr><td>user security</td><td>80</td>
 *       <td>{@link #userSecurityReader(Resource, UnaryOperator)}</td>
 *       <td>{@link UserSecurityRecordMapper}</td></tr>
 * </table>
 *
 * <h2>Distinctions that must never be collapsed</h2>
 *
 * <p>Four pairs or groups of layouts share a width and are nevertheless unrelated. Merging any of
 * them would compile, would pass a test written under the same misunderstanding, and would corrupt
 * data:
 *
 * <ul>
 *   <li><strong>Transaction and daily transaction are both 350 bytes and field-for-field
 * identical</strong>,       differing only in the field-name prefix their copybooks use. They are different
 * datasets with       different lifecycles: the daily transaction file is posting <em>input</em>, consumed
 * once and       superseded, while the transaction file is the master to which postings and synthesised
 *       interest records are written and from which reports and statements are produced. Two entities,
 *       two mappers, two readers, never an alias of one another;
 *   <li><strong>The card cross-reference, the transaction category balance and the disclosure group are
 *       all canonically 50 bytes</strong> and hold nothing in common beyond that number. The
 *       cross-reference resolves a card number to a customer and an account; the category balance
 *       carries a running balance under a three-part key; the disclosure group carries an interest
 *       rate under a different three-part key. This coincidence is not academic - it is what made a
 *       published mapping table misattribute the estate's cross-reference reader program to the
 *       category balance file - so the three stay separate, typed and separately testable;
 *   <li><strong>The transaction type and the transaction category are both 60 bytes</strong>, one keyed
 *       by a two-character type and the other by that type plus a four-digit category. Separate;
 *   <li><strong>The customer layout exists in two copybook forms</strong> that differ in the spelling of
 *       one date field's name and in nothing else - same width, same offsets, same field order. That
 *       is one entity viewed twice, so there is one customer reader and no twelfth method.
 * </ul>
 *
 * <h2>The cross-reference reader accepts two widths, and imposes neither</h2>
 *
 * <p>The cross-reference record is 50 bytes on the mainframe: 36 bytes of data followed by a 14-byte
 * unused run. The sample sequential dataset shipped with the estate omits that run, so its lines
 * measure 36 bytes. Both forms are live, both are legitimate input, and the field offsets are the same
 * in each - the width decides only whether a filler run follows, never where a field begins.
 *
 * <p>This factory therefore imposes <strong>no stride at all</strong> on that layout. It does not pad a
 * short line up to 50, does not truncate a long one down to 36, and does not choose between the two
 * forms by inspecting anything. Each logical line is handed to the mapper exactly as read, and the
 * mapper - which owns the offsets - accepts either width and rejects every other. A hardcoded stride
 * here would have silently broken one of the two forms.
 *
 * <h2>Every byte of every record is passed through untouched</h2>
 *
 * <p>Trailing spaces are contractual in this estate. A disclosure group key is a ten-character field
 * holding values such as {@code DEFAULT} and {@code ZEROAPR} padded with spaces to its full width; a
 * timestamp that the legacy program never populated is 26 spaces; the unmapped filler runs are spaces
 * by definition. Every one of those is part of the record's identity and part of the output the
 * byte-parity gate compares. So no method here trims, strips, folds case, collapses whitespace,
 * normalises, re-encodes, pads or reorders anything. The line arrives from the reader and leaves for
 * the mapper as the same string.
 *
 * <h2>Six configuration decisions, made once, in one place</h2>
 *
 * <p>Every reader this class builds is configured identically through one private helper, so the
 * decisions below cannot diverge between layouts. Two of them defend against silent data loss and are
 * the reason the helper exists at all.
 *
 * <ol>
 *   <li><strong>A stable, unique name.</strong> Spring Batch persists a reader's progress under keys
 *       derived from its name, which is how a restarted execution resumes where it stopped. The names
 *       are declared here as constants rather than composed at run time, because a name assembled from
 *       a job parameter would change the restart key whenever the parameter changed, and a name shared
 *       between two readers would let one execution resume from the other's position. That is also why
 *       the two 350-byte layouts and the three 50-byte layouts carry visibly different names;
 *   <li><strong>An explicit US-ASCII charset.</strong> The reader's own default is UTF-8, and a default
 *       is the wrong thing to rely on for a byte-exact contract: the estate's sequential datasets are
 *       7-bit ASCII, the mappers verify each image is representable in US-ASCII before measuring it,
 *       and the reject writer emits US-ASCII. Naming the charset at every step keeps the decode
 *       deterministic and keeps the platform default out of the path entirely;
 *   <li><strong>An identity record-separator policy.</strong> The simple policy treats each line as a
 *       whole record and returns it unchanged. The alternative policy in the framework joins
 *       continuation lines and reasons about quote characters, which would corrupt a fixed-width image
 *       that legitimately contains a quote and could merge two records into one. Stating the simple
 *       policy explicitly also makes this file immune to a change of framework default;
 *   <li><strong>No comment prefixes.</strong> The reader is seeded by default to treat any line
 *       beginning with {@code #} as a comment and skip it <em>silently</em>, with no exception and no
 *       record count discrepancy to notice. Seven of the eleven layouts begin with an unconstrained
 *       alphanumeric field - the card number, the transaction identifier twice, the cross-reference
 *       card number, the disclosure group key, the two-character type code and the user identifier -
 *       so a legitimate record beginning with {@code #} would vanish. A sequential read of a legacy
 *       dataset has no notion of a comment, so comment recognition is disabled outright;
 *   <li><strong>Strict resource presence.</strong> A missing input resource fails when the reader opens
 *       rather than yielding zero records and a successful step. This mirrors the legacy programs,
 *       every one of which treats a failed open as terminal;
 *   <li><strong>Nothing else.</strong> No chunk size, commit interval, skip limit, retry limit,
 *       timeout, buffer size, item-count cap or thread count is set here. Those belong to the owning
 *       job configuration where they are structural parameters of a step, and none of them is a tuning
 *       figure this class is entitled to choose.
 * </ol>
 *
 * <h2>Line terminators, and why the mappers never see one</h2>
 *
 * <p>The datasets are newline-terminated, so a 300-byte account record occupies a 301-byte stride on
 * disk. The reader reads by line and the terminator is consumed as the separator it is, whether it is
 * a line feed, a carriage return, or both, and whether or not the final record carries one. The image
 * handed to a mapper therefore holds record content only, which is exactly what each mapper's contract
 * demands: a terminator left in place would count towards the width and the mapper would reject an
 * otherwise valid record.
 *
 * <h2>Failures are reported bounded, and never reinterpreted</h2>
 *
 * <p>Width and character validation belong to the mappers and are not repeated here - a second check
 * would be a second thing to keep in step with the layout. When a mapper rejects an image it throws
 * an {@code IllegalArgumentException}; the framework wraps that in a
 * {@code FlatFileParseException} which names the resource and the line number - <strong>and which
 * carries the whole offending record, verbatim, in both its message and its payload</strong>.
 *
 * <p>That wrapping exception must not leave this class, and every reader built here is therefore a
 * {@link SanitisingFlatFileItemReader}: it translates the framework's parse exception into
 * {@link RecordParseException}, which reports the layout, the resource, the line number and a bounded
 * chain of failure type names, and reports no record content at all. The verdict is unchanged - the
 * read still fails, still terminally, still at the same record - so the owning step's
 * display-then-abend behaviour is preserved exactly; what changes is that the diagnosis a step hands
 * to an appender, and that the job repository persists into an execution's exit message, can no
 * longer be a copy of the record.
 *
 * <p><strong>Why this is translated here rather than in each owning step.</strong> Three of the eleven
 * layouts carry data that must not be republished: the card, transaction and daily-transaction images
 * carry a full primary account number, the card image its verification code, the customer image a
 * national identifier, and the user-security image a sign-on credential. A step-by-step remedy would
 * have to be repeated in every present and future consumer of this factory and would be silently
 * incomplete the moment one was added. One translation on the single path every reader is built
 * through covers all eleven layouts and cannot be forgotten. The layout diagnosis the mapper produced
 * is not lost, only unlinked: its type is named in the bounded chain, and the mapper's own message
 * remains asserted by that mapper's own tests, where no record content is involved.
 *
 * <h2>Threading, state and instances</h2>
 *
 * <p>This class is immutable and stateless: it holds no field, caches no reader, and has nothing to
 * synchronise, so the single injected bean is safe to share. The readers it returns are the opposite -
 * each carries an open resource and a cursor - so <strong>every call returns a new instance</strong>
 * and no instance is ever retained or handed out twice. Each returned reader is intended for one
 * strictly sequential step; none is safe to share between steps or to drive from more than one thread,
 * and no multi-threaded or partitioned reader is offered, because concurrency would reorder records
 * that four fixed-width output formats depend on being in order.
 *
 * <p>The eleven methods are instance methods rather than static ones so that a job configuration can
 * take this factory through its constructor like any other collaborator. Nothing is injected into the
 * factory itself: the eleven mappers are static utilities with private constructors, so there is no
 * mapper bean to inject, and inventing a wrapper for one would add indirection without adding a
 * seam.
 *
 * <h2>No reflection, and no library that would need it</h2>
 *
 * <p>The module's reflection budget is zero, and it is honoured structurally rather than by hope. This
 * class performs no bean-property introspection, registers no type to be populated by name, and uses
 * no annotation-driven fixed-width mapping library. It builds a reader whose line mapping is a direct
 * call to a named static method with a checked signature - which is precisely why the eleven mappers
 * are hand-written with explicit offsets. A field-set or target-type reader configuration would have
 * reintroduced reflection through the framework, so neither is used.
 *
 * @see AccountRecordMapper
 * @see CardXrefRecordMapper
 * @see AbstractCobolStep
 */
@Component
public final class FixedWidthFlatFileReaderFactory {

    /**
     * Name of the reader over the 300-byte account layout.
     *
     * <p>Public because the owning job configuration and its tests identify a reader's persisted
     * progress by this name; a literal repeated at those call sites could drift from the reader.
     */
    public static final String ACCOUNT_READER_NAME = "accountFixedWidthItemReader";

    /** Name of the reader over the 150-byte card layout. */
    public static final String CARD_READER_NAME = "cardFixedWidthItemReader";

    /**
     * Name of the reader over the card cross-reference layout, which is 50 bytes on the mainframe and
     * 36 in the sample sequential dataset.
     *
     * <p>Deliberately unlike {@link #TRANSACTION_CATEGORY_BALANCE_READER_NAME} and
     * {@link #DISCLOSURE_GROUP_READER_NAME} even though all three layouts are canonically 50 bytes,
     * so that no restart key can be mistaken for another's.
     */
    public static final String CARD_CROSS_REFERENCE_READER_NAME =
            "cardCrossReferenceFixedWidthItemReader";

    /** Name of the reader over the 500-byte customer layout. */
    public static final String CUSTOMER_READER_NAME = "customerFixedWidthItemReader";

    /**
     * Name of the reader over the 350-byte transaction master layout.
     *
     * <p>Distinct from {@link #DAILY_TRANSACTION_READER_NAME} because the two layouts are identical in
     * width and shape but are different datasets with different lifecycles.
     */
    public static final String TRANSACTION_READER_NAME = "transactionFixedWidthItemReader";

    /**
     * Stable restart name of the fixed-unblocked transaction reader.
     *
     * <p>The logical layout is still the transaction master layout, but its physical record boundary is
     * a 350-byte stride rather than a line terminator. A separate name prevents restart metadata from a
     * line-oriented source being applied to a fixed-unblocked source.
     */
    public static final String FIXED_TRANSACTION_READER_NAME =
            "fixedUnblockedTransactionItemReader";

    /**
     * Stable restart name of the fixed-unblocked category balance reader.
     *
     * <p>Named separately from {@link #TRANSACTION_CATEGORY_BALANCE_READER_NAME} for the same reason the
     * transaction pair is named separately: the logical layout is identical and only the physical record
     * boundary differs, so restart metadata must never cross between them.
     */
    public static final String FIXED_TRANSACTION_CATEGORY_BALANCE_READER_NAME =
            "fixedUnblockedTransactionCategoryBalanceItemReader";

    /**
     * Name of the reader over the 350-byte daily transaction layout, the posting job's input.
     *
     * <p>Distinct from {@link #TRANSACTION_READER_NAME} for the reason given there.
     */
    public static final String DAILY_TRANSACTION_READER_NAME = "dailyTransactionFixedWidthItemReader";

    /** Name of the reader over the 50-byte transaction category balance layout. */
    public static final String TRANSACTION_CATEGORY_BALANCE_READER_NAME =
            "transactionCategoryBalanceFixedWidthItemReader";

    /** Name of the reader over the 50-byte disclosure group layout. */
    public static final String DISCLOSURE_GROUP_READER_NAME = "disclosureGroupFixedWidthItemReader";

    /** Name of the reader over the 60-byte transaction type reference layout. */
    public static final String TRANSACTION_TYPE_READER_NAME = "transactionTypeFixedWidthItemReader";

    /** Name of the reader over the 60-byte transaction category reference layout. */
    public static final String TRANSACTION_CATEGORY_READER_NAME =
            "transactionCategoryFixedWidthItemReader";

    /** Name of the reader over the 80-byte user security layout. */
    public static final String USER_SECURITY_READER_NAME = "userSecurityFixedWidthItemReader";

    /**
     * Charset every reader decodes with, named explicitly so the platform default is never consulted.
     *
     * <p>The estate's sequential datasets are 7-bit ASCII and each mapper verifies that an image is
     * representable in US-ASCII before it measures the image's width, so decoding with anything else
     * would either change a width or hide a corrupt byte behind a substitution.
     */
    public static final String RECORD_CHARSET_NAME = StandardCharsets.US_ASCII.name();

    /**
     * The empty comment-prefix set applied to every reader, which disables comment recognition.
     *
     * <p>Left at its default, a reader silently discards any line beginning with {@code #}. Seven of
     * the eleven layouts open with an unconstrained alphanumeric field, so such a line can be a valid
     * record, and discarding one produces a short output file with no exception raised and nothing in
     * the record counts to reveal it. A sequential read of a legacy dataset has no notion of a
     * comment, so the notion is removed rather than configured.
     *
     * <p>Safe to hold as a shared constant precisely because it is empty: a zero-length array has no
     * element to mutate, and the reader copies the array it is given rather than retaining this one.
     */
    private static final String[] NO_COMMENT_PREFIXES = new String[0];

    /**
     * The diagnostic channel for a translated parse failure.
     *
     * <p>The only logger in this class, and it exists for one statement: the bounded diagnosis raised in
     * place of the framework's record-bearing parse exception. Every other decision this class makes is
     * either configuration, which the framework already reports at start-up, or a caller error, which is
     * reported by the exception the caller receives.
     */
    private static final Logger LOGGER =
            LoggerFactory.getLogger(FixedWidthFlatFileReaderFactory.class);

    /**
     * The fixed segment of the framework's parse-exception message that precedes the resource
     * description.
     *
     * <p>Named here so the one place that parses that message says which segment it reads, and so it is
     * visible that the segment read is the resource and never the {@code input=[...]} segment that
     * follows it and holds the record.
     */
    private static final String RESOURCE_SEGMENT = "resource=[";

    /** Message used when a caller supplies no resource. */
    private static final String RESOURCE_REQUIRED = "resource must not be null";

    /** Message used when a caller supplies no field-sealing function for the customer layout. */
    private static final String SEALER_REQUIRED = "regulatedFieldSealer must not be null";

    /** Message used when a caller supplies no digest function for the user security layout. */
    private static final String DIGEST_REQUIRED = "credentialDigestFunction must not be null";

    /**
     * Creates the factory.
     *
     * <p>Takes no collaborator, and that is a finding rather than an oversight: the eleven record
     * mappers are stateless utilities with private constructors and only static members, so there is
     * no mapper bean available to inject and nothing this factory could hold. The two layouts that do
     * need a caller-owned function - the customer layout's field sealer and the user security
     * layout's credential digest - receive it as a method argument instead, because each is a
     * key-bearing policy owned by the configuration or service layer, and a factory that held one
     * would own a key lifecycle it has no business owning.
     */
    public FixedWidthFlatFileReaderFactory() {
        // Deliberately empty: see the constructor documentation above.
    }

    /**
     * Builds a reader over the account layout, whose canonical record is 300 encoded bytes.
     *
     * <p>The layout's authority is the account copybook {@code CVACT01Y}, and its 300-byte width is
     * corroborated by the legacy reader program's record description, which splits the same image into
     * an 11-digit key and a 289-byte remainder. Every offset within it belongs to
     * {@link AccountRecordMapper}, including the five money fields whose signs are overpunched into
     * their final byte, so this method neither knows nor needs to know where any field sits.
     *
     * <p>Reading this dataset sequentially returns records in ascending key order, because the legacy
     * cluster is keyed on the leading account identifier. That ordering is observable, so a caller that
     * needs it must preserve the file's order and must not sort or parallelise the step.
     *
     * @param  resource the sequential dataset to read, newline-terminated, one 300-byte record per
     *                  line; must not be {@code null} and must exist when the reader is opened
     * @return a new reader, never {@code null}, named {@value #ACCOUNT_READER_NAME}
     * @throws NullPointerException if {@code resource} is {@code null}
     */
    public FlatFileItemReader<Account> accountReader(final Resource resource) {
        // The line is forwarded exactly as read. The mapper owns every offset and every width check.
        return newReader(ACCOUNT_READER_NAME, resource,
                (line, lineNumber) -> AccountRecordMapper.fromRecord(line));
    }

    /**
     * Builds a reader over the card layout, whose record is 150 encoded bytes.
     *
     * <p>The layout's authority is the card copybook {@code CVACT02Y}. Its first field is a 16-character
     * card number with no constraint on its characters, which is one of the seven layouts that made
     * disabling comment recognition necessary rather than merely tidy.
     *
     * @param  resource the sequential dataset to read, newline-terminated, one 150-byte record per
     *                  line; must not be {@code null} and must exist when the reader is opened
     * @return a new reader, never {@code null}, named {@value #CARD_READER_NAME}
     * @throws NullPointerException if {@code resource} is {@code null}
     */
    public FlatFileItemReader<Card> cardReader(final Resource resource) {
        return newReader(CARD_READER_NAME, resource,
                (line, lineNumber) -> CardRecordMapper.fromRecord(line));
    }

    /**
     * Builds a reader over the card cross-reference layout, accepting both of its live widths.
     *
     * <p>The layout's authority is the cross-reference copybook {@code CVACT03Y}: 16 bytes of card
     * number, 9 of customer identifier, 11 of account identifier, then a 14-byte unused run, totalling
     * 50. The sample sequential dataset omits that run, so each of its lines measures 36 bytes.
     *
     * <p><strong>No stride is imposed here.</strong> A short line is not padded, a long one is not
     * truncated, and nothing inspects the line to choose a form. {@link CardXrefRecordMapper} accepts
     * either width - the three field offsets are identical in both, so the width decides only whether
     * a filler run follows - and rejects every other width. Hardcoding 50 in this method would have
     * broken the sample dataset; hardcoding 36 would have broken the mainframe image.
     *
     * <p>This is the reader for the legacy program whose function is to read and print the
     * cross-reference file. A published mapping table attributed that program to the transaction
     * category balance file instead, which is wrong: the program's own file selection, record
     * description and copybook inclusion all name the cross-reference, and the misattribution is
     * explained only by the two layouts sharing a 50-byte width. Use
     * {@link #transactionCategoryBalanceReader(Resource)} for the category balance and never this
     * method.
     *
     * @param  resource the sequential dataset to read, newline-terminated, one record per line at
     *                  either accepted width; must not be {@code null} and must exist when the reader
     *                  is opened
     * @return a new reader, never {@code null}, named {@value #CARD_CROSS_REFERENCE_READER_NAME}
     * @throws NullPointerException if {@code resource} is {@code null}
     */
    public FlatFileItemReader<CardCrossReference> cardCrossReferenceReader(final Resource resource) {
        return newReader(CARD_CROSS_REFERENCE_READER_NAME, resource,
                (line, lineNumber) -> CardXrefRecordMapper.fromRecord(line));
    }

    /**
     * Builds a reader over the customer layout, whose record is 500 encoded bytes, sealing the two
     * regulated identifiers through the caller's function as each record is mapped.
     *
     * <p>The layout's authority is the customer copybook {@code CVCUS01Y}. A second copybook,
     * {@code CUSTREC}, describes the same 500 bytes with the same fields at the same offsets and
     * differs only in how one date field's name is punctuated; it is therefore a second view of one
     * entity and not a second layout, which is why there is one customer reader rather than two.
     *
     * <p>{@link CustomerRecordMapper} applies {@code regulatedFieldSealer} to the national identifier
     * and the government-issued identifier and to no other field, so those two values are never held
     * in the clear beyond the mapping call. The function is a parameter rather than a field of this
     * factory because sealing needs a key, the key belongs to the configuration or service layer that
     * owns it, and a reader factory holding one would take on a key lifecycle that is not its concern.
     *
     * @param  resource             the sequential dataset to read, newline-terminated, one 500-byte
     *                              record per line; must not be {@code null} and must exist when the
     *                              reader is opened
     * @param  regulatedFieldSealer the caller's sealing function, applied by the mapper to the two
     *                              regulated identifiers only; must not be {@code null}
     * @return a new reader, never {@code null}, named {@value #CUSTOMER_READER_NAME}
     * @throws NullPointerException if either argument is {@code null}
     */
    public FlatFileItemReader<Customer> customerReader(final Resource resource,
            final UnaryOperator<String> regulatedFieldSealer) {
        Objects.requireNonNull(regulatedFieldSealer, SEALER_REQUIRED);
        return newReader(CUSTOMER_READER_NAME, resource,
                (line, lineNumber) -> CustomerRecordMapper.fromRecord(line, regulatedFieldSealer));
    }

    /**
     * Builds a reader over the transaction master layout, whose record is 350 encoded bytes.
     *
     * <p>The layout's authority is the transaction copybook {@code CVTRA05Y}. Two 26-byte timestamps
     * sit near its end, at the offsets the estate's external sort specifications address, and an
     * unmapped 20-byte run closes the record; all three are the mapper's business.
     *
     * <p>This is the <strong>master</strong> transaction file: postings, synthesised interest records
     * and the consolidated output all live here, and reports and statements read from here. It is not
     * the posting job's input. Its layout is byte-for-byte identical to the daily transaction layout,
     * which is exactly why the two readers are separate and why substituting one for the other would
     * not fail to compile - see {@link #dailyTransactionReader(Resource)}.
     *
     * @param  resource the sequential dataset to read, newline-terminated, one 350-byte record per
     *                  line; must not be {@code null} and must exist when the reader is opened
     * @return a new reader, never {@code null}, named {@value #TRANSACTION_READER_NAME}
     * @throws NullPointerException if {@code resource} is {@code null}
     */
    public FlatFileItemReader<Transaction> transactionReader(final Resource resource) {
        return newReader(TRANSACTION_READER_NAME, resource,
                (line, lineNumber) -> TransactionRecordMapper.fromRecord(line));
    }

    /**
     * Builds a reader over a fixed-unblocked transaction generation.
     *
     * <p>Unlike {@link #transactionReader(Resource)}, this reader does not look for a line terminator.
     * It reads exactly {@link TransactionRecordMapper#RECORD_LENGTH} US-ASCII bytes per record and
     * reports an incomplete trailing stride as an input failure.
     *
     * @param resource the fixed-unblocked generation; must not be {@code null}
     * @return a new reader, never {@code null}, named {@value #FIXED_TRANSACTION_READER_NAME}
     * @throws NullPointerException if {@code resource} is {@code null}
     */
    public FlatFileItemReader<Transaction> fixedTransactionReader(final Resource resource) {
        return newFixedStrideReader(FIXED_TRANSACTION_READER_NAME, resource,
                TransactionRecordMapper.RECORD_LENGTH,
                (line, lineNumber) -> TransactionRecordMapper.fromRecord(line));
    }

    /**
     * Builds a reader over the daily transaction layout, whose record is 350 encoded bytes.
     *
     * <p>The layout's authority is the daily transaction copybook {@code CVTRA06Y}, which describes
     * the same 350 bytes as the transaction master copybook with the same field order and widths,
     * differing only in the prefix its field names carry.
     *
     * <p>This is the <strong>posting job's input</strong>, the sequential dataset the legacy job stream
     * feeds to the posting program, and it is also the source image that a rejected record carries
     * verbatim in the first 350 bytes of the 430-byte reject record. Because the two 350-byte layouts
     * are indistinguishable by width and shape, only the type distinguishes them: a step that read the
     * master through this reader, or the daily file through
     * {@link #transactionReader(Resource)}, would parse every record successfully and write the results
     * to the wrong dataset. Never substitute one for the other.
     *
     * @param  resource the sequential dataset to read, newline-terminated, one 350-byte record per
     *                  line; must not be {@code null} and must exist when the reader is opened
     * @return a new reader, never {@code null}, named {@value #DAILY_TRANSACTION_READER_NAME}
     * @throws NullPointerException if {@code resource} is {@code null}
     */
    public FlatFileItemReader<DailyTransaction> dailyTransactionReader(final Resource resource) {
        return newReader(DAILY_TRANSACTION_READER_NAME, resource,
                (line, lineNumber) -> DailyTransactionRecordMapper.fromRecord(line));
    }

    /**
     * Builds a reader over the transaction category balance layout, whose record is 50 encoded bytes.
     *
     * <p>The layout's authority is the category balance copybook {@code CVTRA01Y}: a three-part key of
     * account identifier, type code and category code, then a signed two-decimal balance, then an
     * unmapped run. {@link TranCatBalRecordMapper} owns those offsets and also assembles the composite
     * identifier the entity is keyed by.
     *
     * <p>Canonically 50 bytes, as are the cross-reference and disclosure group layouts, and unrelated
     * to both. See {@link #cardCrossReferenceReader(Resource)} for the misattribution that shared width
     * has already caused once.
     *
     * @param  resource the sequential dataset to read, newline-terminated, one 50-byte record per
     *                  line; must not be {@code null} and must exist when the reader is opened
     * @return a new reader, never {@code null}, named
     *         {@value #TRANSACTION_CATEGORY_BALANCE_READER_NAME}
     * @throws NullPointerException if {@code resource} is {@code null}
     */
    public FlatFileItemReader<TransactionCategoryBalance> transactionCategoryBalanceReader(
            final Resource resource) {
        return newReader(TRANSACTION_CATEGORY_BALANCE_READER_NAME, resource,
                (line, lineNumber) -> TranCatBalRecordMapper.fromRecord(line));
    }

    /**
     * Builds a reader over a fixed-unblocked category balance generation.
     *
     * <p>Unlike {@link #transactionCategoryBalanceReader(Resource)}, this reader does not look for a
     * line terminator. It reads exactly {@link TranCatBalRecordMapper#RECORD_LENGTH} US-ASCII bytes per
     * record and reports an incomplete trailing stride as an input failure. It is the reader for a
     * generation this module produced, whose declared record format carries no separator; the
     * line-oriented sibling remains the reader for the repository's newline-delimited sample data.
     *
     * @param  resource the fixed-unblocked generation; must not be {@code null}
     * @return a new reader, never {@code null}, named
     *         {@value #FIXED_TRANSACTION_CATEGORY_BALANCE_READER_NAME}
     * @throws NullPointerException if {@code resource} is {@code null}
     */
    public FlatFileItemReader<TransactionCategoryBalance> fixedTransactionCategoryBalanceReader(
            final Resource resource) {
        return newFixedStrideReader(FIXED_TRANSACTION_CATEGORY_BALANCE_READER_NAME, resource,
                TranCatBalRecordMapper.RECORD_LENGTH,
                (line, lineNumber) -> TranCatBalRecordMapper.fromRecord(line));
    }

    /**
     * Builds a reader over the disclosure group layout, whose record is 50 encoded bytes.
     *
     * <p>The layout's authority is the disclosure group copybook {@code CVTRA02Y}: a three-part key of
     * a ten-character account group identifier, a type code and a category code, then a signed
     * two-decimal interest rate, then an unmapped run.
     *
     * <p>The group identifier is where this layout's trailing spaces matter most. The sample dataset's
     * three groups are named in a ten-character field and are padded to it, so the stored value carries
     * those spaces and any comparison against a trimmed value would miss. This reader passes the field
     * through untouched, as it does every other field; the mapper decides nothing about padding either,
     * because the record already holds exactly what it should.
     *
     * @param  resource the sequential dataset to read, newline-terminated, one 50-byte record per
     *                  line; must not be {@code null} and must exist when the reader is opened
     * @return a new reader, never {@code null}, named {@value #DISCLOSURE_GROUP_READER_NAME}
     * @throws NullPointerException if {@code resource} is {@code null}
     */
    public FlatFileItemReader<DisclosureGroup> disclosureGroupReader(final Resource resource) {
        return newReader(DISCLOSURE_GROUP_READER_NAME, resource,
                (line, lineNumber) -> DisclosureGroupRecordMapper.fromRecord(line));
    }

    /**
     * Builds a reader over the transaction type reference layout, whose record is 60 encoded bytes.
     *
     * <p>The layout's authority is the transaction type copybook {@code CVTRA03Y}: a two-character type
     * code, a 50-character description, then an unmapped run. Keyed by the type code alone, which is
     * what separates it from the transaction category layout of the same width - see
     * {@link #transactionCategoryReader(Resource)}.
     *
     * @param  resource the sequential dataset to read, newline-terminated, one 60-byte record per
     *                  line; must not be {@code null} and must exist when the reader is opened
     * @return a new reader, never {@code null}, named {@value #TRANSACTION_TYPE_READER_NAME}
     * @throws NullPointerException if {@code resource} is {@code null}
     */
    public FlatFileItemReader<TransactionType> transactionTypeReader(final Resource resource) {
        return newReader(TRANSACTION_TYPE_READER_NAME, resource,
                (line, lineNumber) -> TranTypeRecordMapper.fromRecord(line));
    }

    /**
     * Builds a reader over the transaction category reference layout, whose record is 60 encoded bytes.
     *
     * <p>The layout's authority is the transaction category copybook {@code CVTRA04Y}: a two-part key of
     * type code and four-digit category code, a 50-character description, then an unmapped run. Same
     * width as the transaction type layout and a different key, so the two are separate readers over
     * separate datasets and are never interchangeable.
     *
     * @param  resource the sequential dataset to read, newline-terminated, one 60-byte record per
     *                  line; must not be {@code null} and must exist when the reader is opened
     * @return a new reader, never {@code null}, named {@value #TRANSACTION_CATEGORY_READER_NAME}
     * @throws NullPointerException if {@code resource} is {@code null}
     */
    public FlatFileItemReader<TransactionCategory> transactionCategoryReader(final Resource resource) {
        return newReader(TRANSACTION_CATEGORY_READER_NAME, resource,
                (line, lineNumber) -> TranCatRecordMapper.fromRecord(line));
    }

    /**
     * Builds a reader over the user security layout, whose record is 80 encoded bytes, digesting the
     * credential field through the caller's one-way function as each record is mapped.
     *
     * <p>The layout's authority is the user security copybook {@code CSUSR01Y}: an eight-character
     * identifier, two twenty-character names, an eight-character credential, a one-character type, then
     * an unmapped run. The legacy record stores that credential in the clear and the legacy sign-on
     * program compares it directly, which is the one place this migration deliberately departs from
     * the source: {@link UserSecurityRecordMapper} hands the cleartext slice to
     * {@code credentialDigestFunction} in a single expression and stores only the digest, so no
     * cleartext credential is ever bound to a name or persisted. The function is a parameter for the
     * same reason the customer layout's sealer is - it is a policy owned by the layer that configures
     * it, not by a reader factory.
     *
     * <p><strong>A parse failure from this reader is already bounded, and that is not this layout's
     * privilege alone.</strong> When any mapper rejects an image the framework raises a parse exception
     * that carries the offending line verbatim in its message and payload; for this layout that line
     * contains the cleartext credential. Every reader this class builds therefore translates that
     * exception into {@link RecordParseException} before it can reach a caller, so a step that hands the
     * failure straight to a logging appender publishes the layout, the resource, the line number and a
     * chain of failure type names, and no credential. The failure itself is not suppressed or softened -
     * a malformed dataset still ends the read at the record that is malformed.
     *
     * <p><strong>This reader needs one record per line, and this layout is the one of the eleven whose
     * available datasets are not shaped that way.</strong> The estate ships no plain-text sample for it
     * - only an encoded sequential dataset, while the ten seed records themselves are readable card
     * images inside the provisioning job stream - and the fixture derived from that dataset for testing
     * is ten 80-byte images concatenated with no terminator at all, mirroring the fixed-unblocked
     * record format the legacy definition declares. A line-oriented reader cannot split such an image:
     * it would offer the whole file as one line and the mapper would rightly reject it. Reading a
     * terminator-free strided image is the business of the mapper's whole-buffer entry point, which
     * addresses a record by its ordinal within the buffer, and the ten seed records reach the database
     * through the schema migration rather than through any reader. This method therefore exists for the
     * case where the input genuinely is a line-terminated sequential dataset, and its boundary is
     * recorded here rather than papered over with a second reading mode nothing in the estate asks
     * for.
     *
     * @param  resource                 the sequential dataset to read, newline-terminated, one 80-byte
     *                                  record per line; must not be {@code null} and must exist when
     *                                  the reader is opened
     * @param  credentialDigestFunction the caller's one-way digest function, applied by the mapper to
     *                                  the credential field exactly once; must not be {@code null}
     * @return a new reader, never {@code null}, named {@value #USER_SECURITY_READER_NAME}
     * @throws NullPointerException if either argument is {@code null}
     */
    public FlatFileItemReader<UserSecurity> userSecurityReader(final Resource resource,
            final UnaryOperator<String> credentialDigestFunction) {
        Objects.requireNonNull(credentialDigestFunction, DIGEST_REQUIRED);
        return newReader(USER_SECURITY_READER_NAME, resource,
                (line, lineNumber) ->
                        UserSecurityRecordMapper.fromRecord(line, credentialDigestFunction));
    }

    /**
     * Applies the six configuration decisions documented on this class and returns a new reader.
     *
     * <p>Every public method routes through here, so no layout can end up with a different charset, a
     * different separator policy or comment recognition left enabled. The type parameter is bound at
     * each call site by the mapper's own return type, which is what keeps the eleven readers
     * individually typed while the configuration is written once: there is no registry, no lookup by
     * class and no cast anywhere on this path.
     *
     * <p>The line number the framework offers each mapped line is deliberately unused. A mapper
     * identifies a bad record by its content, and the line number and resource description reach the
     * caller anyway - they are carried by the bounded failure this class raises in place of the
     * framework's record-bearing one - so passing them further would add nothing and would tempt a
     * mapper into position-dependent behaviour.
     *
     * @param  <T>        the domain type the mapper produces
     * @param  readerName the stable, unique name under which this reader's progress is persisted
     * @param  resource   the sequential dataset to read; must not be {@code null}
     * @param  lineMapper the mapping from one untouched record image to one domain object
     * @return a new reader, never {@code null} and never shared with a previous caller
     * @throws NullPointerException if {@code resource} is {@code null}
     */
    private static <T> FlatFileItemReader<T> newReader(final String readerName,
            final Resource resource, final LineMapper<T> lineMapper) {
        return configure(readerName, resource, lineMapper, null);
    }

    /**
     * Builds the fixed-stride variant while preserving every other reader decision.
     *
     * @param <T>          the mapped domain type
     * @param readerName   the stable restart name
     * @param resource     the fixed-unblocked resource
     * @param recordLength the exact encoded stride
     * @param lineMapper   maps one complete stride
     * @return a new independent reader
     */
    private static <T> FlatFileItemReader<T> newFixedStrideReader(final String readerName,
            final Resource resource, final int recordLength, final LineMapper<T> lineMapper) {
        final BufferedReaderFactory readerFactory = (source, encoding) ->
                new FixedStrideBufferedReader(
                        new InputStreamReader(source.getInputStream(), encoding), recordLength);
        return configure(readerName, resource, lineMapper, readerFactory);
    }

    /**
     * Applies every reader decision this class owns to one new sanitising reader.
     *
     * <p>The single construction point. Both {@link #newReader} and {@link #newFixedStrideReader} route
     * through here, so the charset, the separator policy, comment recognition, resource strictness and
     * - decisively - the parse-failure translation cannot differ between one layout and another, and a
     * reader added later inherits all of them without its author having to know they exist.
     *
     * <p>The properties are set rather than assembled through the framework's builder because the
     * instance has to be {@link SanitisingFlatFileItemReader} and the builder can only produce the base
     * type. The set is exactly the set the builder was previously given, with the same values; every
     * other property is left at the framework default, as it was before.
     *
     * @param  <T>           the domain type the mapper produces
     * @param  readerName    the stable, unique name under which this reader's progress is persisted,
     *                       and the name by which a failure identifies the layout
     * @param  resource      the sequential dataset to read; must not be {@code null}
     * @param  lineMapper    the mapping from one untouched record image to one domain object
     * @param  readerFactory the framing strategy for fixed-unblocked input, or {@code null} to frame by
     *                       line as the framework does by default
     * @return a new reader, never {@code null} and never shared with a previous caller
     * @throws NullPointerException if {@code resource} is {@code null}
     */
    private static <T> FlatFileItemReader<T> configure(final String readerName,
            final Resource resource, final LineMapper<T> lineMapper,
            final BufferedReaderFactory readerFactory) {
        Objects.requireNonNull(resource, RESOURCE_REQUIRED);
        final SanitisingFlatFileItemReader<T> reader =
                new SanitisingFlatFileItemReader<>(readerName);
        // Stable and unique, so a restarted execution resumes this reader and no other.
        reader.setName(readerName);
        reader.setResource(resource);
        // Explicit: the reader's own default is UTF-8, and the datasets are 7-bit ASCII.
        reader.setEncoding(RECORD_CHARSET_NAME);
        if (readerFactory != null) {
            // Fixed-unblocked input is framed by width; a line-terminated dataset keeps the default.
            reader.setBufferedReaderFactory(readerFactory);
        }
        // One line is one whole record, returned unchanged: no continuation joining, no quote
        // handling, and every trailing space preserved.
        reader.setRecordSeparatorPolicy(new SimpleRecordSeparatorPolicy());
        // Comment recognition off. Left on, a record beginning with a hash would be discarded
        // silently, and seven of the eleven layouts open with an unconstrained field.
        reader.setComments(NO_COMMENT_PREFIXES);
        // A missing input fails on open instead of yielding an empty, apparently successful run.
        reader.setStrict(true);
        // The whole record image, terminator already removed, handed to the mapper untouched.
        reader.setLineMapper(lineMapper);
        return reader;
    }

    /**
     * A flat-file reader whose parse failures name the failure and never republish the record.
     *
     * <p>The framework's reader composes its parse exception as
     * {@code "Parsing error at line: N in resource=[...], input=[<the whole record>]"} and additionally
     * retains the record as the exception's payload. Both are unavoidable at the point of the throw -
     * they are assembled inside the framework - so the only place the disclosure can be removed is
     * immediately above it, which is what this class is. {@link #doRead()} is the single method the
     * framework routes every record read through, so nothing can bypass the translation.
     *
     * <p>The translation is deliberately narrow. Only the parse exception is caught: a missing
     * resource, an unreadable stream and a short fixed-unblocked stride are all reported by other types
     * whose messages this module either authors itself or which name only the resource, so translating
     * those too would obscure diagnoses that are already safe. The read verdict is not softened - no
     * record is skipped, no failure is swallowed, and the step still ends exactly as it did.
     *
     * <p>The diagnosis is logged here, once, before the raise. That is both the module's convention at
     * a boundary and the legacy batch ordering - write the diagnostic, then end - and it means the
     * layout, resource and line survive even where a job's own failure logging is quietened.
     *
     * @param <T> the domain type the bound mapper produces
     */
    private static final class SanitisingFlatFileItemReader<T> extends FlatFileItemReader<T> {

        /** Stable reader name, used to identify the layout in a failure that carries no record. */
        private final String layout;

        /**
         * @param layout the stable reader name identifying the layout being read
         */
        private SanitisingFlatFileItemReader(final String layout) {
            super();
            this.layout = layout;
        }

        /**
         * Reads one record, translating a parse failure into a diagnosis that carries no record.
         *
         * @return the next mapped object, or {@code null} at end of input
         * @throws RecordParseException if the record at the current line could not be mapped
         * @throws Exception            as the framework's own reader declares, for every other failure
         */
        @Override
        protected T doRead() throws Exception {
            try {
                return super.doRead();
            } catch (final FlatFileParseException disclosing) {
                final String resourceDescription = describeResource(disclosing);
                final String failureChain =
                        FailureDiagnostics.failureChainOf(disclosing.getCause());
                LOGGER.error("A fixed-width record could not be mapped: layout={} resource=[{}]"
                        + " line={} failureChain={}. {}", this.layout, resourceDescription,
                        disclosing.getLineNumber(), failureChain,
                        RecordParseException.REDACTION_NOTICE);
                throw new RecordParseException(this.layout, resourceDescription,
                        disclosing.getLineNumber(), failureChain);
            }
        }

        /**
         * Recovers the resource description from the framework's message without carrying the record.
         *
         * <p>The framework does not expose the resource on its parse exception, and this reader's own
         * resource field is private to the framework class, so the description is taken from the one
         * place it is available: the fixed {@code resource=[...]} segment the framework's own message
         * always contains. Only the text between that segment's brackets is taken, so the
         * {@code input=[...]} segment that follows it - the record - is never read. When the segment is
         * absent, because a future framework version words the message differently, the description is
         * reported as unknown rather than guessed at, and the line number and layout still identify the
         * failure.
         *
         * @param  disclosing the framework's parse exception
         * @return the resource description, or {@link RecordParseException#UNKNOWN}
         */
        private static String describeResource(final FlatFileParseException disclosing) {
            final String message = disclosing.getMessage();
            if (message == null) {
                return RecordParseException.UNKNOWN;
            }
            final int start = message.indexOf(RESOURCE_SEGMENT);
            if (start < 0) {
                return RecordParseException.UNKNOWN;
            }
            final int from = start + RESOURCE_SEGMENT.length();
            final int end = message.indexOf(']', from);
            return end < 0 ? RecordParseException.UNKNOWN : message.substring(from, end);
        }
    }

    /**
     * Opens a fixed-unblocked resource as a reader whose {@code readLine()} returns exactly one record.
     *
     * <p>This is the primitive behind {@link #newFixedStrideReader}, exposed so that a step which reads a
     * generation through its own {@link BufferedReader} rather than through a
     * {@link FlatFileItemReader} frames that generation the same way. Every producer in this module
     * writes a fixed-length dataset without a separator, because every DD in the estate declares
     * {@code RECFM=F} or {@code RECFM=FB} and neither carries one; a consumer must therefore frame by
     * width and never by line. Framing such an object by line would return the whole object as one
     * enormous record, and framing a separator-bearing object by width would return the first record
     * correctly and every later one shifted by its ordinal - see {@code docs/decision-log.md} entry
     * DL-213.
     *
     * <p>The returned reader never reports a partial record: a stride that ends early raises
     * {@link IOException} rather than handing back a short image that a mapper would then misparse.
     *
     * @param  source       the fixed-unblocked resource, read as US-ASCII; must not be {@code null}
     * @param  recordLength the declared record length in bytes; must be at least one
     * @return a reader whose {@code readLine()} yields exactly {@code recordLength} characters, or
     *         {@code null} at end of file
     * @throws NullPointerException     if {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code recordLength} is less than one
     * @throws IOException              if the resource cannot be opened
     */
    public static BufferedReader fixedWidthReader(final Path source, final int recordLength)
            throws IOException {
        Objects.requireNonNull(source, RESOURCE_REQUIRED);
        return new FixedStrideBufferedReader(
                new InputStreamReader(Files.newInputStream(source), StandardCharsets.US_ASCII),
                recordLength);
    }

    /**
     * Presents one exact-width stride as one logical line to {@link FlatFileItemReader}.
     */
    private static final class FixedStrideBufferedReader extends BufferedReader {

        /** Number of characters, and therefore US-ASCII bytes, in one record. */
        private final int recordLength;

        private FixedStrideBufferedReader(final Reader delegate, final int recordLength) {
            super(Objects.requireNonNull(delegate, "delegate"), recordLength);
            if (recordLength < 1) {
                throw new IllegalArgumentException("recordLength must be at least one byte");
            }
            this.recordLength = recordLength;
        }

        @Override
        public String readLine() throws IOException {
            final char[] record = new char[this.recordLength];
            int offset = 0;
            while (offset < record.length) {
                final int read = super.read(record, offset, record.length - offset);
                if (read < 0) {
                    if (offset == 0) {
                        return null;
                    }
                    throw new IOException("fixed-unblocked resource ended after " + offset
                            + " byte(s) of a " + this.recordLength + "-byte record");
                }
                offset += read;
            }
            return new String(record);
        }
    }
}
