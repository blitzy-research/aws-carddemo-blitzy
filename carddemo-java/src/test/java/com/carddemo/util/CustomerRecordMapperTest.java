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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.carddemo.domain.Customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Unit test for {@link CustomerRecordMapper}, the hand-written mapper for the <strong>500-byte</strong>
 * {@code CUSTOMER-RECORD} layout - the widest record in the estate and the only one the legacy tree
 * declares under two different field spellings.
 *
 * <h2>This test is an independent oracle, not a mirror of the mapper</h2>
 * Every expectation below is written out by hand from the copybook geometry and from the first record
 * of the shipped ASCII customer fixture. No expectation is computed by calling the class under test,
 * no output is snapshotted, and no value is proved solely by a round trip. The geometry is restated
 * here as this test's own constants with <em>absolute</em> values, so a systematic shift of the
 * layout cannot pass: the mapper derives each offset from the preceding one, and a test that checked
 * only that relationship would agree with a layout that had slid wholesale.
 *
 * <p>The oracle is itself checked. Every hand-written field expectation is asserted to measure the
 * width its picture clause declares, and the assembled record image is asserted to measure exactly
 * 500 encoded bytes with a 168-byte space filler, so a mistyped expectation fails at once rather
 * than quietly weakening a later assertion.
 *
 * <h2>The layout under test</h2>
 * Offsets are zero-based byte offsets into the record image. Field names and picture clauses are
 * layout metadata cited from {@code [app/cpy/CVCUS01Y.cpy]} and {@code [app/cpy/CUSTREC.cpy]}; no
 * legacy source text is reproduced.
 *
 * <pre>
 *  #  COBOL field                PIC      Offset  Length  Java property
 * --  -------------------------  -------  ------  ------  ----------------
 *  1  CUST-ID                    9(09)         0       9  custId (the identifier and the record key)
 *  2  CUST-FIRST-NAME            X(25)         9      25  firstName
 *  3  CUST-MIDDLE-NAME           X(25)        34      25  middleName
 *  4  CUST-LAST-NAME             X(25)        59      25  lastName
 *  5  CUST-ADDR-LINE-1           X(50)        84      50  addrLine1
 *  6  CUST-ADDR-LINE-2           X(50)       134      50  addrLine2
 *  7  CUST-ADDR-LINE-3           X(50)       184      50  addrLine3
 *  8  CUST-ADDR-STATE-CD         X(02)       234       2  addrStateCd
 *  9  CUST-ADDR-COUNTRY-CD       X(03)       236       3  addrCountryCd
 * 10  CUST-ADDR-ZIP              X(10)       239      10  addrZip
 * 11  CUST-PHONE-NUM-1           X(15)       249      15  phoneNum1
 * 12  CUST-PHONE-NUM-2           X(15)       264      15  phoneNum2
 * 13  CUST-SSN                   9(09)       279       9  custSsn
 * 14  CUST-GOVT-ISSUED-ID        X(20)       288      20  govtIssuedId
 * 15  CUST-DOB-YYYY-MM-DD or                 308      10  custDob
 *     CUST-DOB-YYYYMMDD          X(10)
 * 16  CUST-EFT-ACCOUNT-ID        X(10)       318      10  eftAccountId
 * 17  CUST-PRI-CARD-HOLDER-IND   X(01)       328       1  priCardHolderInd
 * 18  CUST-FICO-CREDIT-SCORE     9(03)       329       3  ficoCreditScore
 *  -  FILLER                     X(168)      332     168  not mapped
 * </pre>
 *
 * <p>The mapped prefix ends at 332 and {@code 332 + 168 = 500}. The cluster definition in
 * {@code [app/jcl/CUSTFILE.jcl]} corroborates the geometry from a second, independent direction with
 * {@code RECORDSIZE(500 500)} and {@code KEYS(9 0)} - a nine-byte key at offset zero, which is why
 * the identifier is the legacy business key itself and no surrogate key exists.
 *
 * <h2>No zoned-decimal field exists in this layout</h2>
 * All eighteen properties are text. Not one field is signed, not one carries an implied decimal
 * point and not one is monetary, so {@link ZonedDecimalCodec} is deliberately never invoked from this
 * test and no decimal type appears in it. That absence is asserted by construction rather than merely
 * observed: an expectation stated as a decimal value could not be compared against a text property.
 *
 * <h2>Three traps converge on this record</h2>
 * <ol>
 *   <li><strong>The national identifier is the schema's only intentionally nullable column</strong>,
 *       and every seeded row stores no value in it. Composing a record from such a customer must
 *       therefore succeed and must place nine spaces, so the absent-value path is the common path
 *       rather than an edge case. It is asserted directly, in both directions, and symmetrically
 *       against the government-issued identifier the mapper treats the same way.</li>
 *   <li><strong>The credit score is not range-validated, and must not be.</strong> The first fixture
 *       record carries a score below the 300-to-850 range the account-update screen enforces, and 21
 *       of the 50 seeded rows do, the lowest being {@code 001}. Any range, digit or pattern check
 *       here would make the reference data unloadable, so this test asserts that low, mid and high
 *       values all pass through unchanged and that none of them raises.</li>
 *   <li><strong>Two copybook spellings, one mapper, one entity.</strong> The date-of-birth field is
 *       spelled with hyphens in one copybook and without them in the other; the bytes are identical,
 *       so no variant flag, variant enum, second entity, second mapper or overload pair exists, and
 *       none is exercised here. The statement-generation program includes the unhyphenated copybook,
 *       so both spellings are live at once. The spelling of the <em>name</em> says nothing about the
 *       format of the <em>value</em>: the seeded value is hyphenated.</li>
 * </ol>
 *
 * <h2>The regulated boundary this test crosses</h2>
 * The mapper holds no key and performs no cryptography. It slices the two regulated identifiers
 * exactly as they appear and hands each slice to a caller-supplied sealing operation, because the
 * entity refuses to store either one as cleartext; on the way out it hands the stored envelope to a
 * caller-supplied revealing operation. This test supplies its own deterministic, key-free stand-in
 * for that pair, so what is exercised is the mapper's seam and not a cipher, and the test needs
 * neither randomness nor a clock. No regulated value is ever written into an assertion description,
 * a display name, an exception message or a comment example anywhere in this file.
 *
 * <h2>Scope</h2>
 * This is a pure unit test: no container, no Spring context, no persistence unit, no network and no
 * file access. The record image it maps is assembled from constants in this class, which is what
 * makes it an independent oracle rather than a comparison of the mapper against a file it also
 * shipped with. The complementary traversal of all fifty fixture records lives in the sibling
 * coverage test for this mapper, which reads the fixture from the test resources.
 *
 * <p><strong>Provenance.</strong> Legacy estate at checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Provenance is recorded here as a header
 * fact; it is not asserted against any member of the class under test.
 */
@DisplayName("CustomerRecordMapper - the 500-byte CUSTOMER-RECORD layout")
class CustomerRecordMapperTest {

    // -----------------------------------------------------------------------------------------
    // The hand-written geometry oracle. Absolute values, restated from the copybook rather than
    // read back from the class under test, so that this test can disagree with it.
    // -----------------------------------------------------------------------------------------

    /** Record width the copybook headers and the cluster definition both declare: 500 bytes. */
    private static final int EXPECTED_RECORD_WIDTH = 500;

    /** Sum of the eighteen mapped field widths: 332 bytes. */
    private static final int EXPECTED_MAPPED_DATA_WIDTH = 332;

    /** Offset at which the trailing filler begins, immediately after the mapped prefix: 332. */
    private static final int EXPECTED_FILLER_OFFSET = 332;

    /** Width of the trailing filler: 168 bytes. */
    private static final int EXPECTED_FILLER_LENGTH = 168;

    /** Offset of {@code CUST-ID}: 0. Also the record key's offset, matching {@code KEYS(9 0)}. */
    private static final int EXPECTED_CUST_ID_OFFSET = 0;

    /** Width of {@code CUST-ID}: 9. Also the record key's length. */
    private static final int EXPECTED_CUST_ID_LENGTH = 9;

    /** Offset of {@code CUST-FIRST-NAME}: 9. */
    private static final int EXPECTED_FIRST_NAME_OFFSET = 9;

    /** Width of {@code CUST-FIRST-NAME}: 25. */
    private static final int EXPECTED_FIRST_NAME_LENGTH = 25;

    /** Offset of {@code CUST-MIDDLE-NAME}: 34. */
    private static final int EXPECTED_MIDDLE_NAME_OFFSET = 34;

    /** Width of {@code CUST-MIDDLE-NAME}: 25. */
    private static final int EXPECTED_MIDDLE_NAME_LENGTH = 25;

    /** Offset of {@code CUST-LAST-NAME}: 59. */
    private static final int EXPECTED_LAST_NAME_OFFSET = 59;

    /** Width of {@code CUST-LAST-NAME}: 25. */
    private static final int EXPECTED_LAST_NAME_LENGTH = 25;

    /** Offset of {@code CUST-ADDR-LINE-1}: 84. */
    private static final int EXPECTED_ADDR_LINE_1_OFFSET = 84;

    /** Width of {@code CUST-ADDR-LINE-1}: 50. */
    private static final int EXPECTED_ADDR_LINE_1_LENGTH = 50;

    /** Offset of {@code CUST-ADDR-LINE-2}: 134. */
    private static final int EXPECTED_ADDR_LINE_2_OFFSET = 134;

    /** Width of {@code CUST-ADDR-LINE-2}: 50. */
    private static final int EXPECTED_ADDR_LINE_2_LENGTH = 50;

    /** Offset of {@code CUST-ADDR-LINE-3}: 184. */
    private static final int EXPECTED_ADDR_LINE_3_OFFSET = 184;

    /** Width of {@code CUST-ADDR-LINE-3}: 50. */
    private static final int EXPECTED_ADDR_LINE_3_LENGTH = 50;

    /** Offset of {@code CUST-ADDR-STATE-CD}: 234. */
    private static final int EXPECTED_ADDR_STATE_CD_OFFSET = 234;

    /** Width of {@code CUST-ADDR-STATE-CD}: 2. */
    private static final int EXPECTED_ADDR_STATE_CD_LENGTH = 2;

    /** Offset of {@code CUST-ADDR-COUNTRY-CD}: 236. */
    private static final int EXPECTED_ADDR_COUNTRY_CD_OFFSET = 236;

    /** Width of {@code CUST-ADDR-COUNTRY-CD}: 3. */
    private static final int EXPECTED_ADDR_COUNTRY_CD_LENGTH = 3;

    /** Offset of {@code CUST-ADDR-ZIP}: 239. */
    private static final int EXPECTED_ADDR_ZIP_OFFSET = 239;

    /** Width of {@code CUST-ADDR-ZIP}: 10. */
    private static final int EXPECTED_ADDR_ZIP_LENGTH = 10;

    /** Offset of {@code CUST-PHONE-NUM-1}: 249. */
    private static final int EXPECTED_PHONE_NUM_1_OFFSET = 249;

    /** Width of {@code CUST-PHONE-NUM-1}: 15. */
    private static final int EXPECTED_PHONE_NUM_1_LENGTH = 15;

    /** Offset of {@code CUST-PHONE-NUM-2}: 264. */
    private static final int EXPECTED_PHONE_NUM_2_OFFSET = 264;

    /** Width of {@code CUST-PHONE-NUM-2}: 15. */
    private static final int EXPECTED_PHONE_NUM_2_LENGTH = 15;

    /** Offset of {@code CUST-SSN}: 279. Nine record bytes, whatever the column behind them holds. */
    private static final int EXPECTED_CUST_SSN_OFFSET = 279;

    /** Width of {@code CUST-SSN}: 9. */
    private static final int EXPECTED_CUST_SSN_LENGTH = 9;

