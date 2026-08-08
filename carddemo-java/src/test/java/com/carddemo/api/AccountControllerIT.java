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

import com.carddemo.api.dto.AccountUpdateResponse;
import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.api.dto.FieldErrorDecorator;
import com.carddemo.config.JwtProperties;
import com.carddemo.config.JwtTokenProvider;
import com.carddemo.config.SecurityConfig;
import com.carddemo.config.WebMvcConfig;
import com.carddemo.domain.Account;
import com.carddemo.domain.Card;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.service.AbendService;
import com.carddemo.service.AccountConcurrencyTokenService;
import com.carddemo.service.AccountUpdateService;
import com.carddemo.service.AccountViewService;
import com.carddemo.service.DateValidationService;
import com.carddemo.service.MessageCatalogService;
import com.carddemo.service.NavigationService;
import com.carddemo.service.OnlineTransactionBoundary;
import com.carddemo.service.SensitiveFieldEncryptionService;
import com.carddemo.service.SignOnStateService;
import com.carddemo.service.ValidationLookupService;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.SensitiveValues;
import com.carddemo.support.TestDataFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration;
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
 * Contract specification for the two account-servicing screens over the shipped boundary: transaction
 * {@code CAVW}, the account view, and transaction {@code CAUP}, the account update - the largest single
 * translation in the estate and the home of the thirty-nine-site field-error decoration contract.
 *
 * <h2>What this class establishes, and why each half is here</h2>
 *
 * <p><strong>The view half proves that the view validates nothing.</strong> It is a display screen, and
 * three delivered facts make that observable rather than merely asserted: twenty of the seeded credit
 * scores sit below the update screen's lower bound and the lowest is {@code 001}, so a view that applied
 * the range would refuse rows the module itself ships; the account group identifier is ten spaces in
 * every seeded row, so a view that trimmed would publish a different width from the one the screen had;
 * and the status column carries no check constraint, so a value outside the declared pair has to
 * serialise rather than raise.
 *
 * <p><strong>The update half proves the two-state field-error contract and the gate in front of it.</strong>
 * The legacy decorated a field only when the conversation was a re-entry, and it distinguished a field
 * left blank from one filled in wrongly by two different rendering devices. Both properties are
 * behavioural here: the same rejected submission is driven twice, once as a first submission and once as
 * a re-submission, and only the second carries per-field detail.
 *
 * <h2>Where the fixtures come from, and why the two halves use different ones</h2>
 *
 * <p>The view half reads the delivered seed rows and writes nothing, because the seeded values are
 * exactly what makes the display-only claim checkable. The update half cannot: measured against the two
 * externalised lookup tables, precisely one of the forty-nine seeded customers carries a state and
 * postal-code prefix combination the two-hundred-and-forty-entry table holds, and most seeded telephone
 * area codes are absent from the four-hundred-and-ten-entry general-purpose set - so no faithful echo of
 * a seeded row can reach an all-edits-pass outcome. The update half therefore owns a reserved key range,
 * writes it before every method and removes it after, which is the convention the shared base class
 * states: several other specifications assert against the delivered rows on the same shared server, so
 * none of them may be mutated here.
 *
 * <h2>Container ownership and determinism</h2>
 *
 * <p>The server, the migration and the pinned clock all belong to {@link AbstractPostgresIT}. Nothing
 * here declares a container, a container extension, a dynamic property source, a context-dirtying
 * annotation or an active profile, and nothing here reads a wall clock, a random source or a generated
 * identifier: every date-sensitive outcome is anchored to the base class's pinned instant.
 *
 * <h2>Two shipped behaviours that are deliberately asserted as shipped</h2>
 *
 * <p>First, <strong>the four regulated customer values are withheld on the view turn from every caller
 * that is not an administrator, and revealed to one that is.</strong> The boundary derives that authority
 * from the established identity, by the same derivation the update turn reads, so the national identifier,
 * the birth date, the government-issued identifier and the transfer-account identifier are published as
 * masks of the same width the revealed values occupy for an ordinary caller and in the clear for an
 * administrator. Withholding them from an ordinary caller is a deliberate, documented divergence from the
 * estate, which showed those values to any signed-on operator, and it is recorded in
 * {@code docs/decision-log.md}. The boundary previously held one <em>constant</em> unprivileged authority
 * here, which masked them for an administrator too while the update turn revealed them - the two screens
 * disagreeing about the same four values of the same record. This specification asserts the shipped
 * behaviour rather than the legacy one, and states the divergence here so a reader is not misled.
 *
 * <p>Second, <strong>the update screen's two lock-failure texts stay distinct.</strong> The program has
 * one for the account record and one for the customer record, where the sibling card-update program has
 * a single generic text. Fusing them would be an interface-contract failure and is asserted against.
 *
 * <h2>Provenance</h2>
 *
 * <p>{@code app/cbl/COACTVWC.cbl} and {@code app/cbl/COACTUPC.cbl} with their symbolic maps
 * {@code app/cpy-bms/COACTVW.CPY} and {@code app/cpy-bms/COACTUP.CPY}, the mapsets
 * {@code app/bms/COACTVW.bms} and {@code app/bms/COACTUP.bms}, the decoration macro
 * {@code app/cpy/CSSETATY.cpy}, the lookup lists {@code app/cpy/CSLKPCDY.cpy}, the date cascade
 * {@code app/cpy/CSUTLDPY.cpy}, and the record layouts {@code app/cpy/CVACT01Y.cpy},
 * {@code app/cpy/CVCUS01Y.cpy}, {@code app/cpy/CVCRD01Y.cpy} and {@code app/cpy/COCOM01Y.cpy}, all read
 * as read-only reference. Widths, offsets, counts, codes, field identifiers, paragraph line numbers and
 * operator-visible message texts are contract and metadata; no source line of any legacy member is
 * transcribed here and nothing in the legacy tree is read at run time.
 *
 * @since 1.0.0
 */
@SpringBootTest(classes = AccountControllerIT.AccountScreenContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            // The shared server was already migrated to the head of the delivered set by the base
            // class, so a second migration from this context would be redundant work with no new
            // state to apply.
            "spring.flyway.enabled=false",
            // validate, never none and never a generating value: a mapping that had drifted from the
            // migrated schema must fail this specification at refresh rather than be reconciled
            // silently, and validate cannot emit DDL.
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.batch.job.enabled=false",
            "spring.main.banner-mode=off",
            "management.endpoint.health.validate-group-membership=false",
            "management.tracing.enabled=false",
            // The boundary here is a mock servlet inside this process, so there is no wire for a
            // session to be observed on and the transport requirement is relaxed exactly as the test
            // profile relaxes it. Nothing else is relaxed: both account routes keep the shipped
            // authenticated rule, which is the whole point of asserting against it.
            "carddemo.security.require-https=false"})
@AutoConfigureMockMvc
@DisplayName("Gate 5: the account view (CAVW) and account update (CAUP) screens over the shipped "
        + "boundary")
class AccountControllerIT extends AbstractPostgresIT {

    // ===============================================================================================
    // THE VIEW CONTRACT, RESTATED HERE RATHER THAN IMPORTED
    //
    // Every text below is written out independently of the production constant that also declares it.
    // That is the point of a contract specification: a test that compared a constant with itself would
    // pass however the text drifted.
    // ===============================================================================================

    /** Width of the view screen's informational item, which is padded rather than trimmed. */
    private static final int VIEW_INFO_MESSAGE_WIDTH = 40;

    /** Width of the view screen's message item, which is padded rather than trimmed. */
    private static final int VIEW_ERROR_MESSAGE_WIDTH = 75;

    /** The view screen's standing guidance line, thirty-nine characters inside a forty-wide item. */
    private static final String VIEW_PROMPT_FOR_INPUT = "Enter or update id of account to display";

    /**
     * A text the view program declares and never assigns.
     *
     * <p>{@code app/cbl/COACTVWC.cbl} line 116 declares it as an alternative value of the
     * informational item, and no statement anywhere in the program sets it - only the guidance line
     * above is ever set, at lines 463 and 529. It is therefore asserted to be absent, which is the
     * faithful reading of a declared-but-unassigned condition name.
     */
    private static final String VIEW_NEVER_ASSIGNED_INFO = "Displaying details of given Account";

    /** The message a blank search field leaves on the view screen. */
    private static final String VIEW_NO_INPUT_RECEIVED = "No input received";

    /** The message the view screen's non-numeric arm moves. The double space after the third word is
     * part of the moved literal and is never tidied. */
    private static final String VIEW_FILTER_NOT_NUMERIC =
            "Account Filter must  be a non-zero 11 digit number";

    /** The screen field the view cursor returns to, its map item being the only input on the screen. */
    private static final String VIEW_ACCOUNT_ID_FIELD = "ACCTSID";

    /** Response property the view's account-filter finding names. */
    private static final String VIEW_ACCOUNT_FILTER_PROPERTY = "accountIdFilter";

    /** Transaction the view screen is registered under in the resource definitions. */
    private static final String VIEW_TRANSACTION = "CAVW";

    /** Program the view transaction is bound to. */
    private static final String VIEW_PROGRAM = "COACTVWC";

    /** Wire value of the destination a view turn re-arms on. */
    private static final String VIEW_ROUTE = "account-view";

    /** Wire value of the destination an update turn re-arms on. */
    private static final String UPDATE_ROUTE = "account-update";

    // ===============================================================================================
    // THE UPDATE CONTRACT, RESTATED HERE RATHER THAN IMPORTED
    //
    // Four traps are live in this block and each looks like a typo. None is:
    //   - the success text really carries FOUR dots;
    //   - the conflict text really spells "some one" as TWO words;
    //   - the digit-count texts really capitalise the article, "must be A 3 digit number.";
    //   - one guidance line has NO space after its full stop while its sibling HAS one, and the
    //     inconsistency between the two IS the contract.
    // ===============================================================================================

    /** Guidance line of the turn that asks for the search key. */
    private static final String UPDATE_PROMPT_FOR_SEARCH_KEYS =
            "Enter or update id of account to update";

    /** Guidance line of a turn presenting details for editing. Carries a trailing full stop. */
    private static final String UPDATE_PROMPT_FOR_CHANGES = "Update account details presented above.";

    /** Guidance line of a validated but unconfirmed turn. NO space after the full stop. */
    private static final String UPDATE_PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";

    /** Guidance line of a committed turn. */
    private static final String UPDATE_CONFIRM_SUCCESS = "Changes committed to database";

    /** Guidance line of a failed turn. WITH a space after the full stop, unlike its sibling above. */
    private static final String UPDATE_INFORM_FAILURE = "Changes unsuccessful. Please try again";

    /** Message a faithful echo leaves, which stops the edit cascade before it starts. */
    private static final String UPDATE_NO_CHANGE_DETECTED =
            "No change detected with respect to values fetched.";

    /** Whole-field message of a status outside the declared pair. */
    private static final String UPDATE_STATUS_MUST_BE_Y_OR_N = "Account Active Status must be Y or N";

    /** Whole-field message of an absent credit limit. Ends without a full stop. */
    private static final String UPDATE_CREDIT_LIMIT_REQUIRED = "Credit Limit must be supplied";

    /** Whole-field message of a credit limit that is present and unparseable. */
    private static final String UPDATE_CREDIT_LIMIT_NOT_VALID = "Credit Limit is not valid";

    /** The success text. FOUR dots, twenty-one characters. */
    private static final String UPDATE_LOOKS_GOOD_SO_FAR = "Looks Good.... so far";

    /** The card-expiry month text. One and twelve, not zero-one and twelve. */
    private static final String UPDATE_EXPIRY_MONTH_NOT_VALID =
            "Card expiry month must be between 1 and 12";

    /** The concurrent-change text. "some one" is TWO words and there is no closing full stop. */
    private static final String UPDATE_RECORD_CHANGED =
            "Record changed by some one else. Please review";

    /** The account-record hold failure. Distinct from its customer twin and never unified with it. */
    private static final String UPDATE_COULD_NOT_HOLD_ACCOUNT =
            "Could not lock account record for update";

    /** The customer-record hold failure. */
    private static final String UPDATE_COULD_NOT_HOLD_CUSTOMER =
            "Could not lock customer record for update";

    /** The rewrite failure, which serves both asymmetric arms. */
    private static final String UPDATE_OF_RECORD_FAILED = "Update of record failed";

    /** Credit-score range suffix. Thirty-one characters and NO trailing full stop. */
    private static final String SUFFIX_FICO_OUT_OF_RANGE = ": should be between 300 and 850";

    /** State-membership suffix. Ends without a full stop. */
    private static final String SUFFIX_STATE_NOT_VALID = ": is not a valid state code";

    /** The one message composed with no field-name prefix at all. */
    private static final String MSG_INVALID_ZIP_FOR_STATE = "Invalid zip code for state";

    /** Area-code digit-count suffix. The article is capitalised in the legacy text. */
    private static final String SUFFIX_AREA_CODE_NOT_3_DIGITS =
            ": Area code must be A 3 digit number.";

    /** Prefix absent suffix. */
    private static final String SUFFIX_PREFIX_REQUIRED = ": Prefix code must be supplied.";

    /** Line-number all-zero suffix. Ends without a full stop. */
    private static final String SUFFIX_LINE_NUMBER_ZERO = ": Line number code cannot be zero";

    /** Area code outside the general-purpose set. The longest of the suffixes at fifty-one characters. */
    private static final String SUFFIX_AREA_CODE_NOT_GENERAL_PURPOSE =
            ": Not valid North America general purpose area code";

    /** Required-alphabetic suffix, used to prove the character-class edit is real. */
    private static final String SUFFIX_ALPHABETS_ONLY = " can have alphabets only.";

    /**
     * The three-letter prefix every terminal attribute constant shares.
     *
     * <p>The decoration macro drove two of that family - a colour written over a field in error and a
     * highlight it never used - and neither has an analogue in a machine contract. Refusing the prefix
     * refuses every member of the family at once, which is stronger than naming the individual constants
     * and keeps the terminal vocabulary out of this file as well as out of the payload.
     */
    private static final String TERMINAL_ATTRIBUTE_FAMILY_PREFIX = "DFH";

    /**
     * The three-letter prefix every field of the terminal's control block shares.
     *
     * <p>The response and reason codes the legacy branched on, and the attention-key byte it read, all
     * live behind this prefix. None of them is part of a machine contract, so the prefix refuses the
     * whole block rather than the two or three fields a reviewer would think to name.
     */
    private static final String TERMINAL_CONTROL_BLOCK_PREFIX = "EIB";

    /**
     * The two characters every job-control statement begins with.
     *
     * <p>Nothing about how a batch job is described belongs on an interactive screen, and the marker
     * refuses every statement kind at once.
     */
    private static final String JOB_CONTROL_STATEMENT_PREFIX = "//";

    /** Label the credit-score edit composes its suffix onto. */
    private static final String LABEL_FICO_SCORE = "FICO Score";

    /** Label the state edits compose their suffixes onto. */
    private static final String LABEL_STATE = "State";

    /** Label the first-name edit composes its suffix onto. */
    private static final String LABEL_FIRST_NAME = "First Name";

    /** Label both telephone-one stages compose their suffixes onto. */
    private static final String LABEL_PHONE_NUMBER_1 = "Phone Number 1";

    /** Label the last-name edit composes its suffix onto. */
    private static final String LABEL_LAST_NAME = "Last Name";

    /** Label the city edit composes its suffix onto, the screen item being backed by an address line. */
    private static final String LABEL_CITY = "City";

    /** Label the postal-code edit composes its suffix onto. Three characters, not five. */
    private static final String LABEL_ZIP = "Zip";

    /** Label the status edit composes its suffix onto, which is NOT the whole-field text below. */
    private static final String LABEL_ACCOUNT_STATUS = "Account Status";

    /** Label the credit-limit edits compose their suffixes onto. */
    private static final String LABEL_CREDIT_LIMIT = "Credit Limit";

    /** Generic blank-field suffix. Carries a trailing full stop. */
    private static final String SUFFIX_MUST_BE_SUPPLIED = " must be supplied.";

    /** Generic character-class suffix of the required-numeric edit. */
    private static final String SUFFIX_MUST_BE_ALL_NUMERIC = " must be all numeric.";

    /** Generic suffix of the yes-or-no edit's third arm. */
    private static final String SUFFIX_MUST_BE_Y_OR_N = " must be Y or N.";

    /** Generic malformed-amount suffix. Ends WITHOUT a full stop, unlike its blank sibling. */
    private static final String SUFFIX_IS_NOT_VALID = " is not valid";

    /** Area code absent suffix. */
    private static final String SUFFIX_AREA_CODE_REQUIRED = ": Area code must be supplied.";

    /** Line-number digit-count suffix. Four digits, and the article is capitalised here too. */
    private static final String SUFFIX_LINE_NUMBER_NOT_4_DIGITS =
            ": Line number code must be A 4 digit number.";

    // ===============================================================================================
    // THE INCLUSIVE CREDIT-SCORE BOUND, AS FOUR KEYED VALUES
    // ===============================================================================================

    /** The lower bound itself, which passes. */
    private static final String CREDIT_SCORE_LOWER_BOUND = "300";

    /** The upper bound itself, which passes. */
    private static final String CREDIT_SCORE_UPPER_BOUND = "850";

    /** One below the lower bound, which fails. */
    private static final String CREDIT_SCORE_BELOW_BOUND = "299";

    /** One above the upper bound, which fails. */
    private static final String CREDIT_SCORE_ABOVE_BOUND = "851";

