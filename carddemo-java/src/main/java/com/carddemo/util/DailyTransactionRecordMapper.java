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

import com.carddemo.domain.DailyTransaction;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Converts between the legacy 350-byte daily-transaction record image and
 * {@link DailyTransaction}, by explicit byte offset and nothing else.
 *
 * <p><strong>Why this class matters more than the other ten mappers.</strong> The daily-transaction
 * dataset is the <em>primary input to the whole batch estate</em>: the sample file
 * {@code [app/data/ASCII/dailytran.txt]} is what the posting pipeline consumes from end to end, so
 * this mapper sits on the critical path of the end-to-end byte-equivalence gate and of the
 * named-real-world-artefact gate. A single mis-declared offset here does not fail loudly; it
 * produces a 350-byte record of exactly the right width carrying the wrong content, which is the one
 * defect a downstream width check can never catch. Every offset below was therefore read from the
 * copybook and then re-verified against the sample file byte by byte.</p>
 *
 * <p><strong>The layout, from {@code [app/cpy/CVTRA06Y.cpy]}.</strong> Offsets are zero-based byte
 * positions within the record image; lengths are encoded bytes, never character counts.</p>
 *
 * <table>
 * <caption>{@code DALYTRAN-RECORD} - thirteen mapped fields and one unmapped filler run</caption>
 * <tr><th scope="col">#</th><th scope="col">Legacy field</th><th scope="col">Picture</th>
 *     <th scope="col">Offset</th><th scope="col">Length</th><th scope="col">Java property</th></tr>
 * <tr><td>1</td><td>{@code DALYTRAN-ID}</td><td>{@code X(16)}</td><td>0</td><td>16</td>
 *     <td>{@code dalytranId} - the {@code @Id}</td></tr>
 * <tr><td>2</td><td>{@code DALYTRAN-TYPE-CD}</td><td>{@code X(02)}</td><td>16</td><td>2</td>
 *     <td>{@code dalytranTypeCd}</td></tr>
 * <tr><td>3</td><td>{@code DALYTRAN-CAT-CD}</td><td>{@code 9(04)}</td><td>18</td><td>4</td>
 *     <td>{@code dalytranCatCd} - a {@code String}</td></tr>
 * <tr><td>4</td><td>{@code DALYTRAN-SOURCE}</td><td>{@code X(10)}</td><td>22</td><td>10</td>
 *     <td>{@code dalytranSource}</td></tr>
 * <tr><td>5</td><td>{@code DALYTRAN-DESC}</td><td>{@code X(100)}</td><td>32</td><td>100</td>
 *     <td>{@code dalytranDesc}</td></tr>
 * <tr><td>6</td><td>{@code DALYTRAN-AMT}</td><td>{@code S9(09)V99}</td><td>132</td><td>11</td>
 *     <td>{@code dalytranAmt} - {@link BigDecimal} at scale 2</td></tr>
 * <tr><td>7</td><td>{@code DALYTRAN-MERCHANT-ID}</td><td>{@code 9(09)}</td><td>143</td><td>9</td>
 *     <td>{@code dalytranMerchantId} - a {@code String}</td></tr>
 * <tr><td>8</td><td>{@code DALYTRAN-MERCHANT-NAME}</td><td>{@code X(50)}</td><td>152</td><td>50</td>
 *     <td>{@code dalytranMerchantName}</td></tr>
 * <tr><td>9</td><td>{@code DALYTRAN-MERCHANT-CITY}</td><td>{@code X(50)}</td><td>202</td><td>50</td>
 *     <td>{@code dalytranMerchantCity}</td></tr>
 * <tr><td>10</td><td>{@code DALYTRAN-MERCHANT-ZIP}</td><td>{@code X(10)}</td><td>252</td><td>10</td>
 *     <td>{@code dalytranMerchantZip}</td></tr>
 * <tr><td>11</td><td>{@code DALYTRAN-CARD-NUM}</td><td>{@code X(16)}</td><td>262</td><td>16</td>
 *     <td>{@code dalytranCardNum}</td></tr>
 * <tr><td>12</td><td>{@code DALYTRAN-ORIG-TS}</td><td>{@code X(26)}</td><td>278</td><td>26</td>
 *     <td>{@code dalytranOrigTs} - a {@code String}</td></tr>
 * <tr><td>13</td><td>{@code DALYTRAN-PROC-TS}</td><td>{@code X(26)}</td><td>304</td><td>26</td>
 *     <td>{@code dalytranProcTs} - a {@code String}</td></tr>
 * <tr><td>-</td><td>{@code FILLER}</td><td>{@code X(20)}</td><td>330</td><td>20</td>
 *     <td><em>not mapped, not persisted</em></td></tr>
 * </table>
 *
 * <p><strong>Width arithmetic.</strong>
 * {@code 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 = 330} mapped bytes, and
 * {@code 330 + 20 = 350}. Both sums are asserted at class initialisation, so a future edit that
 * changes one constant without changing its neighbours fails immediately and loudly rather than
 * shifting every field after it. The eleventh field's offset of 262 is the same position the
 * external sort specifications address as one-based column 263, which is the independent
 * corroboration that the preceding ten widths are right.</p>
 *
 * <p><strong>This layout has no {@code DEFINE CLUSTER}, and that is not an omission.</strong> Alone
 * among the estate's record layouts, the daily-transaction dataset is a <em>sequential</em> dataset
 * rather than an indexed cluster: both consuming programs declare the file
 * {@code ORGANIZATION IS SEQUENTIAL} with {@code ACCESS MODE IS SEQUENTIAL}
 * {@code [app/cbl/CBTRN02C.cbl]} {@code [app/cbl/CBTRN01C.cbl]}. There is consequently no
 * {@code KEYS(length offset)} clause anywhere to corroborate the offsets against - the corroboration
 * used instead is the sort column above, plus a byte-level census of the sample file. The identifier
 * at offset zero is still the natural business key and still the {@code @Id}: no surrogate key is
 * introduced here or anywhere (decisions D-29 and DL-017).</p>
 *
 * <p><strong>Two entities, one geometry - and no shared base class.</strong> This layout is
 * field-for-field parallel to the posted-transaction layout {@code [app/cpy/CVTRA05Y.cpy]}, with
 * identical widths at identical offsets; only the field-name prefix differs. The two are nonetheless
 * modelled as separate entities with separate mappers, because they are distinct datasets with
 * distinct lifecycles: daily transactions are the unvalidated <em>input</em> to the posting job and
 * posted transactions are its <em>output</em> (decision DL-033). Duplicating a byte-exact layout is
 * the faithful choice here, and it is deliberate:</p>
 * <ul>
 * <li>the two mappers are <strong>not</strong> merged;</li>
 * <li>neither subclasses the other;</li>
 * <li>no shared abstract base is extracted.</li>
 * </ul>
 * <p>A shared hierarchy would couple two independently versioned contracts, so that a change to the
 * posted-transaction layout would silently alter how inbound work is parsed. The column names make
 * the divergence permanent and visible: every column of the daily-transaction table carries the
 * {@code dalytran_} prefix <em>including the merchant block</em>, whereas the posted-transaction
 * entity maps its merchant identifier to the unprefixed column {@code merchant_id} (decision D-39).
 * Neither entity is to be "corrected" toward the other.</p>
 *
 * <p><strong>Reflection budget zero, which is why this class exists at all.</strong> The unsafe-code
 * audit commits to zero reflection, so all eleven fixed-width mappers are hand-written with explicit
 * offset arithmetic (decisions DL-034 and D-26). No annotation-driven mapper, bean-mapping library or
 * annotation processor is used here, and none may be introduced.</p>
 *
 * <h2>The amount field</h2>
 *
 * <p>{@code DALYTRAN-AMT} is {@code PIC S9(09)V99} under {@code USAGE DISPLAY}: <strong>zoned
 * decimal, eleven encoded bytes</strong>, one ASCII byte per digit, with no separate sign byte and no
 * byte spent on the implied decimal point. The sign is overpunched into the final digit byte, which
 * carries both the low-order digit and the sign:</p>
 *
 * <table>
 * <caption>Overpunched sign convention in the final byte, at offset 142</caption>
 * <tr><th scope="col">Low-order digit</th><th scope="col">0</th><th scope="col">1</th>
 *     <th scope="col">2</th><th scope="col">3</th><th scope="col">4</th><th scope="col">5</th>
 *     <th scope="col">6</th><th scope="col">7</th><th scope="col">8</th><th scope="col">9</th></tr>
 * <tr><th scope="row">Positive</th><td><code>&#123;</code></td><td>{@code A}</td><td>{@code B}</td>
 *     <td>{@code C}</td><td>{@code D}</td><td>{@code E}</td><td>{@code F}</td><td>{@code G}</td>
 *     <td>{@code H}</td><td>{@code I}</td></tr>
 * <tr><th scope="row">Negative</th><td><code>&#125;</code></td><td>{@code J}</td><td>{@code K}</td>
 *     <td>{@code L}</td><td>{@code M}</td><td>{@code N}</td><td>{@code O}</td><td>{@code P}</td>
 *     <td>{@code Q}</td><td>{@code R}</td></tr>
 * </table>
 *
 * <p><strong>All decoding and encoding of that field goes through {@link ZonedDecimalCodec}, the
 * module's single point of decimal truth.</strong> This class never calls {@code setScale}, never
 * names a rounding mode, never performs arithmetic on an amount and never re-scales a decoded value.
 * Centralising the policy is what makes it impossible for one mapper to introduce a different
 * rounding behaviour than another.</p>
 *
 * <p><strong>Truncation is mandatory; {@code HALF_EVEN} and {@code HALF_UP} are forbidden.</strong>
 * The codec applies {@link java.math.RoundingMode#DOWN} at scale 2 (decisions D-02 and DL-013). The
 * evidence is a keyword census: {@code ROUNDED} occurs <strong>zero</strong> times across
 * {@code [app/cbl]} and {@code [app/cpy]}, and a COBOL arithmetic store without {@code ROUNDED}
 * truncates toward zero. {@code HALF_EVEN} - the conventional Java choice - would differ by one cent
 * on roughly half of all interest computations, a byte-parity failure entirely invisible to a test
 * suite written under the same wrong assumption. Relatedly, {@code COMP-3} occurs
 * <strong>zero</strong> times in {@code [app/cpy]}, so no packed-decimal decoder exists or is needed
 * (decision D-01).</p>
 *
 * <p>The property is a {@link BigDecimal} of precision 11 and scale 2. No {@code double},
 * {@code float}, {@code Double} or {@code Float} appears in this class, in the entity, or in the
 * codec.</p>
 *
 * <p><strong>The one asymmetry, and the measurement that shows it does not bite here.</strong> A
 * {@code BigDecimal} cannot carry a negative zero, so an all-zero image ending in
 * <code>&#125;</code> decodes to zero and re-encodes ending in <code>&#123;</code> (decision D-04).
 * The codec offers a signed path that preserves the bit, but the entity's property is a plain
 * {@code BigDecimal} and cannot hold it, so this mapper uses the monetary path. That is safe on the
 * production-representative input: a census of all 300 amount images in
 * {@code [app/data/ASCII/dailytran.txt]} finds <em>no</em> record whose amount digits are all zero,
 * so every one of the 300 records round-trips byte for byte. The asymmetry is unobservable for an
 * ordinary negative amount whose cent digit is zero - such a value ends in <code>&#125;</code> and
 * re-encodes to <code>&#125;</code>.</p>
 *
 * <h2>Verified evidence from the production-representative input</h2>
 *
 * <p>{@code [app/data/ASCII/dailytran.txt]} is <strong>105,300 bytes = 300 records at a 351-byte
 * stride</strong>: 350 record bytes followed by a single {@code 0x0A}. That line feed is a record
 * <em>terminator</em> and is never part of the record - a fixed-width image this class reads or
 * produces is exactly 350 bytes with no terminator, and record separation belongs to the writer in
 * the batch layer (decision D-30). A caller holding the whole file in one buffer therefore addresses
 * record <em>i</em> at {@code i * 351}, which is what {@link #fromRecord(byte[], int)} exists for.
 * Stride arithmetic stays with the caller because the stride is a property of the file, not of the
 * record.</p>
 *
 * <table>
 * <caption>Values observed at each offset, confirmed on records 0, 250 and 299</caption>
 * <tr><th scope="col">Offset</th><th scope="col">Observation</th></tr>
 * <tr><td>0</td><td>a 16-character transaction identifier, fully zero-padded on the left</td></tr>
 * <tr><td>16</td><td>{@code 01}</td></tr>
 * <tr><td>18</td><td>{@code 0001}</td></tr>
 * <tr><td>22</td><td><code>POS TERM&#160;&#160;</code> or <code>OPERATOR&#160;&#160;</code> - each
 *     exactly ten bytes, each with two trailing spaces</td></tr>
 * <tr><td>32</td><td>a 100-byte description, space-padded on the right</td></tr>
 * <tr><td>132</td><td>{@code 0000005047G}, {@code 0000000349I} and {@code 0000006032B}</td></tr>
 * <tr><td>143</td><td>{@code 800000000}</td></tr>
 * <tr><td>152</td><td>a 50-byte merchant name</td></tr>
 * <tr><td>202</td><td>a 50-byte merchant city</td></tr>
 * <tr><td>252</td><td>{@code 72112} plus five spaces, and {@code 53200-7529}</td></tr>
 * <tr><td>262</td><td>a real 16-digit card number - confirms one-based column 263</td></tr>
 * <tr><td>278</td><td>{@code 2022-06-10 19:27:53.000000} - 26 characters</td></tr>
 * <tr><td>304</td><td><strong>26 spaces</strong></td></tr>
 * <tr><td>330</td><td>20 spaces</td></tr>
 * </table>
 *
 * <p><strong>The three worked amounts, decoded.</strong> {@code 0000005047G} is
 * <strong>504.77</strong>: the trailing {@code G} contributes a low-order 7 and a positive sign, so
 * the unsigned digits are {@code 00000050477}, which at scale 2 is 504.77. {@code 0000000349I} is
 * <strong>34.99</strong> and {@code 0000006032B} is <strong>603.22</strong>. A figure of 500.47 has
 * circulated for the first of the three; it is a digit-transposition slip, and it is recorded here as
 * such rather than propagated, because 500.47 would require the image {@code 0000005004G}, which is
 * not what the file contains. The same correction is recorded on {@link ZonedDecimalCodec}, so the two
 * agree.</p>
 *
 * <p><strong>Source-code census over all 300 records at offset 22:</strong>
 * <code>POS TERM&#160;&#160;</code> appears <strong>250</strong> times and
 * <code>OPERATOR&#160;&#160;</code> <strong>50</strong> times - 250 point-of-sale purchases and 50
 * operator-originated returns. That composition is what makes both signed posting directions
 * reachable from seed data alone, and the correspondence is exact: the 50 negative amounts are
 * precisely the 50 operator records.</p>
 *
 * <p><strong>Overpunch census over all 300 amounts, final byte at offset 142.</strong> Every one of
 * the twenty sign characters occurs in real data, so a full-file round trip exercises the entire sign
 * table with no synthetic fixture:</p>
 *
 * <table>
 * <caption>Occurrences of each overpunch character across the 300 sample records</caption>
 * <tr><th scope="col">Sign</th><th scope="col">Positive</th><th scope="col">Count</th>
 *     <th scope="col">Negative</th><th scope="col">Count</th></tr>
 * <tr><td>0</td><td><code>&#123;</code></td><td>25</td><td><code>&#125;</code></td><td>6</td></tr>
 * <tr><td>1</td><td>{@code A}</td><td>28</td><td>{@code J}</td><td>3</td></tr>
 * <tr><td>2</td><td>{@code B}</td><td>29</td><td>{@code K}</td><td>5</td></tr>
 * <tr><td>3</td><td>{@code C}</td><td>30</td><td>{@code L}</td><td>5</td></tr>
 * <tr><td>4</td><td>{@code D}</td><td>29</td><td>{@code M}</td><td>6</td></tr>
 * <tr><td>5</td><td>{@code E}</td><td>23</td><td>{@code N}</td><td>2</td></tr>
 * <tr><td>6</td><td>{@code F}</td><td>21</td><td>{@code O}</td><td>4</td></tr>
 * <tr><td>7</td><td>{@code G}</td><td>24</td><td>{@code P}</td><td>7</td></tr>
 * <tr><td>8</td><td>{@code H}</td><td>17</td><td>{@code Q}</td><td>4</td></tr>
 * <tr><td>9</td><td>{@code I}</td><td>24</td><td>{@code R}</td><td>8</td></tr>
 * <tr><th scope="row">Total</th><td></td><td><strong>250</strong></td><td></td>
 *     <td><strong>50</strong></td></tr>
 * </table>
 *
 * <p><strong>All 300 records carry the same processing date and the same origination
 * timestamp.</strong> The consequence matters to whoever writes the tests: <em>date-window filtering
 * cannot be exercised by this input</em> and needs a separately constructed fixture. That is a
 * batch-layer and test concern, never a mapper concern - this class performs no filtering, no
 * ordering and no date comparison of any kind.</p>
 *
 * <h2>Field-level contracts this class must not tidy up</h2>
 *
 * <p><strong>A 26-space processing timestamp is legitimate and must survive untouched.</strong>
 * {@code DALYTRAN-PROC-TS} is 26 spaces on all 300 seeded records, because the input is staged before
 * the posting run has stamped it. This mapper tolerates that by construction: the field is a raw
 * 26-byte value that is not parsed, not defaulted, not rejected and never replaced by an epoch.</p>
 *
 * <p><strong>Both timestamps stay {@code String}s.</strong> Neither is converted to
 * {@code LocalDateTime}, {@code Timestamp}, {@code Instant} or any other temporal type, and this
 * class imports nothing from {@code java.time} and declares no formatter. The observed form is a
 * DB2-style timestamp with six fractional digits, but a blank value is equally legal, so any temporal
 * type would fail on the very first record of the reference input. Keeping the raw value also
 * preserves the byte image that the end-to-end gate compares. Strict calendar parsing, where it is
 * wanted at all, belongs to the service layer.</p>
 *
 * <p><strong>Numeric-looking identifiers stay {@code String}s.</strong> {@code DALYTRAN-ID} (16),
 * {@code DALYTRAN-CAT-CD} ({@code 9(04)}, observed as {@code 0001}),
 * {@code DALYTRAN-MERCHANT-ID} ({@code 9(09)}, observed as {@code 800000000}) and
 * {@code DALYTRAN-CARD-NUM} (16) carry significant leading zeros at fixed widths. None is ever parsed
 * to an {@code int} or a {@code long} and re-formatted, because a parse-and-reformat round trip loses
 * the leading zeros and silently narrows the field.</p>
 *
 * <p><strong>The merchant postal code is free-form {@code X(10)}, never numeric.</strong> The sample
 * data carries both a five-digit code padded with five spaces and a ZIP+4 form with an embedded
 * hyphen, such as {@code 53200-7529}. Every character field is copied raw: nothing is trimmed,
 * stripped, case-folded, pad-normalised, postal-normalised or validated anywhere in this class.</p>
 *
 * <p><strong>The entity carries no {@code @Version} field.</strong> Optimistic locking is applied to
 * the account and card entities, whose legacy programs compare a before-image with an after-image.
 * The daily-transaction table is an unvalidated inbound landing area that is written once and read
 * once, so it has no version column and this mapper neither reads nor writes one.</p>
 *
 * <h2>The filler run, and the exact bound for a round-trip comparison</h2>
 *
 * <p>{@link #toRecord(DailyTransaction)} emits the 20-byte filler run as <strong>spaces</strong>.
 * COBOL {@code FILLER X(20)} with no {@code VALUE} clause is uninitialised, so no byte value is
 * canonical, and the sample data disagrees with itself: the four master files carry space filler -
 * 20 bytes per daily-transaction record, measured - while the four reference-table files carry
 * ASCII-zero filler. This is anomaly 20, resolved by decision D-10 in favour of a space default.</p>
 *
 * <p><strong>Therefore every fixture round-trip assertion for this layout compares only the mapped
 * data prefix {@code [0, 330)}</strong>, published as {@link #MAPPED_DATA_LENGTH}. Comparing all 350
 * bytes would be asserting a byte the source never defined.</p>
 *
 * <h2>Boundaries - what this mapper deliberately does not do</h2>
 *
 * <ul>
 * <li><strong>No validation and no reject codes.</strong> The five reject reason codes and the
 *     430-byte reject record - a 350-byte source image followed by a 4-digit reason and a 76-character
 *     description - belong to the batch validation processor and the reject writer. This class assigns
 *     no code and builds no reject image. It is also why the landing table carries no inbound foreign
 *     key: a referential constraint here would reject a bad record at insert time and make the
 *     reject-with-reason paths unreachable (decisions DL-032 and D-38).</li>
 * <li><strong>No arithmetic.</strong> The over-limit basis is evaluated strictly left to right in the
 *     posting service and is never rearranged, because truncation makes that arithmetic
 *     non-associative (decisions D-03 and DL-014). None of it belongs here.</li>
 * <li><strong>No posting, no balance update, no cross-reference resolution.</strong></li>
 * <li><strong>No date or timestamp parsing.</strong></li>
 * <li><strong>No enumeration translation.</strong> The source code stays a raw ten-byte value; the
 *     transaction-source enumeration is a service concern.</li>
 * <li><strong>No comparator, no sort, no ordering.</strong></li>
 * <li><strong>No mapping between this entity and the posted-transaction entity.</strong> Promoting a
 *     daily transaction to a posted transaction is service and batch work; this class does not
 *     reference the posted-transaction type at all.</li>
 * <li><strong>No logging.</strong> This package is not among the configured logger names, so a logger
 *     here would be unconfigured. Diagnostics travel in exception messages instead - and never carry
 *     the value they rejected (decision D-16).</li>
 * <li><strong>No persistence.</strong> No repository, no entity manager, no transaction.</li>
 * </ul>
 *
 * <h2>Consumers of this layout in the legacy estate</h2>
 *
 * <p>Two programs read {@code DALYTRAN-RECORD}. {@code [app/cbl/CBTRN02C.cbl]} is the posting program
 * that the daily pipeline runs. {@code [app/cbl/CBTRN01C.cbl]} is a complete 491-line, 18-paragraph
 * program that <strong>no job member, procedure or resource definition invokes</strong> - anomaly 12 -
 * and it becomes a job that is defined and exercised by tests but excluded from the default pipeline
 * (decision DL-058). Both consume this mapper; nothing about either is implemented here.</p>
 *
 * <h2>Thread safety and shape</h2>
 *
 * <p>Stateless and immutable: a final class with a private constructor, only static members, no
 * mutable static state and no instance state. Every method is a pure function of its arguments -
 * there is no I/O, no clock, no environment lookup and no randomness - so this class is safe for
 * unsynchronised concurrent use by any number of batch threads. It also performs no
 * {@code String}-to-byte conversion of its own: every such boundary lives inside
 * {@link FixedWidthFieldReader} and {@link ZonedDecimalCodec}, both of which use US-ASCII explicitly
 * and never the platform default charset.</p>
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the record layout at checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec},
 * upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy tree is
 * read-only reference: every legacy fact above is cited by member path and no COBOL statement is
 * transcribed.</p>
 *
 * @see DailyTransaction
 * @see FixedWidthFieldReader
 * @see ZonedDecimalCodec
 */
public final class DailyTransactionRecordMapper {

    /**
     * Layout name carried in every diagnostic this class or its collaborators raise.
     *
     * <p>It names <em>both</em> identifiers a reader might search for - the record group
     * {@code DALYTRAN-RECORD} and the copybook member {@code CVTRA06Y} - because a failure message
     * that names only one of them sends the reader to the wrong place. Never parsed, never
     * interpreted, and never used to select behaviour.
     */
    public static final String ARTEFACT = "DALYTRAN-RECORD (CVTRA06Y)";

    /**
     * Encoded byte width of the whole record image, {@value}.
     *
     * <p>A record image is exactly this wide with no line terminator (decision D-30). The sample
     * file's 351-byte stride is the record width plus one separator byte, and that extra byte belongs
     * to the file rather than to the record.
     */
    public static final int RECORD_LENGTH = 350;

    /**
     * Encoded byte width of the mapped data prefix, {@value}, being the record width less the
     * unmapped filler run.
     *
     * <p><strong>This is the exact bound for a fixture round-trip comparison:</strong> compare
     * {@code [0, 330)} and nothing beyond it. The filler byte is not uniform across the sample data
     * and no value for it is canonical, so a comparison that ran to 350 would be asserting a byte the
     * source never defined (anomaly 20, decision D-10).
     */
    public static final int MAPPED_DATA_LENGTH = 330;

    /** Zero-based byte offset of {@code DALYTRAN-ID}, {@value}. */
    public static final int DALYTRAN_ID_OFFSET = 0;

    /** Encoded byte length of {@code DALYTRAN-ID}, {@value}, from {@code PIC X(16)}. */
    public static final int DALYTRAN_ID_LENGTH = 16;

    /** Zero-based byte offset of {@code DALYTRAN-TYPE-CD}, {@value}. */
    public static final int DALYTRAN_TYPE_CD_OFFSET = 16;

    /** Encoded byte length of {@code DALYTRAN-TYPE-CD}, {@value}, from {@code PIC X(02)}. */
    public static final int DALYTRAN_TYPE_CD_LENGTH = 2;

    /** Zero-based byte offset of {@code DALYTRAN-CAT-CD}, {@value}. */
    public static final int DALYTRAN_CAT_CD_OFFSET = 18;

    /** Encoded byte length of {@code DALYTRAN-CAT-CD}, {@value}, from {@code PIC 9(04)}. */
    public static final int DALYTRAN_CAT_CD_LENGTH = 4;

    /** Zero-based byte offset of {@code DALYTRAN-SOURCE}, {@value}. */
    public static final int DALYTRAN_SOURCE_OFFSET = 22;

    /** Encoded byte length of {@code DALYTRAN-SOURCE}, {@value}, from {@code PIC X(10)}. */
    public static final int DALYTRAN_SOURCE_LENGTH = 10;

    /** Zero-based byte offset of {@code DALYTRAN-DESC}, {@value}. */
    public static final int DALYTRAN_DESC_OFFSET = 32;

    /** Encoded byte length of {@code DALYTRAN-DESC}, {@value}, from {@code PIC X(100)}. */
    public static final int DALYTRAN_DESC_LENGTH = 100;

    /**
     * Zero-based byte offset of {@code DALYTRAN-AMT}, {@value}.
     *
     * <p>The overpunched sign therefore sits at offset 142, the field's final byte.
     */
    public static final int DALYTRAN_AMT_OFFSET = 132;

    /**
     * Encoded byte length of {@code DALYTRAN-AMT}, {@value}, from {@code PIC S9(09)V99}.
     *
     * <p>Bound to {@link ZonedDecimalCodec#DAILY_TRANSACTION_AMOUNT_WIDTH} rather than restated as a
     * literal, so the width of this field is one fact shared with the codec that decodes it instead
     * of two facts that can drift apart. Nine integer digits plus two implied decimal digits occupy
     * eleven bytes: there is no separate sign byte and no byte for the decimal point.
     */
    public static final int DALYTRAN_AMT_LENGTH = ZonedDecimalCodec.DAILY_TRANSACTION_AMOUNT_WIDTH;

    /** Zero-based byte offset of {@code DALYTRAN-MERCHANT-ID}, {@value}. */
    public static final int DALYTRAN_MERCHANT_ID_OFFSET = 143;

    /** Encoded byte length of {@code DALYTRAN-MERCHANT-ID}, {@value}, from {@code PIC 9(09)}. */
    public static final int DALYTRAN_MERCHANT_ID_LENGTH = 9;

    /** Zero-based byte offset of {@code DALYTRAN-MERCHANT-NAME}, {@value}. */
    public static final int DALYTRAN_MERCHANT_NAME_OFFSET = 152;

    /** Encoded byte length of {@code DALYTRAN-MERCHANT-NAME}, {@value}, from {@code PIC X(50)}. */
    public static final int DALYTRAN_MERCHANT_NAME_LENGTH = 50;

    /** Zero-based byte offset of {@code DALYTRAN-MERCHANT-CITY}, {@value}. */
    public static final int DALYTRAN_MERCHANT_CITY_OFFSET = 202;

    /** Encoded byte length of {@code DALYTRAN-MERCHANT-CITY}, {@value}, from {@code PIC X(50)}. */
    public static final int DALYTRAN_MERCHANT_CITY_LENGTH = 50;

    /** Zero-based byte offset of {@code DALYTRAN-MERCHANT-ZIP}, {@value}. */
    public static final int DALYTRAN_MERCHANT_ZIP_OFFSET = 252;

    /**
     * Encoded byte length of {@code DALYTRAN-MERCHANT-ZIP}, {@value}, from {@code PIC X(10)}.
     *
     * <p>Free-form and alphanumeric, never numeric: the sample data carries both a five-digit code
     * padded with five spaces and a ZIP+4 form with an embedded hyphen.
     */
    public static final int DALYTRAN_MERCHANT_ZIP_LENGTH = 10;

    /**
     * Zero-based byte offset of {@code DALYTRAN-CARD-NUM}, {@value}.
     *
     * <p>The same position the external sort specifications address as one-based column 263, which is
     * the independent corroboration that the ten preceding field widths are correct.
     */
    public static final int DALYTRAN_CARD_NUM_OFFSET = 262;

    /** Encoded byte length of {@code DALYTRAN-CARD-NUM}, {@value}, from {@code PIC X(16)}. */
    public static final int DALYTRAN_CARD_NUM_LENGTH = 16;

    /** Zero-based byte offset of {@code DALYTRAN-ORIG-TS}, {@value}. */
    public static final int DALYTRAN_ORIG_TS_OFFSET = 278;

    /** Encoded byte length of {@code DALYTRAN-ORIG-TS}, {@value}, from {@code PIC X(26)}. */
    public static final int DALYTRAN_ORIG_TS_LENGTH = 26;

    /**
     * Zero-based byte offset of {@code DALYTRAN-PROC-TS}, {@value}.
     *
     * <p>Blank on every seeded record until the posting run stamps it. Twenty-six spaces is a
     * legitimate value here and is carried through untouched.
     */
    public static final int DALYTRAN_PROC_TS_OFFSET = 304;

    /** Encoded byte length of {@code DALYTRAN-PROC-TS}, {@value}, from {@code PIC X(26)}. */
    public static final int DALYTRAN_PROC_TS_LENGTH = 26;

    /**
     * Zero-based byte offset of the unmapped {@code FILLER} run, {@value}.
     *
     * <p>Equal to {@link #MAPPED_DATA_LENGTH}, because the filler run begins exactly where the mapped
     * data ends.
     */
    public static final int FILLER_OFFSET = 330;

    /** Encoded byte length of the unmapped {@code FILLER} run, {@value}, from {@code PIC X(20)}. */
    public static final int FILLER_LENGTH = 20;

    /**
     * The byte this mapper writes across the filler run, a space.
     *
     * <p>Stated at the point of use rather than inherited silently, because {@code FILLER X(20)} with
     * no {@code VALUE} clause is uninitialised and the sample data is not self-consistent: this
     * layout's own sample carries space filler, which decision D-10 adopts as the module-wide
     * default, while the reference-table layouts carry ASCII-zero filler.
     */
    public static final char FILLER_CHARACTER = ' ';

    /** Legacy name of field 1, used as the reader's diagnostic label. */
    private static final String DALYTRAN_ID = "DALYTRAN-ID";

    /** Legacy name of field 2, used as the reader's diagnostic label. */
    private static final String DALYTRAN_TYPE_CD = "DALYTRAN-TYPE-CD";

    /** Legacy name of field 3, used as the reader's diagnostic label. */
    private static final String DALYTRAN_CAT_CD = "DALYTRAN-CAT-CD";

    /** Legacy name of field 4, used as the reader's diagnostic label. */
    private static final String DALYTRAN_SOURCE = "DALYTRAN-SOURCE";

    /** Legacy name of field 5, used as the reader's diagnostic label. */
    private static final String DALYTRAN_DESC = "DALYTRAN-DESC";

    /** Legacy name of field 6, used as the reader's and the codec's diagnostic label. */
    private static final String DALYTRAN_AMT = "DALYTRAN-AMT";

    /** Legacy name of field 7, used as the reader's diagnostic label. */
    private static final String DALYTRAN_MERCHANT_ID = "DALYTRAN-MERCHANT-ID";

    /** Legacy name of field 8, used as the reader's diagnostic label. */
    private static final String DALYTRAN_MERCHANT_NAME = "DALYTRAN-MERCHANT-NAME";

    /** Legacy name of field 9, used as the reader's diagnostic label. */
    private static final String DALYTRAN_MERCHANT_CITY = "DALYTRAN-MERCHANT-CITY";

    /** Legacy name of field 10, used as the reader's diagnostic label. */
    private static final String DALYTRAN_MERCHANT_ZIP = "DALYTRAN-MERCHANT-ZIP";

    /** Legacy name of field 11, used as the reader's diagnostic label. */
    private static final String DALYTRAN_CARD_NUM = "DALYTRAN-CARD-NUM";

    /** Legacy name of field 12, used as the reader's diagnostic label. */
    private static final String DALYTRAN_ORIG_TS = "DALYTRAN-ORIG-TS";

    /** Legacy name of field 13, used as the reader's diagnostic label. */
    private static final String DALYTRAN_PROC_TS = "DALYTRAN-PROC-TS";

    /**
     * Verifies the layout's own arithmetic before this class can be used for anything.
     *
     * <p>Two sums are checked. The thirteen mapped field lengths must total
     * {@link #MAPPED_DATA_LENGTH}, and the mapped prefix plus the filler run must total
     * {@link #RECORD_LENGTH}. Each field's offset is also checked to be exactly the sum of the
     * lengths before it, which is the property that actually matters: a layout is corrupted not by a
     * wrong total but by one wrong width, which shifts every field after it while leaving the total
     * intact.
     *
     * <p>Checking here rather than in a test means a mis-declared constant cannot reach a fixture
     * comparison at all - the class refuses to initialise. That is the same self-enforcing posture the
     * build takes by promoting compiler diagnostics to errors, and it is cheap: the check runs once
     * per class load over compile-time constants.
     */
    static {
        requireContiguous(DALYTRAN_ID, DALYTRAN_ID_OFFSET, 0);
        requireContiguous(DALYTRAN_TYPE_CD, DALYTRAN_TYPE_CD_OFFSET,
                DALYTRAN_ID_OFFSET + DALYTRAN_ID_LENGTH);
        requireContiguous(DALYTRAN_CAT_CD, DALYTRAN_CAT_CD_OFFSET,
                DALYTRAN_TYPE_CD_OFFSET + DALYTRAN_TYPE_CD_LENGTH);
        requireContiguous(DALYTRAN_SOURCE, DALYTRAN_SOURCE_OFFSET,
                DALYTRAN_CAT_CD_OFFSET + DALYTRAN_CAT_CD_LENGTH);
        requireContiguous(DALYTRAN_DESC, DALYTRAN_DESC_OFFSET,
                DALYTRAN_SOURCE_OFFSET + DALYTRAN_SOURCE_LENGTH);
        requireContiguous(DALYTRAN_AMT, DALYTRAN_AMT_OFFSET,
                DALYTRAN_DESC_OFFSET + DALYTRAN_DESC_LENGTH);
        requireContiguous(DALYTRAN_MERCHANT_ID, DALYTRAN_MERCHANT_ID_OFFSET,
                DALYTRAN_AMT_OFFSET + DALYTRAN_AMT_LENGTH);
        requireContiguous(DALYTRAN_MERCHANT_NAME, DALYTRAN_MERCHANT_NAME_OFFSET,
                DALYTRAN_MERCHANT_ID_OFFSET + DALYTRAN_MERCHANT_ID_LENGTH);
        requireContiguous(DALYTRAN_MERCHANT_CITY, DALYTRAN_MERCHANT_CITY_OFFSET,
                DALYTRAN_MERCHANT_NAME_OFFSET + DALYTRAN_MERCHANT_NAME_LENGTH);
        requireContiguous(DALYTRAN_MERCHANT_ZIP, DALYTRAN_MERCHANT_ZIP_OFFSET,
                DALYTRAN_MERCHANT_CITY_OFFSET + DALYTRAN_MERCHANT_CITY_LENGTH);
        requireContiguous(DALYTRAN_CARD_NUM, DALYTRAN_CARD_NUM_OFFSET,
                DALYTRAN_MERCHANT_ZIP_OFFSET + DALYTRAN_MERCHANT_ZIP_LENGTH);
        requireContiguous(DALYTRAN_ORIG_TS, DALYTRAN_ORIG_TS_OFFSET,
                DALYTRAN_CARD_NUM_OFFSET + DALYTRAN_CARD_NUM_LENGTH);
        requireContiguous(DALYTRAN_PROC_TS, DALYTRAN_PROC_TS_OFFSET,
                DALYTRAN_ORIG_TS_OFFSET + DALYTRAN_ORIG_TS_LENGTH);
        requireContiguous("FILLER", FILLER_OFFSET,
                DALYTRAN_PROC_TS_OFFSET + DALYTRAN_PROC_TS_LENGTH);
        requireSum("mapped data prefix", MAPPED_DATA_LENGTH,
                DALYTRAN_PROC_TS_OFFSET + DALYTRAN_PROC_TS_LENGTH);
        requireSum("record image", RECORD_LENGTH, MAPPED_DATA_LENGTH + FILLER_LENGTH);
    }

    /**
     * Not instantiable: this is a stateless mapper exposing only static members, so an instance would
     * carry no state and confer no capability.
     */
    private DailyTransactionRecordMapper() {
    }

    /**
     * Maps a record image supplied as a string into a fully populated entity.
     *
     * <p>Use this overload when the caller already holds text - a fixture line, or a record read
     * through a character reader. The image must be exactly {@link #RECORD_LENGTH} bytes when encoded
     * as US-ASCII, <strong>excluding any line terminator</strong>: a fixture line is 350 bytes even
     * though the file's stride is 351, so a caller that reads lines must have consumed the
     * {@code 0x0A} as a separator (decision D-30). An image one byte too long is very often exactly
     * that mistake, and the diagnostic says so.</p>
     *
     * <p>Every character field is carried across raw: untrimmed, unstripped, not case-folded and not
     * normalised. A 26-space processing timestamp arrives as 26 spaces, a ten-byte source code keeps
     * its two trailing spaces, and a 16-digit identifier keeps its leading zeros.</p>
     *
     * @param recordImage the complete 350-byte record image, without any line terminator; must not be
     *                    {@code null}
     * @return a fully populated entity, never {@code null} and never partially populated
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the image is not exactly {@link #RECORD_LENGTH} encoded
     *                                  bytes, if it contains a character US-ASCII cannot represent,
     *                                  or if the amount field is not a well-formed zoned-decimal image
     */
    public static DailyTransaction fromRecord(String recordImage) {
        Objects.requireNonNull(recordImage, ARTEFACT + " record image must not be null");
        // Measured through the reader's own encoded-length operation, never through String.length(),
        // because a character count is not a width authority. The reader re-asserts the same
        // invariant when it takes ownership of the image; that repetition is deliberate, since the
        // reader's invariant must hold however it was constructed.
        requireRecordWidth(FixedWidthFieldReader.encodedLength(recordImage));
        return fromReader(FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_LENGTH));
    }

    /**
     * Maps a record image supplied as bytes into a fully populated entity.
     *
     * <p>Preferred over {@link #fromRecord(String)} when the caller already holds raw bytes, because
     * it removes any need for the caller to choose a charset. The array's length <em>is</em> the
     * encoded byte length, so the width check needs no measurement step.</p>
     *
     * @param recordImage the complete 350-byte record image, without any line terminator; must not be
     *                    {@code null}. Not retained: the reader takes a private copy
     * @return a fully populated entity, never {@code null} and never partially populated
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the array is not exactly {@link #RECORD_LENGTH} bytes, if
     *                                  any byte is not 7-bit ASCII, or if the amount field is not a
     *                                  well-formed zoned-decimal image
     */
    public static DailyTransaction fromRecord(byte[] recordImage) {
        Objects.requireNonNull(recordImage, ARTEFACT + " record image must not be null");
        requireRecordWidth(recordImage.length);
        return fromReader(FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_LENGTH));
    }

    /**
     * Maps one record held inside a larger byte buffer into a fully populated entity.
     *
     * <p>This is the seam for a batch reader that has loaded a whole newline-terminated fixed-width
     * file into a single buffer. Because such a file's stride is the record width plus one separator
     * byte, record <em>i</em> of {@code [app/data/ASCII/dailytran.txt]} is addressed as
     * {@code fromRecord(buffer, i * 351)}, which selects the 350 record bytes and leaves the
     * {@code 0x0A} behind. Stride arithmetic stays with the caller on purpose: the stride is a
     * property of the file, whereas the record width is a property of the layout, and conflating the
     * two is how a terminator ends up inside a field.</p>
     *
     * @param buffer the buffer holding the record, and possibly many others; must not be {@code null}.
     *               Not retained: the reader takes a private copy of the selected range
     * @param from   zero-based index in {@code buffer} at which the record starts
     * @return a fully populated entity, never {@code null} and never partially populated
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code from} is negative, if the range
     *                                  {@code [from, from + 350)} is not wholly inside
     *                                  {@code buffer}, if any byte in that range is not 7-bit ASCII,
     *                                  or if the amount field is not a well-formed zoned-decimal image
     */
    public static DailyTransaction fromRecord(byte[] buffer, int from) {
        Objects.requireNonNull(buffer, ARTEFACT + " record buffer must not be null");
        // The range check belongs to the reader, which reports whether the fault was a negative index
        // or a range overrunning the buffer. Re-checking the width here would be wrong rather than
        // merely redundant: the buffer is legitimately longer than one record.
        return fromReader(FixedWidthFieldReader.of(ARTEFACT, buffer, from, RECORD_LENGTH));
    }

    /**
     * Renders an entity back into its 350-byte record image as a string.
     *
     * <p>The exact inverse of {@link #fromRecord(String)} for every record of the sample input.
     * Character fields are placed left-justified and padded on the right with spaces; the two
     * numeric-class identifier fields are placed right-justified and padded on the left with ASCII
     * zeros, which is what preserves their significant leading zeros; the amount is re-encoded by
     * {@link ZonedDecimalCodec} into its overpunched eleven-byte image and placed flush against the
     * field's trailing edge so the sign byte stays last. The 20-byte filler run is written as spaces
     * (decision D-10).</p>
     *
     * <p><strong>Compare only {@code [0, }{@link #MAPPED_DATA_LENGTH}{@code )} in a fixture
     * assertion.</strong> The filler byte is not uniform in the sample data and no value for it is
     * canonical, so bytes 330 through 349 are not part of the contract.</p>
     *
     * @param record the entity to render; must not be {@code null}, and each of its thirteen mapped
     *               properties must be present
     * @return the complete record image, exactly {@link #RECORD_LENGTH} encoded bytes, with no line
     *         terminator
     * @throws NullPointerException     if {@code record} is {@code null} or if any mapped property is
     *                                  {@code null}; the message names the absent legacy field
     * @throws IllegalArgumentException if a value is wider than its field, contains a character
     *                                  US-ASCII cannot represent, or is an amount that does not fit
     *                                  eleven bytes at scale 2
     */
    public static String toRecord(DailyTransaction record) {
        return toReader(record).image();
    }

    /**
     * Renders an entity back into its 350-byte record image as bytes.
     *
     * <p>Behaves exactly as {@link #toRecord(DailyTransaction)} and is preferred by a writer that
     * emits bytes, because it removes any need for the caller to choose a charset. The returned array
     * is freshly allocated and unshared, so a caller may write a separator byte into a larger buffer
     * around it without affecting anything here.</p>
     *
     * @param record the entity to render; must not be {@code null}, and each of its thirteen mapped
     *               properties must be present
     * @return a fresh array of exactly {@link #RECORD_LENGTH} US-ASCII bytes, with no line terminator
     * @throws NullPointerException     if {@code record} is {@code null} or if any mapped property is
     *                                  {@code null}; the message names the absent legacy field
     * @throws IllegalArgumentException if a value is wider than its field, contains a character
     *                                  US-ASCII cannot represent, or is an amount that does not fit
     *                                  eleven bytes at scale 2
     */
    public static byte[] toRecordBytes(DailyTransaction record) {
        return toReader(record).toByteArray();
    }

    /**
     * The single implementation of the read direction: thirteen slices, in copybook order.
     *
     * <p>All three public read entry points funnel through here, so the offsets exist in exactly one
     * place. Every slice is taken through the reader's named-field accessor rather than through
     * {@code String.substring}, which means a mis-declared offset identifies itself by legacy field
     * name instead of producing an anonymous index error - and it keeps the charset decision inside
     * the reader, where it is made once.</p>
     *
     * <p>The entity is built through its all-arguments constructor rather than by setting thirteen
     * properties on a blank instance. That is not a stylistic preference: the entity's no-argument
     * constructor is {@code protected} for the persistence provider's exclusive use and is
     * inaccessible from this package, and the entity's own documentation directs application code to
     * the all-arguments constructor. It also means a partially populated instance cannot exist even
     * briefly, which is what the malformed-input contract requires. The constructor's parameter order
     * is the copybook declaration order, so the argument list below reads in the same order as the
     * record image.</p>
     *
     * <p>The amount is the only field that is not carried across verbatim, and it is converted by the
     * codec alone. This method applies no scale, names no rounding mode and performs no arithmetic. An
     * ill-formed overpunch character is the codec's contract, not this mapper's, so the codec's
     * failure is allowed to propagate unwrapped: its diagnostic names the offending byte and the
     * field, and re-wrapping it here would replace that detail with something vaguer.</p>
     *
     * @param reader an immutable reader already validated to be exactly {@link #RECORD_LENGTH} bytes
     * @return a fully populated entity, never {@code null}
     */
    private static DailyTransaction fromReader(FixedWidthFieldReader reader) {
        return new DailyTransaction(
                reader.field(DALYTRAN_ID, DALYTRAN_ID_OFFSET, DALYTRAN_ID_LENGTH),
                reader.field(DALYTRAN_TYPE_CD, DALYTRAN_TYPE_CD_OFFSET, DALYTRAN_TYPE_CD_LENGTH),
                reader.field(DALYTRAN_CAT_CD, DALYTRAN_CAT_CD_OFFSET, DALYTRAN_CAT_CD_LENGTH),
                reader.field(DALYTRAN_SOURCE, DALYTRAN_SOURCE_OFFSET, DALYTRAN_SOURCE_LENGTH),
                reader.field(DALYTRAN_DESC, DALYTRAN_DESC_OFFSET, DALYTRAN_DESC_LENGTH),
                decodeAmount(reader),
                reader.field(DALYTRAN_MERCHANT_ID, DALYTRAN_MERCHANT_ID_OFFSET,
                        DALYTRAN_MERCHANT_ID_LENGTH),
                reader.field(DALYTRAN_MERCHANT_NAME, DALYTRAN_MERCHANT_NAME_OFFSET,
                        DALYTRAN_MERCHANT_NAME_LENGTH),
                reader.field(DALYTRAN_MERCHANT_CITY, DALYTRAN_MERCHANT_CITY_OFFSET,
                        DALYTRAN_MERCHANT_CITY_LENGTH),
                reader.field(DALYTRAN_MERCHANT_ZIP, DALYTRAN_MERCHANT_ZIP_OFFSET,
                        DALYTRAN_MERCHANT_ZIP_LENGTH),
                reader.field(DALYTRAN_CARD_NUM, DALYTRAN_CARD_NUM_OFFSET, DALYTRAN_CARD_NUM_LENGTH),
                reader.field(DALYTRAN_ORIG_TS, DALYTRAN_ORIG_TS_OFFSET, DALYTRAN_ORIG_TS_LENGTH),
                reader.field(DALYTRAN_PROC_TS, DALYTRAN_PROC_TS_OFFSET, DALYTRAN_PROC_TS_LENGTH));
    }

    /**
     * The single implementation of the write direction: thirteen placements plus the filler run.
     *
     * <p>Both public write entry points funnel through here and differ only in whether they ask the
     * completed reader for characters or for bytes, so the offsets and the justification rules exist
     * in exactly one place.</p>
     *
     * <p>The placements deliberately add up to the full record width - thirteen fields totalling 330
     * bytes and a filler run of 20 - because that is what makes a forgotten field visible during
     * review rather than invisible behind a builder buffer that was already space-filled. Two fields
     * use the right-justified numeric placement: the category code {@code PIC 9(04)} and the merchant
     * identifier {@code PIC 9(09)}, whose leading zeros are significant. The eleven remaining
     * character fields use the left-justified alphanumeric placement, which is how COBOL renders
     * {@code PIC X(n)}. The amount also uses the numeric placement, because a zoned-decimal image is
     * right-justified and its overpunched sign must remain in the final byte.</p>
     *
     * <p>Every property is required to be present, and the diagnostic names the absent legacy field.
     * Treating an absent value as an empty one would emit a space-filled field and yield a record of
     * exactly the right width carrying silently lost data - the one failure mode a downstream width
     * check cannot detect - and it would also contradict the entity's schema, where all thirteen
     * columns are non-nullable. No diagnostic here echoes the value it rejected (decision D-16).</p>
     *
     * @param record the entity to render
     * @return an immutable reader over the completed 350-byte image, ready to be asked for characters
     *         or bytes
     */
    private static FixedWidthFieldReader toReader(DailyTransaction record) {
        Objects.requireNonNull(record, ARTEFACT + " source entity must not be null");
        return FixedWidthFieldReader.builder(ARTEFACT, RECORD_LENGTH)
                .putAlphanumeric(DALYTRAN_ID, DALYTRAN_ID_OFFSET, DALYTRAN_ID_LENGTH,
                        requirePresent(record.getDalytranId(), DALYTRAN_ID))
                .putAlphanumeric(DALYTRAN_TYPE_CD, DALYTRAN_TYPE_CD_OFFSET, DALYTRAN_TYPE_CD_LENGTH,
                        requirePresent(record.getDalytranTypeCd(), DALYTRAN_TYPE_CD))
                .putNumeric(DALYTRAN_CAT_CD, DALYTRAN_CAT_CD_OFFSET, DALYTRAN_CAT_CD_LENGTH,
                        requirePresent(record.getDalytranCatCd(), DALYTRAN_CAT_CD))
                .putAlphanumeric(DALYTRAN_SOURCE, DALYTRAN_SOURCE_OFFSET, DALYTRAN_SOURCE_LENGTH,
                        requirePresent(record.getDalytranSource(), DALYTRAN_SOURCE))
                .putAlphanumeric(DALYTRAN_DESC, DALYTRAN_DESC_OFFSET, DALYTRAN_DESC_LENGTH,
                        requirePresent(record.getDalytranDesc(), DALYTRAN_DESC))
                .putNumeric(DALYTRAN_AMT, DALYTRAN_AMT_OFFSET, DALYTRAN_AMT_LENGTH,
                        encodeAmount(record.getDalytranAmt()))
                .putNumeric(DALYTRAN_MERCHANT_ID, DALYTRAN_MERCHANT_ID_OFFSET,
                        DALYTRAN_MERCHANT_ID_LENGTH,
                        requirePresent(record.getDalytranMerchantId(), DALYTRAN_MERCHANT_ID))
                .putAlphanumeric(DALYTRAN_MERCHANT_NAME, DALYTRAN_MERCHANT_NAME_OFFSET,
                        DALYTRAN_MERCHANT_NAME_LENGTH,
                        requirePresent(record.getDalytranMerchantName(), DALYTRAN_MERCHANT_NAME))
                .putAlphanumeric(DALYTRAN_MERCHANT_CITY, DALYTRAN_MERCHANT_CITY_OFFSET,
                        DALYTRAN_MERCHANT_CITY_LENGTH,
                        requirePresent(record.getDalytranMerchantCity(), DALYTRAN_MERCHANT_CITY))
                .putAlphanumeric(DALYTRAN_MERCHANT_ZIP, DALYTRAN_MERCHANT_ZIP_OFFSET,
                        DALYTRAN_MERCHANT_ZIP_LENGTH,
                        requirePresent(record.getDalytranMerchantZip(), DALYTRAN_MERCHANT_ZIP))
                .putAlphanumeric(DALYTRAN_CARD_NUM, DALYTRAN_CARD_NUM_OFFSET,
                        DALYTRAN_CARD_NUM_LENGTH,
                        requirePresent(record.getDalytranCardNum(), DALYTRAN_CARD_NUM))
                .putAlphanumeric(DALYTRAN_ORIG_TS, DALYTRAN_ORIG_TS_OFFSET, DALYTRAN_ORIG_TS_LENGTH,
                        requirePresent(record.getDalytranOrigTs(), DALYTRAN_ORIG_TS))
                .putAlphanumeric(DALYTRAN_PROC_TS, DALYTRAN_PROC_TS_OFFSET, DALYTRAN_PROC_TS_LENGTH,
                        requirePresent(record.getDalytranProcTs(), DALYTRAN_PROC_TS))
                .putFiller(FILLER_OFFSET, FILLER_LENGTH, FILLER_CHARACTER)
                .build();
    }

    /**
     * Decodes the amount field, the one field of the thirteen that is not carried across verbatim.
     *
     * <p>Named as its own operation so that the single point at which this layout touches decimal
     * conversion is visible rather than buried inside a thirteen-argument constructor call. The slice
     * is taken through the reader and the conversion is performed by the codec; this method applies no
     * scale of its own, names no rounding mode and performs no arithmetic. The codec returns a value
     * whose scale is exactly 2, which is the scale the {@code V99} picture clause implies.
     *
     * @param reader an immutable reader over a validated record image
     * @return the decoded amount at scale 2, never {@code null}
     * @throws IllegalArgumentException if the eleven-byte image is not a well-formed zoned decimal -
     *                                  raised by the codec, and deliberately not re-wrapped, because
     *                                  its diagnostic names the offending byte
     */
    private static BigDecimal decodeAmount(FixedWidthFieldReader reader) {
        return ZonedDecimalCodec.decodeMonetary(
                reader.field(DALYTRAN_AMT, DALYTRAN_AMT_OFFSET, DALYTRAN_AMT_LENGTH),
                DALYTRAN_AMT_LENGTH, DALYTRAN_AMT);
    }

    /**
     * Re-encodes the amount into its overpunched eleven-byte image.
     *
     * <p>The mirror of {@link #decodeAmount(FixedWidthFieldReader)}, and the only place in this class
     * that produces a numeric image. The codec brings the value to scale 2 by truncating toward zero
     * and overpunches the sign into the final byte; this method neither re-scales the value nor
     * inspects its sign.
     *
     * @param amount the amount to encode, taken straight from the entity; must be present
     * @return the eleven-byte zoned-decimal image
     * @throws NullPointerException     if {@code amount} is {@code null}
     * @throws IllegalArgumentException if the value does not fit nine integer digits at scale 2 -
     *                                  raised by the codec, which rejects rather than narrowing,
     *                                  because a silently narrowed amount would leave the record the
     *                                  right width and the wrong value (decision D-06)
     */
    private static String encodeAmount(BigDecimal amount) {
        return ZonedDecimalCodec.encodeMonetary(requirePresent(amount, DALYTRAN_AMT),
                DALYTRAN_AMT_LENGTH, DALYTRAN_AMT);
    }

    /**
     * Compares an encoded byte count with the layout's declared record width.
     *
     * <p>Stated at this class's own boundary rather than left entirely to the reader, so that the
     * failure names both this layout's identifiers, the expected width and the actual encoded byte
     * length - which is the whole failure contract for a malformed record.</p>
     *
     * <p>The condition has no legacy antecedent: sequential and indexed records alike are fixed length
     * by construction, so a record of the wrong length cannot arise on the mainframe at all. It is a
     * defect in the calling Java code, and {@link IllegalArgumentException} is its idiomatic signal;
     * none of the module's six own exception types models it (decisions D-08 and D-11). Input is never
     * silently padded, never silently truncated, never partially mapped and never returned as
     * {@code null}.</p>
     *
     * @param actualEncodedLength the supplied image's length in encoded bytes, never a character count
     * @throws IllegalArgumentException if the length is not exactly {@link #RECORD_LENGTH}
     */
    private static void requireRecordWidth(int actualEncodedLength) {
        if (actualEncodedLength == RECORD_LENGTH) {
            return;
        }
        StringBuilder message = new StringBuilder()
                .append(ARTEFACT)
                .append(" record image must be exactly ")
                .append(RECORD_LENGTH)
                .append(" encoded bytes in US-ASCII, but the supplied image is ")
                .append(actualEncodedLength)
                .append(" encoded bytes; a fixed-width record is never padded or truncated to fit");
        if (actualEncodedLength == RECORD_LENGTH + 1) {
            message.append(" (an overshoot of exactly one byte is usually an unstripped 0x0A line ")
                    .append("terminator: the sample file's stride is 351 because the terminator ")
                    .append("separates records and is never record content)");
        }
        throw new IllegalArgumentException(message.toString());
    }

    /**
     * Requires a mapped property to be present, naming the legacy field if it is not.
     *
     * <p>Generic so that the twelve character properties and the one amount property share a single
     * null contract, rather than the amount raising a differently typed failure from its twelve
     * neighbours. The message names the field and never the value.</p>
     *
     * @param value     the property value to check
     * @param fieldName the legacy field name to name in the diagnostic
     * @param <T>       the property's type, {@code String} for twelve fields and {@link BigDecimal}
     *                  for the amount
     * @return {@code value}, once confirmed present
     * @throws NullPointerException if {@code value} is {@code null}
     */
    private static <T> T requirePresent(T value, String fieldName) {
        return Objects.requireNonNull(value, ARTEFACT + " field '" + fieldName
                + "' must be present: a fixed-width record has no concept of an absent field, and"
                + " emitting spaces for one would produce a record of the right width and the wrong"
                + " content");
    }

    /**
     * Verifies that a field begins exactly where the fields before it end.
     *
     * <p>Called only from the static initialiser, over compile-time constants.
     *
     * @param fieldName      the legacy field name to name in the diagnostic
     * @param declaredOffset the offset this class declares for the field
     * @param computedOffset the offset implied by the sum of the preceding field lengths
     * @throws IllegalStateException if the two disagree, which means one declared width is wrong and
     *                               every field after it is displaced
     */
    private static void requireContiguous(String fieldName, int declaredOffset, int computedOffset) {
        if (declaredOffset != computedOffset) {
            throw new IllegalStateException(ARTEFACT + " layout is inconsistent: field '" + fieldName
                    + "' declares offset " + declaredOffset + " but the widths of the fields before"
                    + " it sum to " + computedOffset);
        }
    }

    /**
     * Verifies one of the layout's two published width sums.
     *
     * <p>Called only from the static initialiser, over compile-time constants.
     *
     * @param subject  the sum being checked, for the diagnostic
     * @param declared the width this class publishes
     * @param computed the width implied by adding up its parts
     * @throws IllegalStateException if the two disagree
     */
    private static void requireSum(String subject, int declared, int computed) {
        if (declared != computed) {
            throw new IllegalStateException(ARTEFACT + " layout is inconsistent: the " + subject
                    + " is published as " + declared + " encoded bytes but its parts sum to "
                    + computed);
        }
    }
}