    /** Offset of {@code CUST-GOVT-ISSUED-ID}: 288. */
    private static final int EXPECTED_GOVT_ISSUED_ID_OFFSET = 288;

    /** Width of {@code CUST-GOVT-ISSUED-ID}: 20. */
    private static final int EXPECTED_GOVT_ISSUED_ID_LENGTH = 20;

    /** Offset of the date of birth: 308. One window for both copybook spellings. */
    private static final int EXPECTED_CUST_DOB_OFFSET = 308;

    /** Width of the date of birth: 10. */
    private static final int EXPECTED_CUST_DOB_LENGTH = 10;

    /** Offset of {@code CUST-EFT-ACCOUNT-ID}: 318. */
    private static final int EXPECTED_EFT_ACCOUNT_ID_OFFSET = 318;

    /** Width of {@code CUST-EFT-ACCOUNT-ID}: 10. */
    private static final int EXPECTED_EFT_ACCOUNT_ID_LENGTH = 10;

    /** Offset of {@code CUST-PRI-CARD-HOLDER-IND}: 328. */
    private static final int EXPECTED_PRI_CARD_HOLDER_IND_OFFSET = 328;

    /** Width of {@code CUST-PRI-CARD-HOLDER-IND}: 1. */
    private static final int EXPECTED_PRI_CARD_HOLDER_IND_LENGTH = 1;

    /** Offset of {@code CUST-FICO-CREDIT-SCORE}: 329. */
    private static final int EXPECTED_FICO_CREDIT_SCORE_OFFSET = 329;

    /** Width of {@code CUST-FICO-CREDIT-SCORE}: 3. */
    private static final int EXPECTED_FICO_CREDIT_SCORE_LENGTH = 3;

    /** The artefact label every diagnostic for this layout is expected to carry. */
    private static final String EXPECTED_ARTEFACT = "CUSTOMER-RECORD (CVCUS01Y/CUSTREC)";

    /** The date-of-birth field name as the hyphenated copybook spells it. */
    private static final String EXPECTED_DOB_FIELD_HYPHENATED = "CUST-DOB-YYYY-MM-DD";

    /** The date-of-birth field name as the unhyphenated copybook spells it. */
    private static final String EXPECTED_DOB_FIELD_UNHYPHENATED = "CUST-DOB-YYYYMMDD";

    /** The single space this layout pads and fills with. */
    private static final String SPACE = " ";

    // -----------------------------------------------------------------------------------------
    // The hand-written value oracle: the first record of the shipped ASCII customer fixture,
    // read off by hand at the offsets above. Each field is written as its unpadded content plus
    // the declared width, so the padding is explicit and reviewable rather than a run of trailing
    // whitespace in a source literal; the oracle self-check below proves each one measures its
    // declared width. Nothing here is produced by the class under test.
    // -----------------------------------------------------------------------------------------

    /** {@code CUST-ID} of the first fixture record. The leading zeros are record bytes. */
    private static final String ROW_0_CUST_ID = "000000001";

    /** Unpadded content of {@code CUST-FIRST-NAME}, before the record's space padding. */
    private static final String ROW_0_FIRST_NAME_CONTENT = "Immanuel";

    /** {@code CUST-FIRST-NAME} exactly as the record carries it, padded to its declared width. */
    private static final String ROW_0_FIRST_NAME =
            spacePadded(ROW_0_FIRST_NAME_CONTENT, EXPECTED_FIRST_NAME_LENGTH);

    /** Unpadded content of {@code CUST-MIDDLE-NAME}. */
    private static final String ROW_0_MIDDLE_NAME_CONTENT = "Madeline";

    /** {@code CUST-MIDDLE-NAME} exactly as the record carries it. */
    private static final String ROW_0_MIDDLE_NAME =
            spacePadded(ROW_0_MIDDLE_NAME_CONTENT, EXPECTED_MIDDLE_NAME_LENGTH);

    /** Unpadded content of {@code CUST-LAST-NAME}. */
    private static final String ROW_0_LAST_NAME_CONTENT = "Kessler";

    /** {@code CUST-LAST-NAME} exactly as the record carries it. */
    private static final String ROW_0_LAST_NAME =
            spacePadded(ROW_0_LAST_NAME_CONTENT, EXPECTED_LAST_NAME_LENGTH);

    /** Unpadded content of {@code CUST-ADDR-LINE-1}. */
    private static final String ROW_0_ADDR_LINE_1_CONTENT = "618 Deshaun Route";

    /** {@code CUST-ADDR-LINE-1} exactly as the record carries it. */
    private static final String ROW_0_ADDR_LINE_1 =
            spacePadded(ROW_0_ADDR_LINE_1_CONTENT, EXPECTED_ADDR_LINE_1_LENGTH);

    /** Unpadded content of {@code CUST-ADDR-LINE-2}, read at offset 134 of the fixture record. */
    private static final String ROW_0_ADDR_LINE_2_CONTENT = "Apt. 802";

    /** {@code CUST-ADDR-LINE-2} exactly as the record carries it. */
    private static final String ROW_0_ADDR_LINE_2 =
            spacePadded(ROW_0_ADDR_LINE_2_CONTENT, EXPECTED_ADDR_LINE_2_LENGTH);

    /** Unpadded content of {@code CUST-ADDR-LINE-3}, read at offset 184 of the fixture record. */
    private static final String ROW_0_ADDR_LINE_3_CONTENT = "Altenwerthshire";

    /** {@code CUST-ADDR-LINE-3} exactly as the record carries it. */
    private static final String ROW_0_ADDR_LINE_3 =
            spacePadded(ROW_0_ADDR_LINE_3_CONTENT, EXPECTED_ADDR_LINE_3_LENGTH);

    /** {@code CUST-ADDR-STATE-CD}, which fills its two bytes exactly. */
    private static final String ROW_0_ADDR_STATE_CD = "NC";

    /** {@code CUST-ADDR-COUNTRY-CD}, which fills its three bytes exactly. */
    private static final String ROW_0_ADDR_COUNTRY_CD = "USA";

    /** Unpadded content of {@code CUST-ADDR-ZIP}: five digits in a ten-byte field. */
    private static final String ROW_0_ADDR_ZIP_CONTENT = "12546";

    /** {@code CUST-ADDR-ZIP} exactly as the record carries it, with five trailing spaces. */
    private static final String ROW_0_ADDR_ZIP =
            spacePadded(ROW_0_ADDR_ZIP_CONTENT, EXPECTED_ADDR_ZIP_LENGTH);

    /** Unpadded content of {@code CUST-PHONE-NUM-1}: thirteen characters in a fifteen-byte field. */
    private static final String ROW_0_PHONE_NUM_1_CONTENT = "(908)119-8310";

    /** {@code CUST-PHONE-NUM-1} exactly as the record carries it, with two trailing spaces. */
    private static final String ROW_0_PHONE_NUM_1 =
            spacePadded(ROW_0_PHONE_NUM_1_CONTENT, EXPECTED_PHONE_NUM_1_LENGTH);

    /** Unpadded content of {@code CUST-PHONE-NUM-2}. */
    private static final String ROW_0_PHONE_NUM_2_CONTENT = "(373)693-8684";

    /** {@code CUST-PHONE-NUM-2} exactly as the record carries it, with two trailing spaces. */
    private static final String ROW_0_PHONE_NUM_2 =
            spacePadded(ROW_0_PHONE_NUM_2_CONTENT, EXPECTED_PHONE_NUM_2_LENGTH);

    /**
     * The nine bytes the fixture record carries in the national-identifier window.
     *
     * <p>Held under a neutral name and used only as an expected value and as record content. It is
     * never written into an assertion description, a display name, an exception message or an
     * example, which is the same withholding rule the mapper and the entity apply to their own
     * diagnostics. Where a synthetic stand-in suffices, {@link #SYNTHETIC_NATIONAL_IDENTIFIER} is
     * used instead.
     */
    private static final String ROW_0_REGULATED_NINE_BYTES = "020973888";

    /** {@code CUST-GOVT-ISSUED-ID}, which fills its twenty bytes exactly, leading zeros included. */
    private static final String ROW_0_GOVT_ISSUED_ID = "00000000000049368437";

    /**
     * The date of birth the fixture record carries.
     *
     * <p>Hyphenated ISO form, even though one of the two copybooks spells the <em>field name</em>
     * without hyphens. The two spellings denote the same ten bytes at the same offset, so the name
     * carries no information about the format of the value and the mapper has one code path for both.
     */
    private static final String ROW_0_CUST_DOB = "1961-06-08";

    /** {@code CUST-EFT-ACCOUNT-ID}, whose leading zeros are record bytes. */
    private static final String ROW_0_EFT_ACCOUNT_ID = "0053581756";

    /** {@code CUST-PRI-CARD-HOLDER-IND}, a single character. */
    private static final String ROW_0_PRI_CARD_HOLDER_IND = "Y";

    /**
     * {@code CUST-FICO-CREDIT-SCORE} of the first fixture record: below the 300-to-850 range the
     * account-update screen enforces, and accepted here for exactly that reason.
     */
    private static final String ROW_0_FICO_CREDIT_SCORE = "274";

    /** The trailing filler the fixture record carries: 168 spaces. */
    private static final String ROW_0_FILLER = SPACE.repeat(EXPECTED_FILLER_LENGTH);

    /**
     * The mapped data prefix of the first fixture record: the eighteen fields in record order, and
     * nothing else. This is the exact bound of a round-trip comparison for this layout.
     */
    private static final String ROW_0_MAPPED_PREFIX =
            ROW_0_CUST_ID
                    + ROW_0_FIRST_NAME
                    + ROW_0_MIDDLE_NAME
                    + ROW_0_LAST_NAME
                    + ROW_0_ADDR_LINE_1
                    + ROW_0_ADDR_LINE_2
                    + ROW_0_ADDR_LINE_3
                    + ROW_0_ADDR_STATE_CD
                    + ROW_0_ADDR_COUNTRY_CD
                    + ROW_0_ADDR_ZIP
                    + ROW_0_PHONE_NUM_1
                    + ROW_0_PHONE_NUM_2
                    + ROW_0_REGULATED_NINE_BYTES
                    + ROW_0_GOVT_ISSUED_ID
                    + ROW_0_CUST_DOB
                    + ROW_0_EFT_ACCOUNT_ID
                    + ROW_0_PRI_CARD_HOLDER_IND
                    + ROW_0_FICO_CREDIT_SCORE;

    /** The complete 500-byte image of the first fixture record: mapped prefix then filler. */
    private static final String ROW_0_IMAGE = ROW_0_MAPPED_PREFIX + ROW_0_FILLER;

    // -----------------------------------------------------------------------------------------
    // Synthetic values, used where the fixture's own regulated content would add nothing.
    // -----------------------------------------------------------------------------------------

    /**
     * A synthetic nine-digit stand-in for a national identifier: nine nines, which is not a value
     * the fixture carries and cannot be mistaken for one. Used wherever a non-absent regulated
     * value is needed but its exact content is irrelevant.
     */
    private static final String SYNTHETIC_NATIONAL_IDENTIFIER = "999999999";

    /** The lowest credit score the seeded reference data carries, and far below the screen range. */
    private static final String FICO_LOWEST_SEEDED = "001";

    /** A credit score at the top of the range the account-update screen enforces. */
    private static final String FICO_TOP_OF_SCREEN_RANGE = "850";

    // -----------------------------------------------------------------------------------------
    // The regulated-field seam. A deterministic, key-free stand-in for the module's protected-value
    // envelope, written here rather than borrowed, so this test exercises the mapper's seam and not
    // a cipher: no randomness, no clock and no key material are involved. The entity admits only a
    // value carrying the envelope marker whose decoded body is long enough for an initialisation
    // vector and an authentication tag, so the stand-in reproduces that shape and nothing more.
    // -----------------------------------------------------------------------------------------