    // ===============================================================================================
    // THE THIRTY-NINE DECORATION SITES, IN SOURCE ORDER
    // ===============================================================================================

    /**
     * The thirty-nine screen-field identifiers the decoration macro is expanded against, in the order
     * the expansions appear between lines 3208 and 3432 of {@code app/cbl/COACTUPC.cbl}.
     *
     * <p>Two orderings inside it look wrong and are contractual: the state sits <em>between</em> the two
     * address lines, and the postal code precedes both the city and the country. Two of the adjacent
     * source comments are transposed against the expansions they describe and one is mislabelled, so the
     * macro's substitution token governs in every case - which is why the primary-cardholder indicator
     * maps to {@code ACSPFLG} and the transfer-account identifier to {@code ACSEFTC} rather than the
     * other way round.
     */
    private static final List<String> DECORATION_SITES_IN_SOURCE_ORDER = List.of(
            "ACSTTUS", "OPNYEAR", "OPNMON", "OPNDAY", "ACRDLIM",
            "EXPYEAR", "EXPMON", "EXPDAY", "ACSHLIM", "RISYEAR",
            "RISMON", "RISDAY", "ACURBAL", "ACRCYCR", "ACRCYDB",
            "ACTSSN1", "ACTSSN2", "ACTSSN3", "DOBYEAR", "DOBMON",
            "DOBDAY", "ACSTFCO", "ACSFNAM", "ACSMNAM", "ACSLNAM",
            "ACSADL1", "ACSSTTE", "ACSADL2", "ACSZIPC", "ACSCITY",
            "ACSCTRY", "ACSPH1A", "ACSPH1B", "ACSPH1C", "ACSPH2A",
            "ACSPH2B", "ACSPH2C", "ACSPFLG", "ACSEFTC");

    /**
     * The four map fields an operator may type into that are never decorated.
     *
     * <p>The mapset declares forty-three unprotected fields and the macro is expanded thirty-nine times,
     * so exactly four are editable without a decoration site. None of them may ever appear in a field
     * error, whatever is typed into it.
     */
    private static final List<String> EDITABLE_BUT_NEVER_DECORATED = List.of(
            "accountId", "accountGroupId", "customerId", "governmentIssuedId");

    /** The two decorated fields the source states in place that it codes no edit for. */
    private static final List<String> DECORATED_BUT_NEVER_VALIDATED =
            List.of("middleName", "addressLine2");

    // ===============================================================================================
    // MEASURED CARDINALITIES OF THE EXTERNALISED LOOKUP TABLES
    // ===============================================================================================

    /** General-purpose telephone area codes. */
    private static final int GENERAL_PURPOSE_AREA_CODES = 410;

    /** Easily-recognisable telephone area codes, a disjoint subset of the same numbering plan. */
    private static final int EASY_RECOGNITION_AREA_CODES = 80;

    /** Their union, which is DERIVED and is never stored as a third list. */
    private static final int ALL_AREA_CODES =
            GENERAL_PURPOSE_AREA_CODES + EASY_RECOGNITION_AREA_CODES;

    /** United States state codes, tested by flat membership. */
    private static final int US_STATE_CODES = 56;

    /** State-and-postal-prefix combinations, tested as a four-character composite. */
    private static final int STATE_ZIP_COMBINATIONS = 240;

    /**
     * Postal prefixes the combination table uses that the state table does not carry at all.
     *
     * <p>Two overseas military prefixes, one diplomatic prefix and three Pacific prefixes. Intersecting
     * the two tables would refuse addresses the estate accepts, so they are asserted to be present in one
     * and absent from the other rather than reconciled.
     */
    private static final Set<String> PREFIXES_ABSENT_FROM_THE_STATE_TABLE =
            Set.of("AA", "AE", "AP", "FM", "MH", "PW");

    // ===============================================================================================
    // DELIVERED ROWS THIS SPECIFICATION READS AND NEVER WRITES
    // ===============================================================================================

    /** A delivered account, used for the display-only assertions. */
    private static final String SEEDED_ACCOUNT_ID = "00000000001";

    /** The customer joined to it through the cross-reference. */
    private static final String SEEDED_CUSTOMER_ID = "000000001";

    /** The delivered account whose customer carries the lowest credit score in the seed. */
    private static final String SEEDED_LOW_SCORE_ACCOUNT_ID = "00000000026";

    /** That score, three characters with its leading zeros intact. */
    private static final String SEEDED_LOWEST_CREDIT_SCORE = "001";

    /** The delivered credit limit of {@link #SEEDED_ACCOUNT_ID}, rendered plainly at scale two. */
    private static final String SEEDED_CREDIT_LIMIT_RENDERING = "\"creditLimit\":2020.00";

    /** The delivered balance of the same account, in the same rendering. */
    private static final String SEEDED_BALANCE_RENDERING = "\"currentBalance\":194.00";

    /** The city item of that account's screen, which is backed by the third address line. */
    private static final String SEEDED_CITY = "Altenwerthshire";

    /** Its first telephone number, already parenthesised in the record and never reformatted. */
    private static final String SEEDED_PHONE_NUMBER_1 = "(908)119-8310";

    /** An account identifier no delivered row carries, for the not-found outcome. */
    private static final String ABSENT_ACCOUNT_ID = "00000099999";

    /** Width of the account group item, which is ten spaces in every delivered row. */
    private static final int ACCOUNT_GROUP_ID_WIDTH = 10;

    /** Width of the birth-date item, and therefore of the mask that withholds it. */
    private static final int BIRTH_DATE_WIDTH = 10;

    /** Width of the government-issued identifier, and therefore of its mask. */
    private static final int GOVERNMENT_ID_WIDTH = 20;

    /** The character every withheld value is composed of. */
    private static final String MASK_CHARACTER = "*";

    /** Prefix every sealed column carries, which must never reach a payload. */
    private static final String ENVELOPE_PREFIX = "ENC1:";


    // ===============================================================================================
    // THE KEY RANGE THIS SPECIFICATION OWNS
    //
    // Everything the update half writes is keyed inside a range the delivered seed never occupies, and
    // the cleanup is scoped to that range by identity. Nothing here deletes, counts or mutates a
    // delivered row: several other specifications on this shared server assert against them.
    // ===============================================================================================

    /** Reserved account key, eleven digits and non-zero as the schema's own check requires. */
    private static final String OWNED_ACCOUNT_ID = "90000000001";

    /** Reserved customer key, nine digits. */
    private static final String OWNED_CUSTOMER_ID = "900000001";

    /** Reserved card number, sixteen digits, present only because the cross-reference references one. */
    private static final String OWNED_CARD_NUMBER = "9000000000000001";

    /** Reserved state code, chosen because the combination table holds it with the postal prefix below. */
    private static final String OWNED_STATE_CODE = "NC";

    /** Reserved postal code. Five characters, so the stored value and the screen item coincide. */
    private static final String OWNED_ZIP_CODE = "27000";

    /** A postal code whose prefix the combination table does not pair with the state above. */
    private static final String MISMATCHED_ZIP_CODE = "99999";

    /** Reserved credit score, comfortably inside the update screen's inclusive range. */
    private static final String OWNED_CREDIT_SCORE = "750";

    /** Reserved national identifier, nine digits, sealed before it is stored. */
    private static final String OWNED_NATIONAL_IDENTIFIER = "123456789";

    /** Reserved government-issued identifier, twenty digits, sealed before it is stored. */
    private static final String OWNED_GOVERNMENT_IDENTIFIER = "90000000000000000001";

    /** Reserved balance. */
    private static final BigDecimal OWNED_BALANCE = new BigDecimal("100.00");

    /** Reserved credit limit. */
    private static final BigDecimal OWNED_CREDIT_LIMIT = new BigDecimal("5000.00");

    /** Reserved cash credit limit. */
    private static final BigDecimal OWNED_CASH_CREDIT_LIMIT = new BigDecimal("1000.00");

    /** Reserved cycle amounts, which the delivered rows also carry as zero. */
    private static final BigDecimal OWNED_ZERO_AMOUNT = new BigDecimal("0.00");

    /** Reserved open date. */
    private static final String OWNED_OPEN_DATE = "2009-05-10";

    /** Reserved expiry date, which the reissue date matches as the delivered rows do. */
    private static final String OWNED_EXPIRY_DATE = "2025-10-06";

    /** A telephone area code the general-purpose set holds. */
    private static final String GENERAL_PURPOSE_AREA_CODE = "212";

    /**
     * A telephone area code the easily-recognisable set holds and the general-purpose set does not.
     *
     * <p>The area-code edit consults the general-purpose set alone, so this value is refused - which is
     * exactly why the two subsets are never merged into the derived union for validation purposes.
     */
    private static final String EASILY_RECOGNISABLE_AREA_CODE = "999";

    /** A state code no table entry carries. */
    private static final String UNKNOWN_STATE_CODE = "ZZ";

    /** Presentation prefix an issued session travels behind. */
    private static final String BEARER_PREFIX = "Bearer ";

    /** Wire name of the echoed conversation record. */
    private static final String NAVIGATION_CONTEXT = "navigationContext";

    /** Wire name of the flag inside it that decides whether decoration applies at all. */
    private static final String PROGRAM_CONTEXT = "programContext";

    /** Wire name of the opaque before-image proof a confirmation echoes. */
    private static final String CONCURRENCY_TOKEN = "concurrencyToken";

    /** Wire name of the attention key. */
    private static final String KEY_ACTION = "keyAction";

    /** Wire name of the per-field finding collection. */
    private static final String FIELD_ERRORS = "fieldErrors";

    /** Wire name of the single summary slot. */
    private static final String ERROR_MESSAGE = "errorMessage";

    /** Wire name of the guidance slot, which is not an error. */
    private static final String INFO_MESSAGE = "infoMessage";

    /** Wire name of the turn's own error switch. */
    private static final String ERROR_FLAG = "error";

    /** First-entry value of the conversation flag: no decoration applies. */
    private static final String FIRST_ENTRY = "ENTER";

    /** Re-entry value of the same flag: decoration applies. */
    private static final String RE_ENTRY = "REENTER";

    /**
     * The forty-three map components a client echoes back, in the order the map declares them.
     *
     * <p>Used to copy one presented screen into the next submission unchanged, so a test alters exactly
     * the components it means to alter and every other one crosses byte for byte. The five monetary
     * components appear here too: they leave as exact decimals and return as the raw screen lexemes the
     * request contract carries, which is the asymmetry the request type documents.
     */
    private static final List<String> ECHOED_MAP_COMPONENTS = List.of(
            "accountId", "accountStatus", "openYear", "openMonth", "openDay",
            "creditLimit", "expiryYear", "expiryMonth", "expiryDay", "cashCreditLimit",
            "reissueYear", "reissueMonth", "reissueDay", "currentBalance", "currentCycleCredit",
            "accountGroupId", "currentCycleDebit", "customerId", "ssnPart1", "ssnPart2",
            "ssnPart3", "dateOfBirthYear", "dateOfBirthMonth", "dateOfBirthDay", "ficoScore",
            "firstName", "middleName", "lastName", "addressLine1", "stateCode",
            "addressLine2", "zipCode", "city", "countryCode", "phone1AreaCode",
            "phone1Prefix", "phone1LineNumber", "governmentIssuedId", "phone2AreaCode",
            "phone2Prefix", "phone2LineNumber", "eftAccountId", "primaryCardHolderIndicator");

    /** The five monetary components, which are read back as exact decimals rather than as text. */
    private static final Set<String> MONETARY_COMPONENTS = Set.of(
            "creditLimit", "cashCreditLimit", "currentBalance", "currentCycleCredit",
            "currentCycleDebit");

    /**
     * Reads and writes JSON without binding to a published contract type.
     *
     * <p>Exact decimals are preferred on the way in so that a monetary value read out of one screen and
     * echoed into the next keeps the scale its record field stores, rather than passing through a binary
     * floating-point value on the way.
     */
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    /** The shipped servlet boundary, with the shipped filter chain in front of it. */
    @Autowired
    private MockMvc client;

    /** The shipped session provider, asked directly exactly as the sign-on path asks it. */
    @Autowired
    private JwtTokenProvider tokenProvider;

    /** The account master, for writing and removing the reserved account. */
    @Autowired
    private AccountRepository accounts;

    /** The customer master, for the reserved customer. */
    @Autowired
    private CustomerRepository customers;

    /** The card master, present only because the cross-reference has a foreign key into it. */
    @Autowired
    private CardRepository cards;

    /** The cross-reference, which is how an account resolves to its customer. */
    @Autowired
    private CardCrossReferenceRepository crossReferences;

    /** The shipped sealing service, so the reserved customer's two protected columns hold real
     * envelopes rather than values this specification invented. */
    @Autowired
    private SensitiveFieldEncryptionService encryption;

    /** The shipped lookup tables, for the cardinality assertions. */
    @Autowired
    private ValidationLookupService lookups;

    /** Creates the specification. */
    AccountControllerIT() {
        super();
    }

    // ===============================================================================================
    // LIFECYCLE
    // ===============================================================================================

    /**
     * Writes the reserved account, card, customer and cross-reference immediately before every method.
     *
     * <p>Written rather than reused, because several methods change or remove one of them and a method
     * that left a changed row behind would decide what the next method found. The delivered rows are
     * untouched throughout.
     */
    @BeforeEach
    void writeOwnedRows() {
        removeOwnedRows();
        this.accounts.save(ownedAccount("Y"));
        this.cards.save(TestDataFactory.card()
                .cardNumber(OWNED_CARD_NUMBER)
                .accountId(OWNED_ACCOUNT_ID)
                .build());
        this.customers.save(ownedCustomer());
        this.crossReferences.save(TestDataFactory.cardCrossReference()
                .cardNumber(OWNED_CARD_NUMBER)
                .customerId(OWNED_CUSTOMER_ID)
                .accountId(OWNED_ACCOUNT_ID)
                .build());
    }

    /** Removes only the four rows this specification wrote, in foreign-key order. */
    @AfterEach
    void removeOwnedRows() {
        this.crossReferences.deleteById(OWNED_CARD_NUMBER);
        this.cards.deleteById(OWNED_CARD_NUMBER);
        this.customers.deleteById(OWNED_CUSTOMER_ID);
        this.accounts.deleteById(OWNED_ACCOUNT_ID);
    }

    /**
     * Builds the reserved account with the status the caller asks for.
     *
     * @param  activeStatus the one-character status to store, which the column does not constrain
     * @return the entity, not yet saved
     */
    private static Account ownedAccount(final String activeStatus) {
        return TestDataFactory.account()
                .acctId(OWNED_ACCOUNT_ID)
                .activeStatus(activeStatus)
                .currentBalance(OWNED_BALANCE)
                .creditLimit(OWNED_CREDIT_LIMIT)
                .cashCreditLimit(OWNED_CASH_CREDIT_LIMIT)
                .openDate(OWNED_OPEN_DATE)
                .expirationDate(OWNED_EXPIRY_DATE)
                .reissueDate(OWNED_EXPIRY_DATE)
                .cycleCredit(OWNED_ZERO_AMOUNT)
                .cycleDebit(OWNED_ZERO_AMOUNT)
                .groupId(TestDataFactory.SEEDED_ACCOUNT_GROUP_ID)
                .build();
    }

    /**
     * Builds the reserved customer, sealing both protected identifiers with the shipped service.
     *
     * <p>Sealing rather than supplying a stand-in matters: the boundary opens both columns through the
     * same service, and a column that did not hold a well-formed envelope would surface as a refused turn
     * rather than as the screen under test.
     *
     * @return the entity, not yet saved
     */
    private Customer ownedCustomer() {
        return TestDataFactory.customer()
                .customerId(OWNED_CUSTOMER_ID)
                .stateCode(OWNED_STATE_CODE)
                .addressZip(OWNED_ZIP_CODE)
                .creditScore(OWNED_CREDIT_SCORE)
                .nationalIdentifierProtected(this.encryption.protect(
                        SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD,
                        OWNED_NATIONAL_IDENTIFIER))
                .governmentIdentifierProtected(this.encryption.protect(
                        SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                        OWNED_GOVERNMENT_IDENTIFIER))
                .build();
    }


    // ===============================================================================================
    // SHARED MACHINERY
    //
    // No cleartext credential is read, held or rendered anywhere in this class. A session is minted
    // through the shipped provider, which is what the sign-on path itself does once it has verified a
    // credential, so the eight-character value the legacy record stored appears here in no form at all.
    // ===============================================================================================

    /**
     * Mints a session for a delivered administrative identity.
     *
     * <p>The provider refuses to mint for an identity no record carries, so a session existing at all is
     * evidence that the credential seed applied. Both the mint and the later verification read the pinned
     * clock, so the session neither expires nor depends on when the suite runs.
     *
     * @return the value of the authorization header, presentation prefix included
     */
    private String administrativeSession() {
        return BEARER_PREFIX + this.tokenProvider.issue("ADMIN001", UserType.ADMIN,
                UserType.ADMIN.getCode());
    }

    /**
     * Mints a session for a delivered ordinary identity.
     *
     * <p>Both account routes are reachable by either declared user type, so this session is what proves
     * neither is administrator-only.
     *
     * @return the value of the authorization header, presentation prefix included
     */
    private String ordinarySession() {
        return BEARER_PREFIX + this.tokenProvider.issue("USER0001", UserType.USER,
                UserType.USER.getCode());
    }

