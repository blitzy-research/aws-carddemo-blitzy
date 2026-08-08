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
package com.carddemo.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.api.dto.CardDetailResponse;
import com.carddemo.api.dto.CardListResponse;
import com.carddemo.api.dto.CardUpdateResponse;
import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.api.dto.PageMetadata;
import com.carddemo.config.JwtProperties;
import com.carddemo.config.JwtTokenProvider;
import com.carddemo.config.SecurityConfig;
import com.carddemo.config.WebMvcConfig;
import com.carddemo.domain.Card;
import com.carddemo.domain.enums.CardStatus;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.RecordWriter;
import com.carddemo.service.AbendService;
import com.carddemo.service.CardConcurrencyTokenService;
import com.carddemo.service.CardDetailService;
import com.carddemo.service.CardListService;
import com.carddemo.service.CardUpdateService;
import com.carddemo.service.MessageCatalogService;
import com.carddemo.service.NavigationService;
import com.carddemo.service.OnlineTransactionBoundary;
import com.carddemo.service.SensitiveFieldEncryptionService;
import com.carddemo.service.SignOnStateService;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.TestDataFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The three card screens - {@code CCLI} list, {@code CCDL} detail and {@code CCUP} update - exercised over
 * the shipped servlet boundary, through the shipped filter chain and the shipped services, against a real
 * PostgreSQL server carrying the migrated schema and the delivered seed.
 *
 * <h2>What this specification is for</h2>
 * Gate 5 requires every external interface to be verified by a local test that exercises the real
 * contract, and states that self-certification is not acceptable. Nothing here is stubbed: the browse
 * walks real rows, the conversation token is sealed and opened by the shipped sealer over the shipped
 * cipher, the write goes to the server and the concurrency conflict is provoked by a second writer rather
 * than described. Every route, verb, status code, JSON property name and enumeration constant asserted
 * below was read out of the shipped types; none is guessed, and no production type was altered to make an
 * assertion pass.
 *
 * <h2>The contract items that carry the most risk, and why each is asserted</h2>
 * <ul>
 *   <li><strong>The page size of seven.</strong> {@code app/cbl/COCRDLIC.cbl} fixes it three independent
 *       ways: a 196-character presentation area at lines 250 to 253, a seven-occurrence row table at line
 *       255 whose element is 11 + 16 + 1 characters, and a screen-line constant of the same value. Seven
 *       times twenty-eight accounts for all 196. It is published as
 *       {@link PageMetadata#CARD_LIST_PAGE_SIZE} and is a screen shape rather than a tunable, so it is
 *       asserted as a value and as a constant distinct from the two ten-row screens.</li>
 *   <li><strong>The backward fill.</strong> The backward arm repositions on the retained first key,
 *       consumes that row, and then fills the screen's slots downward from the seventh. The assembled page
 *       therefore presents ascending while the read ran descending, and a partial backward page leaves the
 *       low slots empty. Asserted as an ordered list, because membership alone would pass against a page
 *       assembled in the read order.</li>
 *   <li><strong>The two filter rejection texts.</strong> Reproduced byte for byte, including the absence of
 *       a space after the comma and the wording "A 11" and "A 16" rather than "AN 11" and "AN 16".</li>
 *   <li><strong>Positional selectors.</strong> Seven single-character selectors, at most one action per
 *       page, and a positional indicator that keeps its cleared entries so the offending rows can be
 *       named. Asserted with a selection in the fifth row and blanks around it, and with two selections
 *       whose row positions must both appear.</li>
 *   <li><strong>Three message-width contracts that must not be unified.</strong> The list screen declares
 *       45 and 78; the detail and update screens declare 40 and 80. The detail turn pads its two message
 *       fields to those widths, so they are asserted at full width and never trimmed.</li>
 *   <li><strong>The double in-place upper fold.</strong> The submitted card-data group and the fetched
 *       image are both folded through a 26-character ASCII table before they are compared, at the edit
 *       driver and again at the write path's own comparison. An edit differing only in letter case is
 *       therefore not a change, and the screen says so. The fold is emphatically not the locale-aware
 *       upper-casing method of the standard string type: it is locale-independent and Unicode-unaware,
 *       which is observable because the case variant of a non-ASCII letter <em>is</em> a change.</li>
 *   <li><strong>Two deliberate inconsistencies in the update message block.</strong> The confirmation
 *       prompt has no space after its period while the failure notice has one, and the concurrency notice
 *       spells "some one" as two words. Both are the contract and neither is normalised.</li>
 *   <li><strong>The hidden expiry day.</strong> Hidden, protected and dark on every state of the update
 *       screen, carried through the conversation, never editable and never validated.</li>
 * </ul>
 *
 * <h2>Credentials</h2>
 * <strong>No credential value of any kind appears in this file, in any form.</strong> A session is minted
 * by asking the shipped {@link JwtTokenProvider} for one, which is exactly what the sign-on path does once
 * it has verified a credential, so nothing here reads, holds, folds, hashes, renders or compares a stored
 * secret. The provider refuses to mint for an identity no record carries, so a token existing at all is
 * evidence that the delivered sign-on seed applied.
 *
 * <h2>Shared-server discipline</h2>
 * The container, its migration to the head of the delivered set and the pinned clock all come from
 * {@link AbstractPostgresIT}. This class declares no container, no data-source property source, no profile
 * and no context-dirtying, per that type's subclass contract. It never mutates a delivered row: every row
 * it writes is keyed above the highest delivered card number, so the first two pages of the browse are
 * untouched by it, and every such row is removed after each test.
 *
 * <p>Provenance: {@code app/cbl/COCRDLIC.cbl}, {@code app/cbl/COCRDSLC.cbl}, {@code app/cbl/COCRDUPC.cbl},
 * the three symbolic maps under {@code app/cpy-bms}, the two mapsets {@code app/bms/COCRDLI.bms} and
 * {@code app/bms/COCRDUP.bms}, the record layouts {@code app/cpy/CVACT02Y.cpy} and
 * {@code app/cpy/CVACT03Y.cpy}, the screen work area {@code app/cpy/CVCRD01Y.cpy}, the attention-key
 * copybook {@code app/cpy/CSSTRPFY.cpy} and the cluster definitions {@code app/jcl/CARDFILE.jcl} and
 * {@code app/jcl/XREFFILE.jcl}, every one of them read as read-only reference. Message texts, field
 * widths, record offsets, page sizes and paragraph line numbers are contract and metadata; no legacy
 * source line is transcribed and nothing in the legacy tree is read at run time.
 *
 * <p>The checkout revision and the upstream release stamp that pin those references are deliberately
 * <em>not</em> restated here. They belong to the header of the traceability matrix, which is the one
 * artefact that carries them, so that a single place has to be corrected when the baseline moves.
 */
@SpringBootTest(classes = CardControllerIT.CardScreensContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            // The shared server was migrated to the head of the delivered set before this class was
            // constructed, so a second migration from this context would be work with no new state to
            // apply.
            "spring.flyway.enabled=false",
            // validate, never none and never a generating value: a mapping that had drifted from the
            // migrated schema must fail this specification at refresh rather than be reconciled behind it.
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.open-in-view=false",
            "spring.batch.job.enabled=false",
            "spring.main.banner-mode=off",
            "management.endpoint.health.validate-group-membership=false",
            "management.tracing.enabled=false",
            // The boundary here is a mock servlet inside this process, so there is no wire for a session to
            // be observed on, and the transport requirement is relaxed exactly as the suite profile relaxes
            // it. Nothing else is relaxed: the anonymous set and the administrative gate are the shipped
            // ones, which is the whole point of asserting against them.
            "carddemo.security.require-https=false"})
@AutoConfigureMockMvc
@DisplayName("Gate 5: the card list, detail and update screens over the shipped boundary")
public class CardControllerIT extends AbstractPostgresIT {

    // ===============================================================================================
    // THE CONTRACT, RESTATED HERE RATHER THAN IMPORTED FROM THE SUBJECT
    //
    // Each literal below is written out again so that an assertion compares the served value against an
    // independent statement of the text rather than against the same constant the boundary published. The
    // shipped constant is additionally asserted to agree, which is what makes the two a contract rather
    // than a tautology.
    // ===============================================================================================

    /** Screen shape of the card list: seven rows. Legacy row table, {@code COCRDLIC} line 255. */
    private static final int CARD_LIST_ROWS = 7;

    /** Screen shape of the transaction and user lists: ten rows. Never the card list's figure. */
    private static final int TEN_ROW_SCREEN = 10;

    /** Width the card-list screen declares for its displayed page indicator. */
    private static final int LIST_PAGE_INDICATOR_WIDTH = 3;

    /** Width the transaction and user list screens declare for theirs. */
    private static final int WIDE_PAGE_INDICATOR_WIDTH = 8;

    /** Informational-message width of the detail and update screens. */
    private static final int DETAIL_INFO_WIDTH = 40;

    /** Error-message width of the detail and update screens. */
    private static final int DETAIL_ERROR_WIDTH = 80;

    /** Informational-message width of the list screen, which is deliberately not the detail screen's. */
    private static final int LIST_INFO_WIDTH = 45;

    /** Error-message width of the list screen, which is deliberately not the detail screen's. */
    private static final int LIST_ERROR_WIDTH = 78;

    /** Rejection text for a malformed account filter. No space follows the comma; the wording is "A 11". */
    private static final String ACCOUNT_FILTER_REJECTED =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** Rejection text for a malformed card filter. No space follows the comma; the wording is "A 16". */
    private static final String CARD_FILTER_REJECTED =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /** Rejection text for a row action code outside the accepted pair. */
    private static final String INVALID_ACTION_CODE = "INVALID ACTION CODE";

    /** Rejection text for more than one action on one page. */
    private static final String MORE_THAN_ONE_ACTION = "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE";

    /** Guard text for a backward request already at the start of the browse. */
    private static final String NO_PREVIOUS_PAGES = "NO PREVIOUS PAGES TO DISPLAY";

    /** Guard text for a forward request already past the end of the browse. */
    private static final String NO_MORE_PAGES = "NO MORE PAGES TO DISPLAY";

    /** Text reporting that the data, rather than the navigation, ran out. */
    private static final String NO_MORE_RECORDS = "NO MORE RECORDS TO SHOW";

    /** Text for filters that match nothing: the one boundary text that ends with a full stop. */
    private static final String NO_RECORDS_FOUND = "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";

    /** Informational prompt naming the two action codes the selection column accepts. */
    private static final String ROW_ACTION_PROMPT = "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";

    /** Detail and update prompt for the two search keys. */
    private static final String PROMPT_FOR_SEARCH_KEYS = "Please enter Account and Card Number";

    /** Detail success advisory. Its three leading spaces are part of the value. */
    private static final String DISPLAYING_REQUESTED_DETAILS = "   Displaying requested details";

    /** Blank-account text, shared by the detail and update screens. */
    private static final String ACCOUNT_NOT_PROVIDED = "Account number not provided";

    /** Blank-card text, shared by the detail and update screens. */
    private static final String CARD_NOT_PROVIDED = "Card number not provided";

    /** Both-blank text, shared by the detail and update screens. */
    private static final String NO_INPUT_RECEIVED = "No input received";

    /** Card-not-found text, shared by the detail and update screens. */
    private static final String NO_CARDS_FOR_SEARCH = "Did not find cards for this search condition";

    /** Account-not-found text. Declared by both screens; reachable through neither read path. */
    private static final String ACCOUNT_NOT_IN_CARD_DATABASE =
            "Did not find this account in cards database";

    /**
     * Non-zero-account text. Declared <em>twice</em> in each of the two screens' message groups - detail
     * lines 145 and 147, update lines 190 and 192 - with identical text both times. Asserted once, with
     * the duplication recorded here rather than reproduced.
     */
    private static final String ACCOUNT_MUST_BE_NON_ZERO =
            "Account number must be a non zero 11 digit number";

    /**
     * Sixteen-digit card text, in lower case. The list screen's own filter variant is upper case and says
     * something different; the two are never unified.
     */
    private static final String CARD_MUST_BE_SIXTEEN_DIGITS =
            "Card number if supplied must be a 16 digit number";

    /** Read-failure text of both screens. */
    private static final String CARD_DATA_READ_ERROR = "Error reading Card Data File";

    /** The unfinished-work advisory, with <em>four</em> dots. */
    private static final String CODING_TO_BE_DONE = "Looks Good.... so far";

    /** The corrupt-state advisory both screens declare. */
    private static final String UNEXPECTED_DATA_SCENARIO = "UNEXPECTED DATA SCENARIO";

    /**
     * The exit text, at its full declared width: twenty characters and then fourteen spaces, with
     * <em>no</em> space after the period. The trailing pad is part of the value and is never trimmed here.
     */
    private static final String EXIT_TEXT_34 = "PF03 pressed.Exiting              ";

    /** Update advisory once a card has been fetched. */
    private static final String DETAILS_SHOWN = "Details of selected card shown above";

    /** Update advisory once a change has been rejected. */
    private static final String PROMPT_FOR_CHANGES = "Update card details presented above.";

    /** Update advisory once a change has been accepted. <strong>No space after the period.</strong> */
    private static final String CHANGES_VALIDATED = "Changes validated.Press F5 to save";

    /** Update advisory once the write has completed. */
    private static final String CHANGES_COMMITTED = "Changes committed to database";

    /**
     * Update advisory once the write has failed. <strong>With a space after the period</strong> - the
     * inconsistency against the confirmation prompt is the contract and neither is normalised.
     */
    private static final String CHANGES_UNSUCCESSFUL = "Changes unsuccessful. Please try again";

    /** Blank-name text. */
    private static final String NAME_NOT_PROVIDED = "Card name not provided";

    /** Non-alphabetic-name text. Embedded spaces pass the edit this text reports on. */
    private static final String NAME_MUST_BE_ALPHA = "Card name can only contain alphabets and spaces";

    /** No-change text, including its closing full stop. */
    private static final String NO_CHANGE_DETECTED = "No change detected with respect to values fetched.";

    /** Status text: the stored code must be one of two letters. */
    private static final String STATUS_MUST_BE_YES_OR_NO = "Card Active Status must be Y or N";

    /** Expiry-month text. The wording is "1 and 12" and never "01 and 12". */
    private static final String EXPIRY_MONTH_INVALID = "Card expiry month must be between 1 and 12";

    /** Expiry-year text. It names no range, although the enforced rule is 1950 to 2099. */
    private static final String EXPIRY_YEAR_INVALID = "Invalid card expiry year";

    /** The single generic lock text of this screen. The account-update screen has two distinct variants. */
    private static final String COULD_NOT_LOCK = "Could not lock record for update";

    /** Concurrency notice. <strong>"some one" is two words.</strong> */
    private static final String RECORD_CHANGED = "Record changed by some one else. Please review";

    /** Write-failure text. */
    private static final String UPDATE_FAILED = "Update of record failed";

    /** File-error prefix, <strong>with its trailing space</strong>. */
    private static final String FILE_ERROR_PREFIX = "File Error: ";

    // ===============================================================================================
    // SCREEN FIELD IDENTIFIERS, ROUTES AND WIDTHS
    // ===============================================================================================

    /**
     * The optional account filter of the list and detail screens. Eleven characters, and there is no
     * artificial default: omitting it means no account narrowing at all.
     */
    private static final String PROPERTY_ACCOUNT_ID_FILTER = "accountIdFilter";

    /** The optional card filter of the same two screens, sixteen characters, equally without a default. */
    private static final String PROPERTY_CARD_NUMBER_FILTER = "cardNumberFilter";

    /**
     * The property the list screen echoes the account filter back under, which is deliberately not the
     * property it was transmitted under. The asymmetry is named here rather than left as two similar
     * literals, so a reader is not left wondering which side is which.
     */
    private static final String RESPONSE_ACCOUNT_FILTER = "accountFilter";

    /** Request property naming the account identifier on the update screen. */
    private static final String PROPERTY_ACCOUNT_ID = "accountId";

    /** Request property naming the card number on the update screen. */
    private static final String PROPERTY_CARD_NUMBER = "cardNumber";

    /** Request property naming the embossed name. */
    private static final String PROPERTY_EMBOSSED_NAME = "embossedName";

    /** Request property naming the active status on the update screen. */
    private static final String PROPERTY_ACTIVE_STATUS = "activeStatus";