    /** The envelope marker the entity requires on every protected value it accepts. */
    private static final String ENVELOPE_MARKER = "ENC1:";

    /** The smallest decoded body the entity accepts inside an envelope. */
    private static final int ENVELOPE_MINIMUM_BODY_BYTES = 28;

    /**
     * Pads a value on the right with spaces to the width its picture clause declares.
     *
     * <p>Written as content plus declared width so that a hand-written expectation states its
     * padding explicitly instead of relying on trailing whitespace surviving in a source literal.
     * The declared width is this test's own constant, so the expectation stays independent of the
     * class under test.
     *
     * <p>The padding is computed from the content's <em>encoded</em> length rather than its character
     * count, so the arithmetic is the record's own arithmetic even here, where the two happen to
     * agree because every fixture value is US-ASCII.
     *
     * @param content the unpadded content, never {@code null} and never wider than {@code width}
     * @param width   the declared field width in encoded bytes
     * @return the value exactly as the record carries it
     */
    private static String spacePadded(String content, int width) {
        return content + SPACE.repeat(width - encodedLength(content));
    }

    /**
     * Seals a cleartext value into the envelope shape the entity requires.
     *
     * <p>No cryptography: the body is the value's US-ASCII bytes, zero-padded up to the entity's
     * minimum decoded length so that a short value such as a nine-byte identifier still satisfies
     * the envelope contract, then Base64-encoded behind the marker. {@link #reveal(String)} is its
     * exact inverse.
     *
     * @param cleartext the value exactly as the record image carries it
     * @return the sealed envelope
     */
    private static String seal(String cleartext) {
        byte[] raw = cleartext.getBytes(StandardCharsets.US_ASCII);
        byte[] body = new byte[Math.max(ENVELOPE_MINIMUM_BODY_BYTES, raw.length)];
        System.arraycopy(raw, 0, body, 0, raw.length);
        return ENVELOPE_MARKER + Base64.getEncoder().encodeToString(body);
    }

    /**
     * Recovers the cleartext a sealed envelope carries, undoing {@link #seal(String)} exactly.
     *
     * <p>The zero padding the sealing step added is stripped again. A record field is never
     * zero-terminated - an absent field is spaces, not zero bytes - so stripping trailing zero bytes
     * cannot remove content, including the all-space case.
     *
     * @param envelope the sealed envelope
     * @return the cleartext as it appeared in the record image
     */
    private static String reveal(String envelope) {
        byte[] body = Base64.getDecoder()
                .decode(envelope.substring(ENVELOPE_MARKER.length()));
        int end = body.length;
        while (end > 0 && body[end - 1] == 0) {
            end--;
        }
        return new String(body, 0, end, StandardCharsets.US_ASCII);
    }

    /**
     * The sealing operation the parsing tests supply to the mapper.
     *
     * @return a sealing operation, never {@code null}
     */
    private static UnaryOperator<String> sealer() {
        return CustomerRecordMapperTest::seal;
    }

    /**
     * The revealing operation the composing tests supply to the mapper, the exact inverse of
     * {@link #sealer()}.
     *
     * @return a revealing operation, never {@code null}
     */
    private static UnaryOperator<String> revealer() {
        return CustomerRecordMapperTest::reveal;
    }

    /**
     * Returns the US-ASCII encoded bytes of a value.
     *
     * <p>Every width and content comparison in this class goes through encoded bytes rather than
     * character counts, because a character count is not a width authority for a fixed-width record:
     * one character outside US-ASCII would occupy more than one byte and shift the geometry while a
     * character count still looked correct. The charset is named explicitly at every boundary.
     *
     * @param value the value to encode
     * @return the encoded bytes
     */
    private static byte[] asciiBytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Returns the number of bytes a value occupies when encoded as US-ASCII.
     *
     * @param value the value to measure
     * @return the encoded byte length
     */
    private static int encodedLength(String value) {
        return asciiBytes(value).length;
    }

    /**
     * Builds the customer the first fixture record describes, through the entity's eighteen-argument
     * constructor, with both regulated identifiers sealed.
     *
     * <p>The argument list is written in record order, so the constructor's parameter order is
     * exercised as part of every composing assertion: a transposed pair would place the wrong bytes
     * at the wrong offset and the record comparison would fail.
     *
     * @return a customer equivalent to the first fixture record
     */
    private static Customer fixtureRowZeroCustomer() {
        return new Customer(
                ROW_0_CUST_ID,
                ROW_0_FIRST_NAME,
                ROW_0_MIDDLE_NAME,
                ROW_0_LAST_NAME,
                ROW_0_ADDR_LINE_1,
                ROW_0_ADDR_LINE_2,
                ROW_0_ADDR_LINE_3,
                ROW_0_ADDR_STATE_CD,
                ROW_0_ADDR_COUNTRY_CD,
                ROW_0_ADDR_ZIP,
                ROW_0_PHONE_NUM_1,
                ROW_0_PHONE_NUM_2,
                seal(ROW_0_REGULATED_NINE_BYTES),
                seal(ROW_0_GOVT_ISSUED_ID),
                ROW_0_CUST_DOB,
                ROW_0_EFT_ACCOUNT_ID,
                ROW_0_PRI_CARD_HOLDER_IND,
                ROW_0_FICO_CREDIT_SCORE);
    }

    /**
     * Replaces one window of a hand-written record image with a value of exactly the same width.
     *
     * <p>Used to vary a single field without disturbing the geometry: the replacement is required to
     * be the field's declared width, so the resulting image is still 500 bytes and any failure is
     * attributable to the field rather than to a shifted layout.
     *
     * @param image       the record image to vary
     * @param offset      zero-based offset of the window to replace
     * @param replacement the replacement value, exactly as wide as the window
     * @return the varied image
     */
    private static String withField(String image, int offset, String replacement) {
        return image.substring(0, offset)
                + replacement
                + image.substring(offset + encodedLength(replacement));
    }

    /**
     * Returns one window of a record image as encoded bytes.
     *
     * <p>Byte-level rather than character-level, so a window assertion measures what the record
     * actually carries and cannot be satisfied by a value that merely reads the same.
     *
     * @param image  the record image
     * @param offset zero-based byte offset of the window
     * @param length byte width of the window
     * @return the window's bytes
     */
    private static byte[] window(String image, int offset, int length) {
        byte[] all = asciiBytes(image);
        byte[] slice = new byte[length];
        System.arraycopy(all, offset, slice, 0, length);
        return slice;
    }

    /**
     * Asserts that a customer carries exactly the eighteen values the first fixture record holds.
     *
     * <p>Shared by the parsing assertions and the entry-point agreement assertion so that all three
     * entry points are held to the same eighteen hand-written expectations rather than merely to each
     * other. The two regulated attributes are checked through the inverse of the seam, whose exactness
     * is established independently in the oracle group; the raw slices themselves are asserted
     * directly in the regulated-boundary group.
     *
     * @param customer the customer to check
     */
    private static void assertFixtureRowZeroProperties(Customer customer) {
        assertThat(customer.getCustId())
                .as("CUST-ID at offset 0").isEqualTo(ROW_0_CUST_ID);
        assertThat(customer.getFirstName())
                .as("CUST-FIRST-NAME at offset 9").isEqualTo(ROW_0_FIRST_NAME);
        assertThat(customer.getMiddleName())
                .as("CUST-MIDDLE-NAME at offset 34").isEqualTo(ROW_0_MIDDLE_NAME);
        assertThat(customer.getLastName())
                .as("CUST-LAST-NAME at offset 59").isEqualTo(ROW_0_LAST_NAME);
        assertThat(customer.getAddrLine1())
                .as("CUST-ADDR-LINE-1 at offset 84").isEqualTo(ROW_0_ADDR_LINE_1);
        assertThat(customer.getAddrLine2())
                .as("CUST-ADDR-LINE-2 at offset 134").isEqualTo(ROW_0_ADDR_LINE_2);
        assertThat(customer.getAddrLine3())
                .as("CUST-ADDR-LINE-3 at offset 184").isEqualTo(ROW_0_ADDR_LINE_3);
        assertThat(customer.getAddrStateCd())
                .as("CUST-ADDR-STATE-CD at offset 234").isEqualTo(ROW_0_ADDR_STATE_CD);
        assertThat(customer.getAddrCountryCd())
                .as("CUST-ADDR-COUNTRY-CD at offset 236").isEqualTo(ROW_0_ADDR_COUNTRY_CD);
        assertThat(customer.getAddrZip())
                .as("CUST-ADDR-ZIP at offset 239").isEqualTo(ROW_0_ADDR_ZIP);
        assertThat(customer.getPhoneNum1())
                .as("CUST-PHONE-NUM-1 at offset 249").isEqualTo(ROW_0_PHONE_NUM_1);
        assertThat(customer.getPhoneNum2())
                .as("CUST-PHONE-NUM-2 at offset 264").isEqualTo(ROW_0_PHONE_NUM_2);
        assertThat(reveal(customer.getCustSsn()))
                .as("CUST-SSN at offset 279").isEqualTo(ROW_0_REGULATED_NINE_BYTES);
        assertThat(reveal(customer.getGovtIssuedId()))
                .as("CUST-GOVT-ISSUED-ID at offset 288").isEqualTo(ROW_0_GOVT_ISSUED_ID);
        assertThat(customer.getCustDob())
                .as("the date of birth at offset 308").isEqualTo(ROW_0_CUST_DOB);
        assertThat(customer.getEftAccountId())
                .as("CUST-EFT-ACCOUNT-ID at offset 318").isEqualTo(ROW_0_EFT_ACCOUNT_ID);
        assertThat(customer.getPriCardHolderInd())
                .as("CUST-PRI-CARD-HOLDER-IND at offset 328").isEqualTo(ROW_0_PRI_CARD_HOLDER_IND);
        assertThat(customer.getFicoCreditScore())
                .as("CUST-FICO-CREDIT-SCORE at offset 329").isEqualTo(ROW_0_FICO_CREDIT_SCORE);
    }

    /**
     * Asserts that a diagnostic carries no record content.
     *
     * <p>A message is one of the surfaces most likely to be logged or returned, so the mapper names
     * the artefact, the field, the offset and the widths - all layout facts - and never a value. The
     * regulated windows are included in the check for the same reason they are withheld everywhere
     * else in this class, and the unregulated content is included because a leaked name or address is
     * a leak too.
     *
     * @param message the diagnostic to inspect
     */
    private static void assertNoRecordContent(String message) {
        assertThat(message)
                .as("a diagnostic must name layout facts and never record content")
                .doesNotContain(ROW_0_REGULATED_NINE_BYTES)
                .doesNotContain(ROW_0_GOVT_ISSUED_ID)
                .doesNotContain(ROW_0_CUST_ID)
                .doesNotContain(ROW_0_FIRST_NAME_CONTENT)
                .doesNotContain(ROW_0_MIDDLE_NAME_CONTENT)
                .doesNotContain(ROW_0_LAST_NAME_CONTENT)
                .doesNotContain(ROW_0_ADDR_LINE_1_CONTENT)
                .doesNotContain(ROW_0_ADDR_LINE_2_CONTENT)
                .doesNotContain(ROW_0_ADDR_LINE_3_CONTENT)
                .doesNotContain(ROW_0_ADDR_ZIP_CONTENT)
                .doesNotContain(ROW_0_PHONE_NUM_1_CONTENT)
                .doesNotContain(ROW_0_PHONE_NUM_2_CONTENT)
                .doesNotContain(ROW_0_CUST_DOB)
                .doesNotContain(ROW_0_EFT_ACCOUNT_ID);
    }