    /**
     * Performs one turn of the view screen.
     *
     * @param  accountId      the search field as typed, or {@code null} to omit the parameter entirely
     * @param  programContext the conversation flag to echo, or {@code null} to send no record at all
     * @param  session        the authorization header value, or {@code null} to present none
     * @return the completed result, so status, headers and body can all be inspected
     * @throws Exception if the boundary cannot be reached
     */
    private MvcResult viewTurn(final String accountId, final String programContext,
            final String session) throws Exception {
        var request = MockMvcRequestBuilders.post(AccountController.ACCOUNT_VIEW_PATH)
                .accept(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8);
        if (accountId != null) {
            request = request.param(AccountController.ACCOUNT_ID_PARAM, accountId);
        }
        if (session != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, session);
        }
        if (programContext != null) {
            request = request.contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(Map.of(PROGRAM_CONTEXT, programContext)));
        }
        return this.client.perform(request).andReturn();
    }

    /**
     * Performs one view turn that actually processes the search field, and returns the parsed screen.
     *
     * <p>The re-entry flag is what makes the turn a submission rather than a first presentation: a turn
     * carrying a first-entry flag reaches the arm that merely asks for a key and reads no record at all,
     * which is the legacy behaviour and is asserted separately.
     *
     * @param  accountId the search field as typed
     * @return the parsed body
     * @throws Exception if the boundary cannot be reached
     */
    private JsonNode viewedScreen(final String accountId) throws Exception {
        return bodyOf(viewTurn(accountId, RE_ENTRY, administrativeSession()));
    }

    /**
     * Performs one turn of the update screen.
     *
     * @param  screen         the forty-three map components to transmit
     * @param  token          the before-image proof to echo, or {@code null} for none
     * @param  programContext the conversation flag to echo, or {@code null} to omit the record
     * @param  keyAction      the attention key to transmit, or {@code null} to omit it
     * @param  session        the authorization header value, or {@code null} to present none
     * @return the completed result
     * @throws Exception if the boundary cannot be reached
     */
    private MvcResult updateTurn(final Map<String, Object> screen, final String token,
            final String programContext, final KeyAction keyAction, final String session)
            throws Exception {
        final Map<String, Object> payload = new LinkedHashMap<>(screen);
        if (token != null) {
            payload.put(CONCURRENCY_TOKEN, token);
        }
        if (keyAction != null) {
            payload.put(KEY_ACTION, keyAction.name());
        }
        if (programContext != null) {
            payload.put(NAVIGATION_CONTEXT, Map.of(PROGRAM_CONTEXT, programContext));
        }
        var request = MockMvcRequestBuilders.post(AccountController.ACCOUNT_UPDATE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content(JSON.writeValueAsString(payload));
        if (session != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, session);
        }
        return this.client.perform(request).andReturn();
    }

    /**
     * Fetches the reserved account's detail screen, which is the turn every update assertion starts from.
     *
     * <p>The fetch is a re-entry carrying no before-image proof, which is the one combination that reaches
     * the search-key edit and then the record read: a turn with no proof and a first-entry flag is the
     * screen that merely asks for a key, and a turn carrying a proof is a submission of details.
     *
     * @return the parsed detail screen, carrying the proof the next turn echoes
     * @throws Exception if the boundary cannot be reached
     */
    private JsonNode fetchedScreen() throws Exception {
        final JsonNode presented = bodyOf(updateTurn(Map.of("accountId", OWNED_ACCOUNT_ID), null,
                RE_ENTRY, KeyAction.ENTER, administrativeSession()));
        assertThat(textOf(presented, CONCURRENCY_TOKEN))
                .as("the fetch turn must mint the before-image proof; without it every following turn "
                        + "is another fetch and no edit ever runs. Screen was %s", presented)
                .isNotNull();
        return presented;
    }

    /**
     * Copies one presented screen into the next submission, component by component.
     *
     * <p>Every one of the forty-three map components crosses unchanged, so a caller alters only what it
     * means to alter. An absent component stays absent rather than becoming an empty string: the module
     * omits null properties from a payload, and a turn that supplied an empty string where the screen
     * showed nothing would be transmitting a value the screen never had.
     *
     * @param  presented the screen to echo
     * @return a mutable copy of the forty-three components
     */
    private static Map<String, Object> echo(final JsonNode presented) {
        final Map<String, Object> screen = new LinkedHashMap<>();
        for (final String component : ECHOED_MAP_COMPONENTS) {
            final JsonNode value = presented.get(component);
            if (value == null || value.isNull()) {
                continue;
            }
            // The five monetary components leave the screen as exact decimals and return as the raw
            // screen lexemes the request contract carries, which is the asymmetry that contract
            // documents. They are read as exact decimals rather than through a binary floating-point
            // value on the way, and the transaction compares two amounts numerically, so a value
            // differing only in trailing zeros is correctly not a change.
            screen.put(component, MONETARY_COMPONENTS.contains(component)
                    ? value.decimalValue().toPlainString()
                    : value.asText());
        }
        return screen;
    }

    /**
     * Submits an echoed screen and returns the settled turn.
     *
     * @param  screen         the components to transmit
     * @param  token          the before-image proof to echo
     * @param  programContext the conversation flag, which is what decides whether decoration applies
     * @return the parsed body
     * @throws Exception if the boundary cannot be reached
     */
    private JsonNode submitted(final Map<String, Object> screen, final String token,
            final String programContext) throws Exception {
        return bodyOf(updateTurn(screen, token, programContext, KeyAction.ENTER,
                administrativeSession()));
    }

    /**
     * Copies one presented screen and corrects the single component the reserved fixture cannot hold
     * validly, so that every one of the twenty-four edits passes unless a caller breaks one on purpose.
     *
     * <p>The shared record builder's second telephone number carries an area code the general-purpose
     * numbering-plan set does not hold, and the builder exposes no setter for it. Correcting it here is
     * also what makes the submission a change at all, which every assertion needs: a faithful echo stops
     * the edit cascade before it starts, so a screen with nothing altered can never reach an edit.
     *
     * @param  presented the screen to echo
     * @return a mutable copy that passes every edit as it stands
     */
    private static Map<String, Object> cleanEcho(final JsonNode presented) {
        final Map<String, Object> screen = echo(presented);
        screen.put("phone2AreaCode", GENERAL_PURPOSE_AREA_CODE);
        return screen;
    }

    /**
     * Fetches the reserved screen, corrects it, applies one alteration and submits it as a re-entry.
     *
     * <p>The shape most assertions need: a re-entry is what lets the per-field detail appear at all, and
     * starting from a screen that otherwise passes every edit means the finding under test is the only
     * one raised and the single summary slot carries its message rather than an earlier field's.
     *
     * @param  component the map component to alter
     * @param  value     the value to put in it
     * @return the parsed settled turn
     * @throws Exception if the boundary cannot be reached
     */
    private JsonNode reSubmittedWith(final String component, final String value) throws Exception {
        final JsonNode presented = fetchedScreen();
        final Map<String, Object> screen = cleanEcho(presented);
        screen.put(component, value);
        return submitted(screen, textOf(presented, CONCURRENCY_TOKEN), RE_ENTRY);
    }

    /**
     * Fetches the reserved screen, corrects it and submits it as a re-entry with nothing else altered.
     *
     * @return the parsed settled turn, which must have passed every edit
     * @throws Exception if the boundary cannot be reached
     */
    private JsonNode reSubmittedCleanly() throws Exception {
        final JsonNode presented = fetchedScreen();
        return submitted(cleanEcho(presented), textOf(presented, CONCURRENCY_TOKEN), RE_ENTRY);
    }

    /**
     * Parses the body of a completed turn, having first asserted the status the contract fixes.
     *
     * @param  result the completed turn
     * @return the parsed body
     * @throws Exception if the body cannot be parsed
     */
    private static JsonNode bodyOf(final MvcResult result) throws Exception {
        final String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus())
                .as("every outcome of these two transactions is a screen the legacy program composed "
                        + "and sent, rejections and misses included, so the turn completes with the "
                        + "same status in all of them. Body was %s", body)
                .isEqualTo(200);
        return JSON.readTree(body);
    }

    /**
     * Returns the raw payload of a completed turn, for the assertions that are about the rendering.
     *
     * @param  result the completed turn
     * @return the payload exactly as it was written
     * @throws Exception if the payload cannot be read
     */
    private static String rawBodyOf(final MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Returns a raw body with the sealed concurrency token's value replaced by a fixed stand-in.
     *
     * <p>Used only where a body is scanned for text the module must never publish. The token is an
     * opaque envelope over a random initialisation vector, so the characters of its encoding are
     * effectively drawn at random from the encoding alphabet and can spell any short fragment by
     * chance - the two-character job-control marker among them. Scanning the envelope for module or
     * legacy vocabulary therefore measures the cipher's output rather than the response contract, and
     * would report a disclosure that is not one. The envelope itself is not left unasserted: a
     * separate test refuses to publish a sealed value on either screen, and the token's own contract
     * is asserted wherever a turn carries it forward.
     *
     * @param  body the raw response body
     * @return the same body with the token's value elided, or the body unchanged when it carries no
     *         token
     */
    private static String bodyWithoutTheSealedToken(final String body) {
        return body.replaceAll("(\"" + CONCURRENCY_TOKEN + "\":\")[^\"]*(\")", "$1elided$2");
    }

    /**
     * Reads a textual component of a body, distinguishing absent from present-and-null.
     *
     * @param  body the parsed body
     * @param  name the component name as the published contract declares it
     * @return the value, or {@code null} when the component is absent or null
     */
    private static String textOf(final JsonNode body, final String name) {
        final JsonNode value = body.get(name);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    /**
     * Lists the request-contract property names the per-field findings of one turn name, in order.
     *
     * @param  body the parsed body
     * @return the names, in the order the cascade reported them
     */
    private static List<String> flaggedFields(final JsonNode body) {
        final JsonNode findings = body.get(FIELD_ERRORS);
        if (findings == null || findings.isNull()) {
            return List.of();
        }
        final List<String> names = new ArrayList<>();
        for (final JsonNode entry : findings) {
            names.add(entry.get("fieldName").asText());
        }
        return List.copyOf(names);
    }

    /**
     * Reads the two-state finding one field is in.
     *
     * @param  body      the parsed body
     * @param  fieldName the request-contract property name
     * @return the state name, or {@code null} when the field was not flagged
     */
    private static String stateOf(final JsonNode body, final String fieldName) {
        final JsonNode entry = findingFor(body, fieldName);
        return entry == null ? null : entry.get("state").asText();
    }

    /**
     * Reads the legacy screen-field identifier one finding carries.
     *
     * @param  body      the parsed body
     * @param  fieldName the request-contract property name
     * @return the identifier, or {@code null} when the field was not flagged
     */
    private static String screenFieldIdOf(final JsonNode body, final String fieldName) {
        final JsonNode entry = findingFor(body, fieldName);
        return entry == null ? null : entry.get("screenFieldId").asText();
    }

    /**
     * Finds the one finding raised against a field.
     *
     * @param  body      the parsed body
     * @param  fieldName the request-contract property name
     * @return the finding, or {@code null} when the field was not flagged
     */
    private static JsonNode findingFor(final JsonNode body, final String fieldName) {
        final JsonNode findings = body.get(FIELD_ERRORS);
        if (findings == null || findings.isNull()) {
            return null;
        }
        for (final JsonNode entry : findings) {
            if (fieldName.equals(entry.get("fieldName").asText())) {
                return entry;
            }
        }
        return null;
    }

    /**
     * A value repeated to a width, for the mask assertions.
     *
     * @param  width how many characters the withheld value occupied
     * @return the mask a withheld value of that width is published as
     */
    private static String maskOfWidth(final int width) {
        return MASK_CHARACTER.repeat(width);
    }


    // ===============================================================================================
    // GROUP 1 - THE VIEW SCREEN IS A DISPLAY SCREEN
    // ===============================================================================================

    /** Everything the view turn publishes, and everything it declines to do to what it publishes. */
    @Nested
    @DisplayName("View - display-only fidelity")
    class ViewDisplayOnlyFidelity {

        /** Creates the group. */
        ViewDisplayOnlyFidelity() {
            super();
        }

        @Test
        @DisplayName("publishes the screen the transaction composed, and re-arms itself declaratively")
        void publishesTheComposedScreen() throws Exception {
            final JsonNode screen = viewedScreen(SEEDED_ACCOUNT_ID);

            assertThat(textOf(screen, "transactionName"))
                    .as("the header names the transaction the resource definitions register this "
                            + "screen under")
                    .isEqualTo(VIEW_TRANSACTION);
            assertThat(textOf(screen, "programName"))
                    .as("and the member the transaction is bound to")
                    .isEqualTo(VIEW_PROGRAM);
            assertThat(textOf(screen, "nextRoute"))
                    .as("routing is declarative: the destination is a value in the body and nothing "
                            + "forwards, which is what replaces the legacy transfer dispatch")
                    .isEqualTo(VIEW_ROUTE);
            assertThat(textOf(screen, "focusScreenFieldId"))
                    .as("all three arms of the legacy cursor decision position the cursor on the same "
                            + "item, this screen having exactly one input")
                    .isEqualTo(VIEW_ACCOUNT_ID_FIELD);
            assertThat(screen.get("inputError").asBoolean())
                    .as("a resolved account raises nothing")
                    .isFalse();
            assertThat(flaggedFields(screen))
                    .as("and flags no field")
                    .isEmpty();
        }

        @Test
        @DisplayName("round-trips the eleven-digit key with its leading zeros intact")
        void roundTripsTheKeyWithLeadingZeros() throws Exception {
            // Every numeric legacy identifier crosses this API as bounded text. Carried as a number the
            // key would come back as 1, which names no row at all in an eleven-character key column.
            final JsonNode screen = viewedScreen(SEEDED_ACCOUNT_ID);

            assertThat(textOf(screen, "accountId"))
                    .as("the key is published exactly as it was keyed")
                    .isEqualTo(SEEDED_ACCOUNT_ID)
                    .hasSize(AccountController.ACCOUNT_ID_SCREEN_WIDTH)
                    .isNotEqualTo("1");
            assertThat(textOf(screen, "customerId"))
                    .as("and so is the customer key the cross-reference resolved")
                    .isEqualTo(SEEDED_CUSTOMER_ID);
        }

        @Test
        @DisplayName("shows the guidance line at its declared width, and never the text the program "
                + "declares and never sets")
        void showsTheGuidanceLineAndNeverTheUnassignedText() throws Exception {
            // COACTVWC line 116 declares an alternative value of the informational item that no
            // statement in the program ever sets; only the guidance line is set, at lines 463 and 529.
            final JsonNode screen = viewedScreen(SEEDED_ACCOUNT_ID);
            final String guidance = textOf(screen, INFO_MESSAGE);

            assertThat(guidance)
                    .as("the guidance line is published at the item's own width, trailing space and "
                            + "all, because the padding is part of what the screen showed")
                    .startsWith(VIEW_PROMPT_FOR_INPUT)
                    .hasSize(VIEW_INFO_MESSAGE_WIDTH);
            assertThat(rawBodyOf(viewTurn(SEEDED_ACCOUNT_ID, RE_ENTRY, administrativeSession())))
                    .as("and the alternative value the program declares but never sets must appear "
                            + "nowhere: reproducing an unassigned condition name would invent a screen "
                            + "the legacy never showed")
                    .doesNotContain(VIEW_NEVER_ASSIGNED_INFO);
        }

        @Test
        @DisplayName("publishes money plainly at scale two, never in scientific notation")
        void publishesMoneyPlainlyAtScaleTwo() throws Exception {
            // The response type refuses an amount whose scale is not the scale its record field stores,
            // so the value crossing untouched is the point: re-scaling at the boundary would hide the
            // very defect that guard exists to surface.
            final String payload = rawBodyOf(viewTurn(SEEDED_ACCOUNT_ID, RE_ENTRY,
                    administrativeSession()));

            assertThat(payload)
                    .as("the two decimal places of the record field survive the rendering")
                    .contains(SEEDED_CREDIT_LIMIT_RENDERING)
                    .contains(SEEDED_BALANCE_RENDERING);
            assertThat(payload)
                    .as("and no monetary value is rendered as a floating-point exponent")
                    .doesNotContain("E+")
                    .doesNotContain("e+");
        }

        @Test
        @DisplayName("publishes the telephone number as stored rather than reformatting it")
        void publishesTheTelephoneNumberAsStored() throws Exception {
            // The record already holds the parenthesised form: thirteen characters of content inside a
            // fifteen-character field. The screen item is thirteen wide, so the stored content fits and
            // nothing is assembled, parsed or punctuated at the boundary.
            final JsonNode screen = viewedScreen(SEEDED_ACCOUNT_ID);

            assertThat(textOf(screen, "phoneNumber1"))
                    .as("the value crosses as the record holds it")
                    .isEqualTo(SEEDED_PHONE_NUMBER_1);
        }

        @Test
        @DisplayName("populates the city item from the third address line, there being no city column")
        void populatesTheCityItemFromTheThirdAddressLine() throws Exception {
            // Recorded because the mapping is not the one a reader would assume from the item's name.
            final JsonNode screen = viewedScreen(SEEDED_ACCOUNT_ID);

            assertThat(textOf(screen, "city"))
                    .as("the customer layout carries no city member at all, and the transaction is the "
                            + "authority for this pairing")
                    .isEqualTo(SEEDED_CITY);
        }

        @Test
        @DisplayName("withholds the four regulated values from an ordinary session at the widths their "
                + "revealed forms occupy")
        void withholdsTheFourRegulatedValuesFromAnOrdinarySession() throws Exception {
            // A DOCUMENTED DIVERGENCE, ASSERTED AS SHIPPED. An ordinary caller receives stand-ins for
            // these four, where the estate showed them to any signed-on operator. Recorded in
            // docs/decision-log.md. The masks are the width of the values they replace so no client lays
            // the screen out differently.
            final JsonNode screen = bodyOf(viewTurn(SEEDED_ACCOUNT_ID, RE_ENTRY, ordinarySession()));

            assertThat(textOf(screen, "dateOfBirth"))
                    .as("the birth date is withheld at its own width")
                    .isEqualTo(maskOfWidth(BIRTH_DATE_WIDTH));
            assertThat(textOf(screen, "governmentIssuedId"))
                    .as("and the government-issued identifier at its twenty-character width")
                    .isEqualTo(maskOfWidth(GOVERNMENT_ID_WIDTH));
            assertThat(textOf(screen, "ssn"))
                    .as("the national identifier is the schema's only nullable column and is absent in "
                            + "every delivered row, so there is nothing to withhold and the component "
                            + "is omitted rather than masked")
                    .isNull();
            assertThat(rawBodyOf(viewTurn(SEEDED_ACCOUNT_ID, RE_ENTRY, administrativeSession())))
                    .as("and no sealed column is ever published as its envelope")
                    .doesNotContain(ENVELOPE_PREFIX);
        }

        @Test
        @DisplayName("reveals the same four values to an administrator, because the two screens are the "
                + "same regulated data behind the same policy and must not answer differently")
        void revealsTheFourRegulatedValuesToAnAdministrator() throws Exception {
            // The boundary used to hold one CONSTANT view authority, permanently unprivileged and carrying
            // no user type, while the update turn derived its authority from the principal. An
            // administrator therefore received masks here and cleartext there for the same four values of
            // the same record - a policy that answers by which screen asked is not a policy. Both turns
            // now read one derivation, and this is the assertion that would fail if the constant returned.
            final JsonNode screen = viewedScreen(OWNED_ACCOUNT_ID);

            assertThat(SensitiveValues.fingerprint(textOf(screen, "governmentIssuedId")))
                    .as("the identifier itself, opened from its sealed column, not a stand-in")
                    .isEqualTo(SensitiveValues.fingerprint(OWNED_GOVERNMENT_IDENTIFIER))
                    .isNotEqualTo(SensitiveValues.fingerprint(maskOfWidth(GOVERNMENT_ID_WIDTH)));
            assertThat(textOf(screen, "dateOfBirth"))
                    .as("and the birth date, which must equal the stored value rather than merely differ "
                            + "from the mask")
                    .isEqualTo(AccountControllerIT.this.customers.findById(OWNED_CUSTOMER_ID)
                            .orElseThrow().getCustDob())
                    .isNotEqualTo(maskOfWidth(BIRTH_DATE_WIDTH));
            assertThat(textOf(screen, "ssn").contains(OWNED_NATIONAL_IDENTIFIER.substring(0, 3)))
                    .as("the national identifier reaches this screen as the single dashed item the view "
                            + "map declared, in place of the three positions the update map declares, and "
                            + "it carries the reserved row's digits")
                    .isTrue();
            assertThat(textOf(screen, "ssn").contains(MASK_CHARACTER))
                    .as("and no part of it is stood in for")
                    .isFalse();
            assertThat(rawBodyOf(viewTurn(OWNED_ACCOUNT_ID, RE_ENTRY, administrativeSession())))
                    .as("and revealing never publishes the envelope a value is stored in")
                    .doesNotContain(ENVELOPE_PREFIX);
        }

        @Test
        @DisplayName("decides the reveal from the established identity alone, so an ordinary caller that "
                + "echoes back an administrative user type still receives stand-ins")
        void decidesTheRevealFromTheEstablishedIdentityAlone() throws Exception {
            // The echoed communication area is a client-supplied value. Were it consulted, a caller would
            // grant itself the reveal by typing one character into a request body, and an authorization
            // decided by request content is not an authorization. The forged record is well formed and
            // claims the administrative type and an administrative identifier, so the only reason the
            // screen can withhold is that the decision came from the token.
            final MvcResult forged = AccountControllerIT.this.client.perform(
                            MockMvcRequestBuilders.post(AccountController.ACCOUNT_VIEW_PATH)
                                    .accept(MediaType.APPLICATION_JSON)
                                    .characterEncoding(StandardCharsets.UTF_8)
                                    .param(AccountController.ACCOUNT_ID_PARAM, OWNED_ACCOUNT_ID)
                                    .header(HttpHeaders.AUTHORIZATION, ordinarySession())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(JSON.writeValueAsString(Map.of(
                                            PROGRAM_CONTEXT, RE_ENTRY,
                                            "userType", UserType.ADMIN.getCode(),
                                            "userId", "ADMIN001"))))
                    .andReturn();

            final JsonNode screen = bodyOf(forged);

            assertThat(textOf(screen, "governmentIssuedId"))
                    .as("a forged user type in the echoed record grants nothing")
                    .isEqualTo(maskOfWidth(GOVERNMENT_ID_WIDTH));
            assertThat(textOf(screen, "dateOfBirth"))
                    .as("nor does a forged identifier alongside it")
                    .isEqualTo(maskOfWidth(BIRTH_DATE_WIDTH));
            assertThat(rawBodyOf(forged))
                    .as("and nothing sealed is published on the refused-reveal path either")
                    .doesNotContain(ENVELOPE_PREFIX);
        }

        @Test
        @DisplayName("reports a blank search field on a re-entry, and flags the filter as missing")
        void reportsABlankSearchFieldOnAReEntry() throws Exception {
            // Two texts are assigned in turn here and the second wins: the field edit sets the prompt
            // under its message-still-off guard, and the cross-field edit then replaces it with no guard
            // at all because the filter flag came back blank.
            final JsonNode screen = bodyOf(viewTurn(null, RE_ENTRY, administrativeSession()));

            assertThat(textOf(screen, ERROR_MESSAGE))
                    .as("the ungated assignment is the text that reaches a screen")
                    .startsWith(VIEW_NO_INPUT_RECEIVED)
                    .hasSize(VIEW_ERROR_MESSAGE_WIDTH);
            assertThat(screen.get("inputError").asBoolean())
                    .as("and the turn records that it rejected something")
                    .isTrue();
            assertThat(stateOf(screen, VIEW_ACCOUNT_FILTER_PROPERTY))
                    .as("a filter that was never supplied is the missing state, which is the state the "
                            + "legacy rendered with a marker as well as a colour change")
                    .isEqualTo(ErrorResponse.FieldState.MISSING.name());
            assertThat(screenFieldIdOf(screen, VIEW_ACCOUNT_FILTER_PROPERTY))
                    .as("and the finding names the map item so it can be correlated with the screen")
                    .isEqualTo(VIEW_ACCOUNT_ID_FIELD);
        }

        @Test
        @DisplayName("reports a non-numeric search field on a re-entry, and flags the filter as invalid")
        void reportsANonNumericSearchFieldOnAReEntry() throws Exception {
            // The double space after the third word is part of the literal the program moves, and it
            // differs from the condition name declared for the same purpose - the moved literal is the
            // one that reaches a screen.
            final JsonNode screen = bodyOf(viewTurn("0000000000A", RE_ENTRY, administrativeSession()));

            assertThat(textOf(screen, ERROR_MESSAGE))
                    .as("reproduced exactly as moved, double space included")
                    .startsWith(VIEW_FILTER_NOT_NUMERIC)
                    .hasSize(VIEW_ERROR_MESSAGE_WIDTH);
            assertThat(stateOf(screen, VIEW_ACCOUNT_FILTER_PROPERTY))
                    .as("a filter that was supplied and cannot be used is the invalid state, which the "
                            + "legacy rendered with a colour change alone - the operator's own "
                            + "keystrokes were left on the screen")
                    .isEqualTo(ErrorResponse.FieldState.INVALID.name());
        }

        @Test
        @DisplayName("reports an all-zero key with the same text, the two conditions being "
                + "indistinguishable on the screen")
        void reportsAnAllZeroKeyWithTheSameText() throws Exception {
            // One test in the source serves both the non-numeric and the all-zeros condition, so the
            // operator was told the same thing either way and the two are indistinguishable here too.
            final JsonNode screen = bodyOf(viewTurn("00000000000", RE_ENTRY, administrativeSession()));

            assertThat(textOf(screen, ERROR_MESSAGE))
                    .as("the same moved literal serves both conditions")
                    .startsWith(VIEW_FILTER_NOT_NUMERIC);
            assertThat(stateOf(screen, VIEW_ACCOUNT_FILTER_PROPERTY))
                    .as("and the filter is in the supplied-but-unusable state")
                    .isEqualTo(ErrorResponse.FieldState.INVALID.name());
        }

        @Test
        @DisplayName("resolves the customer through the cross-reference rather than from the account "
                + "itself, the account master carrying no customer key")
        void resolvesTheCustomerThroughTheCrossReference() throws Exception {
            // The legacy read the alternate-index path on the account key, took the first row in
            // ascending base-key order, and only then read the two masters. The account record has no
            // customer field at all, so the join is the only path from one to the other - which is why
            // removing the row has to make the customer unreachable rather than merely slower.
            final CardCrossReference joined = AccountControllerIT.this.crossReferences
                    .findById(OWNED_CARD_NUMBER)
                    .orElseThrow();

            assertThat(joined.getXrefAcctId())
                    .as("the row that carries the account key")
                    .isEqualTo(OWNED_ACCOUNT_ID);
            assertThat(joined.getXrefCustId())
                    .as("and the customer key the screen must arrive at")
                    .isEqualTo(OWNED_CUSTOMER_ID);
            assertThat(textOf(viewedScreen(OWNED_ACCOUNT_ID), "customerId"))
                    .as("which is what the screen publishes, at its nine-character width and with its "
                            + "leading zeros intact")
                    .isEqualTo(OWNED_CUSTOMER_ID);

            AccountControllerIT.this.crossReferences.deleteById(OWNED_CARD_NUMBER);
            final JsonNode withoutTheJoin = viewedScreen(OWNED_ACCOUNT_ID);

            assertThat(withoutTheJoin.get("inputError").asBoolean())
                    .as("with the join gone the turn cannot reach either master, so it is a miss rather "
                            + "than a partially filled screen")
                    .isTrue();
            assertThat(textOf(withoutTheJoin, "customerId"))
                    .as("and no customer key is published, the account master holding none to fall back "
                            + "on")
                    .isNull();
        }

        @Test
        @DisplayName("reports an account no row carries without disclosing anything internal")
        void reportsAnAccountNoRowCarries() throws Exception {
            final MvcResult result = viewTurn(ABSENT_ACCOUNT_ID, RE_ENTRY, administrativeSession());
            final JsonNode screen = bodyOf(result);

            assertThat(screen.get("inputError").asBoolean())
                    .as("a miss is a screen the legacy composed, so the turn completes and records the "
                            + "rejection in the body")
                    .isTrue();
            assertThat(rawBodyOf(result))
                    .as("and the screen text is the whole of what a miss discloses")
                    .doesNotContain("Exception")
                    .doesNotContain("org.postgresql")
                    .doesNotContain("card_cross_reference")
                    .doesNotContain("select ");
        }
    }

    // ===============================================================================================
    // GROUP 2 - THE THREE DELIVERED FACTS THAT MAKE DISPLAY-ONLY CHECKABLE
    // ===============================================================================================

    /** The seed-driven proofs: a sub-range credit score, a ten-space group item, an unconstrained status. */
    @Nested
    @DisplayName("View - sub-300 FICO and ten-space group id")
    class ViewSeedDrivenProofs {

        /** Creates the group. */
        ViewSeedDrivenProofs() {
            super();
        }

        @Test
        @DisplayName("publishes a credit score below the update screen's lower bound, verbatim and with "
                + "its leading zeros")
        void publishesASubRangeCreditScoreVerbatim() throws Exception {
            // The update screen enforces an inclusive 300-to-850 bound as ONLINE INPUT VALIDATION ONLY.
            // Twenty of the delivered customer rows score below it and the lowest is 001, so a view that
            // applied the bound - or a persistence constraint that expressed it - would refuse rows the
            // module itself ships.
            final JsonNode screen = viewedScreen(SEEDED_LOW_SCORE_ACCOUNT_ID);

            assertThat(screen.get("inputError").asBoolean())
                    .as("fetching a row whose score is below the update bound must succeed: the view "
                            + "screen validates nothing")
                    .isFalse();
            assertThat(textOf(screen, "ficoScore"))
                    .as("and the score crosses as three characters with its leading zeros intact, "
                            + "which is why the component is text and not a number")
                    .isEqualTo(SEEDED_LOWEST_CREDIT_SCORE)
                    .isNotEqualTo("1");
        }

        @Test
        @DisplayName("publishes the account group item as ten spaces, untrimmed")
        void publishesTheAccountGroupItemAsTenSpaces() throws Exception {
            // Ten spaces in all fifty delivered rows. It is also why the delivered interest lookup
            // misses its direct disclosure group and exercises the default fallback, so cleaning it
            // would change a batch outcome as well as a screen width.
            for (final String accountId : List.of(SEEDED_ACCOUNT_ID, SEEDED_LOW_SCORE_ACCOUNT_ID)) {
                final String groupId = textOf(viewedScreen(accountId), "accountGroupId");

                assertThat(groupId)
                        .as("account %s must publish the item at its declared width", accountId)
                        .hasSize(ACCOUNT_GROUP_ID_WIDTH);
                assertThat(groupId)
                        .as("account %s must publish it as the spaces the row holds, never trimmed to "
                                + "nothing", accountId)
                        .isEqualTo(" ".repeat(ACCOUNT_GROUP_ID_WIDTH));
            }
        }

        @Test
        @DisplayName("serialises a status outside the declared pair rather than raising")
        void serialisesAStatusOutsideTheDeclaredPair() throws Exception {
            // The status column carries no check constraint, exactly as the legacy one-character record
            // field carried none, so an undeclared value is representable. The view screen shows what
            // the row holds; only the update screen refuses one.
            AccountControllerIT.this.accounts.save(ownedAccount("Q"));

            final JsonNode screen = viewedScreen(OWNED_ACCOUNT_ID);

            assertThat(textOf(screen, "accountStatus"))
                    .as("an undeclared status crosses as the row holds it")
                    .isEqualTo("Q");
            assertThat(screen.get("inputError").asBoolean())
                    .as("and it is not an error on a display screen")
                    .isFalse();
        }
    }


    // ===============================================================================================
    // GROUP 3 - THE GATE IN FRONT OF THE FIELD-ERROR CONTRACT
    // ===============================================================================================

    /**
     * The decoration macro fired only on a re-entry, so a first submission carries no per-field detail
     * however the flags stand. The same rejected screen is driven twice here, and the two turns differ in
     * exactly one component of the echoed conversation record.
     */
    @Nested
    @DisplayName("Update - first submission has no field errors")
    class UpdateFirstSubmissionCarriesNoFieldErrors {

        /** Creates the group. */
        UpdateFirstSubmissionCarriesNoFieldErrors() {
            super();
        }

        @Test
        @DisplayName("reports the summary and no per-field detail at all on a first submission")
        void reportsTheSummaryAndNoFieldDetailOnAFirstSubmission() throws Exception {
            // The macro's own gate is a single condition on the re-enter flag: on a first entry the
            // operator has not been asked yet, so nothing is marked.
            final JsonNode presented = fetchedScreen();
            final Map<String, Object> screen = cleanEcho(presented);
            screen.put("ficoScore", "299");

            final JsonNode settled = bodyOf(updateTurn(screen, textOf(presented, CONCURRENCY_TOKEN),
                    FIRST_ENTRY, KeyAction.ENTER, administrativeSession()));

            assertThat(settled.get(ERROR_FLAG).asBoolean())
                    .as("the edits ran and one of them rejected the submission")
                    .isTrue();
            assertThat(textOf(settled, ERROR_MESSAGE))
                    .as("and the single summary slot carries the rejecting edit's own text")
                    .isEqualTo(LABEL_FICO_SCORE + SUFFIX_FICO_OUT_OF_RANGE);
            assertThat(flaggedFields(settled))
                    .as("but not one field is reported, because the decoration macro is gated on the "
                            + "conversation being a re-entry and this turn is not one")
                    .isEmpty();
        }

        @Test
        @DisplayName("reports the same summary and the per-field detail on a re-submission")
        void reportsTheSameSummaryAndTheFieldDetailOnAReSubmission() throws Exception {
            // Byte for byte the same submission as above, differing only in the echoed conversation
            // flag. That is the whole of the difference the legacy macro turned on.
            final JsonNode settled = reSubmittedWith("ficoScore", "299");

            assertThat(textOf(settled, ERROR_MESSAGE))
                    .as("the summary is unchanged: the cascade behaves identically either way")
                    .isEqualTo(LABEL_FICO_SCORE + SUFFIX_FICO_OUT_OF_RANGE);
            assertThat(flaggedFields(settled))
                    .as("and now the field is reported, which is the only difference the flag makes")
                    .containsExactly("ficoScore");
        }
    }

    // ===============================================================================================
    // GROUP 4 - TWO STATES, NEVER ONE FLAG
    // ===============================================================================================

    /**
     * The macro coloured a field when its flag was not-OK <em>or</em> blank and additionally wrote a
     * marker when it was specifically blank. Two operator mistakes, two remedies, and therefore two
     * states on the wire - and neither of the two rendering devices themselves.
     */
    @Nested
    @DisplayName("Update - MISSING vs INVALID on re-submission")
    class UpdateDistinguishesMissingFromInvalid {

        /** Creates the group. */
        UpdateDistinguishesMissingFromInvalid() {
            super();
        }

        @Test
        @DisplayName("reports a required value left blank as missing")
        void reportsARequiredValueLeftBlankAsMissing() throws Exception {
            // The blank arm is the one the macro additionally marked, and the remedy to offer the
            // operator is "supply a value".
            final JsonNode settled = reSubmittedWith("firstName", "");

            assertThat(stateOf(settled, "firstName"))
                    .as("a field left blank is the missing state")
                    .isEqualTo(ErrorResponse.FieldState.MISSING.name());
            assertThat(screenFieldIdOf(settled, "firstName"))
                    .as("and the finding names the map item the macro substituted")
                    .isEqualTo("ACSFNAM");
        }

        @Test
        @DisplayName("reports a supplied value that fails its edit as invalid")
        void reportsASuppliedValueThatFailsAsInvalid() throws Exception {
            // The not-OK arm left the operator's own keystrokes on the screen, and the remedy is
            // "correct the value".
            final JsonNode settled = reSubmittedWith("firstName", "Mary1");

            assertThat(stateOf(settled, "firstName"))
                    .as("a field filled in wrongly is the invalid state")
                    .isEqualTo(ErrorResponse.FieldState.INVALID.name());
            assertThat(textOf(settled, ERROR_MESSAGE))
                    .as("and the character-class edit is genuinely applied, so this is not a vacuous "
                            + "assertion about a field nothing checks")
                    .isEqualTo(LABEL_FIRST_NAME + SUFFIX_ALPHABETS_ONLY);
        }

        @Test
        @DisplayName("exposes exactly two states, with no third for a field that has no error")
        void exposesExactlyTwoStates() throws Exception {
            // A field with no error simply has no entry, so an OK or unknown constant would be
            // unreachable state that every client would have to handle for no reason.
            assertThat(ErrorResponse.FieldState.values())
                    .as("the published state vocabulary is exactly the two operator mistakes the legacy "
                            + "screen told apart")
                    .containsExactly(ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID);
            assertThat(FieldErrorDecorator.FlagState.values())
                    .as("and the two legacy validation-flag states it is derived from")
                    .containsExactly(FieldErrorDecorator.FlagState.BLANK,
                            FieldErrorDecorator.FlagState.NOT_OK);
            assertThat(FieldErrorDecorator.none()
                    .mark("blankField", "ACSFNAM", FieldErrorDecorator.FlagState.BLANK)
                    .mark("wrongField", "ACSLNAM", FieldErrorDecorator.FlagState.NOT_OK)
                    .fieldErrors())
                    .as("the blank flag becomes the missing state and the not-OK flag the invalid one, "
                            + "in the order the expansions ran")
                    .containsExactly(
                            new ErrorResponse.FieldError("blankField", "ACSFNAM",
                                    ErrorResponse.FieldState.MISSING),
                            new ErrorResponse.FieldError("wrongField", "ACSLNAM",
                                    ErrorResponse.FieldState.INVALID));
        }

        @Test
        @DisplayName("reports one summary alongside as many independent findings as the cascade raised")
        void reportsOneSummaryAlongsideIndependentFindings() throws Exception {
            // The program writes its error message once in four thousand two hundred and thirty-six
            // lines, so one text accompanies however many field flags the cascade set. Every edit sets
            // its own flag; only the first to fail claims the slot.
            final JsonNode settled = reSubmittedWith("firstName", "");
            final JsonNode presented = fetchedScreen();
            final JsonNode several = submitted(withBlankNames(cleanEcho(presented)),
                    textOf(presented, CONCURRENCY_TOKEN), RE_ENTRY);

            assertThat(textOf(settled, ERROR_MESSAGE))
                    .as("one failing field yields one summary")
                    .isNotNull();
            assertThat(flaggedFields(several))
                    .as("three failing fields still yield one summary and three independent findings, "
                            + "in the order the cascade reported them")
                    .containsExactly("firstName", "lastName", "city");
            assertThat(textOf(several, ERROR_MESSAGE))
                    .as("and the slot carries the FIRST failing edit's text, which is why the cascade "
                            + "order is contractual")
                    .isEqualTo(LABEL_FIRST_NAME + " must be supplied.");
        }

        @Test
        @DisplayName("never publishes a colour, an attribute token or the marker byte itself")
        void neverPublishesARenderingDevice() throws Exception {
            // The macro's two devices - a colour attribute written over the field and a marker character
            // written into it - are terminal rendering mechanisms with no analogue here. The contract
            // exposes the two STATES and discards both mechanisms entirely.
            //
            // The whole attribute family shares one three-letter prefix, so refusing the prefix refuses
            // every member of it at once - the two colours the macro used, the highlight it did not, and
            // any other that a later change might reach for. That is strictly stronger than naming the
            // three constants, and it keeps the terminal vocabulary out of this file as well as out of
            // the payload.
            final JsonNode presented = fetchedScreen();
            final Map<String, Object> screen = cleanEcho(presented);
            screen.put("firstName", "");
            final MvcResult result = updateTurn(screen, textOf(presented, CONCURRENCY_TOKEN), RE_ENTRY,
                    KeyAction.ENTER, administrativeSession());

            assertThat(rawBodyOf(result))
                    .as("no attribute constant of the terminal layer reaches the payload, the family "
                            + "prefix being absent altogether")
                    .doesNotContain(TERMINAL_ATTRIBUTE_FAMILY_PREFIX);
            assertThat(findingFor(bodyOf(result), "firstName").toString())
                    .as("and the marker character the macro wrote over a blank value is expressed as a "
                            + "state rather than transmitted as a byte")
                    .doesNotContain("\"*\"");
            assertThat(stateOf(bodyOf(result), "firstName"))
                    .as("the state IS the mechanism's replacement, so it must be the thing that is "
                            + "present")
                    .isEqualTo(ErrorResponse.FieldState.MISSING.name());
        }

        @Test
        @DisplayName("keeps the thirty-nine decoration sites in source order, the two transposed "
                + "comments resolved by the substitution token")
        void keepsTheThirtyNineDecorationSitesInSourceOrder() throws Exception {
            final List<String> declared = Arrays
                    .stream(AccountUpdateService.ScreenField.values())
                    .map(AccountUpdateService.ScreenField::getBmsFieldId)
                    .toList();

            assertThat(declared)
                    .as("thirty-nine expansions, in the order they appear between lines 3208 and 3432")
                    .containsExactlyElementsOf(DECORATION_SITES_IN_SOURCE_ORDER)
                    .hasSize(DECORATION_SITES_IN_SOURCE_ORDER.size());
            assertThat(AccountUpdateService.ScreenField.PRI_CARDHOLDER.getBmsFieldId())
                    .as("the comment at line 3426 names the other field; the substitution token governs, "
                            + "so the primary-cardholder indicator maps here")
                    .isEqualTo("ACSPFLG");
            assertThat(AccountUpdateService.ScreenField.EFT_ACCOUNT_ID.getBmsFieldId())
                    .as("and the transfer-account identifier here, the comment at line 3431 being the "
                            + "transposed twin of the one above")
                    .isEqualTo("ACSEFTC");
            assertThat(declared.indexOf("ACSSTTE"))
                    .as("the state sits BETWEEN the two address lines, which looks wrong and is the "
                            + "order the expansions run in")
                    .isBetween(declared.indexOf("ACSADL1"), declared.indexOf("ACSADL2"));
            assertThat(declared.indexOf("ACSZIPC"))
                    .as("and the postal code precedes both the city and the country")
                    .isLessThan(declared.indexOf("ACSCITY"))
                    .isLessThan(declared.indexOf("ACSCTRY"));
        }

        /**
         * Blanks the three required alphabetic components, so one submission raises three findings.
         *
         * @param  screen the screen to alter
         * @return the same map, altered
         */
        private Map<String, Object> withBlankNames(final Map<String, Object> screen) {
            screen.put("firstName", "");
            screen.put("lastName", "");
            screen.put("city", "");
            return screen;
        }
    }

    // ===============================================================================================
    // GROUP 5 - THE FOUR EDITABLE FIELDS THAT CARRY NO DECORATION SITE
    // ===============================================================================================

    /**
     * The mapset declares forty-three unprotected fields and the macro is expanded thirty-nine times, so
     * exactly four are editable and never decorated. None may ever appear in a finding.
     */
    @Nested
    @DisplayName("Update - the four undecorated editable fields")
    class UpdateNeverDecoratesTheFourEditableFields {

        /** Creates the group. */
        UpdateNeverDecoratesTheFourEditableFields() {
            super();
        }

        @Test
        @DisplayName("never reports the account key, the group item, the customer key or the "
                + "government-issued identifier")
        void neverReportsTheFourUndecoratedFields() throws Exception {
            // Every one of the four is given a value its neighbours would be refused for, and one
            // genuinely decorated field is broken alongside them so the turn certainly does report
            // something. None of the four may appear in what it reports.
            final JsonNode presented = fetchedScreen();
            final Map<String, Object> screen = cleanEcho(presented);
            screen.put("accountGroupId", "@@@@@@@@@@");
            screen.put("governmentIssuedId", "!!!!!!!!!!!!!!!!!!!!");
            screen.put("firstName", "");

            final JsonNode settled = submitted(screen, textOf(presented, CONCURRENCY_TOKEN), RE_ENTRY);

            assertThat(flaggedFields(settled))
                    .as("the turn does report the one decorated field that failed")
                    .contains("firstName");
            assertThat(flaggedFields(settled))
                    .as("and none of the four editable-but-undecorated fields, whatever was typed into "
                            + "them: inventing a finding for one would exceed the thirty-nine sites the "
                            + "macro declares")
                    .doesNotContainAnyElementsOf(EDITABLE_BUT_NEVER_DECORATED);
        }

        @Test
        @DisplayName("declares no decoration site for any of the four")
        void declaresNoDecorationSiteForAnyOfTheFour() {
            final List<String> decorated = Arrays
                    .stream(AccountUpdateService.ScreenField.values())
                    .map(AccountUpdateService.ScreenField::getFieldName)
                    .toList();

            assertThat(decorated)
                    .as("the four are absent from the thirty-nine sites by construction, not by chance")
                    .doesNotContainAnyElementsOf(EDITABLE_BUT_NEVER_DECORATED);
        }
    }

    // ===============================================================================================
    // GROUP 6 - THE TWO FIELDS THE SOURCE STATES IN PLACE THAT IT DOES NOT EDIT
    // ===============================================================================================

    /**
     * The middle name and the second address line are decorated for display and never validated. Adding
     * a constraint to either - even a width constraint - would reject input the legacy system accepts,
     * so this is a hard prohibition rather than a preference.
     */
    @Nested
    @DisplayName("Update - middle name and address line 2 unrestricted")
    class UpdateLeavesTwoFieldsUnrestricted {

        /** Creates the group. */
        UpdateLeavesTwoFieldsUnrestricted() {
            super();
        }

        @Test
        @DisplayName("accepts any middle name at all, and never reports one")
        void acceptsAnyMiddleName() throws Exception {
            // Empty, far longer than any neighbour tolerates, digits, punctuation and mixed case. The
            // one edit the driver reaches for this field is the optional alphabetic stage, and the
            // source's own comment at line 3345 records that no edit is coded, so nothing runs at all.
            for (final String value : List.of("", "x".repeat(120), "9042 o'BRiEN-de la Cruz, Jr.")) {
                final JsonNode settled = reSubmittedWith("middleName", value);

                assertThat(flaggedFields(settled))
                        .as("middle name %s must be accepted", value.length())
                        .doesNotContain("middleName");
                assertThat(textOf(settled, INFO_MESSAGE))
                        .as("and the turn must reach the confirmation prompt, which it cannot do with "
                                + "any edit outstanding")
                        .isEqualTo(UPDATE_PROMPT_FOR_CONFIRMATION);
            }
        }

        @Test
        @DisplayName("accepts any second address line at all, and never reports one")
        void acceptsAnySecondAddressLine() throws Exception {
            // The flag this field's decoration consumes is never assigned anywhere in the program and
            // the statement that would set its error label is commented out, so the decoration can never
            // fire and the field accepts any value.
            for (final String value : List.of("", "y".repeat(120), "#7-B / Apt 12\u00bd (rear)")) {
                final JsonNode settled = reSubmittedWith("addressLine2", value);

                assertThat(flaggedFields(settled))
                        .as("second address line %s must be accepted", value.length())
                        .doesNotContain("addressLine2");
                assertThat(textOf(settled, INFO_MESSAGE))
                        .as("and the turn must reach the confirmation prompt")
                        .isEqualTo(UPDATE_PROMPT_FOR_CONFIRMATION);
            }
        }

        @Test
        @DisplayName("declares both as decorated yet never validated")
        void declaresBothAsDecoratedYetNeverValidated() {
            final List<AccountUpdateService.ScreenField> unedited = Arrays
                    .stream(AccountUpdateService.ScreenField.values())
                    .filter(AccountUpdateService.ScreenField::neverValidated)
                    .toList();

            assertThat(unedited.stream().map(AccountUpdateService.ScreenField::getFieldName).toList())
                    .as("exactly these two, and the declaration is what a maintainer has to read before "
                            + "attaching a constraint")
                    .containsExactlyElementsOf(DECORATED_BUT_NEVER_VALIDATED);
        }
    }

    // ===============================================================================================
    // GROUP 7 - THE INCLUSIVE CREDIT-SCORE BOUND
    // ===============================================================================================

    /**
     * The credit-score range is declared over a three-digit numeric redefinition and both of its ends are
     * inclusive, so the four values either side of it are the whole of the contract: 300 and 850 pass,
     * 299 and 851 do not.
     *
     * <p>The bound is <strong>online input validation only</strong>. Group 2 proves the same figure is
     * neither range-checked on display nor constrained in the schema, and it cannot be: twenty of the
     * delivered customer rows score below the lower bound. A declarative annotation or a check constraint
     * would refuse the module's own delivered data.
     */
    @Nested
    @DisplayName("Update - FICO boundaries")
    class UpdateEnforcesTheCreditScoreBoundInclusively {

        /** Creates the group. */
        UpdateEnforcesTheCreditScoreBoundInclusively() {
            super();
        }

        @Test
        @DisplayName("accepts the lower bound itself")
        void acceptsTheLowerBoundItself() throws Exception {
            // Source lines 848 to 849 declare the range over the numeric redefinition, and the edit at
            // 2514 to 2530 tests it with two inclusive comparisons rather than two strict ones.
            final JsonNode settled = reSubmittedWith("ficoScore", CREDIT_SCORE_LOWER_BOUND);

            assertThat(flaggedFields(settled))
                    .as("three hundred is inside the range, not on the wrong side of it")
                    .doesNotContain("ficoScore");
            assertThat(textOf(settled, INFO_MESSAGE))
                    .as("and with nothing else broken the turn reaches the confirmation prompt")
                    .isEqualTo(UPDATE_PROMPT_FOR_CONFIRMATION);
        }

        @Test
        @DisplayName("accepts the upper bound itself")
        void acceptsTheUpperBoundItself() throws Exception {
            final JsonNode settled = reSubmittedWith("ficoScore", CREDIT_SCORE_UPPER_BOUND);

            assertThat(flaggedFields(settled))
                    .as("eight hundred and fifty is inside the range")
                    .doesNotContain("ficoScore");
            assertThat(textOf(settled, INFO_MESSAGE))
                    .isEqualTo(UPDATE_PROMPT_FOR_CONFIRMATION);
        }

        @Test
        @DisplayName("refuses one below the lower bound, with the suffix that carries no full stop")
        void refusesOneBelowTheLowerBound() throws Exception {
            final JsonNode settled = reSubmittedWith("ficoScore", CREDIT_SCORE_BELOW_BOUND);

            assertThat(stateOf(settled, "ficoScore"))
                    .as("a value present and out of range is invalid, never missing")
                    .isEqualTo(ErrorResponse.FieldState.INVALID.name());
            assertThat(textOf(settled, ERROR_MESSAGE))
                    .as("the message is the trimmed label followed by the range suffix, and the suffix "
                            + "ends without a full stop where its neighbours carry one")
                    .isEqualTo(LABEL_FICO_SCORE + SUFFIX_FICO_OUT_OF_RANGE);
            assertThat(SUFFIX_FICO_OUT_OF_RANGE)
                    .as("byte-exact, thirty-one characters, no trailing full stop")
                    .isEqualTo(AccountUpdateResponse.SUFFIX_FICO_OUT_OF_RANGE)
                    .hasSize(31)
                    .doesNotEndWith(".");
        }

        @Test
        @DisplayName("refuses one above the upper bound, with the same message")
        void refusesOneAboveTheUpperBound() throws Exception {
            final JsonNode settled = reSubmittedWith("ficoScore", CREDIT_SCORE_ABOVE_BOUND);

            assertThat(stateOf(settled, "ficoScore"))
                    .isEqualTo(ErrorResponse.FieldState.INVALID.name());
            assertThat(textOf(settled, ERROR_MESSAGE))
                    .as("one text serves both ends of the range, which is what the operator saw")
                    .isEqualTo(LABEL_FICO_SCORE + SUFFIX_FICO_OUT_OF_RANGE);
            assertThat(screenFieldIdOf(settled, "ficoScore"))
                    .as("and the finding carries the screen field the twenty-second expansion names")
                    .isEqualTo("ACSTFCO");
        }

        @Test
        @DisplayName("does not range-check a score that already failed its character class, so no field "
                + "earns two messages")
        void doesNotRangeCheckAScoreThatAlreadyFailed() throws Exception {
            // Lines 1553 to 1554 gate the range edit on the field's own flag still being valid. A
            // non-numeric score therefore earns the character-class message and stops; reporting the
            // range as well would tell the operator two things about one keystroke.
            final JsonNode settled = reSubmittedWith("ficoScore", "7X0");

            assertThat(textOf(settled, ERROR_MESSAGE))
                    .as("the character-class message, and not the range one")
                    .isEqualTo(LABEL_FICO_SCORE + SUFFIX_MUST_BE_ALL_NUMERIC);
            assertThat(textOf(settled, ERROR_MESSAGE))
                    .doesNotContain(SUFFIX_FICO_OUT_OF_RANGE);
            assertThat(flaggedFields(settled))
                    .as("and the field is reported exactly once")
                    .containsOnlyOnce("ficoScore");
        }
    }

    // ===============================================================================================
    // GROUP 8 - THE CHARACTER-CLASS EDIT ACCEPTS AN EMBEDDED SPACE
    // ===============================================================================================

    /**
     * The alphabetic edit is the estate's blank-and-trim idiom: every letter of the fifty-two-character
     * table is converted to a space and the remainder is trimmed and tested for emptiness. A space was
     * already a space, so it is trimmed away exactly as a blanked letter is and a value containing one
     * <strong>passes</strong>.
     *
     * <p>A per-character letter test would refuse an ordinary given name and a delivered fixture value
     * alike, so it is prohibited outright rather than merely discouraged. The failing direction is still
     * asserted, because an edit that accepts everything is not the same as one that accepts a space.
     */
    @Nested
    @DisplayName("Update - alphabetic accepts embedded spaces")
    class UpdateAlphabeticEditAcceptsEmbeddedSpaces {

        /** Creates the group. */
        UpdateAlphabeticEditAcceptsEmbeddedSpaces() {
            super();
        }

        @Test
        @DisplayName("accepts a two-part given name, the space surviving the blank-and-trim idiom")
        void acceptsATwoPartGivenName() throws Exception {
            // The edit is at source line 1898. Its comment at 2078 claims one table and the code at
            // 2079 to 2082 uses another; the code governs, and neither reading refuses a space.
            for (final String name : List.of("MARY ANN", "Aniya Von", "de la Cruz")) {
                final JsonNode settled = reSubmittedWith("firstName", name);

                assertThat(flaggedFields(settled))
                        .as("%s must be accepted: the idiom blanks letters and trims, so an embedded "
                                + "space is indistinguishable from a blanked letter", name)
                        .doesNotContain("firstName");
                assertThat(textOf(settled, INFO_MESSAGE))
                        .as("and the turn reaches the confirmation prompt with %s keyed", name)
                        .isEqualTo(UPDATE_PROMPT_FOR_CONFIRMATION);
            }
        }

        @Test
        @DisplayName("accepts an embedded space in the family name and in the city item too")
        void acceptsAnEmbeddedSpaceInTheOtherAlphabeticItems() throws Exception {
            // The same edit serves the family name at line 1580 and the city item at 1616, the latter
            // being backed by the third address line rather than by a city column.
            final JsonNode presented = fetchedScreen();
            final Map<String, Object> screen = cleanEcho(presented);
            screen.put("lastName", "Van Der Berg");
            screen.put("city", "New York");

            final JsonNode settled = submitted(screen, textOf(presented, CONCURRENCY_TOKEN), RE_ENTRY);

            assertThat(flaggedFields(settled))
                    .as("one predicate serves every alphabetic item, so all of them accept a space")
                    .doesNotContain("lastName", "city");
            assertThat(textOf(settled, INFO_MESSAGE))
                    .isEqualTo(UPDATE_PROMPT_FOR_CONFIRMATION);
        }

        @Test
        @DisplayName("refuses a digit, so the edit is real rather than vacuous")
        void refusesADigit() throws Exception {
            final JsonNode settled = reSubmittedWith("firstName", "Mary1");

            assertThat(stateOf(settled, "firstName"))
                    .as("present and outside the class, so invalid rather than missing")
                    .isEqualTo(ErrorResponse.FieldState.INVALID.name());
            assertThat(textOf(settled, ERROR_MESSAGE))
                    .as("the trimmed label followed by the class suffix")
                    .isEqualTo(LABEL_FIRST_NAME + SUFFIX_ALPHABETS_ONLY);
        }

        @Test
        @DisplayName("treats an all-space value as absent, the blank arm being tested before the class")
        void treatsAnAllSpaceValueAsAbsent() throws Exception {
            // The arms are ordered: blank first, class second. A field holding only spaces is what the
            // terminal transmitted for a field the operator cleared, and it is a missing value rather
            // than an ill-formed one - which is the whole distinction the two-state contract draws.
            final JsonNode settled = reSubmittedWith("lastName", "   ");

            assertThat(stateOf(settled, "lastName"))
                    .as("spaces only is the missing state, never the invalid one")
                    .isEqualTo(ErrorResponse.FieldState.MISSING.name());
            assertThat(textOf(settled, ERROR_MESSAGE))
                    .as("and the blank arm's own suffix, which carries a full stop")
                    .isEqualTo(LABEL_LAST_NAME + SUFFIX_MUST_BE_SUPPLIED);
        }

        @Test
        @DisplayName("keeps the city item's own label, the screen item and its backing column differing")
        void keepsTheCityItemsOwnLabel() throws Exception {
            final JsonNode settled = reSubmittedWith("city", "Roswell7");

            assertThat(textOf(settled, ERROR_MESSAGE))
                    .as("the operator is told about the item on the screen, not about the column it is "
                            + "read from")
                    .isEqualTo(LABEL_CITY + SUFFIX_ALPHABETS_ONLY);
            assertThat(screenFieldIdOf(settled, "city"))
                    .isEqualTo("ACSCITY");
        }
    }

    // ===============================================================================================
    // GROUP 9 - ONE MISMATCH, TWO FLAGS
    // ===============================================================================================

    /**
     * The single cross-field edit tests a four-character composite built by positional concatenation of
     * the two-character state and the first two characters of the postal code, with no trimming anywhere,
     * and on failure it flags <strong>both</strong> contributing fields with one shared message.
     *
     * <p>The message is the one message in the program composed with no field-name prefix at all, and the
     * two tables it and the state edit consult are <strong>never intersected</strong>: six of the prefixes
     * the composite table uses do not appear among the fifty-six state codes.
     */
    @Nested
    @DisplayName("Update - state/ZIP dual flag")
    class UpdateFlagsBothStateAndPostalCode {

        /** Creates the group. */
        UpdateFlagsBothStateAndPostalCode() {
            super();
        }

        @Test
        @DisplayName("flags the state and the postal code together for one mismatch, with one shared "
                + "message")
        void flagsBothFieldsForOneMismatch() throws Exception {
            // Source lines 2536 to 2557. The reserved row is keyed so that its own combination is in the
            // table; only the postal code is moved, so the state is unimpeachable on its own and is
            // still flagged.
            final JsonNode settled = reSubmittedWith("zipCode", MISMATCHED_ZIP_CODE);

            assertThat(flaggedFields(settled))
                    .as("one mismatch, two findings: neither field is wrong by itself and the edit "
                            + "cannot say which the operator meant")
                    .contains("stateCode", "zipCode");
            assertThat(stateOf(settled, "stateCode"))
                    .as("both are present values that failed, so both are invalid")
                    .isEqualTo(ErrorResponse.FieldState.INVALID.name());
            assertThat(stateOf(settled, "zipCode"))
                    .isEqualTo(ErrorResponse.FieldState.INVALID.name());
            assertThat(textOf(settled, ERROR_MESSAGE))
                    .as("the one message in the program composed with NO field-name prefix, so it must "
                            + "not acquire one")
                    .isEqualTo(MSG_INVALID_ZIP_FOR_STATE)
                    .doesNotStartWith(LABEL_STATE)
                    .doesNotStartWith(LABEL_ZIP);
            assertThat(MSG_INVALID_ZIP_FOR_STATE)
                    .isEqualTo(AccountUpdateResponse.MSG_INVALID_ZIP_FOR_STATE);
        }

        @Test
        @DisplayName("flags the state alone for an unknown code, the cross-field edit never running")
        void flagsTheStateAloneForAnUnknownCode() throws Exception {
            // Lines 1664 to 1669 gate the cross-field edit on both inputs already being valid, so an
            // unknown state suppresses it. Flagging the postal code here would report a field the
            // operator keyed correctly.
            final JsonNode settled = reSubmittedWith("stateCode", UNKNOWN_STATE_CODE);

            assertThat(flaggedFields(settled))
                    .as("the state alone")
                    .contains("stateCode")
                    .doesNotContain("zipCode");
            assertThat(textOf(settled, ERROR_MESSAGE))
                    .as("the membership suffix, which ends without a full stop")
                    .isEqualTo(LABEL_STATE + SUFFIX_STATE_NOT_VALID);
            assertThat(SUFFIX_STATE_NOT_VALID)
                    .isEqualTo(AccountUpdateResponse.SUFFIX_STATE_NOT_VALID)
                    .doesNotEndWith(".");
        }

        @Test
        @DisplayName("tests the state by flat membership, with no trim, no character count and no blank "
                + "pre-check of its own")
        void testsTheStateByFlatMembership() throws Exception {
            // Line 2494 is a bare move into the lookup field. A two-character code that is simply not in
            // the table fails, and it fails for that reason alone.
            assertThat(AccountControllerIT.this.lookups.isValidUsStateCode(UNKNOWN_STATE_CODE))
                    .as("the code the unknown-state assertion above relies on really is absent")
                    .isFalse();
            assertThat(AccountControllerIT.this.lookups.isValidUsStateCode(OWNED_STATE_CODE))
                    .as("and the reserved row's own code really is present")
                    .isTrue();
            assertThat(AccountControllerIT.this.lookups.usStateCodes())
                    .as("fifty-six codes, the fifty states plus the district and the territories")
                    .hasSize(US_STATE_CODES);
            assertThat(ValidationLookupService.US_STATE_CODE_COUNT)
                    .as("and the count is declared alongside the table it describes")
                    .isEqualTo(US_STATE_CODES);
        }

        @Test
        @DisplayName("builds the composite positionally, so a shorter key is a different key")
        void buildsTheCompositePositionally() throws Exception {
            // The key is the state followed by exactly two characters of the postal code, space-filled
            // when the code is shorter. Trimming either part would collapse distinct keys onto one.
            assertThat(AccountControllerIT.this.lookups
                    .isValidUsStateZipCodeCombination(OWNED_STATE_CODE + OWNED_ZIP_CODE.substring(0, 2)))
                    .as("the reserved row's four-character composite is in the table")
                    .isTrue();
            assertThat(AccountControllerIT.this.lookups
                    .isValidUsStateZipCodeCombination(OWNED_STATE_CODE + MISMATCHED_ZIP_CODE
                            .substring(0, 2)))
                    .as("and the mismatched one is not, which is what the dual-flag turn exercises")
                    .isFalse();
            assertThat(AccountControllerIT.this.lookups
                    .isValidUsStateZipCodeCombination(OWNED_STATE_CODE + OWNED_ZIP_CODE.charAt(0)))
                    .as("a three-character key is not a four-character key: the composite is positional "
                            + "and the table is keyed on the whole width")
                    .isFalse();
            assertThat(ValidationLookupService.US_STATE_AND_FIRST_ZIP2_WIDTH)
                    .as("two characters of state and two of postal code")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("never intersects the two tables, six composite prefixes having no state code at all")
        void neverIntersectsTheTwoTables() {
            // Two overseas military prefixes, one diplomatic and three Pacific. Reconciling the tables
            // would refuse addresses the estate accepts, so the disjointness is asserted rather than
            // repaired.
            final Set<String> stateCodes = AccountControllerIT.this.lookups.usStateCodes();
            final Set<String> combinations = AccountControllerIT.this.lookups
                    .usStateZipCodeCombinations();

            assertThat(combinations)
                    .as("two hundred and forty composites")
                    .hasSize(STATE_ZIP_COMBINATIONS);
            assertThat(ValidationLookupService.US_STATE_ZIP_COMBINATION_COUNT)
                    .isEqualTo(STATE_ZIP_COMBINATIONS);
            for (final String prefix : PREFIXES_ABSENT_FROM_THE_STATE_TABLE) {
                assertThat(combinations.stream().anyMatch(entry -> entry.startsWith(prefix)))
                        .as("%s heads at least one composite", prefix)
                        .isTrue();
                assertThat(stateCodes)
                        .as("yet %s is not a state code, so an intersection would discard its "
                                + "composites", prefix)
                        .doesNotContain(prefix);
            }
        }
    }

    // ===============================================================================================
    // GROUP 10 - THE TELEPHONE CASCADE RUNS ALL THREE STAGES, ALWAYS
    // ===============================================================================================

    /**
     * One of only three genuine multi-paragraph ranges in the estate. Every failure inside it forwards to
     * the <em>next</em> stage rather than to the exit, so all three stages always run and all three flags
     * are always written: a number with three bad parts yields three findings and, because the summary
     * slot is claimed by the first failure, exactly one message.
     *
     * <p>The fourth area-code arm consults the four-hundred-and-ten-entry general-purpose set and never
     * the derived union of it with the eighty easily-recognisable codes, so an easily-recognisable code is
     * refused here.
     */
    @Nested
    @DisplayName("Update - phone three-stage cascade")
    class UpdateTelephoneCascadeRunsEveryStage {

        /** Creates the group. */
        UpdateTelephoneCascadeRunsEveryStage() {
            super();
        }

        @Test
        @DisplayName("evaluates the prefix and the line number even though the area code already failed")
        void evaluatesEveryStageDespiteAnEarlierFailure() throws Exception {
            // The range head is at source line 2225 and its stages at 2246, 2316 and 2370. The four
            // area-code failures at 2259, 2277, 2291 and 2311 all target the prefix stage and the three
            // prefix failures at 2330, 2348 and 2362 all target the line-number stage, so nothing short
            // of the line-number stage can end the range.
            final JsonNode presented = fetchedScreen();
            final Map<String, Object> screen = cleanEcho(presented);
            screen.put("phone1AreaCode", "12");
            screen.put("phone1Prefix", "  ");
            screen.put("phone1LineNumber", "99");

            final JsonNode settled = submitted(screen, textOf(presented, CONCURRENCY_TOKEN), RE_ENTRY);

            assertThat(flaggedFields(settled))
                    .as("three stages ran and three flags were written: a failing stage forwards, it "
                            + "does not abort")
                    .contains("phone1AreaCode", "phone1Prefix", "phone1LineNumber");
            assertThat(stateOf(settled, "phone1AreaCode"))
                    .as("present and two digits wide, so invalid")
                    .isEqualTo(ErrorResponse.FieldState.INVALID.name());
            assertThat(stateOf(settled, "phone1Prefix"))
                    .as("spaces only, so missing - and the two states really do coexist in one turn")
                    .isEqualTo(ErrorResponse.FieldState.MISSING.name());
            assertThat(stateOf(settled, "phone1LineNumber"))
                    .as("present and two digits wide against a four-digit field, so invalid")
                    .isEqualTo(ErrorResponse.FieldState.INVALID.name());
            assertThat(textOf(settled, ERROR_MESSAGE))
                    .as("and exactly one summary, the first failure's, both stages behind it having "
                            + "been suppressed by the first-error-wins gate")
                    .isEqualTo(LABEL_PHONE_NUMBER_1 + SUFFIX_AREA_CODE_NOT_3_DIGITS);
        }

        @Test
        @DisplayName("carries the three stages' own suffixes, the article capitalised as the source "
                + "capitalises it")
        void carriesEachStagesOwnSuffix() throws Exception {
            assertThat(SUFFIX_AREA_CODE_REQUIRED)
                    .isEqualTo(AccountUpdateResponse.SUFFIX_AREA_CODE_REQUIRED);
            assertThat(SUFFIX_AREA_CODE_NOT_3_DIGITS)
                    .as("must be A 3 digit number: the article is capitalised in the legacy text and "
                            + "correcting it would change an operator-visible string")
                    .isEqualTo(AccountUpdateResponse.SUFFIX_AREA_CODE_NOT_3_DIGITS)
                    .contains(" A 3 digit ");
            assertThat(SUFFIX_PREFIX_REQUIRED)
                    .isEqualTo(AccountUpdateResponse.SUFFIX_PREFIX_REQUIRED);
            assertThat(SUFFIX_LINE_NUMBER_NOT_4_DIGITS)
                    .as("four digits here rather than three, and the same capitalisation")
                    .isEqualTo(AccountUpdateResponse.SUFFIX_LINE_NUMBER_NOT_4_DIGITS)
                    .contains(" A 4 digit ");
            assertThat(SUFFIX_LINE_NUMBER_ZERO)
                    .as("and the all-zero suffixes end without a full stop where the absent-value ones "
                            + "carry one")
                    .isEqualTo(AccountUpdateResponse.SUFFIX_LINE_NUMBER_ZERO)
                    .doesNotEndWith(".");

            // The blank prefix arm, observed rather than merely declared.
            final JsonNode settled = reSubmittedWith("phone1Prefix", " ");

            assertThat(textOf(settled, ERROR_MESSAGE))
                    .isEqualTo(LABEL_PHONE_NUMBER_1 + SUFFIX_PREFIX_REQUIRED);
        }

        @Test
        @DisplayName("refuses an easily-recognisable area code, the fourth arm consulting the "
                + "general-purpose set alone")
        void refusesAnEasilyRecognisableAreaCode() throws Exception {
            // Lines 2296 to 2298 consult the 410-entry set. The 490 figure is the DERIVED union of that
            // set with the 80 easily-recognisable codes and is stored nowhere, so consulting the union
            // here would admit a code the legacy refused.
            final JsonNode settled = reSubmittedWith("phone1AreaCode", EASILY_RECOGNISABLE_AREA_CODE);

            assertThat(textOf(settled, ERROR_MESSAGE))
                    .as("the longest of the suffixes, and the only arm that consults a table")
                    .isEqualTo(LABEL_PHONE_NUMBER_1 + SUFFIX_AREA_CODE_NOT_GENERAL_PURPOSE);
            assertThat(stateOf(settled, "phone1AreaCode"))
                    .isEqualTo(ErrorResponse.FieldState.INVALID.name());
            assertThat(SUFFIX_AREA_CODE_NOT_GENERAL_PURPOSE)
                    .isEqualTo(AccountUpdateResponse.SUFFIX_AREA_CODE_NOT_GENERAL_PURPOSE)
                    .hasSize(51);
        }

        @Test
        @DisplayName("holds the two sets disjoint and the union derived, never stored")
        void holdsTheTwoSetsDisjointAndTheUnionDerived() {
            final Set<String> generalPurpose = AccountControllerIT.this.lookups
                    .generalPurposeAreaCodes();
            final Set<String> easilyRecognisable = AccountControllerIT.this.lookups
                    .easilyRecognisableAreaCodes();

            assertThat(generalPurpose).hasSize(GENERAL_PURPOSE_AREA_CODES);
            assertThat(easilyRecognisable).hasSize(EASY_RECOGNITION_AREA_CODES);
            assertThat(AccountControllerIT.this.lookups.phoneAreaCodes())
                    .as("four hundred and ninety is the union of the two and is derived from them, so "
                            + "the arithmetic has to hold rather than a third list agreeing with it")
                    .hasSize(ALL_AREA_CODES);
            assertThat(ValidationLookupService.PHONE_AREA_CODE_COUNT)
                    .isEqualTo(ALL_AREA_CODES);
            assertThat(generalPurpose)
                    .as("the two sets are disjoint, which is why the sizes add rather than merge")
                    .doesNotContainAnyElementsOf(easilyRecognisable);
            assertThat(easilyRecognisable)
                    .as("the code the assertion above refuses belongs to the easily-recognisable set")
                    .contains(EASILY_RECOGNISABLE_AREA_CODE);
            assertThat(generalPurpose)
                    .as("and not to the general-purpose one")
                    .doesNotContain(EASILY_RECOGNISABLE_AREA_CODE)
                    .contains(GENERAL_PURPOSE_AREA_CODE);
        }

        @Test
        @DisplayName("★ reproduces the shortcut defect: a blank area code and prefix accept the number "
                + "whatever the line number holds")
        void reproducesTheShortcutDefect() throws Exception {
            // Source lines 2234 to 2239. The shortcut has three condition groups and the third tests the
            // AREA CODE where it plainly meant the line number, so with the first two groups satisfied it
            // fires regardless of the line number and all three flags are set valid. The defect is
            // reproduced rather than corrected: correcting it would refuse a number the legacy accepted.
            final JsonNode presented = fetchedScreen();
            final Map<String, Object> screen = cleanEcho(presented);
            screen.put("phone1AreaCode", " ");
            screen.put("phone1Prefix", " ");
            screen.put("phone1LineNumber", "1234");

            final JsonNode settled = submitted(screen, textOf(presented, CONCURRENCY_TOKEN), RE_ENTRY);

            assertThat(flaggedFields(settled))
                    .as("no finding for any of the three, though a line number was plainly supplied and "
                            + "a corrected shortcut would have demanded the rest of the number")
                    .doesNotContain("phone1AreaCode", "phone1Prefix", "phone1LineNumber");
            assertThat(textOf(settled, INFO_MESSAGE))
                    .as("and the turn is validated, which is the whole point: the defect is observable "
                            + "as an acceptance, not as a different message")
                    .isEqualTo(UPDATE_PROMPT_FOR_CONFIRMATION);
        }

        @Test
        @DisplayName("does not extend the shortcut to a supplied area code, so the cascade still runs")
        void doesNotExtendTheShortcutToASuppliedAreaCode() throws Exception {
            // The boundary of the reproduced defect. With the area code supplied the first condition
            // group fails and the shortcut cannot fire, so the blank prefix is reported as it should be.
            final JsonNode presented = fetchedScreen();
            final Map<String, Object> screen = cleanEcho(presented);
            screen.put("phone1Prefix", " ");
            screen.put("phone1LineNumber", " ");

            final JsonNode settled = submitted(screen, textOf(presented, CONCURRENCY_TOKEN), RE_ENTRY);

            assertThat(flaggedFields(settled))
                    .as("the prefix and the line number are both reported missing, the shortcut being "
                            + "unreachable while the area code stands")
                    .contains("phone1Prefix", "phone1LineNumber");
            assertThat(stateOf(settled, "phone1Prefix"))
                    .isEqualTo(ErrorResponse.FieldState.MISSING.name());
            assertThat(stateOf(settled, "phone1LineNumber"))
                    .isEqualTo(ErrorResponse.FieldState.MISSING.name());
        }
    }

    // ===============================================================================================
    // GROUP 11 - THE MESSAGE TEXTS, INCLUDING THE ONES THAT LOOK LIKE TYPOGRAPHICAL ERRORS
    // ===============================================================================================

    /**
     * Every operator-visible text this transaction can produce is an external contract, and four of them
     * look like defects: the success text carries four dots, the conflict text spells "some one" as two
     * words, the digit-count texts capitalise the article, and one guidance line has no space after its
     * full stop while its sibling has one.
     *
     * <p><strong>The inconsistency between the last pair is itself the contract.</strong> Normalising
     * either toward the other would change a string an operator reads and a downstream matcher may key on,
     * so both are asserted exactly as the source declares them.
     *
     * <p>The message block declares more texts than this program emits, because several belong to the
     * sibling card transactions that share the block's conditions. Those are asserted as published
     * constants; the ones this program does emit are asserted from a real turn as well.
     */
    @Nested
    @DisplayName("Update - message fidelity")
    class UpdateMessageFidelity {

        /** Creates the group. */
        UpdateMessageFidelity() {
            super();
        }

        @Test
        @DisplayName("★ publishes the success text with FOUR dots")
        void publishesTheSuccessTextWithFourDots() {
            // Source line 528. Three dots would be an ellipsis and four is what the programmer typed;
            // the text is what the operator read, so the fourth dot stays.
            assertThat(UPDATE_LOOKS_GOOD_SO_FAR)
                    .isEqualTo(AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR)
                    .contains("....")
                    .doesNotContain(".....")
                    .hasSize(21);
        }

        @Test
        @DisplayName("★ publishes the conflict text with \"some one\" as TWO words")
        void publishesTheConflictTextAsTwoWords() {
            // Source line 522, and it carries no closing full stop after "review".
            assertThat(UPDATE_RECORD_CHANGED)
                    .isEqualTo(AccountUpdateResponse.MSG_RECORD_CHANGED_BEFORE_UPDATE)
                    .contains("some one")
                    .doesNotContain("someone")
                    .doesNotEndWith(".");
        }

        @Test
        @DisplayName("★ keeps one guidance line without a space after its full stop and its sibling with "
                + "one, the inconsistency being the contract")
        void keepsTheSpacingInconsistencyBetweenTheTwoGuidanceLines() {
            assertThat(UPDATE_PROMPT_FOR_CONFIRMATION)
                    .as("no space after the full stop")
                    .isEqualTo("Changes validated.Press F5 to save")
                    .contains(".Press")
                    .doesNotContain(". Press");
            assertThat(UPDATE_INFORM_FAILURE)
                    .as("and a space after the full stop in its sibling, which is why neither may be "
                            + "normalised toward the other")
                    .isEqualTo("Changes unsuccessful. Please try again")
                    .contains(". Please");
        }

        @Test
        @DisplayName("names one and twelve rather than zero-one and twelve in the expiry-month text")
        void namesOneAndTwelveInTheExpiryMonthText() {
            // Source line 510. Every other month value in the estate is two characters wide, so the
            // unpadded bound in the text is deliberate and visible.
            assertThat(UPDATE_EXPIRY_MONTH_NOT_VALID)
                    .isEqualTo(AccountUpdateResponse.MSG_EXPIRY_MONTH_NOT_VALID)
                    .endsWith("1 and 12")
                    .doesNotContain("01 and 12");
        }

        @Test
        @DisplayName("★ keeps the account and customer hold failures as two distinct texts")
        void keepsTheTwoHoldFailuresDistinct() {
            // Source lines 518 and 520. This program distinguishes which record could not be held; the
            // sibling card transaction carries a single generic text. Unifying them would erase the one
            // piece of information the operator needed to know which master to look at.
            assertThat(UPDATE_COULD_NOT_HOLD_ACCOUNT)
                    .isEqualTo(AccountUpdateResponse.MSG_COULD_NOT_HOLD_ACCOUNT_FOR_UPDATE)
                    .contains("account record");
            assertThat(UPDATE_COULD_NOT_HOLD_CUSTOMER)
                    .isEqualTo(AccountUpdateResponse.MSG_COULD_NOT_HOLD_CUSTOMER_FOR_UPDATE)
                    .contains("customer record");
            assertThat(UPDATE_COULD_NOT_HOLD_ACCOUNT)
                    .as("two texts, never one")
                    .isNotEqualTo(UPDATE_COULD_NOT_HOLD_CUSTOMER);
        }

        @Test
        @DisplayName("publishes the whole-field texts the message block declares, byte for byte")
        void publishesTheWholeFieldTextsByteForByte() {
            // Source lines 496 to 526. Several of these serve conditions this program shares with the
            // card transactions rather than raising them itself, so they are asserted where they are
            // declared. None carries a trailing full stop.
            assertThat(AccountUpdateResponse.MSG_ACCOUNT_NUMBER_NOT_USABLE)
                    .as("one text serves both an all-zero key and a non-numeric one, exactly as the "
                            + "screen said the same thing either way")
                    .isEqualTo("Account number must be a non zero 11 digit number");
            assertThat(UPDATE_STATUS_MUST_BE_Y_OR_N)
                    .as("the whole-field status text says Active where the composed one does not")
                    .isEqualTo(AccountUpdateResponse.MSG_ACCOUNT_STATUS_MUST_BE_YES_NO)
                    .isNotEqualTo(LABEL_ACCOUNT_STATUS + SUFFIX_MUST_BE_Y_OR_N);
            assertThat(UPDATE_CREDIT_LIMIT_REQUIRED)
                    .isEqualTo(AccountUpdateResponse.MSG_CREDIT_LIMIT_REQUIRED)
                    .doesNotEndWith(".");
            assertThat(UPDATE_CREDIT_LIMIT_NOT_VALID)
                    .as("and this one coincides with the composed form, the generic suffix having no "
                            + "full stop either")
                    .isEqualTo(AccountUpdateResponse.MSG_CREDIT_LIMIT_NOT_VALID)
                    .isEqualTo(LABEL_CREDIT_LIMIT + SUFFIX_IS_NOT_VALID);
            assertThat(AccountUpdateResponse.MSG_EXPIRY_YEAR_NOT_VALID)
                    .isEqualTo("Invalid card expiry year");
            assertThat(AccountUpdateResponse.MSG_ACCOUNT_NOT_IN_CARD_DATABASE)
                    .isEqualTo("Did not find this account in cards database");
            assertThat(AccountUpdateResponse.MSG_NO_CARDS_FOR_SEARCH_CONDITION)
                    .isEqualTo("Did not find cards for this search condition");
            assertThat(AccountUpdateResponse.MSG_CARD_DATA_READ_ERROR)
                    .isEqualTo("Error reading Card Data File");
            assertThat(UPDATE_OF_RECORD_FAILED)
                    .as("the rewrite failure, which serves both of the asymmetric arms")
                    .isEqualTo(AccountUpdateResponse.MSG_UPDATE_OF_RECORD_FAILED);
        }

        @Test
        @DisplayName("leaves the no-change message on a faithful echo, and runs no edit behind it")
        void leavesTheNoChangeMessageOnAFaithfulEcho() throws Exception {
            // The comparison short-circuits the cascade: with nothing altered the transaction records
            // that fact and returns before the first edit runs. Every other assertion in this file
            // therefore has to alter something, and this is the turn that proves why.
            final JsonNode presented = fetchedScreen();

            final JsonNode settled = submitted(echo(presented), textOf(presented, CONCURRENCY_TOKEN),
                    RE_ENTRY);

            assertThat(textOf(settled, ERROR_MESSAGE))
                    .as("the text carries a trailing full stop where most of its neighbours do not")
                    .isEqualTo(UPDATE_NO_CHANGE_DETECTED)
                    .endsWith(".");
            assertThat(flaggedFields(settled))
                    .as("and no field is reported, the cascade never having run: an echo of a screen "
                            + "the transaction itself composed cannot be ill-formed")
                    .isEmpty();
            assertThat(textOf(settled, INFO_MESSAGE))
                    .as("the details stay on the screen for the operator to change something")
                    .isEqualTo(UPDATE_PROMPT_FOR_CHANGES);
        }

        @Test
        @DisplayName("shows the search prompt on a turn that carries neither a key nor a before-image "
                + "proof")
        void showsTheSearchPromptWithNothingToWorkFrom() throws Exception {
            final JsonNode settled = bodyOf(updateTurn(Map.of(), null, FIRST_ENTRY, KeyAction.ENTER,
                    administrativeSession()));

            assertThat(textOf(settled, INFO_MESSAGE))
                    .as("the guidance line of the turn that asks for a key, which names update rather "
                            + "than display and so is not the view screen's prompt")
                    .isEqualTo(UPDATE_PROMPT_FOR_SEARCH_KEYS)
                    .isNotEqualTo(VIEW_PROMPT_FOR_INPUT);
        }

        @Test
        @DisplayName("composes a field message as the trimmed label followed by its suffix, never the "
                + "other way about")
        void composesAFieldMessageAsLabelThenSuffix() throws Exception {
            // Every suffix begins where the label ends, so a message is readable in one direction only.
            // Two are checked from real turns because the composition is what produces them.
            final JsonNode statusTurn = reSubmittedWith("accountStatus", "Q");

            assertThat(textOf(statusTurn, ERROR_MESSAGE))
                    .isEqualTo(LABEL_ACCOUNT_STATUS + SUFFIX_MUST_BE_Y_OR_N)
                    .startsWith(LABEL_ACCOUNT_STATUS)
                    .endsWith(SUFFIX_MUST_BE_Y_OR_N);

            final JsonNode amountTurn = reSubmittedWith("creditLimit", "12.3X");

            assertThat(textOf(amountTurn, ERROR_MESSAGE))
                    .as("and a malformed amount earns the not-valid suffix, which ends without a full "
                            + "stop unlike the absent-value one")
                    .isEqualTo(LABEL_CREDIT_LIMIT + SUFFIX_IS_NOT_VALID)
                    .doesNotEndWith(".");
        }
    }

    // ===============================================================================================
    // GROUP 12 - THE BEFORE-IMAGE PROOF, AND THE ASYMMETRIC ROLLBACK BEHIND IT
    // ===============================================================================================

    /**
     * The legacy held its records under a locking update model and compared a before image with an after
     * image; nothing was journalled, nothing was recoverable and reads were uncommitted. A version column
     * under read-committed isolation is a <strong>strict improvement</strong> on that baseline, and it is
     * recorded here as an improvement so that a reviewer does not mistake the stronger guarantee for a
     * behavioural regression.
     *
     * <p><strong>The rollback is asymmetric and stays asymmetric.</strong> The estate's only rollback sits
     * on the customer-rewrite failure arm at source lines 4095 to 4103, with the rollback itself at 4099
     * to 4101; the account-rewrite failure arm at 4076 to 4081 issues none. Both arms surface the same
     * rewrite-failure text, so the asymmetry is invisible on the wire and is recorded rather than asserted
     * through a screen. What is asserted through a screen is the conflict arm, which is reachable, and the
     * fact that a refused save leaves the stored record exactly as it stood.
     */
    @Nested
    @DisplayName("Update - optimistic lock and rollback asymmetry")
    class UpdateOptimisticLockAndRollbackAsymmetry {

        /** Creates the group. */
        UpdateOptimisticLockAndRollbackAsymmetry() {
            super();
        }

        @Test
        @DisplayName("commits a confirmed change and says so, the stored record moving with it")
        void commitsAConfirmedChange() throws Exception {
            // The confirmation key is what turns a validated screen into a write; source lines 2602 to
            // 2615 test the state and the key together, and that conjoined clause has to be evaluated
            // before the state is tested alone or a save could never happen.
            final JsonNode presented = fetchedScreen();
            final Map<String, Object> screen = cleanEcho(presented);
            screen.put("city", "Newer Altenwerth");

            final JsonNode settled = bodyOf(updateTurn(screen, textOf(presented, CONCURRENCY_TOKEN),
                    RE_ENTRY, KeyAction.PFK05, administrativeSession()));

            assertThat(textOf(settled, INFO_MESSAGE))
                    .as("the committed guidance line")
                    .isEqualTo(UPDATE_CONFIRM_SUCCESS);
            assertThat(textOf(settled, ERROR_MESSAGE))
                    .as("and nothing on the error line, the write having succeeded")
                    .isNull();
            assertThat(AccountControllerIT.this.customers.findById(OWNED_CUSTOMER_ID)
                    .orElseThrow().getAddrLine3())
                    .as("the stored row moved, so the acknowledgement is not merely a message: the city "
                            + "item is backed by the third address line")
                    .isEqualTo("Newer Altenwerth");
        }

        @Test
        @DisplayName("★ refuses a save whose before-image proof no longer matches, with \"some one\" as "
                + "two words")
        void refusesASaveAgainstAStaleBeforeImage() throws Exception {
            // The proof is minted from a digest of both records as they stood when the screen was built
            // and is verified against the records as they stand when the write begins. Moving the stored
            // balance between the two is exactly the interleaving the legacy image comparison existed to
            // catch.
            final JsonNode presented = fetchedScreen();
            final Map<String, Object> screen = cleanEcho(presented);
            screen.put("city", "Contended City");

            final Account interfering = AccountControllerIT.this.accounts.findById(OWNED_ACCOUNT_ID)
                    .orElseThrow();
            interfering.setAcctCurrBal(new BigDecimal("111.00"));
            AccountControllerIT.this.accounts.save(interfering);

            final JsonNode settled = bodyOf(updateTurn(screen, textOf(presented, CONCURRENCY_TOKEN),
                    RE_ENTRY, KeyAction.PFK05, administrativeSession()));

            assertThat(textOf(settled, ERROR_MESSAGE))
                    .as("the conflict text, spelt as the source spells it")
                    .isEqualTo(UPDATE_RECORD_CHANGED);
            assertThat(textOf(settled, INFO_MESSAGE))
                    .as("and the details are presented again so the operator can review what changed, "
                            + "the outcome selection mapping the conflict back onto the display state")
                    .isEqualTo(UPDATE_PROMPT_FOR_CHANGES);
            assertThat(AccountControllerIT.this.customers.findById(OWNED_CUSTOMER_ID)
                    .orElseThrow().getAddrLine3())
                    .as("nothing was written: a refused save leaves the stored record exactly as it "
                            + "stood, which is the guarantee the proof exists to give")
                    .isEqualTo(SEEDED_CITY);
            assertThat(AccountControllerIT.this.accounts.findById(OWNED_ACCOUNT_ID)
                    .orElseThrow().getAcctCurrBal())
                    .as("and the interfering change survives rather than being overwritten by the "
                            + "screen's stale amount")
                    .isEqualByComparingTo(new BigDecimal("111.00"));
        }

        @Test
        @DisplayName("refuses a save that presents no before-image proof at all, treating the turn as "
                + "another fetch")
        void refusesASaveThatPresentsNoProof() throws Exception {
            // With no proof the incoming state resolves to details-not-fetched however the key was
            // pressed, so a confirmation cannot be honoured out of nowhere.
            final JsonNode presented = fetchedScreen();
            final Map<String, Object> screen = cleanEcho(presented);
            screen.put("city", "Unproven City");

            final JsonNode settled = bodyOf(updateTurn(screen, null, RE_ENTRY, KeyAction.PFK05,
                    administrativeSession()));

            assertThat(textOf(settled, INFO_MESSAGE))
                    .as("the turn goes back to presenting details rather than writing")
                    .isEqualTo(UPDATE_PROMPT_FOR_CHANGES);
            assertThat(AccountControllerIT.this.customers.findById(OWNED_CUSTOMER_ID)
                    .orElseThrow().getAddrLine3())
                    .as("and nothing was written")
                    .isEqualTo(SEEDED_CITY);
        }

        @Test
        @DisplayName("refuses to write a screen that has an outstanding edit, whatever key was pressed")
        void refusesToWriteAScreenWithAnOutstandingEdit() throws Exception {
            // The conjoined clause requires the validated state, and a screen carrying an edit failure
            // never reaches it. So the confirmation key on a rejected screen redisplays rather than
            // writing, which is what keeps a rejected value out of the master.
            final JsonNode presented = fetchedScreen();
            final Map<String, Object> screen = cleanEcho(presented);
            screen.put("city", "Rejected City");
            screen.put("ficoScore", CREDIT_SCORE_ABOVE_BOUND);

            final JsonNode settled = bodyOf(updateTurn(screen, textOf(presented, CONCURRENCY_TOKEN),
                    RE_ENTRY, KeyAction.PFK05, administrativeSession()));

            assertThat(settled.get(ERROR_FLAG).asBoolean())
                    .as("the turn reports an input error")
                    .isTrue();
            assertThat(flaggedFields(settled))
                    .contains("ficoScore");
            assertThat(AccountControllerIT.this.customers.findById(OWNED_CUSTOMER_ID)
                    .orElseThrow().getAddrLine3())
                    .as("and the valid field alongside the rejected one is not written either: the "
                            + "screen is accepted or refused whole")
                    .isEqualTo(SEEDED_CITY);
        }

        @Test
        @DisplayName("raises the error flag for a conflict yet decorates nothing, no keystroke having "
                + "been at fault")
        void raisesTheErrorFlagForAConflictYetDecoratesNothing() throws Exception {
            // The image-comparison paragraph sets the error flag exactly as the legacy one did, so the
            // conflict is an error and the screen says so. What it cannot do is name a field: the
            // decoration macro is driven off the per-field flags and a conflict writes none of them, so
            // the two-state collection stays empty however the conversation flag stands. That is the
            // shape a conflict has to have - one summary and no per-field detail - and it is what
            // distinguishes a record outcome from a keying one on the wire.
            final JsonNode presented = fetchedScreen();
            final Map<String, Object> screen = cleanEcho(presented);
            screen.put("city", "Unkeyed City");

            final Account interfering = AccountControllerIT.this.accounts.findById(OWNED_ACCOUNT_ID)
                    .orElseThrow();
            interfering.setAcctCurrCycCredit(new BigDecimal("7.00"));
            AccountControllerIT.this.accounts.save(interfering);

            final JsonNode settled = bodyOf(updateTurn(screen, textOf(presented, CONCURRENCY_TOKEN),
                    RE_ENTRY, KeyAction.PFK05, administrativeSession()));

            assertThat(textOf(settled, ERROR_MESSAGE))
                    .isEqualTo(UPDATE_RECORD_CHANGED);
            assertThat(settled.get(ERROR_FLAG).asBoolean())
                    .as("the paragraph raises the flag, so a conflict is not silently a success")
                    .isTrue();
            assertThat(flaggedFields(settled))
                    .as("and yet nothing is decorated, because no field flag was written and there is "
                            + "no field to attribute the failure to")
                    .isEmpty();
            assertThat(settled.get("currentCycleCredit").decimalValue())
                    .as("and the screen is rebuilt from the records as they now stand, so the operator "
                            + "reviews the change that beat them rather than their own stale copy")
                    .isEqualByComparingTo(new BigDecimal("7.00"));
        }
    }

    // ===============================================================================================
    // GROUP 13 - WHAT NEITHER SCREEN MAY DISCLOSE, AND WHO MAY REACH THEM
    // ===============================================================================================

    /**
     * Both transactions are registered without an administrative classification, so either declared user
     * type reaches them; the five administratively gated transactions are elsewhere and this file must not
     * widen or narrow that set.
     *
     * <p>Every outcome either screen can reach is a screen the legacy program composed, so no outcome may
     * carry a stack trace, an exception class name, a statement, a table or column name, a filesystem
     * path, a job-control or map artefact, a terminal response or reason code, or a two-byte file status.
     */
    @Nested
    @DisplayName("Security negatives")
    class SecurityNegatives {

        /** Creates the group. */
        SecurityNegatives() {
            super();
        }

        @Test
        @DisplayName("reaches both screens with an ordinary session, neither being administratively "
                + "gated")
        void reachesBothScreensWithAnOrdinarySession() throws Exception {
            final MvcResult viewed = viewTurn(SEEDED_ACCOUNT_ID, RE_ENTRY, ordinarySession());
            final MvcResult updated = updateTurn(Map.of("accountId", OWNED_ACCOUNT_ID), null, RE_ENTRY,
                    KeyAction.ENTER, ordinarySession());

            assertThat(viewed.getResponse().getStatus())
                    .as("the view transaction is registered without an administrative classification")
                    .isEqualTo(200);
            assertThat(updated.getResponse().getStatus())
                    .as("and so is the update transaction; widening the five gated ones to include "
                            + "either would lock out the operators the estate served")
                    .isEqualTo(200);
            assertThat(textOf(bodyOf(updated), "accountId"))
                    .as("and the ordinary session really did reach the record rather than a refusal")
                    .isEqualTo(OWNED_ACCOUNT_ID);
        }

        @Test
        @DisplayName("refuses a turn that presents no session, on both routes")
        void refusesATurnThatPresentsNoSession() throws Exception {
            final MvcResult viewed = viewTurn(SEEDED_ACCOUNT_ID, RE_ENTRY, null);
            final MvcResult updated = updateTurn(Map.of("accountId", OWNED_ACCOUNT_ID), null, RE_ENTRY,
                    KeyAction.ENTER, null);

            assertThat(viewed.getResponse().getStatus())
                    .as("an unauthenticated caller is refused before either transaction is entered")
                    .isEqualTo(401);
            assertThat(updated.getResponse().getStatus())
                    .isEqualTo(401);
            assertThat(rawBodyOf(viewed))
                    .as("and the refusal discloses nothing about the record that was asked for")
                    .doesNotContain(SEEDED_CITY)
                    .doesNotContain("Exception");
        }

        @Test
        @DisplayName("discloses nothing internal on a rejected update turn")
        void disclosesNothingInternalOnARejectedUpdateTurn() throws Exception {
            final JsonNode presented = fetchedScreen();
            final Map<String, Object> screen = cleanEcho(presented);
            screen.put("ficoScore", CREDIT_SCORE_BELOW_BOUND);
            screen.put("stateCode", UNKNOWN_STATE_CODE);

            final MvcResult result = updateTurn(screen, textOf(presented, CONCURRENCY_TOKEN), RE_ENTRY,
                    KeyAction.ENTER, administrativeSession());
            final String body = bodyWithoutTheSealedToken(rawBodyOf(result));

            assertThat(body)
                    .as("no exception surface and no persistence surface")
                    .doesNotContain("Exception")
                    .doesNotContain("Throwable")
                    .doesNotContain("at com.carddemo")
                    .doesNotContain("org.postgresql")
                    .doesNotContain("org.hibernate")
                    .doesNotContain("select ")
                    .doesNotContain("update ")
                    .doesNotContain("cust_addr_line_3")
                    .doesNotContain("acct_curr_bal");
            assertThat(body)
                    .as("no filesystem path and no build artefact")
                    .doesNotContain("/tmp/")
                    .doesNotContain("classpath:")
                    .doesNotContain(".jar");
            assertThat(body)
                    .as("and no legacy artefact: the screen is expressed in the module's own vocabulary "
                            + "and the estate's names belong in the traceability matrix, not on the wire. "
                            + "Each needle is a family marker rather than one constant, so it refuses "
                            + "every member of its family at once - every attribute and map macro, every "
                            + "terminal control block field, and every job-control statement")
                    .doesNotContain(TERMINAL_ATTRIBUTE_FAMILY_PREFIX)
                    .doesNotContain(TERMINAL_CONTROL_BLOCK_PREFIX)
                    .doesNotContain(JOB_CONTROL_STATEMENT_PREFIX);
        }

        @Test
        @DisplayName("discloses no two-byte file status and no terminal response code")
        void disclosesNoStatusOrResponseCode() throws Exception {
            // The legacy normalised a two-byte status into a coarse outcome before branching on it, and
            // neither the raw status nor a terminal response code was ever put on a map. A miss is
            // reported as the screen text and nothing else.
            final String body = rawBodyOf(viewTurn(ABSENT_ACCOUNT_ID, RE_ENTRY,
                    administrativeSession()));

            assertThat(body)
                    .doesNotContain("fileStatus")
                    .doesNotContain("responseCode")
                    .doesNotContain("reasonCode")
                    .doesNotContain("APPL-RESULT")
                    .doesNotContain("NOTFND");
        }

        @Test
        @DisplayName("never publishes the sealed form of a protected value on either screen")
        void neverPublishesTheSealedFormOfAProtectedValue() throws Exception {
            // The reserved row's national and government identifiers are stored sealed. A screen must
            // show either the value or a stand-in for it, never the envelope: publishing the envelope
            // would leak the storage scheme and, with it, which values are protected at all.
            final String viewed = rawBodyOf(viewTurn(OWNED_ACCOUNT_ID, RE_ENTRY,
                    administrativeSession()));

            assertThat(viewed)
                    .as("no sealed envelope crosses the view screen")
                    .doesNotContain(ENVELOPE_PREFIX);

            final JsonNode updateScreen = fetchedScreen();

            assertThat(SensitiveValues.fingerprint(textOf(updateScreen, "governmentIssuedId")))
                    .as("and the update screen publishes the identifier itself to a caller entitled to "
                            + "see it, not the envelope it is stored in")
                    .isEqualTo(SensitiveValues.fingerprint(OWNED_GOVERNMENT_IDENTIFIER));
            assertThat(textOf(updateScreen, "governmentIssuedId").startsWith(ENVELOPE_PREFIX))
                    .as("and not the envelope it is stored in")
                    .isFalse();
        }

        @Test
        @DisplayName("withholds the regulated values from an ordinary session on the update screen too")
        void withholdsTheRegulatedValuesFromAnOrdinarySession() throws Exception {
            // The update screen reveals to an entitled caller and withholds otherwise, which is a
            // documented divergence from a legacy map that carried every one of these in clear. The
            // divergence can only disclose less, never more.
            final JsonNode screen = bodyOf(updateTurn(Map.of("accountId", OWNED_ACCOUNT_ID), null,
                    RE_ENTRY, KeyAction.ENTER, ordinarySession()));

            assertThat(textOf(screen, "governmentIssuedId"))
                    .as("a stand-in at the width the revealed value occupies")
                    .isEqualTo(maskOfWidth(GOVERNMENT_ID_WIDTH));
            assertThat(textOf(screen, "accountId"))
                    .as("while the unregulated items are published as they stand, so the screen is "
                            + "still usable")
                    .isEqualTo(OWNED_ACCOUNT_ID);
            assertThat(textOf(screen, "city"))
                    .isEqualTo(SEEDED_CITY);
        }

        @Test
        @DisplayName("declines a body wider than the map field it is keyed into, before any edit runs")
        void declinesABodyWiderThanTheMapField() throws Exception {
            // The width refusal is declarative and sits in front of the transaction, so it is not one of
            // the screen outcomes and does not answer with a screen. The map field is eleven characters.
            final MvcResult result = updateTurn(Map.of("accountId", "9".repeat(64)), null, RE_ENTRY,
                    KeyAction.ENTER, administrativeSession());

            assertThat(result.getResponse().getStatus())
                    .as("a value no map field could have held never reaches the transaction")
                    .isEqualTo(400);
            assertThat(rawBodyOf(result))
                    .as("and the refusal names no internal surface")
                    .doesNotContain("Exception")
                    .doesNotContain("org.springframework");
        }
    }




    // ===============================================================================================
    // THE GRAPH UNDER TEST
    // ===============================================================================================

    /**
     * The account-servicing surface: the shipped boundary, the shipped filter chain, both shipped
     * transactions with every collaborator they declare, and the four masters the records live in.
     *
     * <p>Assembled explicitly rather than by scanning, which is this module's established practice for a
     * context-booting specification: a scan of the base package would sweep the test tree's own
     * configuration classes into the graph, and an explicit list lets a reader see in one place exactly
     * what took part.
     *
     * <p><strong>Nothing is stubbed.</strong> The two transactions, the regulated-data gate with its real
     * field encryption, the before-image proof service, the date cascade, the externalised lookup tables,
     * the shared message catalogue, the navigation vocabulary, the abend path, the transactional boundary,
     * the two contract adapters, the failure handler and the filter chain are all the shipped ones, and
     * every row comes off a real server.
     *
     * <p>Batch auto-configuration is excluded because nothing here launches a job, and the exemplar
     * auto-configuration because this graph carries no exemplar sampler for it to decorate. The clock is
     * the pinned instant the shared base publishes, so a birth-date edit and a session's issued-at image
     * mean the same thing on every run and on every host.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {PrometheusExemplarsAutoConfiguration.class,
            BatchAutoConfiguration.class})
    @Import({AccountController.class, ModuleErrorController.class, GlobalExceptionHandler.class,
        JsonRefusalBodyRenderer.class, AccountProtectedDataAdapter.class, ScreenStateAdapter.class,
        AccountUpdateContractAdapter.class, AccountViewService.class, AccountUpdateService.class,
        AccountConcurrencyTokenService.class, SensitiveFieldEncryptionService.class,
        ValidationLookupService.class, DateValidationService.class, MessageCatalogService.class,
        NavigationService.class, AbendService.class, OnlineTransactionBoundary.class,
        SignOnStateService.class, SecurityConfig.class, JwtTokenProvider.class, WebMvcConfig.class})
    @EnableConfigurationProperties(JwtProperties.class)
    @EnableJpaRepositories(basePackageClasses = AccountRepository.class)
    @EntityScan(basePackageClasses = Account.class)
    static class AccountScreenContext {

        /** Creates the slice. */
        AccountScreenContext() {
            super();
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