    /** Request property naming the expiry month. */
    private static final String PROPERTY_EXPIRY_MONTH = "expiryMonth";

    /** Request property naming the expiry year. */
    private static final String PROPERTY_EXPIRY_YEAR = "expiryYear";

    /** Request property naming the hidden, protected, never-validated expiry day. */
    private static final String PROPERTY_EXPIRY_DAY = "expiryDay";

    /** Opaque label correlating a finding with the account field of the maps this screen derives from. */
    private static final String SCREEN_FIELD_ACCOUNT = "ACCTSID";

    /** Opaque label correlating a finding with the card field of the same maps. */
    private static final String SCREEN_FIELD_CARD = "CARDSID";

    /** Opaque label correlating a finding with the embossed-name field of the update map. */
    private static final String SCREEN_FIELD_EMBOSSED_NAME = "CRDNAME";

    /** Route value a card-list turn settles on when it re-presents itself. */
    private static final String ROUTE_CARD_LIST = "card-list";

    /** Route value a view selection settles on. */
    private static final String ROUTE_CARD_DETAIL = "card-detail";

    /** Route value an update selection settles on. */
    private static final String ROUTE_CARD_UPDATE = "card-update";

    /** Presentation prefix an issued session travels behind. */
    private static final String BEARER_PREFIX = "Bearer ";

    /** Summary a refusal for want of a session carries. */
    private static final String AUTHENTICATION_REQUIRED = "Authentication required";

    // ===============================================================================================
    // THE DELIVERED IDENTITIES AND THE DELIVERED SEED
    // ===============================================================================================

    /** A delivered administrative identity, from the sign-on seed. */
    private static final String DELIVERED_ADMIN_IDENTITY = "ADMIN001";

    /** A delivered ordinary identity, from the same seed. */
    private static final String DELIVERED_USER_IDENTITY = "USER0001";

    /** The delivered card count, from {@code app/data/ASCII/carddata.txt}: fifty records of 150 bytes. */
    private static final int SEEDED_CARD_COUNT = 50;

    /**
     * The first seven delivered card numbers in ascending key order - the whole of page one.
     *
     * <p>Ascending base-cluster order is the order the browse walks, because the cluster is keyed on the
     * card number: {@code app/jcl/CARDFILE.jcl} defines the base cluster with the card number as its key
     * and declares the account alternate index separately.
     */
    private static final List<String> PAGE_ONE_CARDS = List.of(
            "0500024453765740",
            "0683586198171516",
            "0923877193247330",
            "0927987108636232",
            "0982496213629795",
            "1014086565224350",
            "1142167692878931");

    /** The owning accounts of those seven cards, in the same order. */
    private static final List<String> PAGE_ONE_ACCOUNTS = List.of(
            "00000000050",
            "00000000027",
            "00000000002",
            "00000000020",
            "00000000012",
            "00000000044",
            "00000000037");

    /** The eighth delivered card in key order, which is the first row of page two. */
    private static final String PAGE_TWO_FIRST_CARD = "1561409106491600";

    /** The account whose single delivered card is the first row of page one. */
    private static final String SINGLE_CARD_ACCOUNT = "00000000050";

    /** That account's single delivered card. */
    private static final String SINGLE_CARD_NUMBER = "0500024453765740";

    /** An eleven-digit account identifier the delivered seed does not carry. */
    private static final String ABSENT_ACCOUNT = "00000000099";

    /**
     * A card number the delivered seed does not carry, taken from the shared fixture factory so that the
     * two agree on which key is reserved for a not-found assertion.
     */
    private static final String ABSENT_CARD_NUMBER = TestDataFactory.UNKNOWN_CARD_NUMBER;

    // ===============================================================================================
    // THE ROWS THIS SPECIFICATION OWNS
    //
    // Every key is above the highest delivered card number, so nothing written here can appear on page one
    // or page two of the browse, and every one of them is removed after each test. No delivered row is
    // written, deleted or counted by this class.
    // ===============================================================================================

    /** The account a reserved row is attached to, so the card's foreign key resolves. */
    private static final String RESERVED_ROW_ACCOUNT = "00000000012";

    /** That account's delivered card, which ascending order places before any reserved row. */
    private static final String RESERVED_ROW_ACCOUNT_SEEDED_CARD = "0982496213629795";

    /** Reserved key carrying a status outside the two the estate declares. */
    private static final String RESERVED_UNKNOWN_STATUS_CARD = "9990000000000001";

    /** Reserved key the update conversation edits. */
    private static final String RESERVED_UPDATE_CARD = "9990000000000002";

    /** Reserved key whose stored name carries a non-ASCII letter. */
    private static final String RESERVED_NON_ASCII_CARD = "9990000000000003";

    /** Every reserved key, so the cleanup is exhaustive without enumerating it at each site. */
    private static final List<String> RESERVED_CARDS = List.of(
            RESERVED_UNKNOWN_STATUS_CARD, RESERVED_UPDATE_CARD, RESERVED_NON_ASCII_CARD);

    /** A stored status the estate never declares. The column carries no check constraint. */
    private static final String UNDECLARED_CARD_STATUS = "X";

    /**
     * The active status as the one-character text both the API and the column carry. The enum keeps the
     * code as a {@code char} because the legacy field is exactly one character wide, so the width is a
     * property of the type; this is that same character as the text a JSON property holds.
     */
    private static final String STATUS_ACTIVE = String.valueOf(CardStatus.Y.getCode());

    /** The inactive status as the same one-character text. */
    private static final String STATUS_INACTIVE = String.valueOf(CardStatus.N.getCode());

    /** Verification code every reserved row carries. Three digits, as the layout declares. */
    private static final String RESERVED_VERIFICATION_CODE = "123";

    /** Expiration date every reserved row carries, whose three components split cleanly. */
    private static final String RESERVED_EXPIRATION_DATE = "2027-07-31";

    /** The year component of that date. */
    private static final String RESERVED_EXPIRY_YEAR = "2027";

    /** The month component of that date. */
    private static final String RESERVED_EXPIRY_MONTH = "07";

    /** The day component of that date - hidden, protected and never validated. */
    private static final String RESERVED_EXPIRY_DAY = "31";

    /** The stored embossed name of a reserved row, already folded and free of digits. */
    private static final String RESERVED_EMBOSSED_NAME = "UPDATE FIXTURE NAME";

    /**
     * The same letters as {@link #RESERVED_EMBOSSED_NAME} in the opposite case. Every character is inside
     * the twenty-six-character table, so the fold maps this value onto that one and the change comparison
     * cannot tell them apart.
     */
    private static final String RESERVED_EMBOSSED_NAME_CASE_VARIANT = "update fixture name";

    /** A replacement name with an embedded space, which the alphabetic edit must accept. */
    private static final String NAME_WITH_EMBEDDED_SPACE = "MARY ANN";

    /** A replacement name carrying a digit, which the same edit must reject. */
    private static final String NAME_WITH_A_DIGIT = "MARY ANN 2";

    /**
     * A stored name carrying a non-ASCII upper-case letter, written as an escape so this file stays
     * seven-bit.
     */
    private static final String NON_ASCII_NAME_UPPER = "MAR\u00c9A";

    /** The same name with that letter in lower case: the case variant the ASCII table cannot fold. */
    private static final String NON_ASCII_NAME_LOWER = "MAR\u00e9A";

    /** Reads and writes JSON without binding to a published contract type. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The shipped servlet boundary, with the shipped filter chain in front of it. */
    @Autowired
    private MockMvc mockMvc;

    /** The shipped session provider. Asked directly, so no credential is named anywhere in this class. */
    @Autowired
    private JwtTokenProvider tokenProvider;

    /** The card master, for writing and removing the rows this specification owns. */
    @Autowired
    private CardRepository cards;

    /** Creates the specification. */
    public CardControllerIT() {
        super();
    }

    /**
     * Removes every row this specification owns, whether the test that ran wrote it or not.
     *
     * <p>Scoped to the reserved keys by name and applied unconditionally, so a test that failed part way
     * through still leaves the shared server exactly as it found it. No delivered row is touched.
     */
    @AfterEach
    void removeReservedRows() {
        for (final String reserved : RESERVED_CARDS) {
            this.cards.findById(reserved).ifPresent(this.cards::delete);
        }
    }

    // ===============================================================================================
    // SHARED MACHINERY
    // ===============================================================================================

    /** The card-list route, assembled from the boundary's own published path constants. */
    private static String listRoute() {
        return CardController.CARDS_BASE_PATH + CardController.CARD_LIST_PATH;
    }

    /** The card-detail route, assembled the same way. */
    private static String detailRoute() {
        return CardController.CARDS_BASE_PATH + CardController.CARD_DETAIL_PATH;
    }

    /** The card-update route, assembled the same way. */
    private static String updateRoute() {
        return CardController.CARDS_BASE_PATH + CardController.CARD_UPDATE_PATH;
    }

    /**
     * Mints a session for a delivered ordinary identity, without naming a credential.
     *
     * <p>The shipped provider is asked directly, which is what the sign-on path itself does once it has
     * verified a credential. No stored secret is read, held or rendered here, and the ordinary authority is
     * used for the card routes because those routes are not administratively gated - which is one of the
     * things this specification asserts rather than assumes.
     *
     * @return the value of the authorization header, including its presentation prefix
     */
    private String userSession() {
        return BEARER_PREFIX + this.tokenProvider.issue(DELIVERED_USER_IDENTITY, UserType.USER,
                UserType.USER.getCode());
    }

    /**
     * Mints a session for a delivered administrative identity, in the same way and for the same reason.
     *
     * @return the value of the authorization header, including its presentation prefix
     */
    private String adminSession() {
        return BEARER_PREFIX + this.tokenProvider.issue(DELIVERED_ADMIN_IDENTITY, UserType.ADMIN,
                UserType.ADMIN.getCode());
    }