    /**
     * A sealing or revealing operation that records every value handed to it and then delegates.
     *
     * <p>This is how the test observes what the mapper passes across the regulated seam without
     * inspecting the mapper: the recorded values are the slices the mapper took. The captured values
     * stay in memory and are never written to any output; nothing in this class logs or prints.
     */
    private static final class RecordingSeam implements UnaryOperator<String> {

        /** Every value handed to this operation, in the order the mapper handed it over. */
        private final List<String> observed = new ArrayList<>();

        /** The operation this one delegates to once it has recorded the value. */
        private final UnaryOperator<String> delegate;

        /**
         * Creates a recording operation over a delegate.
         *
         * @param delegate the operation to delegate to, never {@code null}
         */
        private RecordingSeam(UnaryOperator<String> delegate) {
            this.delegate = delegate;
        }

        @Override
        public String apply(String value) {
            observed.add(value);
            return delegate.apply(value);
        }

        /**
         * The values handed over so far, in order.
         *
         * @return an unmodifiable snapshot
         */
        private List<String> observed() {
            return List.copyOf(observed);
        }
    }

    @Nested
    @DisplayName("the hand-written oracle this test judges the mapper by")
    class TheHandWrittenOracle {

        @Test
        @DisplayName("every hand-written field expectation measures the width its picture clause "
                + "declares, so a mistyped expectation cannot silently weaken a later assertion")
        void everyExpectationMeasuresItsDeclaredWidth() {
            assertThat(encodedLength(ROW_0_CUST_ID))
                    .as("CUST-ID expectation width")
                    .isEqualTo(EXPECTED_CUST_ID_LENGTH);
            assertThat(encodedLength(ROW_0_FIRST_NAME))
                    .as("CUST-FIRST-NAME expectation width")
                    .isEqualTo(EXPECTED_FIRST_NAME_LENGTH);
            assertThat(encodedLength(ROW_0_MIDDLE_NAME))
                    .as("CUST-MIDDLE-NAME expectation width")
                    .isEqualTo(EXPECTED_MIDDLE_NAME_LENGTH);
            assertThat(encodedLength(ROW_0_LAST_NAME))
                    .as("CUST-LAST-NAME expectation width")
                    .isEqualTo(EXPECTED_LAST_NAME_LENGTH);
            assertThat(encodedLength(ROW_0_ADDR_LINE_1))
                    .as("CUST-ADDR-LINE-1 expectation width")
                    .isEqualTo(EXPECTED_ADDR_LINE_1_LENGTH);
            assertThat(encodedLength(ROW_0_ADDR_LINE_2))
                    .as("CUST-ADDR-LINE-2 expectation width")
                    .isEqualTo(EXPECTED_ADDR_LINE_2_LENGTH);
            assertThat(encodedLength(ROW_0_ADDR_LINE_3))
                    .as("CUST-ADDR-LINE-3 expectation width")
                    .isEqualTo(EXPECTED_ADDR_LINE_3_LENGTH);
            assertThat(encodedLength(ROW_0_ADDR_STATE_CD))
                    .as("CUST-ADDR-STATE-CD expectation width")
                    .isEqualTo(EXPECTED_ADDR_STATE_CD_LENGTH);
            assertThat(encodedLength(ROW_0_ADDR_COUNTRY_CD))
                    .as("CUST-ADDR-COUNTRY-CD expectation width")
                    .isEqualTo(EXPECTED_ADDR_COUNTRY_CD_LENGTH);
            assertThat(encodedLength(ROW_0_ADDR_ZIP))
                    .as("CUST-ADDR-ZIP expectation width")
                    .isEqualTo(EXPECTED_ADDR_ZIP_LENGTH);
            assertThat(encodedLength(ROW_0_PHONE_NUM_1))
                    .as("CUST-PHONE-NUM-1 expectation width")
                    .isEqualTo(EXPECTED_PHONE_NUM_1_LENGTH);
            assertThat(encodedLength(ROW_0_PHONE_NUM_2))
                    .as("CUST-PHONE-NUM-2 expectation width")
                    .isEqualTo(EXPECTED_PHONE_NUM_2_LENGTH);
            assertThat(encodedLength(ROW_0_REGULATED_NINE_BYTES))
                    .as("CUST-SSN expectation width")
                    .isEqualTo(EXPECTED_CUST_SSN_LENGTH);
            assertThat(encodedLength(ROW_0_GOVT_ISSUED_ID))
                    .as("CUST-GOVT-ISSUED-ID expectation width")
                    .isEqualTo(EXPECTED_GOVT_ISSUED_ID_LENGTH);
            assertThat(encodedLength(ROW_0_CUST_DOB))
                    .as("date-of-birth expectation width")
                    .isEqualTo(EXPECTED_CUST_DOB_LENGTH);
            assertThat(encodedLength(ROW_0_EFT_ACCOUNT_ID))
                    .as("CUST-EFT-ACCOUNT-ID expectation width")
                    .isEqualTo(EXPECTED_EFT_ACCOUNT_ID_LENGTH);
            assertThat(encodedLength(ROW_0_PRI_CARD_HOLDER_IND))
                    .as("CUST-PRI-CARD-HOLDER-IND expectation width")
                    .isEqualTo(EXPECTED_PRI_CARD_HOLDER_IND_LENGTH);
            assertThat(encodedLength(ROW_0_FICO_CREDIT_SCORE))
                    .as("CUST-FICO-CREDIT-SCORE expectation width")
                    .isEqualTo(EXPECTED_FICO_CREDIT_SCORE_LENGTH);
        }

        @Test
        @DisplayName("the assembled record image is exactly 500 encoded bytes, its mapped prefix is "
                + "332 and its filler is 168 spaces")
        void theAssembledImageIsFiveHundredEncodedBytes() {
            assertThat(encodedLength(ROW_0_MAPPED_PREFIX))
                    .as("the eighteen field expectations must sum to the mapped data width")
                    .isEqualTo(EXPECTED_MAPPED_DATA_WIDTH);
            assertThat(encodedLength(ROW_0_FILLER))
                    .as("the filler expectation must measure the declared filler width")
                    .isEqualTo(EXPECTED_FILLER_LENGTH);
            assertThat(encodedLength(ROW_0_IMAGE))
                    .as("mapped prefix plus filler must be the declared record width")
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
            assertThat(asciiBytes(ROW_0_FILLER))
                    .as("every filler byte must be an ASCII space")
                    .containsOnly((byte) 0x20);
        }

        @Test
        @DisplayName("the sealing and revealing stand-in is an exact inverse pair, including for an "
                + "all-space field, so a round trip proves the mapper rather than the seam")
        void theSeamIsAnExactInversePair() {
            assertThat(reveal(seal(SYNTHETIC_NATIONAL_IDENTIFIER)))
                    .as("a nine-character value must survive the seam unchanged")
                    .isEqualTo(SYNTHETIC_NATIONAL_IDENTIFIER);
            assertThat(reveal(seal(ROW_0_GOVT_ISSUED_ID)))
                    .as("a twenty-character value must survive the seam unchanged")
                    .isEqualTo(ROW_0_GOVT_ISSUED_ID);

            String allSpaces = SPACE.repeat(EXPECTED_GOVT_ISSUED_ID_LENGTH);
            assertThat(reveal(seal(allSpaces)))
                    .as("an all-space field must survive the seam with its width intact")
                    .isEqualTo(allSpaces);
            assertThat(seal(SYNTHETIC_NATIONAL_IDENTIFIER))
                    .as("the sealed form must carry the envelope marker the entity requires")
                    .startsWith(ENVELOPE_MARKER);
        }
    }

    @Nested
    @DisplayName("the declared geometry")
    class DeclaredGeometry {

        @Test
        @DisplayName("all eighteen field offsets are the absolute offsets the copybook fixes, so a "
                + "layout that had slid wholesale could not pass")
        void allEighteenOffsetsAreTheAbsoluteCopybookOffsets() {
            assertThat(CustomerRecordMapper.CUST_ID_OFFSET)
                    .as("CUST-ID offset").isEqualTo(EXPECTED_CUST_ID_OFFSET);
            assertThat(CustomerRecordMapper.FIRST_NAME_OFFSET)
                    .as("CUST-FIRST-NAME offset").isEqualTo(EXPECTED_FIRST_NAME_OFFSET);
            assertThat(CustomerRecordMapper.MIDDLE_NAME_OFFSET)
                    .as("CUST-MIDDLE-NAME offset").isEqualTo(EXPECTED_MIDDLE_NAME_OFFSET);
            assertThat(CustomerRecordMapper.LAST_NAME_OFFSET)
                    .as("CUST-LAST-NAME offset").isEqualTo(EXPECTED_LAST_NAME_OFFSET);
            assertThat(CustomerRecordMapper.ADDR_LINE_1_OFFSET)
                    .as("CUST-ADDR-LINE-1 offset").isEqualTo(EXPECTED_ADDR_LINE_1_OFFSET);
            assertThat(CustomerRecordMapper.ADDR_LINE_2_OFFSET)
                    .as("CUST-ADDR-LINE-2 offset").isEqualTo(EXPECTED_ADDR_LINE_2_OFFSET);
            assertThat(CustomerRecordMapper.ADDR_LINE_3_OFFSET)
                    .as("CUST-ADDR-LINE-3 offset").isEqualTo(EXPECTED_ADDR_LINE_3_OFFSET);
            assertThat(CustomerRecordMapper.ADDR_STATE_CD_OFFSET)
                    .as("CUST-ADDR-STATE-CD offset").isEqualTo(EXPECTED_ADDR_STATE_CD_OFFSET);
            assertThat(CustomerRecordMapper.ADDR_COUNTRY_CD_OFFSET)
                    .as("CUST-ADDR-COUNTRY-CD offset").isEqualTo(EXPECTED_ADDR_COUNTRY_CD_OFFSET);
            assertThat(CustomerRecordMapper.ADDR_ZIP_OFFSET)
                    .as("CUST-ADDR-ZIP offset").isEqualTo(EXPECTED_ADDR_ZIP_OFFSET);
            assertThat(CustomerRecordMapper.PHONE_NUM_1_OFFSET)
                    .as("CUST-PHONE-NUM-1 offset").isEqualTo(EXPECTED_PHONE_NUM_1_OFFSET);
            assertThat(CustomerRecordMapper.PHONE_NUM_2_OFFSET)
                    .as("CUST-PHONE-NUM-2 offset").isEqualTo(EXPECTED_PHONE_NUM_2_OFFSET);
            assertThat(CustomerRecordMapper.CUST_SSN_OFFSET)
                    .as("CUST-SSN offset").isEqualTo(EXPECTED_CUST_SSN_OFFSET);
            assertThat(CustomerRecordMapper.GOVT_ISSUED_ID_OFFSET)
                    .as("CUST-GOVT-ISSUED-ID offset").isEqualTo(EXPECTED_GOVT_ISSUED_ID_OFFSET);
            assertThat(CustomerRecordMapper.CUST_DOB_OFFSET)
                    .as("date-of-birth offset").isEqualTo(EXPECTED_CUST_DOB_OFFSET);
            assertThat(CustomerRecordMapper.EFT_ACCOUNT_ID_OFFSET)
                    .as("CUST-EFT-ACCOUNT-ID offset").isEqualTo(EXPECTED_EFT_ACCOUNT_ID_OFFSET);
            assertThat(CustomerRecordMapper.PRI_CARD_HOLDER_IND_OFFSET)
                    .as("CUST-PRI-CARD-HOLDER-IND offset")
                    .isEqualTo(EXPECTED_PRI_CARD_HOLDER_IND_OFFSET);
            assertThat(CustomerRecordMapper.FICO_CREDIT_SCORE_OFFSET)
                    .as("CUST-FICO-CREDIT-SCORE offset")
                    .isEqualTo(EXPECTED_FICO_CREDIT_SCORE_OFFSET);
        }

        @Test
        @DisplayName("all eighteen field widths are the widths the picture clauses declare")
        void allEighteenWidthsAreTheDeclaredWidths() {
            assertThat(CustomerRecordMapper.CUST_ID_LENGTH)
                    .as("CUST-ID width").isEqualTo(EXPECTED_CUST_ID_LENGTH);
            assertThat(CustomerRecordMapper.FIRST_NAME_LENGTH)
                    .as("CUST-FIRST-NAME width").isEqualTo(EXPECTED_FIRST_NAME_LENGTH);
            assertThat(CustomerRecordMapper.MIDDLE_NAME_LENGTH)
                    .as("CUST-MIDDLE-NAME width").isEqualTo(EXPECTED_MIDDLE_NAME_LENGTH);
            assertThat(CustomerRecordMapper.LAST_NAME_LENGTH)
                    .as("CUST-LAST-NAME width").isEqualTo(EXPECTED_LAST_NAME_LENGTH);
            assertThat(CustomerRecordMapper.ADDR_LINE_1_LENGTH)
                    .as("CUST-ADDR-LINE-1 width").isEqualTo(EXPECTED_ADDR_LINE_1_LENGTH);
            assertThat(CustomerRecordMapper.ADDR_LINE_2_LENGTH)
                    .as("CUST-ADDR-LINE-2 width").isEqualTo(EXPECTED_ADDR_LINE_2_LENGTH);
            assertThat(CustomerRecordMapper.ADDR_LINE_3_LENGTH)
                    .as("CUST-ADDR-LINE-3 width").isEqualTo(EXPECTED_ADDR_LINE_3_LENGTH);
            assertThat(CustomerRecordMapper.ADDR_STATE_CD_LENGTH)
                    .as("CUST-ADDR-STATE-CD width").isEqualTo(EXPECTED_ADDR_STATE_CD_LENGTH);
            assertThat(CustomerRecordMapper.ADDR_COUNTRY_CD_LENGTH)
                    .as("CUST-ADDR-COUNTRY-CD width").isEqualTo(EXPECTED_ADDR_COUNTRY_CD_LENGTH);
            assertThat(CustomerRecordMapper.ADDR_ZIP_LENGTH)
                    .as("CUST-ADDR-ZIP width").isEqualTo(EXPECTED_ADDR_ZIP_LENGTH);
            assertThat(CustomerRecordMapper.PHONE_NUM_1_LENGTH)
                    .as("CUST-PHONE-NUM-1 width").isEqualTo(EXPECTED_PHONE_NUM_1_LENGTH);
            assertThat(CustomerRecordMapper.PHONE_NUM_2_LENGTH)
                    .as("CUST-PHONE-NUM-2 width").isEqualTo(EXPECTED_PHONE_NUM_2_LENGTH);
            assertThat(CustomerRecordMapper.CUST_SSN_LENGTH)
                    .as("CUST-SSN width").isEqualTo(EXPECTED_CUST_SSN_LENGTH);
            assertThat(CustomerRecordMapper.GOVT_ISSUED_ID_LENGTH)
                    .as("CUST-GOVT-ISSUED-ID width").isEqualTo(EXPECTED_GOVT_ISSUED_ID_LENGTH);
            assertThat(CustomerRecordMapper.CUST_DOB_LENGTH)
                    .as("date-of-birth width").isEqualTo(EXPECTED_CUST_DOB_LENGTH);
            assertThat(CustomerRecordMapper.EFT_ACCOUNT_ID_LENGTH)
                    .as("CUST-EFT-ACCOUNT-ID width").isEqualTo(EXPECTED_EFT_ACCOUNT_ID_LENGTH);
            assertThat(CustomerRecordMapper.PRI_CARD_HOLDER_IND_LENGTH)
                    .as("CUST-PRI-CARD-HOLDER-IND width")
                    .isEqualTo(EXPECTED_PRI_CARD_HOLDER_IND_LENGTH);
            assertThat(CustomerRecordMapper.FICO_CREDIT_SCORE_LENGTH)
                    .as("CUST-FICO-CREDIT-SCORE width")
                    .isEqualTo(EXPECTED_FICO_CREDIT_SCORE_LENGTH);
        }

        @Test
        @DisplayName("the record width is 500, the mapped prefix ends at 332, and 332 + 168 = 500")
        void theWidthArithmeticCloses() {
            assertThat(CustomerRecordMapper.RECORD_WIDTH)
                    .as("the declared record width")
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
            assertThat(CustomerRecordMapper.MAPPED_DATA_WIDTH)
                    .as("the mapped prefix must end where the eighteen fields end")
                    .isEqualTo(EXPECTED_MAPPED_DATA_WIDTH);
            assertThat(CustomerRecordMapper.FILLER_LENGTH)
                    .as("the filler width")
                    .isEqualTo(EXPECTED_FILLER_LENGTH);
            assertThat(CustomerRecordMapper.MAPPED_DATA_WIDTH + CustomerRecordMapper.FILLER_LENGTH)
                    .as("mapped prefix plus filler must close the record exactly")
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the eighteen declared widths sum to the mapped prefix width, so the prefix "
                + "tiles the record with no gap and no overlap")
        void theEighteenWidthsSumToTheMappedPrefix() {
            int sum = EXPECTED_CUST_ID_LENGTH
                    + EXPECTED_FIRST_NAME_LENGTH
                    + EXPECTED_MIDDLE_NAME_LENGTH
                    + EXPECTED_LAST_NAME_LENGTH
                    + EXPECTED_ADDR_LINE_1_LENGTH
                    + EXPECTED_ADDR_LINE_2_LENGTH
                    + EXPECTED_ADDR_LINE_3_LENGTH
                    + EXPECTED_ADDR_STATE_CD_LENGTH
                    + EXPECTED_ADDR_COUNTRY_CD_LENGTH
                    + EXPECTED_ADDR_ZIP_LENGTH
                    + EXPECTED_PHONE_NUM_1_LENGTH
                    + EXPECTED_PHONE_NUM_2_LENGTH
                    + EXPECTED_CUST_SSN_LENGTH
                    + EXPECTED_GOVT_ISSUED_ID_LENGTH
                    + EXPECTED_CUST_DOB_LENGTH
                    + EXPECTED_EFT_ACCOUNT_ID_LENGTH
                    + EXPECTED_PRI_CARD_HOLDER_IND_LENGTH
                    + EXPECTED_FICO_CREDIT_SCORE_LENGTH;

            assertThat(sum)
                    .as("the eighteen widths must sum to 332")
                    .isEqualTo(EXPECTED_MAPPED_DATA_WIDTH);
            assertThat(CustomerRecordMapper.MAPPED_DATA_WIDTH)
                    .as("the mapper must agree with that sum")
                    .isEqualTo(sum);
        }

        @Test
        @DisplayName("the key is nine bytes at offset zero, corroborating the cluster's KEYS(9 0), "
                + "and it is the leading substring of the record so no surrogate key exists")
        void theKeyIsNineBytesAtOffsetZero() {
            assertThat(CustomerRecordMapper.CUST_ID_OFFSET)
                    .as("the cluster declares the key at offset zero")
                    .isZero();
            assertThat(CustomerRecordMapper.CUST_ID_LENGTH)
                    .as("the cluster declares a nine-byte key")
                    .isEqualTo(EXPECTED_CUST_ID_LENGTH);

            Customer parsed = CustomerRecordMapper.fromRecord(ROW_0_IMAGE, sealer());

            // The identifier the entity carries is the record's own leading nine bytes, which is what
            // makes the business key the primary key. A surrogate key would sever exactly this
            // correspondence between the record image and the row.
            assertThat(parsed.getCustId())
                    .as("the identifier must be the record's own leading nine bytes")
                    .isEqualTo(ROW_0_CUST_ID);
            assertThat(ROW_0_IMAGE.startsWith(parsed.getCustId()))
                    .as("the key must be the leading substring of the record image")
                    .isTrue();
        }

        @Test
        @DisplayName("the filler begins where the mapped prefix ends and closes the record")
        void theFillerClosesTheRecord() {
            assertThat(CustomerRecordMapper.FILLER_OFFSET)
                    .as("the filler must begin immediately after the mapped prefix")
                    .isEqualTo(EXPECTED_FILLER_OFFSET);
            assertThat(CustomerRecordMapper.FILLER_OFFSET)
                    .as("the filler offset and the mapped prefix width are the same boundary")
                    .isEqualTo(CustomerRecordMapper.MAPPED_DATA_WIDTH);
            assertThat(CustomerRecordMapper.FILLER_OFFSET + CustomerRecordMapper.FILLER_LENGTH)
                    .as("the filler must run to the end of the record and no further")
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("both copybook spellings of the date-of-birth field are published and denote "
                + "one single ten-byte window, so no variant branch exists")
        void bothDateOfBirthSpellingsDenoteOneWindow() {
            assertThat(CustomerRecordMapper.CUST_DOB_FIELD_CVCUS01Y)
                    .as("the hyphenated spelling")
                    .isEqualTo(EXPECTED_DOB_FIELD_HYPHENATED);
            assertThat(CustomerRecordMapper.CUST_DOB_FIELD_CUSTREC)
                    .as("the unhyphenated spelling, which the statement program's copybook uses")
                    .isEqualTo(EXPECTED_DOB_FIELD_UNHYPHENATED);
            assertThat(CustomerRecordMapper.CUST_DOB_FIELD_CVCUS01Y)
                    .as("the two spellings are genuinely different names")
                    .isNotEqualTo(CustomerRecordMapper.CUST_DOB_FIELD_CUSTREC);

            // One offset and one width serve both names: the bytes do not differ, so the code must
            // not branch on the spelling. There is exactly one offset constant and one width
            // constant for this field, and the assertions below are the whole of the geometry.
            assertThat(CustomerRecordMapper.CUST_DOB_OFFSET)
                    .as("one offset for both spellings")
                    .isEqualTo(EXPECTED_CUST_DOB_OFFSET);
            assertThat(CustomerRecordMapper.CUST_DOB_LENGTH)
                    .as("one width for both spellings")
                    .isEqualTo(EXPECTED_CUST_DOB_LENGTH);
        }

        @Test
        @DisplayName("the artefact label names both copybooks, so a diagnostic sends a reader to "
                + "the right member whichever spelling they are reading against")
        void theArtefactLabelNamesBothCopybooks() {
            assertThat(CustomerRecordMapper.ARTEFACT)
                    .as("the artefact label")
                    .isEqualTo(EXPECTED_ARTEFACT);
        }
    }

    @Nested
    @DisplayName("parsing the first fixture record")
    class ParsingTheFirstFixtureRecord {

        @Test
        @DisplayName("all eighteen properties carry the values the record holds at their own offsets")
        void allEighteenPropertiesCarryTheRecordValues() {
            Customer parsed = CustomerRecordMapper.fromRecord(ROW_0_IMAGE, sealer());

            assertFixtureRowZeroProperties(parsed);
        }

        @Test
        @DisplayName("the five fields that merely look numeric keep their leading zeros, because "
                + "those zeros are record bytes and not an artefact of formatting")
        void theLookNumericFieldsKeepTheirLeadingZeros() {
            Customer parsed = CustomerRecordMapper.fromRecord(ROW_0_IMAGE, sealer());

            assertThat(parsed.getCustId())
                    .as("CUST-ID keeps its leading zeros")
                    .isEqualTo(ROW_0_CUST_ID)
                    .startsWith("0");
            assertThat(reveal(parsed.getCustSsn()))
                    .as("the national identifier keeps its leading zero")
                    .isEqualTo(ROW_0_REGULATED_NINE_BYTES)
                    .startsWith("0");
            assertThat(reveal(parsed.getGovtIssuedId()))
                    .as("CUST-GOVT-ISSUED-ID keeps its leading zeros")
                    .isEqualTo(ROW_0_GOVT_ISSUED_ID)
                    .startsWith("0");
            assertThat(parsed.getEftAccountId())
                    .as("CUST-EFT-ACCOUNT-ID keeps its leading zeros")
                    .isEqualTo(ROW_0_EFT_ACCOUNT_ID)
                    .startsWith("0");

            // The credit score of this record has no leading zero, so the lowest seeded score is
            // varied in instead: 001 is the value that proves the field is text and not a number.
            Customer lowest = CustomerRecordMapper.fromRecord(
                    withField(ROW_0_IMAGE, EXPECTED_FICO_CREDIT_SCORE_OFFSET, FICO_LOWEST_SEEDED),
                    sealer());
            assertThat(lowest.getFicoCreditScore())
                    .as("CUST-FICO-CREDIT-SCORE keeps its leading zeros")
                    .isEqualTo(FICO_LOWEST_SEEDED)
                    .startsWith("0");
        }