    /**
     * Submits one turn to one card route.
     *
     * @param  route the route to submit to
     * @param  body the transmitted screen, or {@code null} to submit no body at all
     * @param  authorization the session to present, or {@code null} to present none
     * @return the completed result, so both the body and the status line can be inspected
     * @throws Exception if the boundary cannot be reached
     */
    private MvcResult submit(final String route, final Map<String, Object> body,
            final String authorization) throws Exception {
        var request = MockMvcRequestBuilders.post(route)
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .accept(MediaType.APPLICATION_JSON)
                .content(body == null ? "{}" : JSON.writeValueAsString(body));
        if (authorization != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return this.mockMvc.perform(request).andReturn();
    }

    /**
     * Answers the raw served body, whatever status it came with.
     *
     * <p>Used where the assertion is about what a body may <em>not</em> contain, which has to hold for a
     * refusal just as much as for an accepted turn.
     *
     * @param  result the completed exchange
     * @return the body as text
     * @throws Exception when the body cannot be read
     */
    private static String bodyOf(final MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Parses the body of a completed turn, having first asserted the status the contract fixes.
     *
     * <p>Every outcome of these three screens is a screen the legacy program composed and sent, rejections
     * included, so the turn completes with the same status in all of them.
     *
     * @param  result the completed turn
     * @return the parsed body
     * @throws Exception if the body cannot be parsed
     */
    private static JsonNode okBody(final MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("every outcome of this screen is one the legacy program composed and sent, so the "
                        + "turn completes with the same status whether it accepted or rejected the input")
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * Submits one card-list turn as an ordinary operator and returns its parsed screen.
     *
     * @param  body the transmitted screen
     * @return the parsed screen
     * @throws Exception if the boundary cannot be reached
     */
    private JsonNode listTurn(final Map<String, Object> body) throws Exception {
        return okBody(submit(listRoute(), body, userSession()));
    }

    /**
     * Submits one card-detail turn as an ordinary operator and returns its parsed screen.
     *
     * @param  body the transmitted screen
     * @return the parsed screen
     * @throws Exception if the boundary cannot be reached
     */
    private JsonNode detailTurn(final Map<String, Object> body) throws Exception {
        return okBody(submit(detailRoute(), body, userSession()));
    }

    /**
     * Submits one card-update turn as an ordinary operator and returns its parsed screen.
     *
     * @param  body the transmitted screen
     * @return the parsed screen
     * @throws Exception if the boundary cannot be reached
     */
    private JsonNode updateTurn(final Map<String, Object> body) throws Exception {
        return okBody(submit(updateRoute(), body, userSession()));
    }

    /**
     * Starts a transmitted screen carrying nothing but the key that submits one.
     *
     * @return a mutable payload the caller adds the screen's own fields to
     */
    private static Map<String, Object> turnWith(final KeyAction keyAction) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("keyAction", keyAction.name());
        return body;
    }

    /**
     * Echoes the navigation state and the paging state a previous card-list turn handed back.
     *
     * <p>This is what a client does, and it is the only way the screen edits anything: the source receives
     * and edits the transmitted fields only when the echoed state names this program as the originator, and
     * it reads the retained page number and the retained further-pages flag out of the echoed paging state
     * rather than recomputing either.
     *
     * @param body the payload being assembled
     * @param previous the screen the previous turn produced
     */
    private static void echoListState(final Map<String, Object> body, final JsonNode previous) {
        body.put("navigationContext", previous.get("navigationContext"));
        final JsonNode paging = previous.get("pageMetadata");
        if (paging != null && !paging.isNull()) {
            final Map<String, Object> cursor = new LinkedHashMap<>();
            cursor.put("previousCursorKey", textOf(paging, "previousCursorKey"));
            cursor.put("nextCursorKey", textOf(paging, "nextCursorKey"));
            cursor.put("direction", textOf(paging, "direction"));
            cursor.put("displayedPageNumber", textOf(paging, "displayedPageNumber"));
            cursor.put("nextPageIndicated", flagOf(paging, "hasMorePages"));
            body.put("pageMetadata", cursor);
        }
        body.put("lastPageAlreadyShown", flagOf(previous, "lastPageAlreadyShown"));
    }

    /**
     * Places one action code in one of the seven screen slots.
     *
     * @param body the payload being assembled
     * @param screenSlot the one-based slot, between one and seven
     * @param actionCode the single character to transmit in that slot
     */
    private static void select(final Map<String, Object> body, final int screenSlot,
            final String actionCode) {
        body.put("selection" + screenSlot, actionCode);
    }

    /**
     * Echoes the navigation state a previous detail turn handed back, which is what raises the
     * re-submission gate the detail screen edits its inputs behind.
     *
     * @param  previous the screen the previous turn produced
     * @return a mutable payload carrying the echoed state
     */
    private static Map<String, Object> resubmission(final JsonNode previous) {
        final Map<String, Object> body = turnWith(KeyAction.ENTER);
        body.put("navigationContext", previous.get("navigationContext"));
        return body;
    }

    /**
     * Echoes the navigation state and the sealed conversation state a previous card-update turn handed
     * back, and presses one key.
     *
     * <p>The sealed state is echoed rather than fabricated because the boundary opens it: a value this
     * server did not seal cannot be opened and is refused, which is the whole behaviour the seal exists to
     * produce.
     *
     * @param  previous the screen the previous turn produced
     * @param  keyAction the key the operator pressed
     * @return a mutable payload carrying the echoed conversation
     */
    private static Map<String, Object> echoUpdateState(final JsonNode previous,
            final KeyAction keyAction) {
        final Map<String, Object> body = turnWith(keyAction);
        body.put("navigationContext", previous.get("navigationContext"));
        body.put("concurrencyToken", textOf(previous, "concurrencyToken"));
        return body;
    }

    /**
     * Reads a textual property, distinguishing an absent property from a present null one.
     *
     * @param  node the parsed object
     * @param  name the property name, as the published contract declares it
     * @return the value, or {@code null} when the property is absent or null
     */
    private static String textOf(final JsonNode node, final String name) {
        final JsonNode property = node.get(name);
        return property == null || property.isNull() ? null : property.asText();
    }

    /**
     * Reads a boolean property that the contract declares as an explicit component.
     *
     * @param  node the parsed object
     * @param  name the property name
     * @return the value the boundary published
     */
    private static boolean flagOf(final JsonNode node, final String name) {
        final JsonNode property = node.get(name);
        assertThat(property)
                .as("%s is an explicit component of the published contract, so a body that omits it would "
                        + "leave every caller inferring it", name)
                .isNotNull();
        return property.asBoolean();
    }

    /**
     * Reads the card numbers of a page in the order the screen presents them.
     *
     * @param  screen the parsed screen
     * @return the card numbers, in presentation order
     */
    private static List<String> cardNumbersOf(final JsonNode screen) {
        final List<String> numbers = new ArrayList<>(CARD_LIST_ROWS);
        for (final JsonNode row : screen.get("rows")) {
            numbers.add(textOf(row, "cardNumber"));
        }
        return numbers;
    }

    /**
     * Reads the screen slots a page's rows occupy, in the order the screen presents them.
     *
     * @param  screen the parsed screen
     * @return the one-based slots, in presentation order
     */
    private static List<Integer> screenSlotsOf(final JsonNode screen) {
        final List<Integer> slots = new ArrayList<>(CARD_LIST_ROWS);
        for (final JsonNode row : screen.get("rows")) {
            slots.add(row.get("screenSlot").asInt());
        }
        return slots;
    }

    /**
     * Reads the positional selection indicator as an ordered list, cleared entries included.
     *
     * @param  screen the parsed screen
     * @return one entry per published row, in the same order
     */
    private static List<Boolean> selectionErrorFlagsOf(final JsonNode screen) {
        final List<Boolean> flags = new ArrayList<>(CARD_LIST_ROWS);
        for (final JsonNode flag : screen.get("selectionErrorFlags")) {
            flags.add(flag.asBoolean());
        }
        return flags;
    }

    /**
     * Reads the property names an object carries, so a contract can be asserted to be exactly what it
     * declares and nothing more.
     *
     * @param  node the parsed object
     * @return the property names, in serialization order
     */
    private static List<String> propertyNamesOf(final JsonNode node) {
        final List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * Reads one field-level finding by the request property it names.
     *
     * @param  screen the parsed screen
     * @param  fieldName the request property the finding names
     * @return the finding, or empty when the screen reported none for that property
     */
    private static Optional<JsonNode> findingFor(final JsonNode screen, final String fieldName) {
        for (final JsonNode finding : screen.get("fieldErrors")) {
            if (fieldName.equals(textOf(finding, "fieldName"))) {
                return Optional.of(finding);
            }
        }
        return Optional.empty();
    }

    /**
     * Pads a value to the width of the fixed field it is moved into, exactly as a COBOL move does.
     *
     * <p>Padding rather than trimming, deliberately. The trailing spaces of a fixed-width space-filled
     * screen field are part of what was displayed, so an assertion that trimmed either side would accept a
     * value the screen never carried. Nothing anywhere in this class trims or strips a served value.
     *
     * @param  value the sending value
     * @param  width the receiving width
     * @return exactly {@code width} characters
     */
    private static String padded(final String value, final int width) {
        final StringBuilder field = new StringBuilder(width).append(value);
        while (field.length() < width) {
            field.append(' ');
        }
        return field.toString();
    }

    /**
     * Builds one of the rows this specification owns.
     *
     * <p>Built through the shared fixture factory and written through the shipped repository: no raw
     * statement, no schema change and no direct connection is used to create it. Every reserved key is
     * above the highest delivered card number and names a delivered account, so the row satisfies the
     * layout's own width and digit rules and the card-to-account foreign key alike.
     *
     * @param  cardNumber the reserved key
     * @param  embossedName the stored embossed name
     * @param  activeStatus the stored status character
     * @return the stored row
     */
    private Card writeReservedCard(final String cardNumber, final String embossedName,
            final String activeStatus) {
        return this.cards.saveAndFlush(TestDataFactory.card()
                .cardNumber(cardNumber)
                .accountId(RESERVED_ROW_ACCOUNT)
                .verificationCode(RESERVED_VERIFICATION_CODE)
                .embossedName(embossedName)
                .expirationDate(RESERVED_EXPIRATION_DATE)
                .activeStatus(activeStatus)
                .build());
    }

    /**
     * Reads a reserved row back as the server now holds it.
     *
     * @param  cardNumber the reserved key
     * @return the stored row
     */
    private Card storedCard(final String cardNumber) {
        final Optional<Card> stored = this.cards.findById(cardNumber);
        assertThat(stored).as("the reserved row must exist").isPresent();
        return stored.orElseThrow();
    }

    /**
     * The first turn of the card-update conversation: the fresh entry that asks for the two search keys.
     *
     * <p>A turn carrying no echoed state at all is the analogue of a zero-length communication area, which
     * is the state the legacy reaches on a genuine first entry. It is not an error and it is not treated as
     * one.
     *
     * @return the screen the fresh entry produces
     * @throws Exception if the boundary cannot be reached
     */
    private JsonNode updateFreshEntry() throws Exception {
        return updateTurn(turnWith(KeyAction.ENTER));
    }

    /**
     * The first two turns of the card-update conversation: the fresh entry followed by the fetch.
     *
     * @param  accountId the eleven-character account identifier to key on
     * @param  cardNumber the sixteen-character card number to key on
     * @return the screen the fetch produces
     * @throws Exception if the boundary cannot be reached
     */
    private JsonNode updateFetch(final String accountId, final String cardNumber) throws Exception {
        final Map<String, Object> fetch = echoUpdateState(updateFreshEntry(), KeyAction.ENTER);
        fetch.put(PROPERTY_ACCOUNT_ID, accountId);
        fetch.put(PROPERTY_CARD_NUMBER, cardNumber);
        return updateTurn(fetch);
    }

    /**
     * A resubmission carrying the values the previous turn presented, so a caller changes only what it means
     * to change.
     *
     * <p>The account identifier is deliberately absent. It is protected once a card has been fetched, the
     * write takes the owning account from the carried image rather than from the screen, and the confirming
     * submission's own constraint group requires its absence.
     *
     * @param  presented the screen the previous turn produced
     * @param  keyAction the key the operator pressed
     * @return a mutable payload carrying the presented values
     */
    private static Map<String, Object> resubmissionOf(final JsonNode presented,
            final KeyAction keyAction) {
        final Map<String, Object> body = echoUpdateState(presented, keyAction);
        body.put(PROPERTY_CARD_NUMBER, textOf(presented, PROPERTY_CARD_NUMBER));
        body.put(PROPERTY_EMBOSSED_NAME, textOf(presented, PROPERTY_EMBOSSED_NAME));
        body.put(PROPERTY_ACTIVE_STATUS, textOf(presented, PROPERTY_ACTIVE_STATUS));
        body.put(PROPERTY_EXPIRY_MONTH, textOf(presented, PROPERTY_EXPIRY_MONTH));
        body.put(PROPERTY_EXPIRY_YEAR, textOf(presented, PROPERTY_EXPIRY_YEAR));
        return body;
    }

    // ===============================================================================================
    // THE CARD LIST: THE PAGE SIZE OF SEVEN
    // ===============================================================================================

    /**
     * The screen shape of {@code COCRDLIC}, which the source fixes three independent ways.
     */
    @Nested
    @DisplayName("List - page size exactly 7")
    class ListPageSize {

        /** Creates the nested specification. */
        ListPageSize() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("a full page carries exactly seven rows, in ascending key order, one per screen slot")
        void aFullPageCarriesExactlySevenRows() throws Exception {
            // COCRDLIC lines 250 to 253 declare a 196-character presentation area; line 255 redefines it
            // as seven occurrences of an 11 + 16 + 1 character row. Seven times twenty-eight is 196.
            final JsonNode screen = listTurn(turnWith(KeyAction.ENTER));

            assertThat(screen.get("rows").size())
                    .as("the screen has seven row slots and a full page fills all of them")
                    .isEqualTo(CARD_LIST_ROWS);
            assertThat(cardNumbersOf(screen))
                    .as("the browse walks the base cluster, which is keyed on the card number, so a "
                            + "forward page presents ascending")
                    .containsExactlyElementsOf(PAGE_ONE_CARDS);
            assertThat(screenSlotsOf(screen))
                    .as("a full forward page fills the slots upward from the first")
                    .containsExactly(1, 2, 3, 4, 5, 6, 7);
            assertThat(screen.get("pageMetadata").get("pageSize").asInt())
                    .as("the published page size is the screen's shape, not a negotiated value")
                    .isEqualTo(CARD_LIST_ROWS);
        }

        @Test
        @DisplayName("the three screen page sizes are three separate constants and this screen's is seven")
        void theThreePageSizesAreThreeSeparateConstants() {
            assertThat(PageMetadata.CARD_LIST_PAGE_SIZE)
                    .as("the card list has seven rows")
                    .isEqualTo(CARD_LIST_ROWS);
            assertThat(PageMetadata.TRANSACTION_LIST_PAGE_SIZE)
                    .as("the transaction list has ten, established from its own loop bounds")
                    .isEqualTo(TEN_ROW_SCREEN);
            assertThat(PageMetadata.USER_LIST_PAGE_SIZE)
                    .as("the user list has ten, established from its own row table")
                    .isEqualTo(TEN_ROW_SCREEN);
            assertThat(PageMetadata.CARD_LIST_PAGE_SIZE)
                    .as("three screens, three constants: derive one from another and a change to either "
                            + "screen would silently move the other")
                    .isNotEqualTo(PageMetadata.TRANSACTION_LIST_PAGE_SIZE)
                    .isNotEqualTo(PageMetadata.USER_LIST_PAGE_SIZE);
        }

        @Test
        @DisplayName("the paging state is cursor-based, carries two independent indicators, and publishes "
                + "no total, offset or framework paging type")
        void thePagingStateIsCursorBasedAndCarriesNoTotals() throws Exception {
            final JsonNode paging = listTurn(turnWith(KeyAction.ENTER)).get("pageMetadata");

            assertThat(propertyNamesOf(paging))
                    .as("the browse resumes from a record key, which is what a keyed browse retains")
                    .contains("pageSize", "previousCursorKey", "nextCursorKey", "direction",
                            "hasMorePages", "hasPreviousPages", "displayedPageNumber")
                    .as("a total, an offset or a framework page shape would describe a screen the legacy "
                            + "never had and could not have computed")
                    .doesNotContain("totalElements", "totalPages", "count", "offset", "number",
                            "numberOfElements", "pageable", "sort", "content", "first", "last", "empty",
                            "totalRecords", "size");
            assertThat(flagOf(paging, "hasMorePages"))
                    .as("a page follows the first one, because the seed carries more than seven cards")
                    .isTrue();
            assertThat(flagOf(paging, "hasPreviousPages"))
                    .as("the two indicators are independent, which is why the source renders a different "
                            + "message at each end of the browse")
                    .isFalse();
        }

        @Test
        @DisplayName("the displayed page indicator is three characters wide here and eight on the ten-row "
                + "screens, and the two widths are never unified")
        void thePageIndicatorWidthIsThisScreensOwn() throws Exception {
            // COCRDLI.CPY declares a three-character page field; COTRN00.CPY and COUSR00.CPY declare
            // eight-character ones.
            assertThat(CardListResponse.DISPLAYED_PAGE_NUMBER_LENGTH)
                    .isEqualTo(LIST_PAGE_INDICATOR_WIDTH);
            assertThat(PageMetadata.DISPLAYED_PAGE_NUMBER_MAX_LENGTH)
                    .isEqualTo(WIDE_PAGE_INDICATOR_WIDTH);
            assertThat(CardListResponse.DISPLAYED_PAGE_NUMBER_LENGTH)
                    .as("two character fields of different width, and neither is derived from the other")
                    .isNotEqualTo(PageMetadata.DISPLAYED_PAGE_NUMBER_MAX_LENGTH);

            final JsonNode screen = listTurn(turnWith(KeyAction.ENTER));
            assertThat(textOf(screen, "displayedPageNumber"))
                    .as("the retained page number starts at one and the screen displays it")
                    .isEqualTo("1");
        }
    }

    // ===============================================================================================
    // THE CARD LIST: THE BACKWARD FILL AND THE FOUR BOUNDARY TEXTS
    // ===============================================================================================

    /**
     * The backward browse, which fills the screen's slots downward from the seventh, and the four texts the
     * source composes at the ends of the browse.
     */
    @Nested
    @DisplayName("List - backward fill 7 down to 1")
    class ListBackwardFill {

        /** Creates the nested specification. */
        ListBackwardFill() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("a backward turn returns the preceding page as an ordered list, filled from the "
                + "seventh slot down to the first")
        void aBackwardTurnReturnsThePrecedingPageInOrder() throws Exception {
            // The forward arm at COCRDLIC line 486 repositions on the retained last key and raises the
            // page number; the backward arm at line 501 lowers it and walks the key sequence the other
            // way, storing each accepted row into a slot it then decrements. The read therefore runs
            // descending while the assembled page presents ascending.
            final JsonNode pageOne = listTurn(turnWith(KeyAction.ENTER));

            final Map<String, Object> forward = turnWith(KeyAction.PFK08);
            echoListState(forward, pageOne);
            final JsonNode pageTwo = listTurn(forward);

            assertThat(cardNumbersOf(pageTwo))
                    .as("the second page begins at the row the look-ahead moved the cursor to")
                    .hasSize(CARD_LIST_ROWS)
                    .doesNotContainAnyElementsOf(PAGE_ONE_CARDS)
                    .element(0)
                    .isEqualTo(PAGE_TWO_FIRST_CARD);
            assertThat(textOf(pageTwo, "displayedPageNumber")).isEqualTo("2");

            final Map<String, Object> backward = turnWith(KeyAction.PFK07);
            echoListState(backward, pageTwo);
            final JsonNode returned = listTurn(backward);

            assertThat(cardNumbersOf(returned))
                    .as("membership is not enough: the page must come back in the presentation order the "
                            + "downward fill produced, and nothing here re-sorts it to make that true")
                    .containsExactlyElementsOf(PAGE_ONE_CARDS);
            assertThat(screenSlotsOf(returned))
                    .as("a full backward page occupies every slot, and the rows are published against "
                            + "the slots they were filled into")
                    .containsExactly(1, 2, 3, 4, 5, 6, 7);
            assertThat(textOf(returned.get("pageMetadata"), "direction"))
                    .as("the direction the browse walked is published, because the client echoes it")
                    .isEqualTo("BACKWARD");
            assertThat(textOf(returned, "displayedPageNumber"))
                    .as("the backward arm lowers the retained page number before it reads")
                    .isEqualTo("1");
        }

        @Test
        @DisplayName("the backward key on the first page is refused with the no-previous-pages text and "
                + "re-presents the same page")
        void theBackwardKeyOnTheFirstPageIsRefused() throws Exception {
            // Arms at COCRDLIC lines 439 and 444: the backward key while already on the first page
            // re-reads the same page forward from its retained first key.
            final JsonNode pageOne = listTurn(turnWith(KeyAction.ENTER));

            final Map<String, Object> backward = turnWith(KeyAction.PFK07);
            echoListState(backward, pageOne);
            final JsonNode refused = listTurn(backward);

            assertThat(textOf(refused, "errorMessage")).isEqualTo(NO_PREVIOUS_PAGES);
            assertThat(CardListResponse.MSG_NO_PREVIOUS_PAGES)
                    .as("the boundary and this specification must be stating the same text")
                    .isEqualTo(NO_PREVIOUS_PAGES);
            assertThat(cardNumbersOf(refused))
                    .as("the refusal still presents a screen, and it is the same page")
                    .containsExactlyElementsOf(PAGE_ONE_CARDS);
        }

        @Test
        @DisplayName("a page that runs out of data reports the end of the data rather than a refused "
                + "navigation")
        void aPartialPageReportsTheEndOfTheData() throws Exception {
            // The end-of-file arm of the forward read raises this text when no message is standing.
            final JsonNode pageOne = listTurn(turnWith(KeyAction.ENTER));

            final Map<String, Object> narrowed = turnWith(KeyAction.ENTER);
            echoListState(narrowed, pageOne);
            narrowed.put(PROPERTY_ACCOUNT_ID_FILTER, SINGLE_CARD_ACCOUNT);
            final JsonNode screen = listTurn(narrowed);

            assertThat(cardNumbersOf(screen)).containsExactly(SINGLE_CARD_NUMBER);
            assertThat(textOf(screen, "errorMessage")).isEqualTo(NO_MORE_RECORDS);
            assertThat(CardListResponse.MSG_NO_MORE_RECORDS).isEqualTo(NO_MORE_RECORDS);
        }

        @Test
        @DisplayName("a forward turn past a page already known to be the last is refused with the "
                + "no-more-pages text")
        void aForwardTurnPastTheLastPageIsRefused() throws Exception {
            // Three arms of the message cascade in sequence: the end-of-data arm, then the arm that
            // records the last page as shown, then the arm that refuses a further advance.
            final JsonNode pageOne = listTurn(turnWith(KeyAction.ENTER));

            final Map<String, Object> narrowed = turnWith(KeyAction.ENTER);
            echoListState(narrowed, pageOne);
            narrowed.put(PROPERTY_ACCOUNT_ID_FILTER, SINGLE_CARD_ACCOUNT);
            final JsonNode lastPage = listTurn(narrowed);
            assertThat(flagOf(lastPage, "lastPageAlreadyShown")).isFalse();

            final Map<String, Object> advance = turnWith(KeyAction.PFK08);
            echoListState(advance, lastPage);
            advance.put(PROPERTY_ACCOUNT_ID_FILTER, SINGLE_CARD_ACCOUNT);
            final JsonNode recorded = listTurn(advance);
            assertThat(flagOf(recorded, "lastPageAlreadyShown"))
                    .as("the first advance past the end records that the last page has been shown")
                    .isTrue();

            final Map<String, Object> advanceAgain = turnWith(KeyAction.PFK08);
            echoListState(advanceAgain, recorded);
            advanceAgain.put(PROPERTY_ACCOUNT_ID_FILTER, SINGLE_CARD_ACCOUNT);
            final JsonNode refused = listTurn(advanceAgain);

            assertThat(textOf(refused, "errorMessage")).isEqualTo(NO_MORE_PAGES);
            assertThat(CardListResponse.MSG_NO_MORE_PAGES).isEqualTo(NO_MORE_PAGES);
        }

        @Test
        @DisplayName("filters that match nothing produce the one boundary text that ends with a full stop")
        void filtersThatMatchNothingProduceTheOnlyTextEndingInAFullStop() throws Exception {
            final JsonNode pageOne = listTurn(turnWith(KeyAction.ENTER));

            final Map<String, Object> narrowed = turnWith(KeyAction.ENTER);
            echoListState(narrowed, pageOne);
            narrowed.put(PROPERTY_ACCOUNT_ID_FILTER, ABSENT_ACCOUNT);
            final JsonNode screen = listTurn(narrowed);

            assertThat(screen.get("rows").size()).isZero();
            assertThat(textOf(screen, "errorMessage")).isEqualTo(NO_RECORDS_FOUND);
            assertThat(CardListResponse.MSG_NO_RECORDS_FOUND).isEqualTo(NO_RECORDS_FOUND);
            assertThat(NO_RECORDS_FOUND)
                    .as("this is the only one of the four boundary texts that carries a full stop")
                    .endsWith(".");
            assertThat(NO_PREVIOUS_PAGES).doesNotEndWith(".");
            assertThat(NO_MORE_PAGES).doesNotEndWith(".");
            assertThat(NO_MORE_RECORDS).doesNotEndWith(".");
            assertThat(textOf(screen, "infoMessage"))
                    .as("the advisory is cleared when nothing was found, so the screen carries the "
                            + "no-records text alone")
                    .isEmpty();
        }
    }

    // ===============================================================================================
    // THE CARD LIST: THE TWO OPTIONAL FILTERS
    // ===============================================================================================

    /**
     * The two optional key filters, their rejection texts, and the absence of any default for either.
     */
    @Nested
    @DisplayName("List - filter literals and no default")
    class ListFilters {

        /** Creates the nested specification. */
        ListFilters() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("the account filter rejection text is byte exact, with no space after the comma and "
                + "the wording \"A 11\"")
        void theAccountFilterRejectionTextIsByteExact() throws Exception {
            final JsonNode pageOne = listTurn(turnWith(KeyAction.ENTER));

            final Map<String, Object> malformed = turnWith(KeyAction.ENTER);
            echoListState(malformed, pageOne);
            // Eleven characters, so the width bound is satisfied and the screen's own digit edit is what
            // rejects it. The class condition is true only when every position holds a digit.
            malformed.put(PROPERTY_ACCOUNT_ID_FILTER, "0000000000A");
            final JsonNode screen = listTurn(malformed);

            assertThat(textOf(screen, "errorMessage")).isEqualTo(ACCOUNT_FILTER_REJECTED);
            assertThat(CardListResponse.MSG_ACCOUNT_FILTER_INVALID).isEqualTo(ACCOUNT_FILTER_REJECTED);
            assertThat(ACCOUNT_FILTER_REJECTED)
                    .as("no space follows the comma, and the wording is \"A 11\" rather than \"AN 11\"")
                    .contains("FILTER,IF")
                    .doesNotContain("FILTER, IF")
                    .contains("A 11 DIGIT")
                    .doesNotContain("AN 11");
            assertThat(flagOf(screen, "generalError"))
                    .as("a rejected filter raises the program's own error switch")
                    .isTrue();
        }

        @Test
        @DisplayName("the card filter rejection text is byte exact, with no space after the comma and the "
                + "wording \"A 16\"")
        void theCardFilterRejectionTextIsByteExact() throws Exception {
            final JsonNode pageOne = listTurn(turnWith(KeyAction.ENTER));

            final Map<String, Object> malformed = turnWith(KeyAction.ENTER);
            echoListState(malformed, pageOne);
            malformed.put(PROPERTY_CARD_NUMBER_FILTER, "000000000000000A");
            final JsonNode screen = listTurn(malformed);

            assertThat(textOf(screen, "errorMessage")).isEqualTo(CARD_FILTER_REJECTED);
            assertThat(CardListResponse.MSG_CARD_FILTER_INVALID).isEqualTo(CARD_FILTER_REJECTED);
            assertThat(CARD_FILTER_REJECTED)
                    .contains("FILTER,IF")
                    .doesNotContain("FILTER, IF")
                    .contains("A 16 DIGIT")
                    .doesNotContain("AN 16");
        }

        @Test
        @DisplayName("omitting both filters returns an unfiltered first page and echoes neither filter")
        void omittingBothFiltersReturnsAnUnfilteredFirstPage() throws Exception {
            // Both filters are optional and neither carries a default: an absent filter is not a filter of
            // zeros, of spaces or of anything else, and a defaulted one would narrow a page the operator
            // asked to see whole.
            final JsonNode screen = listTurn(turnWith(KeyAction.ENTER));

            assertThat(cardNumbersOf(screen))
                    .as("an unfiltered page is the first seven rows of the whole cluster")
                    .containsExactlyElementsOf(PAGE_ONE_CARDS);
            assertThat(textOf(screen, RESPONSE_ACCOUNT_FILTER))
                    .as("nothing was typed into the account filter, so nothing is echoed back into it")
                    .isNull();
            assertThat(textOf(screen, PROPERTY_CARD_NUMBER_FILTER)).isNull();
        }

        @Test
        @DisplayName("a supplied account filter narrows the page to that account's cards, in key order")
        void aSuppliedAccountFilterNarrowsThePage() throws Exception {
            // The behaviour that replaces the CARDDATA alternate index on the account identifier, which
            // app/jcl/CARDFILE.jcl defines over eleven bytes at offset sixteen. Only the results and their
            // order are asserted; no index is named.
            writeReservedCard(RESERVED_UPDATE_CARD, RESERVED_EMBOSSED_NAME, STATUS_ACTIVE);

            final JsonNode pageOne = listTurn(turnWith(KeyAction.ENTER));
            final Map<String, Object> narrowed = turnWith(KeyAction.ENTER);
            echoListState(narrowed, pageOne);
            narrowed.put(PROPERTY_ACCOUNT_ID_FILTER, RESERVED_ROW_ACCOUNT);
            final JsonNode screen = listTurn(narrowed);

            assertThat(cardNumbersOf(screen))
                    .as("both of that account's cards are returned, ordered on the base cluster's own key")
                    .containsExactly(RESERVED_ROW_ACCOUNT_SEEDED_CARD, RESERVED_UPDATE_CARD);
            assertThat(textOf(screen, RESPONSE_ACCOUNT_FILTER))
                    .as("the filter is echoed exactly as transmitted, at its full eleven characters")
                    .isEqualTo(RESERVED_ROW_ACCOUNT);
        }

        @Test
        @DisplayName("a supplied card filter narrows the page to that one card")
        void aSuppliedCardFilterNarrowsThePageToOneCard() throws Exception {
            final JsonNode pageOne = listTurn(turnWith(KeyAction.ENTER));

            final Map<String, Object> narrowed = turnWith(KeyAction.ENTER);
            echoListState(narrowed, pageOne);
            narrowed.put(PROPERTY_CARD_NUMBER_FILTER, SINGLE_CARD_NUMBER);
            final JsonNode screen = listTurn(narrowed);

            assertThat(cardNumbersOf(screen)).containsExactly(SINGLE_CARD_NUMBER);
            assertThat(textOf(screen, PROPERTY_CARD_NUMBER_FILTER)).isEqualTo(SINGLE_CARD_NUMBER);
        }
    }

    // ===============================================================================================
    // THE CARD LIST: THE SEVEN POSITIONAL SELECTORS
    // ===============================================================================================

    /**
     * The seven action selectors, the one-action-per-page rule, and the positional indicator that names the
     * offending rows.
     */
    @Nested
    @DisplayName("List - positional selectors and at-most-one action")
    class ListSelectors {

        /** Creates the nested specification. */
        ListSelectors() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("a selection in the fifth row is reported at the fifth position, with the empty "
                + "positions surviving around it")
        void aSelectionInTheFifthRowIsReportedAtTheFifthPosition() throws Exception {
            // COCRDLIC lines 1099 to 1115 walk the seven slots in order; the trailing arm at line 1108
            // raises the slot's own flag. Lines 1088 to 1093 write every slot, so the cleared entries
            // survive at their own positions - which is the whole reason the indicator is positional.
            final JsonNode pageOne = listTurn(turnWith(KeyAction.ENTER));

            final Map<String, Object> submission = turnWith(KeyAction.ENTER);
            echoListState(submission, pageOne);
            select(submission, 5, "X");
            final JsonNode screen = listTurn(submission);

            assertThat(selectionErrorFlagsOf(screen))
                    .as("one entry per row, in row order, with the fifth raised and the six others "
                            + "cleared rather than absent")
                    .containsExactly(false, false, false, false, true, false, false);
            assertThat(textOf(screen, "errorMessage")).isEqualTo(INVALID_ACTION_CODE);
            assertThat(CardListResponse.MSG_INVALID_ACTION_CODE).isEqualTo(INVALID_ACTION_CODE);
            assertThat(findingFor(screen, "selection5"))
                    .as("the finding names the row by its position, so a client can mark the row the "
                            + "operator typed into")
                    .isPresent();
            assertThat(textOf(findingFor(screen, "selection5").orElseThrow(), "state"))
                    .as("a value was supplied and failed its edit, which is the invalid state and not the "
                            + "missing one")
                    .isEqualTo(ErrorResponse.FieldState.INVALID.name());
        }

        @Test
        @DisplayName("two selections on one page are refused, and both offending row positions are named")
        void twoSelectionsAreRefusedAndBothRowsAreNamed() throws Exception {
            // COCRDLIC lines 1079 to 1082 tally the marks across the seven slots and lines 1084 to 1095
            // refuse a count above one, writing the positional indicator from the same flags.
            final JsonNode pageOne = listTurn(turnWith(KeyAction.ENTER));

            final Map<String, Object> submission = turnWith(KeyAction.ENTER);
            echoListState(submission, pageOne);
            select(submission, 2, "S");
            select(submission, 6, "U");
            final JsonNode screen = listTurn(submission);

            assertThat(textOf(screen, "errorMessage")).isEqualTo(MORE_THAN_ONE_ACTION);
            assertThat(CardListResponse.MSG_MORE_THAN_ONE_ACTION).isEqualTo(MORE_THAN_ONE_ACTION);
            assertThat(selectionErrorFlagsOf(screen))
                    .as("the refusal names which rows offended, and it names them by position")
                    .containsExactly(false, true, false, false, false, true, false);
            assertThat(findingFor(screen, "selection2")).isPresent();
            assertThat(findingFor(screen, "selection6")).isPresent();
            assertThat(findingFor(screen, "selection1")).isNotPresent();
            assertThat(textOf(screen, "nextRoute"))
                    .as("a refused submission goes nowhere: it re-presents this screen")
                    .isEqualTo(ROUTE_CARD_LIST);
        }

        @Test
        @DisplayName("the view selector sends the client to the card-detail screen")
        void theViewSelectorSendsTheClientToTheDetailScreen() throws Exception {
            final JsonNode pageOne = listTurn(turnWith(KeyAction.ENTER));

            final Map<String, Object> submission = turnWith(KeyAction.ENTER);
            echoListState(submission, pageOne);
            select(submission, 3, "S");
            final JsonNode screen = listTurn(submission);

            assertThat(textOf(screen, "nextRoute")).isEqualTo(ROUTE_CARD_DETAIL);
            assertThat(flagOf(screen, "generalError")).isFalse();
        }

        @Test
        @DisplayName("the update selector sends the client to the card-update screen")
        void theUpdateSelectorSendsTheClientToTheUpdateScreen() throws Exception {
            final JsonNode pageOne = listTurn(turnWith(KeyAction.ENTER));

            final Map<String, Object> submission = turnWith(KeyAction.ENTER);
            echoListState(submission, pageOne);
            select(submission, 3, "U");
            final JsonNode screen = listTurn(submission);

            assertThat(textOf(screen, "nextRoute")).isEqualTo(ROUTE_CARD_UPDATE);
            assertThat(flagOf(screen, "generalError")).isFalse();
        }

        @Test
        @DisplayName("the instructional text names the two action codes the selection column accepts")
        void theInstructionalTextNamesTheTwoActionCodes() throws Exception {
            final JsonNode screen = listTurn(turnWith(KeyAction.ENTER));

            assertThat(textOf(screen, "infoMessage")).isEqualTo(ROW_ACTION_PROMPT);
            assertThat(CardListResponse.MSG_ROW_ACTION_PROMPT).isEqualTo(ROW_ACTION_PROMPT);
            assertThat(ROW_ACTION_PROMPT.length())
                    .as("the advisory fits the informational field this screen declares")
                    .isLessThanOrEqualTo(LIST_INFO_WIDTH);
        }
    }

    // ===============================================================================================
    // THE CARD LIST: WHAT A ROW DOES NOT CARRY
    // ===============================================================================================

    /**
     * The row contract: the four value items the program uses, the screen slot the list order cannot carry,
     * and nothing else.
     */
    @Nested
    @DisplayName("List - unmodelled companion fields absent")
    class ListRowShape {

        /** Creates the nested specification. */
        ListRowShape() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("a row carries the four value items plus the screen slot the list order cannot carry, "
                + "and carries the six dark companion items not at all")
        void aRowCarriesOnlyItsFourValueItemsAndItsSlot() throws Exception {
            // The list map declares six further dark, auto-skip protected companion items - numbered two
            // through seven, with no number one - and an exhaustive search of the 1,459-line program finds
            // zero references to any of them. They are therefore neither request nor response items. The
            // screen slot is published because the list order does not carry it: a partial backward page
            // leaves the low slots empty, and a client that inferred the slot from the list position would
            // mark its action against a different card than the operator chose.
            final JsonNode screen = listTurn(turnWith(KeyAction.ENTER));
            final JsonNode row = screen.get("rows").get(0);

            assertThat(propertyNamesOf(row))
                    .as("exactly the four value items and the slot; anything else would be an item the "
                            + "program never reads")
                    .containsExactlyInAnyOrder("screenSlot", "selection", "accountNumber", "cardNumber",
                            "cardStatus");
            assertThat(textOf(row, "accountNumber"))
                    .as("eleven characters, as the row table's account item declares")
                    .hasSize(CardListResponse.ACCOUNT_NUMBER_LENGTH)
                    .isEqualTo(PAGE_ONE_ACCOUNTS.get(0));
            assertThat(textOf(row, "cardNumber"))
                    .as("sixteen characters, as the row table's card item declares")
                    .hasSize(CardListResponse.CARD_NUMBER_LENGTH);
            assertThat(textOf(row, "cardStatus"))
                    .as("one character, as the row table's status item declares")
                    .hasSize(CardListResponse.CARD_STATUS_LENGTH);
        }

        @Test
        @DisplayName("the card number is carried in full, because the legacy row carried it in full and "
                + "no requirement introduces masking")
        void theCardNumberIsCarriedInFull() throws Exception {
            final JsonNode screen = listTurn(turnWith(KeyAction.ENTER));

            assertThat(cardNumbersOf(screen))
                    .as("a shortened business key identifies nothing, and the absence of masking here is "
                            + "a documented gap in the legacy design rather than a defect to close")
                    .containsExactlyElementsOf(PAGE_ONE_CARDS);
            for (final String cardNumber : cardNumbersOf(screen)) {
                assertThat(cardNumber).doesNotContain("*").hasSize(16);
            }
        }

        @Test
        @DisplayName("every row's owning account is published at full width, so a leading zero survives")
        void everyRowCarriesItsOwningAccountAtFullWidth() throws Exception {
            final JsonNode screen = listTurn(turnWith(KeyAction.ENTER));

            final List<String> accounts = new ArrayList<>(CARD_LIST_ROWS);
            for (final JsonNode row : screen.get("rows")) {
                assertThat(row.get("accountNumber").isTextual())
                        .as("an identifier crosses this contract as text; a numeric type would discard "
                                + "the leading zeros the eleven-character field carries")
                        .isTrue();
                accounts.add(textOf(row, "accountNumber"));
            }
            assertThat(accounts).containsExactlyElementsOf(PAGE_ONE_ACCOUNTS);
        }

        @Test
        @DisplayName("no screen artefact of any kind crosses the boundary")
        void noScreenArtefactCrossesTheBoundary() throws Exception {
            final String body = submit(listRoute(), turnWith(KeyAction.ENTER), userSession())
                    .getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(body)
                    .as("no attribute byte, colour, highlight, coordinate, terminal area or generated "
                            + "control item may reach a client")
                    .doesNotContain("DFH")
                    .doesNotContain("ATTRB")
                    .doesNotContain("TIOA")
                    .doesNotContain("EXEC CICS")
                    .doesNotContain("PICTURE X")
                    .doesNotContain("OCCURS");
        }
    }

    // ===============================================================================================
    // THE CARD DETAIL SCREEN
    // ===============================================================================================

    /**
     * The detail screen's own message widths, its absent expiry day, and the fidelity of the texts its
     * message group declares.
     */
    @Nested
    @DisplayName("Detail - 40/80 message widths and literal fidelity")
    class DetailScreen {

        /** Creates the nested specification. */
        DetailScreen() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("the information and error widths are this screen's own and are not the list screen's")
        void theMessageWidthsAreThisScreensOwn() {
            // COCRDSL.CPY declares a 40-character information field and an 80-character error field, while
            // COCRDLI.CPY declares 45 and 78. Four separate declarations, and no constant is shared.
            assertThat(CardDetailResponse.INFO_MESSAGE_LENGTH).isEqualTo(DETAIL_INFO_WIDTH);
            assertThat(CardDetailResponse.ERROR_MESSAGE_LENGTH).isEqualTo(DETAIL_ERROR_WIDTH);
            assertThat(CardListResponse.INFO_MESSAGE_LENGTH).isEqualTo(LIST_INFO_WIDTH);
            assertThat(CardListResponse.ERROR_MESSAGE_LENGTH).isEqualTo(LIST_ERROR_WIDTH);
            assertThat(CardDetailResponse.INFO_MESSAGE_LENGTH)
                    .as("sharing a width constant across two screens would let a change to one map move "
                            + "the other screen's field")
                    .isNotEqualTo(CardListResponse.INFO_MESSAGE_LENGTH);
            assertThat(CardDetailResponse.ERROR_MESSAGE_LENGTH)
                    .isNotEqualTo(CardListResponse.ERROR_MESSAGE_LENGTH);
            assertThat(CardUpdateResponse.INFORMATION_MESSAGE_LENGTH)
                    .as("the update map declares the same two widths as the detail map, and each declares "
                            + "them for itself")
                    .isEqualTo(DETAIL_INFO_WIDTH);
            assertThat(CardUpdateResponse.ERROR_MESSAGE_LENGTH).isEqualTo(DETAIL_ERROR_WIDTH);
        }

        @Test
        @DisplayName("a first entry prompts for both keys, at the declared forty characters, with the error "
                + "field blank at its declared eighty")
        void aFirstEntryPromptsForBothKeysAtFullWidth() throws Exception {
            // COCRDSLC line 132 declares the prompt; the move into the outbound field pads it to forty.
            final JsonNode screen = detailTurn(turnWith(KeyAction.ENTER));

            assertThat(textOf(screen, "infoMessage"))
                    .as("the trailing spaces of a fixed-width field are part of what the screen carried, "
                            + "so the value is asserted at full width and never trimmed")
                    .isEqualTo(padded(PROMPT_FOR_SEARCH_KEYS, DETAIL_INFO_WIDTH))
                    .hasSize(DETAIL_INFO_WIDTH);
            assertThat(CardDetailResponse.MSG_PROMPT_FOR_INPUT).isEqualTo(PROMPT_FOR_SEARCH_KEYS);
            assertThat(textOf(screen, "errorMessage"))
                    .as("the error field is blank rather than absent, and it is blank at its own width")
                    .hasSize(DETAIL_ERROR_WIDTH)
                    .isBlank();
            assertThat(flagOf(screen, "generalError")).isFalse();
        }

        @Test
        @DisplayName("the detail screen carries no expiry-day item at all")
        void theDetailScreenCarriesNoExpiryDayItem() throws Exception {
            // The map declares a month and a year and no day item of any kind, which is why nothing here
            // publishes one - unlike the update screen, which carries a hidden protected day.
            final JsonNode screen = detailTurn(turnWith(KeyAction.ENTER));

            assertThat(propertyNamesOf(screen))
                    .contains(PROPERTY_EXPIRY_MONTH, PROPERTY_EXPIRY_YEAR)
                    .doesNotContain(PROPERTY_EXPIRY_DAY);
        }

        @Test
        @DisplayName("a blank account is answered with the account-not-provided text and a missing-state "
                + "finding")
        void aBlankAccountIsAnsweredWithItsOwnText() throws Exception {
            final Map<String, Object> submission = resubmission(detailTurn(turnWith(KeyAction.ENTER)));
            submission.put(PROPERTY_CARD_NUMBER_FILTER, SINGLE_CARD_NUMBER);
            final JsonNode screen = detailTurn(submission);

            assertThat(textOf(screen, "errorMessage"))
                    .isEqualTo(padded(ACCOUNT_NOT_PROVIDED, DETAIL_ERROR_WIDTH));
            assertThat(CardDetailResponse.MSG_PROMPT_FOR_ACCOUNT).isEqualTo(ACCOUNT_NOT_PROVIDED);
            final JsonNode finding = findingFor(screen, PROPERTY_ACCOUNT_ID).orElseThrow();
            assertThat(textOf(finding, "state"))
                    .as("a field left blank is the missing state, which is a different remedy from a "
                            + "value that failed its edit")
                    .isEqualTo(ErrorResponse.FieldState.MISSING.name());
            assertThat(textOf(finding, "screenFieldId")).isEqualTo(SCREEN_FIELD_ACCOUNT);
        }

        @Test
        @DisplayName("a blank card is answered with the card-not-provided text and a missing-state finding")
        void aBlankCardIsAnsweredWithItsOwnText() throws Exception {
            final Map<String, Object> submission = resubmission(detailTurn(turnWith(KeyAction.ENTER)));
            submission.put(PROPERTY_ACCOUNT_ID_FILTER, SINGLE_CARD_ACCOUNT);
            final JsonNode screen = detailTurn(submission);

            assertThat(textOf(screen, "errorMessage"))
                    .isEqualTo(padded(CARD_NOT_PROVIDED, DETAIL_ERROR_WIDTH));
            assertThat(CardDetailResponse.MSG_PROMPT_FOR_CARD).isEqualTo(CARD_NOT_PROVIDED);
            final JsonNode finding = findingFor(screen, PROPERTY_CARD_NUMBER).orElseThrow();
            assertThat(textOf(finding, "state")).isEqualTo(ErrorResponse.FieldState.MISSING.name());
            assertThat(textOf(finding, "screenFieldId")).isEqualTo(SCREEN_FIELD_CARD);
        }

        @Test
        @DisplayName("both keys blank is answered with the no-input text, which is assigned ungated and "
                + "therefore overwrites the first message raised")
        void bothKeysBlankIsAnsweredWithTheNoInputText() throws Exception {
            // The account edit raises its own text first, and the both-blank assignment that follows is
            // not gated on the message being empty, so it wins. Reproduced rather than tidied.
            final JsonNode screen = detailTurn(resubmission(detailTurn(turnWith(KeyAction.ENTER))));

            assertThat(textOf(screen, "errorMessage"))
                    .isEqualTo(padded(NO_INPUT_RECEIVED, DETAIL_ERROR_WIDTH));
            assertThat(CardDetailResponse.MSG_NO_SEARCH_CRITERIA_RECEIVED).isEqualTo(NO_INPUT_RECEIVED);
            assertThat(flagOf(screen, "generalError")).isTrue();
        }

        @Test
        @DisplayName("a non-numeric account raises the upper-case filter text, while the lower-case "
                + "non-zero text stays a declared-but-unraised item - declared twice, with one text")
        void aNonNumericAccountRaisesTheUpperCaseFilterText() throws Exception {
            // The failure arm moves the filter literal rather than the condition name declared above it,
            // so the served text is the upper-case one. The lower-case name is declared at COCRDSLC lines
            // 145 and 147 - twice, with identical text - and is never set anywhere in the member.
            final Map<String, Object> submission = resubmission(detailTurn(turnWith(KeyAction.ENTER)));
            submission.put(PROPERTY_ACCOUNT_ID_FILTER, "0000000000A");
            submission.put(PROPERTY_CARD_NUMBER_FILTER, SINGLE_CARD_NUMBER);
            final JsonNode screen = detailTurn(submission);

            assertThat(textOf(screen, "errorMessage"))
                    .isEqualTo(padded(ACCOUNT_FILTER_REJECTED, DETAIL_ERROR_WIDTH));
            assertThat(CardDetailResponse.MSG_ACCOUNT_FILTER_NOT_NUMERIC)
                    .isEqualTo(ACCOUNT_FILTER_REJECTED);
            assertThat(CardDetailResponse.MSG_SEARCHED_ACCOUNT_ZEROES)
                    .as("the two declarations carry one text, which is why it is asserted once")
                    .isEqualTo(ACCOUNT_MUST_BE_NON_ZERO)
                    .isEqualTo(CardDetailResponse.MSG_SEARCHED_ACCOUNT_NOT_NUMERIC);
            assertThat(CardDetailResponse.MSG_SEARCHED_CARD_NOT_NUMERIC)
                    .as("the card text of this screen is lower case and says something different from the "
                            + "list screen's upper-case filter text; the two are never unified")
                    .isEqualTo(CARD_MUST_BE_SIXTEEN_DIGITS)
                    .isNotEqualTo(CARD_FILTER_REJECTED);
        }

        @Test
        @DisplayName("a delivered card is presented with both identifiers at full width and the success "
                + "advisory, whose three leading spaces are part of the text")
        void aDeliveredCardIsPresentedWithItsIdentifiers() throws Exception {
            final Map<String, Object> submission = resubmission(detailTurn(turnWith(KeyAction.ENTER)));
            submission.put(PROPERTY_ACCOUNT_ID_FILTER, SINGLE_CARD_ACCOUNT);
            submission.put(PROPERTY_CARD_NUMBER_FILTER, SINGLE_CARD_NUMBER);
            final JsonNode screen = detailTurn(submission);

            assertThat(textOf(screen, PROPERTY_ACCOUNT_ID))
                    .as("eleven characters, leading zeros intact")
                    .isEqualTo(SINGLE_CARD_ACCOUNT);
            assertThat(textOf(screen, PROPERTY_CARD_NUMBER))
                    .as("sixteen characters, carried in full and unaltered")
                    .isEqualTo(SINGLE_CARD_NUMBER);
            assertThat(textOf(screen, "infoMessage"))
                    .isEqualTo(padded(DISPLAYING_REQUESTED_DETAILS, DETAIL_INFO_WIDTH));
            assertThat(CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT)
                    .as("the three leading spaces are part of the value")
                    .isEqualTo(DISPLAYING_REQUESTED_DETAILS)
                    .startsWith("   ");
            assertThat(textOf(screen, "cardActiveStatus")).hasSize(1);
            assertThat(textOf(screen, PROPERTY_EXPIRY_MONTH)).hasSize(2);
            assertThat(textOf(screen, PROPERTY_EXPIRY_YEAR)).hasSize(4);
            assertThat(flagOf(screen, "generalError")).isFalse();
        }

        @Test
        @DisplayName("a card the master does not carry is answered with the not-found text and faults both "
                + "key fields")
        void aCardTheMasterDoesNotCarryIsAnsweredWithTheNotFoundText() throws Exception {
            // The read keys on the card number alone, and its not-found arm faults both filter fields -
            // which the account-keyed read's own not-found arm does not do. Neither difference is smoothed.
            final Map<String, Object> submission = resubmission(detailTurn(turnWith(KeyAction.ENTER)));
            submission.put(PROPERTY_ACCOUNT_ID_FILTER, SINGLE_CARD_ACCOUNT);
            submission.put(PROPERTY_CARD_NUMBER_FILTER, ABSENT_CARD_NUMBER);
            final JsonNode screen = detailTurn(submission);

            assertThat(textOf(screen, "errorMessage"))
                    .isEqualTo(padded(NO_CARDS_FOR_SEARCH, DETAIL_ERROR_WIDTH));
            assertThat(CardDetailResponse.MSG_NO_CARDS_FOR_SEARCH_CONDITION)
                    .isEqualTo(NO_CARDS_FOR_SEARCH);
            assertThat(findingFor(screen, PROPERTY_ACCOUNT_ID)).isPresent();
            assertThat(findingFor(screen, PROPERTY_CARD_NUMBER)).isPresent();
            assertThat(textOf(findingFor(screen, PROPERTY_ACCOUNT_ID).orElseThrow(), "state"))
                    .as("both fields were supplied, so both are in the invalid state rather than the "
                            + "missing one")
                    .isEqualTo(ErrorResponse.FieldState.INVALID.name());
        }

        @Test
        @DisplayName("the exit key leaves the screen, and the exit text it declares keeps its fourteen "
                + "trailing spaces and has no space after its period")
        void theExitKeyLeavesTheScreenAndItsDeclaredTextKeepsItsPad() throws Exception {
            // The exit arm resolves a destination and transfers, so it composes no message: the text is a
            // declared item of the message group at COCRDSLC line 137 and is asserted as such.
            assertThat(CardDetailResponse.MSG_EXIT)
                    .as("twenty characters and then fourteen spaces, and the pad is part of the value")
                    .isEqualTo(EXIT_TEXT_34)
                    .hasSize(34)
                    .startsWith("PF03 pressed.Exiting")
                    .doesNotContain("pressed. Exiting");
            assertThat(EXIT_TEXT_34.substring(EXIT_TEXT_34.indexOf('.') + 1))
                    .as("the character after the period is a letter, not a space")
                    .startsWith("E");

            final JsonNode screen = detailTurn(turnWith(KeyAction.PFK03));
            assertThat(textOf(screen, "nextRoute"))
                    .as("with no caller carried, the exit key resolves to the user menu")
                    .isEqualTo("user-menu");
        }

        @Test
        @DisplayName("the remaining declared texts of this screen's message group are byte exact, "
                + "including the four-dot advisory")
        void theRemainingDeclaredTextsAreByteExact() {
            assertThat(CardDetailResponse.MSG_CODING_TO_BE_DONE)
                    .as("four dots, not three and not an ellipsis")
                    .isEqualTo(CODING_TO_BE_DONE)
                    .contains("Good....")
                    .doesNotContain("Good.....");
            assertThat(CardDetailResponse.MSG_UNEXPECTED_DATA_SCENARIO)
                    .isEqualTo(UNEXPECTED_DATA_SCENARIO);
            assertThat(CardDetailResponse.MSG_ACCOUNT_NOT_IN_CARD_CROSS_REFERENCE)
                    .as("declared by this screen, and reachable through neither of its read paths, since "
                            + "the account-keyed read is left unwired exactly as the source leaves it")
                    .isEqualTo(ACCOUNT_NOT_IN_CARD_DATABASE);
            assertThat(CardDetailResponse.MSG_CARD_DATA_READ_ERROR).isEqualTo(CARD_DATA_READ_ERROR);
            assertThat(CardDetailResponse.SCREEN_FIELD_ACCOUNT_ID).isEqualTo(SCREEN_FIELD_ACCOUNT);
            assertThat(CardDetailResponse.SCREEN_FIELD_CARD_NUMBER).isEqualTo(SCREEN_FIELD_CARD);
        }
    }

    // ===============================================================================================
    // THE CARD UPDATE SCREEN: THE EDITABLE SURFACE
    // ===============================================================================================

    /**
     * The seven components of the update screen that are in scope, one of which is not editable at all, and
     * the two-step gate that stands between a validated change and a written one.
     */
    @Nested
    @DisplayName("Update - seven components and the hidden expiry day")
    class UpdateEditableSurface {

        /** Creates the nested specification. */
        UpdateEditableSurface() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("a fresh entry asks for the two search keys, and the fetch that follows presents the "
                + "card with its hidden expiry day carried through")
        void aFreshEntryAsksForKeysAndTheFetchPresentsTheCard() throws Exception {
            writeReservedCard(RESERVED_UPDATE_CARD, RESERVED_EMBOSSED_NAME, STATUS_ACTIVE);

            final JsonNode entry = updateFreshEntry();
            assertThat(textOf(entry, "informationMessage")).isEqualTo(PROMPT_FOR_SEARCH_KEYS);
            assertThat(CardUpdateResponse.Messages.PROMPT_FOR_SEARCH_KEYS)
                    .isEqualTo(PROMPT_FOR_SEARCH_KEYS);
            assertThat(textOf(entry, "concurrencyToken"))
                    .as("the conversation state is sealed on the way out, so the next turn can be "
                            + "authenticated rather than trusted")
                    .isNotBlank();

            final JsonNode fetched = updateFetch(RESERVED_ROW_ACCOUNT, RESERVED_UPDATE_CARD);

            assertThat(textOf(fetched, "informationMessage")).isEqualTo(DETAILS_SHOWN);
            assertThat(CardUpdateResponse.Messages.FOUND_CARDS_FOR_ACCOUNT).isEqualTo(DETAILS_SHOWN);
            assertThat(textOf(fetched, PROPERTY_ACCOUNT_ID)).isEqualTo(RESERVED_ROW_ACCOUNT);
            assertThat(textOf(fetched, PROPERTY_CARD_NUMBER)).isEqualTo(RESERVED_UPDATE_CARD);
            assertThat(textOf(fetched, PROPERTY_EMBOSSED_NAME)).isEqualTo(RESERVED_EMBOSSED_NAME);
            assertThat(textOf(fetched, PROPERTY_ACTIVE_STATUS)).isEqualTo("Y");
            assertThat(textOf(fetched, PROPERTY_EXPIRY_MONTH)).isEqualTo(RESERVED_EXPIRY_MONTH);
            assertThat(textOf(fetched, PROPERTY_EXPIRY_YEAR)).isEqualTo(RESERVED_EXPIRY_YEAR);
            assertThat(textOf(fetched, PROPERTY_EXPIRY_DAY))
                    .as("the day is hidden and unwritable on every state of this screen, and it survives "
                            + "the round trip because the send paragraph writes the fetched value into it")
                    .isEqualTo(RESERVED_EXPIRY_DAY);
        }

        @Test
        @DisplayName("a transmitted expiry day is neither bound, nor validated, nor stored")
        void aTransmittedExpiryDayIsNeitherBoundNorValidatedNorStored() throws Exception {
            writeReservedCard(RESERVED_UPDATE_CARD, RESERVED_EMBOSSED_NAME, STATUS_ACTIVE);
            final JsonNode fetched = updateFetch(RESERVED_ROW_ACCOUNT, RESERVED_UPDATE_CARD);

            final Map<String, Object> submission = resubmissionOf(fetched, KeyAction.ENTER);
            // A value no calendar day could hold. It must change nothing, because the contract declines to
            // bind this component at all and the boundary supplies it from the verified carried image.
            submission.put(PROPERTY_EXPIRY_DAY, "99");
            final JsonNode screen = updateTurn(submission);

            assertThat(textOf(screen, PROPERTY_EXPIRY_DAY))
                    .as("the served day is the fetched one, never the transmitted one")
                    .isEqualTo(RESERVED_EXPIRY_DAY);
            assertThat(findingFor(screen, PROPERTY_EXPIRY_DAY))
                    .as("the day is never validated, so it can never carry a finding - which is the "
                            + "opposite of the account-update screen, where the day is unprotected")
                    .isNotPresent();
            assertThat(textOf(screen, "errorMessage"))
                    .as("resubmitting the fetched values changes nothing, and the comparison includes the "
                            + "day, which the boundary supplied from the image")
                    .isEqualTo(NO_CHANGE_DETECTED);
            assertThat(storedCard(RESERVED_UPDATE_CARD).getCardExpirationDate())
                    .as("no stored state moved")
                    .isEqualTo(RESERVED_EXPIRATION_DATE);
        }

        @Test
        @DisplayName("a validated change is not written until the fifth program-function key is pressed")
        void aValidatedChangeIsNotWrittenUntilTheSaveKeyIsPressed() throws Exception {
            writeReservedCard(RESERVED_UPDATE_CARD, RESERVED_EMBOSSED_NAME, STATUS_ACTIVE);
            final JsonNode fetched = updateFetch(RESERVED_ROW_ACCOUNT, RESERVED_UPDATE_CARD);

            final Map<String, Object> edit = resubmissionOf(fetched, KeyAction.ENTER);
            edit.put(PROPERTY_EMBOSSED_NAME, NAME_WITH_EMBEDDED_SPACE);
            final JsonNode validated = updateTurn(edit);

            assertThat(textOf(validated, "informationMessage"))
                    .as("no space follows the period in this text")
                    .isEqualTo(CHANGES_VALIDATED);
            assertThat(storedCard(RESERVED_UPDATE_CARD).getCardEmbossedName())
                    .as("validation is not a write: the row must still hold what it held")
                    .isEqualTo(RESERVED_EMBOSSED_NAME);

            final JsonNode committed = updateTurn(resubmissionOf(validated, KeyAction.PFK05));

            assertThat(textOf(committed, "informationMessage")).isEqualTo(CHANGES_COMMITTED);
            assertThat(CardUpdateResponse.Messages.CONFIRM_UPDATE_SUCCESS).isEqualTo(CHANGES_COMMITTED);
            assertThat(storedCard(RESERVED_UPDATE_CARD).getCardEmbossedName())
                    .as("the submitted text is written verbatim and is not folded on the way in")
                    .isEqualTo(NAME_WITH_EMBEDDED_SPACE);
            assertThat(storedCard(RESERVED_UPDATE_CARD).getCardExpirationDate())
                    .as("the expiration date is reassembled from the three components, the hidden day "
                            + "among them, so an unchanged date comes back unchanged")
                    .isEqualTo(RESERVED_EXPIRATION_DATE);
        }

        @Test
        @DisplayName("the confirming submission may not carry the account identifier that state protects")
        void theConfirmingSubmissionMayNotCarryTheProtectedAccountIdentifier() throws Exception {
            writeReservedCard(RESERVED_UPDATE_CARD, RESERVED_EMBOSSED_NAME, STATUS_ACTIVE);
            final JsonNode fetched = updateFetch(RESERVED_ROW_ACCOUNT, RESERVED_UPDATE_CARD);
            final Map<String, Object> edit = resubmissionOf(fetched, KeyAction.ENTER);
            edit.put(PROPERTY_EMBOSSED_NAME, NAME_WITH_EMBEDDED_SPACE);
            final JsonNode validated = updateTurn(edit);

            final Map<String, Object> confirm = resubmissionOf(validated, KeyAction.PFK05);
            confirm.put(PROPERTY_ACCOUNT_ID, RESERVED_ROW_ACCOUNT);
            final MvcResult result = submit(updateRoute(), confirm, userSession());

            assertThat(result.getResponse().getStatus())
                    .as("the terminal could not transmit a protected field, so there is no legacy screen "
                            + "to reproduce and the honest answer is that the submission could not have "
                            + "come from this screen")
                    .isEqualTo(400);
            final String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body)
                    .doesNotContain("Exception")
                    .doesNotContain("com.carddemo")
                    .doesNotContain("org.springframework");
            assertThat(storedCard(RESERVED_UPDATE_CARD).getCardEmbossedName())
                    .as("a refused submission writes nothing")
                    .isEqualTo(RESERVED_EMBOSSED_NAME);
        }
    }

    // ===============================================================================================
    // THE CARD UPDATE SCREEN: THE DOUBLE IN-PLACE FOLD
    // ===============================================================================================

    /**
     * The consequence of folding both sides of the change comparison through a 26-character ASCII table:
     * a case-only edit is not a change, and a case-only edit of a letter the table does not carry is.
     */
    @Nested
    @DisplayName("Update - case-only edit reports no change")
    class UpdateCaseOnlyEdit {

        /** Creates the nested specification. */
        UpdateCaseOnlyEdit() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("an edit differing only in letter case is not detected as a change")
        void anEditDifferingOnlyInLetterCaseIsNotAChange() throws Exception {
            // The fetched name is folded before it is captured, at COCRDUPC line 1357, and the submitted
            // group is folded again at the head of the comparison at line 1499, before the comparison at
            // lines 1503 to 1508 reads it. Both sides are therefore folded and letter case cannot differ.
            writeReservedCard(RESERVED_UPDATE_CARD, RESERVED_EMBOSSED_NAME, STATUS_ACTIVE);
            final JsonNode fetched = updateFetch(RESERVED_ROW_ACCOUNT, RESERVED_UPDATE_CARD);

            final Map<String, Object> caseOnly = resubmissionOf(fetched, KeyAction.ENTER);
            caseOnly.put(PROPERTY_EMBOSSED_NAME, RESERVED_EMBOSSED_NAME_CASE_VARIANT);
            final JsonNode screen = updateTurn(caseOnly);

            assertThat(RESERVED_EMBOSSED_NAME_CASE_VARIANT)
                    .as("the two spellings really do differ, so the comparison genuinely had to fold them")
                    .isNotEqualTo(RESERVED_EMBOSSED_NAME);
            assertThat(textOf(screen, "errorMessage"))
                    .as("the same letters in a different case are the same value to this comparison")
                    .isEqualTo(NO_CHANGE_DETECTED);
            assertThat(CardUpdateResponse.Messages.NO_CHANGES_DETECTED).isEqualTo(NO_CHANGE_DETECTED);
            assertThat(storedCard(RESERVED_UPDATE_CARD).getCardEmbossedName())
                    .as("a change that is not detected is never written, which is why storing the folded "
                            + "form would have been incoherent")
                    .isEqualTo(RESERVED_EMBOSSED_NAME);
        }

        @Test
        @DisplayName("the fold leaves a non-ASCII letter untouched, so its case variant is a change - "
                + "which a locale-aware upper-casing would have hidden")
        void theFoldLeavesANonAsciiLetterUntouched() throws Exception {
            // The fold is a 26-character table substitution, locale-independent and Unicode-unaware. A
            // letter outside that table is left exactly as it stands, so a case variant of it does not
            // compare equal - the observable difference between a table fold and a library upper-casing.
            // The same table-based reasoning governs the alphabetic edit, which is what then rejects it.
            writeReservedCard(RESERVED_NON_ASCII_CARD, NON_ASCII_NAME_UPPER, STATUS_ACTIVE);
            final JsonNode fetched = updateFetch(RESERVED_ROW_ACCOUNT, RESERVED_NON_ASCII_CARD);
            assertThat(textOf(fetched, PROPERTY_EMBOSSED_NAME))
                    .as("the capture folds the stored name, and the table leaves this letter alone")
                    .isEqualTo(NON_ASCII_NAME_UPPER);

            final Map<String, Object> caseOnly = resubmissionOf(fetched, KeyAction.ENTER);
            caseOnly.put(PROPERTY_EMBOSSED_NAME, NON_ASCII_NAME_LOWER);
            final JsonNode screen = updateTurn(caseOnly);

            assertThat(textOf(screen, "errorMessage"))
                    .as("had the fold been locale-aware, the two spellings would have compared equal and "
                            + "this turn would have reported no change instead")
                    .isNotEqualTo(NO_CHANGE_DETECTED)
                    .isEqualTo(NAME_MUST_BE_ALPHA);
            assertThat(storedCard(RESERVED_NON_ASCII_CARD).getCardEmbossedName())
                    .isEqualTo(NON_ASCII_NAME_UPPER);
        }
    }

    // ===============================================================================================
    // THE CARD UPDATE SCREEN: THE ALPHABETIC EDIT
    // ===============================================================================================

    /**
     * The blank-and-trim alphabetic idiom, whose observable consequence is that an embedded space passes.
     */
    @Nested
    @DisplayName("Update - alphabetic accepts embedded spaces")
    class UpdateAlphabeticEdit {

        /** Creates the nested specification. */
        UpdateAlphabeticEdit() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("a name with an embedded space is accepted, because the idiom blanks every letter and "
                + "then measures what is left")
        void aNameWithAnEmbeddedSpaceIsAccepted() throws Exception {
            // The edit converts the letters to spaces and tests that nothing remains, so a space inside
            // the value survives the conversion and passes. A predicate demanding that every character be
            // a letter would reject a value the estate accepts.
            writeReservedCard(RESERVED_UPDATE_CARD, RESERVED_EMBOSSED_NAME, STATUS_ACTIVE);
            final JsonNode fetched = updateFetch(RESERVED_ROW_ACCOUNT, RESERVED_UPDATE_CARD);

            final Map<String, Object> edit = resubmissionOf(fetched, KeyAction.ENTER);
            edit.put(PROPERTY_EMBOSSED_NAME, NAME_WITH_EMBEDDED_SPACE);
            final JsonNode screen = updateTurn(edit);

            assertThat(NAME_WITH_EMBEDDED_SPACE).contains(" ");
            assertThat(textOf(screen, "informationMessage")).isEqualTo(CHANGES_VALIDATED);
            assertThat(findingFor(screen, PROPERTY_EMBOSSED_NAME)).isNotPresent();
            assertThat(textOf(screen, "errorMessage")).isEmpty();
        }

        @Test
        @DisplayName("a name carrying a digit is rejected with the alphabets-and-spaces text")
        void aNameCarryingADigitIsRejected() throws Exception {
            writeReservedCard(RESERVED_UPDATE_CARD, RESERVED_EMBOSSED_NAME, STATUS_ACTIVE);
            final JsonNode fetched = updateFetch(RESERVED_ROW_ACCOUNT, RESERVED_UPDATE_CARD);

            final Map<String, Object> edit = resubmissionOf(fetched, KeyAction.ENTER);
            edit.put(PROPERTY_EMBOSSED_NAME, NAME_WITH_A_DIGIT);
            final JsonNode screen = updateTurn(edit);

            assertThat(textOf(screen, "errorMessage")).isEqualTo(NAME_MUST_BE_ALPHA);
            assertThat(CardUpdateResponse.Messages.NAME_MUST_BE_ALPHA).isEqualTo(NAME_MUST_BE_ALPHA);
            final JsonNode finding = findingFor(screen, PROPERTY_EMBOSSED_NAME).orElseThrow();
            assertThat(textOf(finding, "state"))
                    .as("a value was supplied and failed, which is the invalid state")
                    .isEqualTo(ErrorResponse.FieldState.INVALID.name());
            assertThat(textOf(finding, "screenFieldId")).isEqualTo(SCREEN_FIELD_EMBOSSED_NAME);
            assertThat(textOf(screen, "informationMessage"))
                    .as("a rejected change puts the screen back into the prompt-for-changes state")
                    .isEqualTo(PROMPT_FOR_CHANGES);
        }

        @Test
        @DisplayName("a blank name is reported as missing rather than as invalid")
        void aBlankNameIsReportedAsMissing() throws Exception {
            writeReservedCard(RESERVED_UPDATE_CARD, RESERVED_EMBOSSED_NAME, STATUS_ACTIVE);
            final JsonNode fetched = updateFetch(RESERVED_ROW_ACCOUNT, RESERVED_UPDATE_CARD);

            final Map<String, Object> edit = resubmissionOf(fetched, KeyAction.ENTER);
            edit.put(PROPERTY_EMBOSSED_NAME, "  ");
            final JsonNode screen = updateTurn(edit);

            assertThat(textOf(screen, "errorMessage")).isEqualTo(NAME_NOT_PROVIDED);
            assertThat(CardUpdateResponse.Messages.PROMPT_FOR_NAME).isEqualTo(NAME_NOT_PROVIDED);
            assertThat(textOf(findingFor(screen, PROPERTY_EMBOSSED_NAME).orElseThrow(), "state"))
                    .as("blank and unusable are two different operator experiences and the contract keeps "
                            + "them apart")
                    .isEqualTo(ErrorResponse.FieldState.MISSING.name());
        }
    }

    // ===============================================================================================
    // THE CARD UPDATE SCREEN: MESSAGE FIDELITY
    // ===============================================================================================

    /**
     * The remaining declared texts of the update screen's message group, including the two deliberate
     * inconsistencies that are the contract rather than defects awaiting a tidy-up.
     */
    @Nested
    @DisplayName("Update - message fidelity and the 167/171 inconsistency")
    class UpdateMessageFidelity {

        /** Creates the nested specification. */
        UpdateMessageFidelity() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("the confirmation prompt has no space after its period while the failure notice has "
                + "one, and neither is normalised toward the other")
        void theConfirmationPromptAndTheFailureNoticePunctuateDifferently() {
            // COCRDUPC line 167 against line 171. Two texts in one message group, one space apart. Both
            // are reproduced as declared; an executor that "fixed" either would break an operator's
            // screen-scraping match.
            assertThat(CardUpdateResponse.Messages.PROMPT_FOR_CONFIRMATION)
                    .isEqualTo(CHANGES_VALIDATED)
                    .contains("validated.Press")
                    .doesNotContain("validated. Press");
            assertThat(CardUpdateResponse.Messages.INFORM_FAILURE)
                    .isEqualTo(CHANGES_UNSUCCESSFUL)
                    .contains("unsuccessful. Please")
                    .doesNotContain("unsuccessful.Please");
            assertThat(CHANGES_VALIDATED.charAt(CHANGES_VALIDATED.indexOf('.') + 1))
                    .as("a letter follows the period here")
                    .isEqualTo('P');
            assertThat(CHANGES_UNSUCCESSFUL.charAt(CHANGES_UNSUCCESSFUL.indexOf('.') + 1))
                    .as("a space follows the period there, and the difference is the contract")
                    .isEqualTo(' ');
        }

        @Test
        @DisplayName("the month text names one and twelve, not zero-one and twelve")
        void theMonthTextNamesOneAndTwelve() throws Exception {
            writeReservedCard(RESERVED_UPDATE_CARD, RESERVED_EMBOSSED_NAME, STATUS_ACTIVE);
            final JsonNode fetched = updateFetch(RESERVED_ROW_ACCOUNT, RESERVED_UPDATE_CARD);

            final Map<String, Object> edit = resubmissionOf(fetched, KeyAction.ENTER);
            edit.put(PROPERTY_EXPIRY_MONTH, "13");
            final JsonNode screen = updateTurn(edit);

            assertThat(textOf(screen, "errorMessage")).isEqualTo(EXPIRY_MONTH_INVALID);
            assertThat(CardUpdateResponse.Messages.CARD_EXPIRY_MONTH_NOT_VALID)
                    .isEqualTo(EXPIRY_MONTH_INVALID)
                    .contains("between 1 and 12")
                    .doesNotContain("between 01 and 12");
            assertThat(findingFor(screen, PROPERTY_EXPIRY_MONTH)).isPresent();
        }

        @Test
        @DisplayName("the year text names no range at all, even though a range is enforced")
        void theYearTextNamesNoRange() throws Exception {
            writeReservedCard(RESERVED_UPDATE_CARD, RESERVED_EMBOSSED_NAME, STATUS_ACTIVE);
            final JsonNode fetched = updateFetch(RESERVED_ROW_ACCOUNT, RESERVED_UPDATE_CARD);

            final Map<String, Object> edit = resubmissionOf(fetched, KeyAction.ENTER);
            edit.put(PROPERTY_EXPIRY_YEAR, "1949");
            final JsonNode screen = updateTurn(edit);

            assertThat(textOf(screen, "errorMessage")).isEqualTo(EXPIRY_YEAR_INVALID);
            assertThat(CardUpdateResponse.Messages.CARD_EXPIRY_YEAR_NOT_VALID)
                    .as("the text names neither bound of the rule it reports on, and inventing one would "
                            + "be a new external contract")
                    .isEqualTo(EXPIRY_YEAR_INVALID)
                    .doesNotContain("1950")
                    .doesNotContain("2099")
                    .doesNotContain("between");
        }

        @Test
        @DisplayName("the enforced year boundaries are inclusive at both ends, and are asserted apart from "
                + "the text that reports them")
        void theEnforcedYearBoundariesAreInclusiveAtBothEnds() throws Exception {
            writeReservedCard(RESERVED_UPDATE_CARD, RESERVED_EMBOSSED_NAME, STATUS_ACTIVE);

            assertThat(yearOutcome("1949"))
                    .as("one below the low bound")
                    .isEqualTo(EXPIRY_YEAR_INVALID);
            assertThat(yearOutcome("1950"))
                    .as("the low bound itself is admitted")
                    .isEmpty();
            assertThat(yearOutcome("2099"))
                    .as("the high bound itself is admitted")
                    .isEmpty();
            assertThat(yearOutcome("2100"))
                    .as("one above the high bound")
                    .isEqualTo(EXPIRY_YEAR_INVALID);
        }

        @Test
        @DisplayName("the active status admits exactly the two declared characters")
        void theActiveStatusAdmitsExactlyTheTwoDeclaredCharacters() throws Exception {
            writeReservedCard(RESERVED_UPDATE_CARD, RESERVED_EMBOSSED_NAME, STATUS_ACTIVE);
            final JsonNode fetched = updateFetch(RESERVED_ROW_ACCOUNT, RESERVED_UPDATE_CARD);

            final Map<String, Object> edit = resubmissionOf(fetched, KeyAction.ENTER);
            edit.put(PROPERTY_ACTIVE_STATUS, "Q");
            final JsonNode rejected = updateTurn(edit);

            assertThat(textOf(rejected, "errorMessage")).isEqualTo(STATUS_MUST_BE_YES_OR_NO);
            assertThat(CardUpdateResponse.Messages.CARD_STATUS_MUST_BE_YES_NO)
                    .isEqualTo(STATUS_MUST_BE_YES_OR_NO);
            assertThat(CardStatus.fromCode("Q"))
                    .as("the enum declares the same two codes the edit admits")
                    .isEmpty();

            final Map<String, Object> flip = resubmissionOf(fetched, KeyAction.ENTER);
            flip.put(PROPERTY_ACTIVE_STATUS, STATUS_INACTIVE);
            assertThat(textOf(updateTurn(flip), "informationMessage"))
                    .as("the other declared character is admitted")
                    .isEqualTo(CHANGES_VALIDATED);
        }

        @Test
        @DisplayName("the file-error prefix keeps its trailing space, and no response body carries the raw "
                + "two-character status the legacy appended to it")
        void theFileErrorPrefixKeepsItsTrailingSpaceAndLeaksNoStatusCode() throws Exception {
            // COCRDUPC line 135. The legacy appends the raw file status to this prefix and shows it on the
            // terminal. The REST contract keeps the prefix as declared and declines to publish the code,
            // which is an interface detail and not an operator message.
            assertThat(CardUpdateResponse.Messages.FILE_ERROR_PREFIX)
                    .isEqualTo(FILE_ERROR_PREFIX)
                    .endsWith(" ")
                    .hasSize(12);

            final Map<String, Object> fetch = echoUpdateState(updateFreshEntry(), KeyAction.ENTER);
            fetch.put(PROPERTY_ACCOUNT_ID, RESERVED_ROW_ACCOUNT);
            fetch.put(PROPERTY_CARD_NUMBER, ABSENT_CARD_NUMBER);
            final MvcResult result = submit(updateRoute(), fetch, userSession());
            final String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(okBody(result).get("errorMessage").asText())
                    .as("a read that found nothing is reported in the screen's own words")
                    .isEqualTo(NO_CARDS_FOR_SEARCH);
            assertThat(body)
                    .as("neither the prefix nor a bare interface status reaches the caller")
                    .doesNotContain(FILE_ERROR_PREFIX)
                    .doesNotContain("\"23\"")
                    .doesNotContain("\"00\"")
                    .doesNotContain("\"10\"")
                    .doesNotContain("RESP")
                    .doesNotContain("RESP2");
        }

        @Test
        @DisplayName("the non-zero account text is declared twice in the source and reproduced once, and "
                + "the screen raises the upper-case filter text instead")
        void theNonZeroAccountTextIsDeclaredTwiceAndReproducedOnce() throws Exception {
            // COCRDUPC lines 190 and 192 declare the same text under two condition names. Neither is ever
            // set: the account edit raises the upper-case filter text, exactly as the detail screen does.
            assertThat(CardUpdateResponse.Messages.ACCOUNT_MUST_BE_NON_ZERO_11_DIGITS)
                    .isEqualTo(ACCOUNT_MUST_BE_NON_ZERO);
            assertThat(CardUpdateResponse.Messages.ACCOUNT_FILTER_MUST_BE_11_DIGITS)
                    .isEqualTo(ACCOUNT_FILTER_REJECTED);

            final Map<String, Object> fetch = echoUpdateState(updateFreshEntry(), KeyAction.ENTER);
            fetch.put(PROPERTY_ACCOUNT_ID, "0000000000X");
            fetch.put(PROPERTY_CARD_NUMBER, RESERVED_ROW_ACCOUNT_SEEDED_CARD);
            final JsonNode screen = updateTurn(fetch);

            assertThat(textOf(screen, "errorMessage"))
                    .as("the raised text is the upper-case filter one, which is why the twice-declared "
                            + "lower-case text is asserted as a declaration and not as an outcome")
                    .isEqualTo(ACCOUNT_FILTER_REJECTED)
                    .isNotEqualTo(ACCOUNT_MUST_BE_NON_ZERO);
        }

        @Test
        @DisplayName("the lock text is a single generic sentence, and the changed-record text spells "
                + "\"some one\" as two words")
        void theLockAndChangedTextsAreDeclaredAsTheSourceDeclaresThem() {
            // COCRDUPC line 206 declares one lock text where the account-update member declares two
            // distinct ones; line 208 spells the pronoun as two words; line 210 is the post-lock failure.
            assertThat(CardUpdateResponse.Messages.COULD_NOT_LOCK_FOR_UPDATE)
                    .isEqualTo(COULD_NOT_LOCK)
                    .doesNotContain("someone");
            assertThat(CardUpdateResponse.Messages.DATA_WAS_CHANGED)
                    .isEqualTo(RECORD_CHANGED)
                    .contains("some one")
                    .doesNotContain("someone");
            assertThat(CardUpdateResponse.Messages.LOCKED_BUT_UPDATE_FAILED).isEqualTo(UPDATE_FAILED);
            assertThat(OptimisticLockConflictException.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE)
                    .as("the exception and the screen name the same text, so the outcome reads the same "
                            + "however it reaches the caller")
                    .isEqualTo(RECORD_CHANGED);
            assertThat(OptimisticLockConflictException.MSG_LOCKED_BUT_UPDATE_FAILED)
                    .isEqualTo(UPDATE_FAILED);
        }

        @Test
        @DisplayName("this screen declares the same four-dot advisory and the same data-scenario text as "
                + "its sibling, and keeps the read-error text unchanged")
        void theRemainingDeclaredTextsOfThisScreenAreByteExact() {
            assertThat(CardUpdateResponse.Messages.CODING_TO_BE_DONE)
                    .as("four dots on this screen too")
                    .isEqualTo(CODING_TO_BE_DONE)
                    .contains("Good....")
                    .doesNotContain("Good...s")
                    .doesNotContain("Good\u2026");
            assertThat(CardUpdateResponse.Messages.UNEXPECTED_DATA_SCENARIO)
                    .isEqualTo(UNEXPECTED_DATA_SCENARIO);
            assertThat(CardUpdateResponse.Messages.CARD_DATA_READ_ERROR).isEqualTo(CARD_DATA_READ_ERROR);
            assertThat(CardUpdateResponse.Messages.DID_NOT_FIND_ACCOUNT_IN_CARD_DATA)
                    .isEqualTo(ACCOUNT_NOT_IN_CARD_DATABASE);
            assertThat(CardUpdateResponse.Messages.DID_NOT_FIND_ACCOUNT_CARD_COMBINATION)
                    .isEqualTo(NO_CARDS_FOR_SEARCH);
            assertThat(CardUpdateResponse.Messages.NO_SEARCH_CRITERIA_RECEIVED).isEqualTo(NO_INPUT_RECEIVED);
            assertThat(CardUpdateResponse.Messages.PROMPT_FOR_ACCOUNT).isEqualTo(ACCOUNT_NOT_PROVIDED);
            assertThat(CardUpdateResponse.Messages.PROMPT_FOR_CARD).isEqualTo(CARD_NOT_PROVIDED);
            assertThat(CardUpdateResponse.Messages.CARD_MUST_BE_16_DIGITS)
                    .isEqualTo(CARD_MUST_BE_SIXTEEN_DIGITS);
            assertThat(CardUpdateResponse.Messages.CARD_FILTER_MUST_BE_16_DIGITS)
                    .isEqualTo(CARD_FILTER_REJECTED);
            assertThat(CardUpdateResponse.Messages.EXIT_MESSAGE)
                    .as("the pad is part of the value on this screen as well")
                    .isEqualTo(EXIT_TEXT_34)
                    .hasSize(34);
        }

        /**
         * Fetches the reserved row afresh, submits one expiry year against it and answers the summary text
         * the screen came back with.
         *
         * @param expiryYear the four-character year to submit
         * @return the summary text, empty when the submission was accepted
         * @throws Exception when the exchange itself fails
         */
        private String yearOutcome(final String expiryYear) throws Exception {
            final JsonNode fetched = updateFetch(RESERVED_ROW_ACCOUNT, RESERVED_UPDATE_CARD);
            final Map<String, Object> edit = resubmissionOf(fetched, KeyAction.ENTER);
            edit.put(PROPERTY_EXPIRY_YEAR, expiryYear);
            return textOf(updateTurn(edit), "errorMessage");
        }
    }

    // ===============================================================================================
    // THE CARD UPDATE SCREEN: OPTIMISTIC LOCKING
    // ===============================================================================================

    /**
     * The before-and-after image comparison the legacy performed by hand, reached through a row that moved
     * between the fetch and the save.
     */
    @Nested
    @DisplayName("Update - optimistic lock")
    class UpdateOptimisticLock {

        /** Creates the nested specification. */
        UpdateOptimisticLock() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("a row that moved between the fetch and the save is refused with the changed-record "
                + "text, and the refused change is not written")
        void aRowThatMovedBetweenFetchAndSaveIsRefused() throws Exception {
            writeReservedCard(RESERVED_UPDATE_CARD, RESERVED_EMBOSSED_NAME, STATUS_ACTIVE);
            final JsonNode fetched = updateFetch(RESERVED_ROW_ACCOUNT, RESERVED_UPDATE_CARD);
            final Map<String, Object> edit = resubmissionOf(fetched, KeyAction.ENTER);
            edit.put(PROPERTY_EMBOSSED_NAME, NAME_WITH_EMBEDDED_SPACE);
            final JsonNode validated = updateTurn(edit);
            assertThat(textOf(validated, "informationMessage")).isEqualTo(CHANGES_VALIDATED);

            // Somebody else moves the row while this conversation is between its validate turn and its
            // save turn. The comparison at the head of the write path is what notices.
            final Card moved = storedCard(RESERVED_UPDATE_CARD);
            moved.setCardActiveStatus(STATUS_INACTIVE);
            CardControllerIT.this.cards.saveAndFlush(moved);

            final JsonNode refused = updateTurn(resubmissionOf(validated, KeyAction.PFK05));

            assertThat(textOf(refused, "errorMessage"))
                    .as("two words, and the sentence the source declares")
                    .isEqualTo(RECORD_CHANGED);
            assertThat(textOf(refused, "informationMessage"))
                    .as("the refusal puts the screen back into the display state so the operator can "
                            + "review what the row now holds")
                    .isEqualTo(DETAILS_SHOWN);
            assertThat(textOf(refused, PROPERTY_ACTIVE_STATUS))
                    .as("the presented image is refreshed from the row, which is what makes a retry "
                            + "converge rather than spin")
                    .isEqualTo(STATUS_INACTIVE);
            assertThat(storedCard(RESERVED_UPDATE_CARD).getCardEmbossedName())
                    .as("the refused change wrote nothing")
                    .isEqualTo(RESERVED_EMBOSSED_NAME);
        }

        @Test
        @DisplayName("resubmitting after the refusal succeeds against the refreshed image, which is the "
                + "observable end of the retry the legacy expressed as a backward jump")
        void resubmittingAfterTheRefusalSucceedsAgainstTheRefreshedImage() throws Exception {
            writeReservedCard(RESERVED_UPDATE_CARD, RESERVED_EMBOSSED_NAME, STATUS_ACTIVE);
            final JsonNode fetched = updateFetch(RESERVED_ROW_ACCOUNT, RESERVED_UPDATE_CARD);
            final Map<String, Object> edit = resubmissionOf(fetched, KeyAction.ENTER);
            edit.put(PROPERTY_EMBOSSED_NAME, NAME_WITH_EMBEDDED_SPACE);
            final JsonNode validated = updateTurn(edit);

            final Card moved = storedCard(RESERVED_UPDATE_CARD);
            moved.setCardActiveStatus(STATUS_INACTIVE);
            CardControllerIT.this.cards.saveAndFlush(moved);
            final JsonNode refused = updateTurn(resubmissionOf(validated, KeyAction.PFK05));
            assertThat(textOf(refused, "errorMessage")).isEqualTo(RECORD_CHANGED);

            // The operator reviews the refreshed screen and puts the same edit back on it.
            final Map<String, Object> retry = resubmissionOf(refused, KeyAction.ENTER);
            retry.put(PROPERTY_EMBOSSED_NAME, NAME_WITH_EMBEDDED_SPACE);
            final JsonNode revalidated = updateTurn(retry);
            assertThat(textOf(revalidated, "informationMessage")).isEqualTo(CHANGES_VALIDATED);

            final JsonNode committed = updateTurn(resubmissionOf(revalidated, KeyAction.PFK05));

            assertThat(textOf(committed, "informationMessage")).isEqualTo(CHANGES_COMMITTED);
            final Card stored = storedCard(RESERVED_UPDATE_CARD);
            assertThat(stored.getCardEmbossedName()).isEqualTo(NAME_WITH_EMBEDDED_SPACE);
            assertThat(stored.getCardActiveStatus())
                    .as("the other party's change survives, because the retry rebuilt itself on top of it")
                    .isEqualTo(STATUS_INACTIVE);
        }
    }

    // ===============================================================================================
    // IDENTIFIER TYPING, STORED VOCABULARY AND THE ATTENTION-KEY FOLD
    // ===============================================================================================

    /**
     * The cross-cutting shape of the three screens: identifiers as bounded text, a stored status the
     * schema does not constrain, and an attention-key vocabulary that stops at twelve.
     */
    @Nested
    @DisplayName("Contract shape - identifiers, stored status and the attention-key fold")
    class ContractShape {

        /** Creates the nested specification. */
        ContractShape() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("both identifiers cross the boundary as bounded text that keeps its leading zeros")
        void bothIdentifiersCrossTheBoundaryAsBoundedText() throws Exception {
            // A filter is read only on a re-submission of this screen, so the conversation is opened first
            // and the filter is typed onto the screen the first turn handed back.
            final Map<String, Object> body = turnWith(KeyAction.ENTER);
            echoListState(body, listTurn(turnWith(KeyAction.ENTER)));
            body.put(PROPERTY_ACCOUNT_ID_FILTER, SINGLE_CARD_ACCOUNT);
            final JsonNode screen = listTurn(body);
            assertThat(screen.get("rows")).hasSize(1);
            final JsonNode row = screen.get("rows").get(0);

            assertThat(row.get("accountNumber").isTextual())
                    .as("a numeric JSON value could not have carried the eight leading zeros this "
                            + "eleven-character key opens with")
                    .isTrue();
            assertThat(row.get("cardNumber").isTextual()).isTrue();
            assertThat(textOf(row, "accountNumber"))
                    .isEqualTo(SINGLE_CARD_ACCOUNT)
                    .hasSize(11)
                    .startsWith("000");
            assertThat(textOf(row, "cardNumber"))
                    .as("sixteen characters, round-tripped unchanged and never masked - the estate "
                            + "exposes the whole number and no requirement here introduces masking")
                    .isEqualTo(SINGLE_CARD_NUMBER)
                    .hasSize(16);
            assertThat(textOf(screen, RESPONSE_ACCOUNT_FILTER))
                    .as("the echoed filter keeps its own leading zeros too")
                    .isEqualTo(SINGLE_CARD_ACCOUNT);
        }

        @Test
        @DisplayName("a stored status outside the two declared characters is served as it stands, because "
                + "the column carries no check constraint")
        void aStoredStatusOutsideTheDeclaredVocabularyIsServedAsItStands() throws Exception {
            // A file-sourced value outside the two known codes flowed through the legacy untouched, and
            // the migrated column is a plain one-character column, so the list must serve it untouched
            // rather than reject it. Seeded through the repository: no raw statement, no schema change.
            writeReservedCard(RESERVED_UNKNOWN_STATUS_CARD, RESERVED_EMBOSSED_NAME,
                    UNDECLARED_CARD_STATUS);
            assertThat(CardStatus.fromCode(UNDECLARED_CARD_STATUS))
                    .as("the lookup answers empty rather than throwing, and declares no synthetic "
                            + "absorbing constant")
                    .isEmpty();

            final Map<String, Object> body = turnWith(KeyAction.ENTER);
            echoListState(body, listTurn(turnWith(KeyAction.ENTER)));
            body.put(PROPERTY_CARD_NUMBER_FILTER, RESERVED_UNKNOWN_STATUS_CARD);
            final JsonNode screen = listTurn(body);

            assertThat(cardNumbersOf(screen)).containsExactly(RESERVED_UNKNOWN_STATUS_CARD);
            assertThat(textOf(screen.get("rows").get(0), "cardStatus"))
                    .isEqualTo(UNDECLARED_CARD_STATUS);
        }

        @Test
        @DisplayName("the seeded baseline is fifty cards, fifty accounts and fifty cross-references, and "
                + "no served row carries a filler component")
        void theSeededBaselineIsFiftyCards() throws Exception {
            assertThat(this.deliveredCardCount())
                    .as("the delivered fixture holds fifty card records; the reserved rows this class "
                            + "writes are removed after every test, so the baseline is stable")
                    .isEqualTo(SEEDED_CARD_COUNT);

            final JsonNode screen = listTurn(turnWith(KeyAction.ENTER));

            assertThat(propertyNamesOf(screen.get("rows").get(0)))
                    .as("the fourteen filler bytes of the cross-reference layout and the fifty-nine of "
                            + "the card layout are storage, not contract")
                    .noneSatisfy(name -> assertThat(name.toLowerCase(Locale.ROOT)).contains("filler"));
        }

        @Test
        @DisplayName("the attention-key vocabulary stops at twelve, so a higher key is not a distinct "
                + "action and cannot be named at all")
        void theAttentionKeyVocabularyStopsAtTwelve() throws Exception {
            assertThat(KeyAction.values())
                    .as("enter, clear, the two program-access keys and twelve function keys")
                    .hasSize(16);
            assertThat(KeyAction.values())
                    .allSatisfy(action -> assertThat(action.getAid())
                            .as("every code occupies the same five-character field, two of them by "
                                    + "carrying trailing spaces")
                            .hasSize(5));
            assertThat(KeyAction.valueOf("PFK12")).isNotNull();
            assertThat(KeyAction.PA1.getAid()).isEqualTo("PA1  ");
            assertThat(KeyAction.PA2.getAid()).isEqualTo("PA2  ");
            for (int higher = 13; higher <= 24; higher++) {
                final String name = "PFK" + higher;
                assertThat(KeyAction.values())
                        .as("key %d folds onto key %d and is therefore not a constant of its own",
                                higher, higher - 12)
                        .noneMatch(action -> action.name().equals(name));
            }

            final Map<String, Object> body = turnWith(KeyAction.ENTER);
            body.put("keyAction", "PFK19");
            final MvcResult result = submit(listRoute(), body, userSession());

            assertThat(result.getResponse().getStatus())
                    .as("a name the vocabulary does not carry cannot be bound, which is the fold's "
                            + "observable consequence at this boundary")
                    .isEqualTo(400);
        }

        /**
         * Counts the delivered card population, excluding the rows this class writes for itself.
         *
         * @return the number of cards that came from the seed migration
         */
        private long deliveredCardCount() {
            return CardControllerIT.this.cards.findAll().stream()
                    .map(Card::getCardNum)
                    .filter(number -> !RESERVED_CARDS.contains(number))
                    .count();
        }
    }

    // ===============================================================================================
    // SECURITY NEGATIVES
    // ===============================================================================================

    /**
     * What the three routes refuse, and what a refusal is allowed to say.
     */
    @Nested
    @DisplayName("Security negatives")
    class SecurityNegatives {

        /** Creates the nested specification. */
        SecurityNegatives() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("all three routes refuse a caller that presents no session, and say only that a "
                + "session is required")
        void allThreeRoutesRefuseACallerWithNoSession() throws Exception {
            for (final String route : List.of(listRoute(), detailRoute(), updateRoute())) {
                final MvcResult result = submit(route, turnWith(KeyAction.ENTER), null);

                assertThat(result.getResponse().getStatus())
                        .as("route %s", route)
                        .isEqualTo(401);
                assertThat(summaryOf(result))
                        .as("route %s says a session is required and nothing more", route)
                        .isEqualTo(AUTHENTICATION_REQUIRED);
            }
            assertThat(GlobalExceptionHandler.AUTHENTICATION_REQUIRED_MESSAGE)
                    .isEqualTo(AUTHENTICATION_REQUIRED);
        }

        @Test
        @DisplayName("all three routes admit an ordinary session, because none of the three is one of the "
                + "five administratively classified transactions")
        void allThreeRoutesAdmitAnOrdinarySession() throws Exception {
            assertThat(okBody(submit(listRoute(), turnWith(KeyAction.ENTER), userSession()))).isNotNull();
            assertThat(okBody(submit(detailRoute(), turnWith(KeyAction.ENTER), userSession()))).isNotNull();
            assertThat(okBody(submit(updateRoute(), turnWith(KeyAction.ENTER), userSession()))).isNotNull();
        }

        @Test
        @DisplayName("an administrative session reaches the same three routes, since the entitlement "
                + "gates a different five transactions")
        void anAdministrativeSessionReachesTheSameThreeRoutes() throws Exception {
            for (final String route : List.of(listRoute(), detailRoute(), updateRoute())) {
                assertThat(submit(route, turnWith(KeyAction.ENTER), adminSession())
                        .getResponse().getStatus())
                        .as("route %s", route)
                        .isEqualTo(200);
            }
        }

        @Test
        @DisplayName("a session that cannot be read is refused, and the refusal names no cause")
        void aSessionThatCannotBeReadIsRefused() throws Exception {
            final MvcResult result = submit(listRoute(), turnWith(KeyAction.ENTER),
                    BEARER_PREFIX + "not-a-readable-session");

            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(summaryOf(result)).isEqualTo(AUTHENTICATION_REQUIRED);
            assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .doesNotContain("not-a-readable-session");
        }

        @Test
        @DisplayName("no response of any of the three screens discloses an implementation detail")
        void noResponseDisclosesAnImplementationDetail() throws Exception {
            writeReservedCard(RESERVED_UPDATE_CARD, RESERVED_EMBOSSED_NAME, STATUS_ACTIVE);
            final Map<String, Object> absentSearch =
                    resubmission(detailTurn(turnWith(KeyAction.ENTER)));
            absentSearch.put(PROPERTY_ACCOUNT_ID_FILTER, ABSENT_ACCOUNT);
            absentSearch.put(PROPERTY_CARD_NUMBER_FILTER, ABSENT_CARD_NUMBER);

            final List<String> bodies = new ArrayList<>();
            bodies.add(bodyOf(submit(listRoute(), turnWith(KeyAction.ENTER), userSession())));
            bodies.add(bodyOf(submit(detailRoute(), turnWith(KeyAction.ENTER), userSession())));
            bodies.add(bodyOf(submit(updateRoute(), turnWith(KeyAction.ENTER), userSession())));
            bodies.add(bodyOf(submit(detailRoute(), absentSearch, userSession())));
            bodies.add(bodyOf(submit(listRoute(), turnWith(KeyAction.ENTER), null)));
            bodies.add(bodyOf(submit(updateRoute(), null, userSession())));

            for (final String body : bodies) {
                assertThat(body)
                        .doesNotContain("Exception")
                        .doesNotContain("com.carddemo")
                        .doesNotContain("org.springframework")
                        .doesNotContain("org.hibernate")
                        .doesNotContain("jakarta.")
                        .doesNotContain("\tat ")
                        .doesNotContain("select ")
                        .doesNotContain("SELECT ")
                        .doesNotContain("insert into")
                        .doesNotContain("jdbc:")
                        .doesNotContain("/tmp/")
                        .doesNotContain("EXEC CICS")
                        .doesNotContain("DFHRED")
                        .doesNotContain("//STEP")
                        .doesNotContain("DSN=")
                        .doesNotContain(BEARER_PREFIX)
                        .doesNotContain("password")
                        .doesNotContain("secret");
            }
        }

        /**
         * Extracts the summary text a refusal envelope carries.
         *
         * @param result the completed exchange
         * @return the summary text
         * @throws Exception when the body cannot be read
         */
        private String summaryOf(final MvcResult result) throws Exception {
            final String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body)
                    .as("a refusal carries this module's own envelope rather than an empty body")
                    .isNotEmpty();
            return textOf(JSON.readTree(body), "message");
        }
    }

    // ===============================================================================================
    // THE BOUNDARY UNDER TEST, ASSEMBLED EXPLICITLY
    // ===============================================================================================

    /**
     * The three card screens and everything they genuinely need, named one bean at a time.
     *
     * <p><strong>Explicit rather than scanned, and that is a correctness requirement.</strong> A component
     * scan of the base package reaches both compiled trees, so it would sweep the suite's own probe
     * controllers and slice configurations into the context beside the delivered ones. The graph is
     * therefore stated here in one place, and a reader can see exactly what took part in every assertion.
     *
     * <p><strong>Nothing on the path under test is stubbed.</strong> The controller, the three screen
     * services, the navigation and message collaborators, the abend service, the transaction boundary, the
     * record writer, the conversation-state sealing service with its real field encryption, the failure
     * boundary, the security filter chain and the session token provider are all the shipped ones, and the
     * card rows come off a real PostgreSQL 16 server migrated by the base class. There is no mock in this
     * class at all: a stubbed screen service could not have proved the page size, the fill order or the
     * double fold, which are precisely the behaviours in question.
     *
     * <p>The record writer is a {@code @Repository}-annotated class rather than a Spring Data interface, so
     * repository scanning does not reach it and it is imported by name. The clock is the base class's
     * pinned instant, so an issued session and a written audit value mean the same thing on every run.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({CardController.class, GlobalExceptionHandler.class, JsonRefusalBodyRenderer.class,
            ModuleErrorController.class, ScreenStateAdapter.class, CardListService.class,
            CardDetailService.class, CardUpdateService.class, CardConcurrencyTokenService.class,
            SensitiveFieldEncryptionService.class, NavigationService.class, MessageCatalogService.class,
            AbendService.class, OnlineTransactionBoundary.class, RecordWriter.class,
            SignOnStateService.class, SecurityConfig.class, JwtTokenProvider.class, WebMvcConfig.class})
    @EnableConfigurationProperties(JwtProperties.class)
    @EnableJpaRepositories(basePackageClasses = CardRepository.class)
    @EntityScan(basePackageClasses = Card.class)
    static class CardScreensContext {

        /** Creates the slice. */
        CardScreensContext() {
            // Intentionally empty: the graph is declared by the annotations above.
        }

        /**
         * The pinned clock every collaborator in the graph reads.
         *
         * @return the shared fixed clock
         */
        @Bean
        Clock fixedClock() {
            return FIXED_CLOCK;
        }
    }
}