        @Test
        @DisplayName("padded fields arrive at their full declared width, trailing spaces intact, "
                + "and are therefore not equal to their unpadded content")
        void paddedFieldsArriveUntrimmed() {
            Customer parsed = CustomerRecordMapper.fromRecord(ROW_0_IMAGE, sealer());

            assertThat(parsed.getAddrZip())
                    .as("CUST-ADDR-ZIP keeps its five trailing spaces")
                    .isEqualTo(ROW_0_ADDR_ZIP)
                    .isNotEqualTo(ROW_0_ADDR_ZIP_CONTENT);
            assertThat(encodedLength(parsed.getAddrZip()))
                    .as("CUST-ADDR-ZIP occupies its full declared width")
                    .isEqualTo(EXPECTED_ADDR_ZIP_LENGTH);

            assertThat(parsed.getPhoneNum1())
                    .as("CUST-PHONE-NUM-1 keeps its two trailing spaces")
                    .isEqualTo(ROW_0_PHONE_NUM_1)
                    .isNotEqualTo(ROW_0_PHONE_NUM_1_CONTENT);
            assertThat(encodedLength(parsed.getPhoneNum1()))
                    .as("CUST-PHONE-NUM-1 occupies exactly fifteen bytes")
                    .isEqualTo(EXPECTED_PHONE_NUM_1_LENGTH);

            assertThat(parsed.getPhoneNum2())
                    .as("CUST-PHONE-NUM-2 keeps its two trailing spaces")
                    .isEqualTo(ROW_0_PHONE_NUM_2)
                    .isNotEqualTo(ROW_0_PHONE_NUM_2_CONTENT);
            assertThat(encodedLength(parsed.getPhoneNum2()))
                    .as("CUST-PHONE-NUM-2 occupies exactly fifteen bytes")
                    .isEqualTo(EXPECTED_PHONE_NUM_2_LENGTH);

            assertThat(parsed.getFirstName())
                    .as("CUST-FIRST-NAME arrives at its full width")
                    .isEqualTo(ROW_0_FIRST_NAME)
                    .isNotEqualTo(ROW_0_FIRST_NAME_CONTENT);
            assertThat(parsed.getMiddleName())
                    .as("CUST-MIDDLE-NAME arrives at its full width")
                    .isEqualTo(ROW_0_MIDDLE_NAME)
                    .isNotEqualTo(ROW_0_MIDDLE_NAME_CONTENT);
            assertThat(parsed.getLastName())
                    .as("CUST-LAST-NAME arrives at its full width")
                    .isEqualTo(ROW_0_LAST_NAME)
                    .isNotEqualTo(ROW_0_LAST_NAME_CONTENT);
            assertThat(parsed.getAddrLine1())
                    .as("CUST-ADDR-LINE-1 arrives at its full width")
                    .isEqualTo(ROW_0_ADDR_LINE_1)
                    .isNotEqualTo(ROW_0_ADDR_LINE_1_CONTENT);
            assertThat(parsed.getAddrLine2())
                    .as("CUST-ADDR-LINE-2 arrives at its full width")
                    .isEqualTo(ROW_0_ADDR_LINE_2)
                    .isNotEqualTo(ROW_0_ADDR_LINE_2_CONTENT);
            assertThat(parsed.getAddrLine3())
                    .as("CUST-ADDR-LINE-3 arrives at its full width")
                    .isEqualTo(ROW_0_ADDR_LINE_3)
                    .isNotEqualTo(ROW_0_ADDR_LINE_3_CONTENT);

            assertThat(encodedLength(parsed.getFirstName()))
                    .as("CUST-FIRST-NAME occupies its full declared width")
                    .isEqualTo(EXPECTED_FIRST_NAME_LENGTH);
            assertThat(encodedLength(parsed.getMiddleName()))
                    .as("CUST-MIDDLE-NAME occupies its full declared width")
                    .isEqualTo(EXPECTED_MIDDLE_NAME_LENGTH);
            assertThat(encodedLength(parsed.getLastName()))
                    .as("CUST-LAST-NAME occupies its full declared width")
                    .isEqualTo(EXPECTED_LAST_NAME_LENGTH);
            assertThat(encodedLength(parsed.getAddrLine1()))
                    .as("CUST-ADDR-LINE-1 occupies its full declared width")
                    .isEqualTo(EXPECTED_ADDR_LINE_1_LENGTH);
            assertThat(encodedLength(parsed.getAddrLine2()))
                    .as("CUST-ADDR-LINE-2 occupies its full declared width")
                    .isEqualTo(EXPECTED_ADDR_LINE_2_LENGTH);
            assertThat(encodedLength(parsed.getAddrLine3()))
                    .as("CUST-ADDR-LINE-3 occupies its full declared width")
                    .isEqualTo(EXPECTED_ADDR_LINE_3_LENGTH);
        }

        @Test
        @DisplayName("the date of birth is the hyphenated value the record carries, even though one "
                + "copybook spells the field name without hyphens")
        void theDateOfBirthIsTheHyphenatedRecordValue() {
            // The two copybooks name this field differently - hyphenated in CVCUS01Y, unhyphenated in
            // CUSTREC, which the statement-generation program includes - but they describe the same
            // ten bytes at the same offset. One mapper and one entity attribute serve both, and the
            // spelling of the name says nothing about the format of the value: this record's value is
            // hyphenated. Anomaly 17 records a further difference between the two members, that the
            // opening field declarations of CUSTREC are indented with literal tab characters; it is
            // source formatting only and moves no byte of the record.
            Customer parsed = CustomerRecordMapper.fromRecord(ROW_0_IMAGE, sealer());

            assertThat(parsed.getCustDob())
                    .as("the date of birth arrives exactly as the record carries it")
                    .isEqualTo(ROW_0_CUST_DOB);
            assertThat(encodedLength(parsed.getCustDob()))
                    .as("the date of birth occupies its ten declared bytes")
                    .isEqualTo(EXPECTED_CUST_DOB_LENGTH);
        }

        @Test
        @DisplayName("an unhyphenated date value in the same window passes through the same single "
                + "code path unchanged, because no variant flag and no overload pair exist")
        void anUnhyphenatedDateValuePassesThroughTheSameCodePath() {
            // Nothing selects a spelling: the mapper is invoked exactly as it is for the hyphenated
            // value, with no extra argument, no variant enum and no second entry point, and the ten
            // bytes come back verbatim. The absence of such an API is what this test demonstrates -
            // there is no other call to make - and it is demonstrated without reflection.
            String unhyphenated = "19610608  ";
            String varied = withField(ROW_0_IMAGE, EXPECTED_CUST_DOB_OFFSET, unhyphenated);

            Customer parsed = CustomerRecordMapper.fromRecord(varied, sealer());

            assertThat(parsed.getCustDob())
                    .as("an unhyphenated value is carried verbatim, exactly as a hyphenated one is")
                    .isEqualTo(unhyphenated);
            assertThat(encodedLength(parsed.getCustDob()))
                    .as("the window is ten bytes wide whichever form the value takes")
                    .isEqualTo(EXPECTED_CUST_DOB_LENGTH);
        }

        @Test
        @DisplayName("the string, byte-array and byte-range entry points agree on every property")
        void allThreeEntryPointsAgree() {
            byte[] recordBytes = asciiBytes(ROW_0_IMAGE);

            // A whole-file buffer: two records on a 501-byte stride, being the 500-byte record plus a
            // 0x0A separator. Addressing the second record at one stride proves the range entry point
            // leaves the terminator behind rather than mapping it as content.
            String twoRecordFile = ROW_0_IMAGE + "\n" + ROW_0_IMAGE + "\n";
            byte[] fileBuffer = asciiBytes(twoRecordFile);
            int stride = EXPECTED_RECORD_WIDTH + 1;

            Customer fromString = CustomerRecordMapper.fromRecord(ROW_0_IMAGE, sealer());
            Customer fromBytes = CustomerRecordMapper.fromRecord(recordBytes, sealer());
            Customer fromFirstRange = CustomerRecordMapper.fromRecord(fileBuffer, 0, sealer());
            Customer fromSecondRange = CustomerRecordMapper.fromRecord(fileBuffer, stride, sealer());

            assertFixtureRowZeroProperties(fromString);
            assertFixtureRowZeroProperties(fromBytes);
            assertFixtureRowZeroProperties(fromFirstRange);
            assertFixtureRowZeroProperties(fromSecondRange);

            assertThat(fromBytes)
                    .as("the byte-array entry point yields the same row identity")
                    .isEqualTo(fromString);
            assertThat(fromFirstRange)
                    .as("the byte-range entry point yields the same row identity")
                    .isEqualTo(fromString);
            assertThat(fromSecondRange)
                    .as("a record addressed at one stride yields the same row identity")
                    .isEqualTo(fromString);
            assertThat(fromBytes.hashCode())
                    .as("identity agreement extends to the hash, which is keyed on the identifier")
                    .isEqualTo(fromString.hashCode());
        }
    }

    @Nested
    @DisplayName("the regulated-identity boundary")
    class TheRegulatedIdentityBoundary {

        @Test
        @DisplayName("the nine bytes of the national-identifier window are handed across the seam "
                + "exactly as the record carries them, with no masking and no transformation")
        void theNationalIdentifierIsSlicedExactlyAsItAppears() {
            RecordingSeam seam = new RecordingSeam(sealer());

            CustomerRecordMapper.fromRecord(ROW_0_IMAGE, seam);

            // The recorded values are the slices the mapper took. The first is the nine-byte window at
            // offset 279 and the second is the twenty-byte window at offset 288, in record order.
            assertThat(seam.observed())
                    .as("the two regulated windows are handed over in record order, unaltered")
                    .containsExactly(ROW_0_REGULATED_NINE_BYTES, ROW_0_GOVT_ISSUED_ID);
            assertThat(encodedLength(seam.observed().get(0)))
                    .as("the national-identifier slice is exactly nine encoded bytes")
                    .isEqualTo(EXPECTED_CUST_SSN_LENGTH);
            assertThat(encodedLength(seam.observed().get(1)))
                    .as("the government-issued-identifier slice is exactly twenty encoded bytes")
                    .isEqualTo(EXPECTED_GOVT_ISSUED_ID_LENGTH);
        }

        @Test
        @DisplayName("the sealing operation is applied exactly twice for one record - once per "
                + "regulated window - so no other field is put through it")
        void theSealingOperationIsAppliedExactlyTwice() {
            RecordingSeam seam = new RecordingSeam(sealer());

            CustomerRecordMapper.fromRecord(ROW_0_IMAGE, seam);

            assertThat(seam.observed())
                    .as("exactly the two regulated windows cross the seam")
                    .hasSize(2);
            assertThat(seam.observed())
                    .as("no unregulated field is handed to the sealing operation")
                    .doesNotContain(ROW_0_CUST_ID, ROW_0_FIRST_NAME, ROW_0_ADDR_ZIP, ROW_0_CUST_DOB,
                            ROW_0_EFT_ACCOUNT_ID, ROW_0_FICO_CREDIT_SCORE);
        }

        @Test
        @DisplayName("the entity stores the sealed envelope rather than the cleartext window, and "
                + "the envelope reveals back to the record's own bytes")
        void theEntityStoresAnEnvelopeThatRevealsToTheRecordBytes() {
            Customer parsed = CustomerRecordMapper.fromRecord(ROW_0_IMAGE, sealer());

            assertThat(parsed.getCustSsn())
                    .as("the national identifier is stored under the module's envelope marker")
                    .startsWith(ENVELOPE_MARKER);
            assertThat(parsed.getGovtIssuedId())
                    .as("the government-issued identifier is stored under the same marker")
                    .startsWith(ENVELOPE_MARKER);
            assertThat(reveal(parsed.getCustSsn()))
                    .as("revealing recovers the record's own nine bytes")
                    .isEqualTo(ROW_0_REGULATED_NINE_BYTES);
            assertThat(reveal(parsed.getGovtIssuedId()))
                    .as("revealing recovers the record's own twenty bytes")
                    .isEqualTo(ROW_0_GOVT_ISSUED_ID);
        }

        @Test
        @DisplayName("a sealing operation that returns nothing is rejected, and the diagnostic names "
                + "the artefact and the field and carries no field value")
        void aSealingOperationThatReturnsNothingIsRejected() {
            UnaryOperator<String> dropsEverything = value -> null;

            Throwable thrown = catchThrowable(
                    () -> CustomerRecordMapper.fromRecord(ROW_0_IMAGE, dropsEverything));

            assertThat(thrown)
                    .as("a dropped regulated value is a caller defect, not a mapping outcome")
                    .isExactlyInstanceOf(IllegalArgumentException.class);
            assertThat(thrown.getMessage())
                    .as("the diagnostic identifies the layout and the field it failed on")
                    .contains(EXPECTED_ARTEFACT)
                    .contains("CUST-SSN");
            assertNoRecordContent(thrown.getMessage());
        }

        @Test
        @DisplayName("a sealing operation that drops only the government-issued identifier is "
                + "rejected naming that field, so the two windows are checked independently")
        void aSealingOperationThatDropsOnlyTheGovernmentIdentifierIsRejected() {
            UnaryOperator<String> dropsTheWiderWindow = value ->
                    encodedLength(value) == EXPECTED_GOVT_ISSUED_ID_LENGTH ? null : seal(value);

            Throwable thrown = catchThrowable(
                    () -> CustomerRecordMapper.fromRecord(ROW_0_IMAGE, dropsTheWiderWindow));

            assertThat(thrown)
                    .as("the second regulated window is checked as strictly as the first")
                    .isExactlyInstanceOf(IllegalArgumentException.class);
            assertThat(thrown.getMessage())
                    .as("the diagnostic names the field that was dropped")
                    .contains("CUST-GOVT-ISSUED-ID");
            assertNoRecordContent(thrown.getMessage());
        }
    }

    @Nested
    @DisplayName("emitting a record")
    class EmittingARecord {

        @Test
        @DisplayName("a customer built through the eighteen-argument constructor renders the fixture "
                + "record byte for byte, which proves the argument order is the record order")
        void theEighteenArgumentConstructorRendersTheRecordByteForByte() {
            // Construction is positional and the argument list is written in record order, so a
            // transposed pair would place the wrong bytes at the wrong offset and this comparison
            // would fail. That is what makes this assertion the argument-order proof.
            String emitted = CustomerRecordMapper.toRecord(fixtureRowZeroCustomer(), revealer());

            assertThat(encodedLength(emitted))
                    .as("the emitted image measures exactly the declared record width")
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
            assertThat(asciiBytes(emitted))
                    .as("the emitted image equals the fixture record byte for byte")
                    .containsExactly(asciiBytes(ROW_0_IMAGE));
        }

        @Test
        @DisplayName("the mapped data prefix round-trips byte for byte over the range [0, 332)")
        void theMappedPrefixRoundTripsByteForByte() {
            Customer parsed = CustomerRecordMapper.fromRecord(ROW_0_IMAGE, sealer());

            String emitted = CustomerRecordMapper.toRecord(parsed, revealer());

            assertThat(window(emitted, 0, EXPECTED_MAPPED_DATA_WIDTH))
                    .as("the eighteen mapped fields are reproduced exactly")
                    .containsExactly(window(ROW_0_IMAGE, 0, EXPECTED_MAPPED_DATA_WIDTH));
            assertThat(window(emitted, 0, EXPECTED_MAPPED_DATA_WIDTH))
                    .as("the mapped prefix is the hand-written prefix, byte for byte")
                    .containsExactly(asciiBytes(ROW_0_MAPPED_PREFIX));
        }

        @Test
        @DisplayName("the whole 500-byte image round-trips, and its 168-byte filler is all spaces")
        void theWholeImageRoundTripsWithSpaceFiller() {
            Customer parsed = CustomerRecordMapper.fromRecord(ROW_0_IMAGE, sealer());

            String emitted = CustomerRecordMapper.toRecord(parsed, revealer());

            assertThat(encodedLength(emitted))
                    .as("the emitted image measures exactly 500 encoded bytes")
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
            assertThat(asciiBytes(emitted))
                    .as("the whole record is reproduced, filler included")
                    .containsExactly(asciiBytes(ROW_0_IMAGE));
            assertThat(window(emitted, EXPECTED_FILLER_OFFSET, EXPECTED_FILLER_LENGTH))
                    .as("every byte from 332 to the end of the record is an ASCII space")
                    .containsOnly((byte) 0x20);
        }

        @Test
        @DisplayName("the byte-emitting entry point agrees with the string-emitting one")
        void theByteEmittingEntryPointAgrees() {
            Customer customer = fixtureRowZeroCustomer();

            byte[] emittedBytes = CustomerRecordMapper.toRecordBytes(customer, revealer());
            String emittedString = CustomerRecordMapper.toRecord(customer, revealer());

            assertThat(emittedBytes)
                    .as("the byte entry point returns exactly the declared width")
                    .hasSize(EXPECTED_RECORD_WIDTH);
            assertThat(emittedBytes)
                    .as("the two entry points produce the same bytes")
                    .containsExactly(asciiBytes(emittedString));
            assertThat(emittedBytes)
                    .as("and both reproduce the fixture record")
                    .containsExactly(asciiBytes(ROW_0_IMAGE));
        }

        @Test
        @DisplayName("an absent national identifier is rendered as nine spaces, the image is still "
                + "500 bytes, and nothing is raised")
        void anAbsentNationalIdentifierIsRenderedAsNineSpaces() {
            // Every seeded row stores no national identifier, so this is the common path rather than
            // an edge case: raising here would break every round trip against the seeded database.
            Customer withoutNationalIdentifier = new Customer(
                    ROW_0_CUST_ID,
                    ROW_0_FIRST_NAME,
                    ROW_0_MIDDLE_NAME,
                    ROW_0_LAST_NAME,
                    ROW_0_ADDR_LINE_1,
                    ROW_0_ADDR_LINE_2,
                    ROW_0_ADDR_LINE_3,
                    ROW_0_ADDR_STATE_CD,
                    ROW_0_ADDR_COUNTRY_CD,
                    ROW_0_ADDR_ZIP,
                    ROW_0_PHONE_NUM_1,
                    ROW_0_PHONE_NUM_2,
                    null,
                    seal(ROW_0_GOVT_ISSUED_ID),
                    ROW_0_CUST_DOB,
                    ROW_0_EFT_ACCOUNT_ID,
                    ROW_0_PRI_CARD_HOLDER_IND,
                    ROW_0_FICO_CREDIT_SCORE);

            assertThatCode(() -> CustomerRecordMapper.toRecord(withoutNationalIdentifier, revealer()))
                    .as("an absent value in the schema's one nullable column is a legitimate state")
                    .doesNotThrowAnyException();

            String emitted = CustomerRecordMapper.toRecord(withoutNationalIdentifier, revealer());

            assertThat(encodedLength(emitted))
                    .as("the image is still exactly 500 encoded bytes")
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
            assertThat(window(emitted, EXPECTED_CUST_SSN_OFFSET, EXPECTED_CUST_SSN_LENGTH))
                    .as("the nine bytes at offset 279 are spaces, not zero-filled digits")
                    .containsOnly((byte) 0x20);
            assertThat(window(emitted, 0, EXPECTED_CUST_SSN_OFFSET))
                    .as("no other byte before the window moves")
                    .containsExactly(window(ROW_0_IMAGE, 0, EXPECTED_CUST_SSN_OFFSET));
            assertThat(window(emitted, EXPECTED_GOVT_ISSUED_ID_OFFSET,
                    EXPECTED_RECORD_WIDTH - EXPECTED_GOVT_ISSUED_ID_OFFSET))
                    .as("and no byte after it moves either")
                    .containsExactly(window(ROW_0_IMAGE, EXPECTED_GOVT_ISSUED_ID_OFFSET,
                            EXPECTED_RECORD_WIDTH - EXPECTED_GOVT_ISSUED_ID_OFFSET));
        }

        @Test
        @DisplayName("the revealing operation is not consulted for an absent identifier, because "
                + "there is no envelope to open")
        void theRevealingOperationIsNotConsultedForAnAbsentIdentifier() {
            Customer customer = fixtureRowZeroCustomer();
            customer.setCustSsn(null);
            RecordingSeam seam = new RecordingSeam(revealer());

            CustomerRecordMapper.toRecord(customer, seam);

            assertThat(seam.observed())
                    .as("only the identifier that is actually held crosses the seam")
                    .hasSize(1);
            assertThat(seam.observed())
                    .as("and it is the government-issued identifier's envelope")
                    .containsExactly(seal(ROW_0_GOVT_ISSUED_ID));
        }

        @Test
        @DisplayName("the revealing operation is consulted exactly once per identifier that is held")
        void theRevealingOperationIsConsultedOncePerHeldIdentifier() {
            RecordingSeam seam = new RecordingSeam(revealer());

            CustomerRecordMapper.toRecord(fixtureRowZeroCustomer(), seam);

            assertThat(seam.observed())
                    .as("two held identifiers means two crossings, in record order")
                    .containsExactly(seal(ROW_0_REGULATED_NINE_BYTES), seal(ROW_0_GOVT_ISSUED_ID));
        }

        @Test
        @DisplayName("an absent government-issued identifier is rendered as twenty spaces, "
                + "symmetrically with the national identifier")
        void anAbsentGovernmentIdentifierIsRenderedAsTwentySpaces() {
            Customer customer = fixtureRowZeroCustomer();
            customer.setGovtIssuedId(null);

            String emitted = CustomerRecordMapper.toRecord(customer, revealer());

            assertThat(encodedLength(emitted))
                    .as("the image is still exactly 500 encoded bytes")
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
            assertThat(window(emitted, EXPECTED_GOVT_ISSUED_ID_OFFSET,
                    EXPECTED_GOVT_ISSUED_ID_LENGTH))
                    .as("the twenty bytes at offset 288 are spaces, not zero-filled digits")
                    .containsOnly((byte) 0x20);
            assertThat(window(emitted, EXPECTED_CUST_SSN_OFFSET, EXPECTED_CUST_SSN_LENGTH))
                    .as("the national-identifier window is untouched and still holds its value")
                    .containsExactly(asciiBytes(ROW_0_REGULATED_NINE_BYTES));
        }

        @Test
        @DisplayName("a customer holding neither identifier - the shape every seeded row has - emits "
                + "both windows blank and a whole record everywhere else")
        void aCustomerHoldingNeitherIdentifierEmitsBothWindowsBlank() {
            Customer customer = fixtureRowZeroCustomer();
            customer.setCustSsn(null);
            customer.setGovtIssuedId(null);

            String emitted = CustomerRecordMapper.toRecord(customer, revealer());

            assertThat(encodedLength(emitted))
                    .as("the image is still exactly 500 encoded bytes")
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
            assertThat(window(emitted, EXPECTED_CUST_SSN_OFFSET,
                    EXPECTED_CUST_SSN_LENGTH + EXPECTED_GOVT_ISSUED_ID_LENGTH))
                    .as("both regulated windows are blank, twenty-nine spaces in a row")
                    .containsOnly((byte) 0x20);
            assertThat(window(emitted, 0, EXPECTED_CUST_SSN_OFFSET))
                    .as("everything before the two windows is the fixture record")
                    .containsExactly(window(ROW_0_IMAGE, 0, EXPECTED_CUST_SSN_OFFSET));
            assertThat(window(emitted, EXPECTED_CUST_DOB_OFFSET,
                    EXPECTED_RECORD_WIDTH - EXPECTED_CUST_DOB_OFFSET))
                    .as("everything after them is the fixture record")
                    .containsExactly(window(ROW_0_IMAGE, EXPECTED_CUST_DOB_OFFSET,
                            EXPECTED_RECORD_WIDTH - EXPECTED_CUST_DOB_OFFSET));
        }

        @Test
        @DisplayName("a revealing operation that returns nothing for an identifier that is held is "
                + "rejected, because a fixed-width field cannot be left unwritten")
        void aRevealingOperationThatReturnsNothingIsRejected() {
            // The absent-identifier path never consults the operation at all, so this is the only way
            // the operation can fail to produce the bytes the field needs: it was consulted, and it
            // returned nothing. Composing the record anyway would emit a syntactically valid image
            // whose regulated window was never written.
            UnaryOperator<String> dropsEverything = envelope -> null;

            Throwable thrown = catchThrowable(
                    () -> CustomerRecordMapper.toRecord(fixtureRowZeroCustomer(), dropsEverything));

            assertThat(thrown)
                    .as("a record is never partially composed")
                    .isExactlyInstanceOf(IllegalArgumentException.class);
            assertThat(thrown.getMessage())
                    .as("the diagnostic identifies the layout and the field it failed on")
                    .contains(EXPECTED_ARTEFACT)
                    .contains("CUST-SSN");
            assertNoRecordContent(thrown.getMessage());
        }

        @Test
        @DisplayName("an absent required attribute is refused, and the diagnostic names the property "
                + "and the legacy field and carries no value")
        void anAbsentRequiredAttributeIsRefused() {
            Customer customer = fixtureRowZeroCustomer();
            customer.setAddrZip(null);

            Throwable thrown = catchThrowable(
                    () -> CustomerRecordMapper.toRecord(customer, revealer()));

            assertThat(thrown)
                    .as("a record is never partially composed and never silently defaulted")
                    .isExactlyInstanceOf(IllegalArgumentException.class);
            assertThat(thrown.getMessage())
                    .as("the diagnostic names the Java property and the legacy field")
                    .contains("addrZip")
                    .contains("CUST-ADDR-ZIP");
            assertNoRecordContent(thrown.getMessage());
        }
    }

    @Nested
    @DisplayName("the credit score, which is deliberately not range-validated")
    class TheCreditScoreIsNotRangeValidated {

        @Test
        @DisplayName("a score below the screen's range, the record's own score and a score at the "
                + "top of the range all parse unchanged, and none of them raises")
        void lowMiddleAndHighScoresAllParseUnchanged() {
            // The 300-to-850 range is screen-level edit validation on the account-update path and is
            // not a property of the stored record. 21 of the 50 seeded rows carry a score below 300,
            // the lowest being 001, so a range, digit or pattern check in this mapper would make the
            // reference data unloadable and fail the end-to-end and named-artefact gates outright.
            assertParsedScore(FICO_LOWEST_SEEDED);
            assertParsedScore(ROW_0_FICO_CREDIT_SCORE);
            assertParsedScore(FICO_TOP_OF_SCREEN_RANGE);
        }

        @Test
        @DisplayName("a score below the screen's range is emitted unchanged too, so a low score "
                + "survives a whole round trip")
        void aLowScoreIsEmittedUnchanged() {
            String lowScoreImage =
                    withField(ROW_0_IMAGE, EXPECTED_FICO_CREDIT_SCORE_OFFSET, FICO_LOWEST_SEEDED);

            Customer parsed = CustomerRecordMapper.fromRecord(lowScoreImage, sealer());
            String emitted = CustomerRecordMapper.toRecord(parsed, revealer());

            assertThat(window(emitted, EXPECTED_FICO_CREDIT_SCORE_OFFSET,
                    EXPECTED_FICO_CREDIT_SCORE_LENGTH))
                    .as("the three bytes at offset 329 are emitted exactly as they were read")
                    .containsExactly(asciiBytes(FICO_LOWEST_SEEDED));
            assertThat(asciiBytes(emitted))
                    .as("and the rest of the record is unaffected")
                    .containsExactly(asciiBytes(lowScoreImage));
        }

        /**
         * Asserts that a three-byte credit score is carried through unchanged and raises nothing.
         *
         * @param score the score as the record image carries it
         */
        private void assertParsedScore(String score) {
            String image = withField(ROW_0_IMAGE, EXPECTED_FICO_CREDIT_SCORE_OFFSET, score);

            assertThatCode(() -> CustomerRecordMapper.fromRecord(image, sealer()))
                    .as("no range check exists in this mapper, so no score is rejected")
                    .doesNotThrowAnyException();
            assertThat(CustomerRecordMapper.fromRecord(image, sealer()).getFicoCreditScore())
                    .as("the score is carried as text, so its bytes survive verbatim")
                    .isEqualTo(score);
        }
    }

    @Nested
    @DisplayName("rejecting malformed input")
    class RejectingMalformedInput {

        @Test
        @DisplayName("an image one byte short of the declared width is rejected, and the message "
                + "names the artefact, the expected 500 and the actual 499, and no record content")
        void anImageOneByteShortIsRejected() {
            String tooShort = ROW_0_IMAGE.substring(0, EXPECTED_RECORD_WIDTH - 1);
            assertThat(encodedLength(tooShort))
                    .as("the malformed image is one byte short by construction")
                    .isEqualTo(EXPECTED_RECORD_WIDTH - 1);

            Throwable thrown = catchThrowable(
                    () -> CustomerRecordMapper.fromRecord(tooShort, sealer()));

            assertThat(thrown)
                    .as("a short record has no legacy antecedent, so the platform's own precondition "
                            + "exception is the honest signal")
                    .isExactlyInstanceOf(IllegalArgumentException.class);
            assertThat(thrown.getMessage())
                    .as("the message states the whole failure contract")
                    .contains(EXPECTED_ARTEFACT)
                    .contains(String.valueOf(EXPECTED_RECORD_WIDTH))
                    .contains(String.valueOf(EXPECTED_RECORD_WIDTH - 1));
            assertNoRecordContent(thrown.getMessage());
        }

        @Test
        @DisplayName("an image one byte long - the fixture's line terminator left on - is rejected "
                + "rather than truncated, and the message names the expected 500 and the actual 501")
        void anImageOneByteLongIsRejected() {
            String tooLong = ROW_0_IMAGE + "\n";
            assertThat(encodedLength(tooLong))
                    .as("the malformed image is one byte long by construction")
                    .isEqualTo(EXPECTED_RECORD_WIDTH + 1);

            Throwable thrown = catchThrowable(
                    () -> CustomerRecordMapper.fromRecord(tooLong, sealer()));

            assertThat(thrown)
                    .as("input is never silently truncated")
                    .isExactlyInstanceOf(IllegalArgumentException.class);
            assertThat(thrown.getMessage())
                    .as("the message states the whole failure contract")
                    .contains(EXPECTED_ARTEFACT)
                    .contains(String.valueOf(EXPECTED_RECORD_WIDTH))
                    .contains(String.valueOf(EXPECTED_RECORD_WIDTH + 1));
            assertNoRecordContent(thrown.getMessage());
        }

        @Test
        @DisplayName("a byte image of the wrong width is rejected on the byte entry point too")
        void aByteImageOfTheWrongWidthIsRejected() {
            byte[] tooShort = asciiBytes(ROW_0_IMAGE.substring(0, EXPECTED_MAPPED_DATA_WIDTH));

            Throwable thrown = catchThrowable(
                    () -> CustomerRecordMapper.fromRecord(tooShort, sealer()));

            assertThat(thrown)
                    .as("the width check applies to every entry point")
                    .isExactlyInstanceOf(IllegalArgumentException.class);
            assertThat(thrown.getMessage())
                    .as("the message names the expected and the actual encoded widths")
                    .contains(String.valueOf(EXPECTED_RECORD_WIDTH))
                    .contains(String.valueOf(EXPECTED_MAPPED_DATA_WIDTH));
            assertNoRecordContent(thrown.getMessage());
        }

        @Test
        @DisplayName("a byte range that runs past the end of its buffer is rejected")
        void aByteRangePastTheBufferEndIsRejected() {
            byte[] oneRecord = asciiBytes(ROW_0_IMAGE);

            Throwable thrown = catchThrowable(
                    () -> CustomerRecordMapper.fromRecord(oneRecord, 1, sealer()));

            assertThat(thrown)
                    .as("a range that does not fit is a caller defect")
                    .isExactlyInstanceOf(IllegalArgumentException.class);
            assertThat(thrown.getMessage())
                    .as("the message names the artefact and the widths involved")
                    .contains(EXPECTED_ARTEFACT)
                    .contains(String.valueOf(EXPECTED_RECORD_WIDTH));
            assertNoRecordContent(thrown.getMessage());
        }

        @Test
        @DisplayName("an empty image is rejected rather than padded")
        void anEmptyImageIsRejected() {
            Throwable thrown = catchThrowable(() -> CustomerRecordMapper.fromRecord("", sealer()));

            assertThat(thrown)
                    .as("input is never padded up to the declared width")
                    .isExactlyInstanceOf(IllegalArgumentException.class);
            assertThat(thrown.getMessage())
                    .as("the message names the expected width and the actual zero")
                    .contains(String.valueOf(EXPECTED_RECORD_WIDTH))
                    .contains("0");
        }

        @Test
        @DisplayName("every null argument is rejected by name, on both directions of the mapping")
        void everyNullArgumentIsRejectedByName() {
            String nullImage = null;
            byte[] nullBytes = null;
            Customer nullCustomer = null;

            assertThatNullPointerException()
                    .as("a null string image")
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(nullImage, sealer()))
                    .withMessageContaining("recordImage");
            assertThatNullPointerException()
                    .as("a null byte image")
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(nullBytes, sealer()))
                    .withMessageContaining("recordImage");
            assertThatNullPointerException()
                    .as("a null buffer on the range entry point")
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(nullBytes, 0, sealer()))
                    .withMessageContaining("buffer");
            assertThatNullPointerException()
                    .as("a null sealing operation")
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(ROW_0_IMAGE, null))
                    .withMessageContaining("regulatedFieldSealer");
            assertThatNullPointerException()
                    .as("a null customer")
                    .isThrownBy(() -> CustomerRecordMapper.toRecord(nullCustomer, revealer()))
                    .withMessageContaining("customer");
            assertThatNullPointerException()
                    .as("a null revealing operation")
                    .isThrownBy(() ->
                            CustomerRecordMapper.toRecord(fixtureRowZeroCustomer(), null))
                    .withMessageContaining("regulatedFieldRevealer");
            assertThatNullPointerException()
                    .as("a null customer on the byte-emitting entry point")
                    .isThrownBy(() -> CustomerRecordMapper.toRecordBytes(nullCustomer, revealer()))
                    .withMessageContaining("customer");
        }
    }
}
