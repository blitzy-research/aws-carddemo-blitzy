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

import com.carddemo.api.dto.BillPaymentRequest;
import com.carddemo.api.dto.BillPaymentResponse;
import com.carddemo.config.JwtProperties;
import com.carddemo.config.JwtTokenProvider;
import com.carddemo.config.SecurityConfig;
import com.carddemo.config.WebMvcConfig;
import com.carddemo.domain.Account;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.BillPaymentService;
import com.carddemo.service.MessageCatalogService;
import com.carddemo.service.NavigationService;
import com.carddemo.service.OnlineTransactionBoundary;
import com.carddemo.service.SignOnStateService;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.TestDataFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Integration specification of transaction {@code CB00}, the bill-payment screen, exercised over the
 * shipped REST boundary, the shipped filter chain and a real PostgreSQL 16 server.
 *
 * <h2>What this class establishes that a unit test cannot</h2>
 *
 * <p>The unit specification of the boundary places an outcome at the controller and asserts the
 * projection. That proves the crossing and nothing about the rule, because the outcome it projects is
 * the one the test invented. Three of this screen's four headline behaviours are only observable against
 * a real table:
 *
 * <ul>
 *   <li><strong>The payment is always the whole balance</strong>, so the row the turn leaves behind
 *       carries exactly zero. A stubbed service can claim that; only a real update demonstrates it.</li>
 *   <li><strong>The identifier is the highest existing key plus one</strong>, so the first identifier
 *       issued against a table the reference seed leaves empty is the sixteen-character
 *       {@code 0000000000000001} and two consecutive payments carry consecutive identifiers. No stub can
 *       establish that, because the rule is a read of the table.</li>
 *   <li><strong>The posted transaction carries fifteen fixed values and two identical timestamps</strong>,
 *       and it carries them into a column set whose widths the migrated schema enforces. Asserting the
 *       projection proves the record was assembled; asserting the stored row proves it was accepted.</li>
 * </ul>
 *
 * <h2>How a turn reaches the payment logic, and why every method here echoes navigation state</h2>
 *
 * <p>The legacy program distinguishes a first entry from a re-submission and reads the transmitted screen
 * only on the latter, at {@code app/cbl/COBIL00C.cbl} line 124. A first entry therefore has no
 * confirmation character at all - the field is blank whatever the caller sent - so the confirmation
 * evaluation at lines 173 to 191 can only be exercised by a turn that echoes the re-entry state, and so
 * can the empty-account-identifier check at lines 158 to 167. Every method below that asserts a
 * confirmation arm or an ordered check therefore submits the re-entry state, and the two methods that
 * assert the first-entry behaviour say so in their names. Inventing a shortcut - submitting a
 * confirmation on a first entry and expecting it to count - would test a program this module does not
 * ship.
 *
 * <h2>Fixture hygiene on a shared server</h2>
 *
 * <p>The server is shared across the whole integration tier and the reference seed is applied to it, so
 * this class reserves its own account and cross-reference keys outside the seeded range, writes them
 * through the repositories before each method and removes them after. No delivered row is read for
 * mutation, updated or deleted: several sibling specifications assert against the fifty seeded accounts
 * on this same server, and driving one of their balances to zero would break them in a way that looks
 * like a defect in them.
 *
 * <p>The transaction master is the one table the seed leaves empty, which is what makes the
 * first-identifier assertion possible. It is also shared, so the two methods that depend on emptiness
 * establish it explicitly rather than assuming it, and every method removes the rows it caused.
 *
 * <h2>What this class does not declare, and why</h2>
 *
 * <p>No container, no container annotation, no data-source property and no context-dirtying marker. The
 * shared base owns the server for the lifetime of the JVM and publishes its address into any context a
 * subclass boots; a per-class container lifecycle here would stop that server after this class finished
 * and leave every later class on a dead one. No wall clock either: the base publishes a clock frozen at
 * the pinned instant and this specification's graph reads it, which is what lets the two timestamps of a
 * posted transaction be asserted at an exact value rather than inside a window.
 *
 * <p>Message texts, field widths, record widths, fixed field values, transaction identifiers and paragraph
 * line numbers are contract and metadata; no source line of any legacy member is transcribed here, and
 * nothing under {@code app/} is read at run time.
 */
@SpringBootTest(classes = BillPaymentControllerIT.BillPaymentContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            // The shared base migrated this server to the head of the delivered set before the first
            // subclass was constructed, so a second migration from this context would apply nothing.
            "spring.flyway.enabled=false",
            // validate, never none and never a generating value. The shipped profile declares validate
            // and this graph keeps it: a mapping that had drifted from the migrated schema must fail this
            // specification at refresh rather than be silently reconciled, and validate emits no DDL.
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.batch.job.enabled=false",
            "spring.main.banner-mode=off",
            "management.endpoint.health.validate-group-membership=false",
            "management.tracing.enabled=false",
            // The boundary is a mock servlet inside this process, so there is no wire for a token to be
            // observed on and the transport requirement is relaxed exactly as the suite profile relaxes
            // it. Nothing else is relaxed: the anonymous set and the closing authenticated rule are the
            // shipped ones, which is what makes the refusal assertion below meaningful.
            "carddemo.security.require-https=false",
            "carddemo.security.jwt.issuer=carddemo-java",
            "carddemo.security.jwt.expiration=PT15M"})
@AutoConfigureMockMvc
@DisplayName("The bill-payment screen of transaction CB00 over the shipped boundary and a real server")
public class BillPaymentControllerIT extends AbstractPostgresIT {

    // ===============================================================================================
    // THE CONTRACT, RESTATED HERE RATHER THAN IMPORTED FROM THE SUBJECT
    //
    // Every text below is written out as its own literal. An expectation that borrows the constant the
    // subject publishes proves only that the subject agrees with itself, and each of these is an
    // operator-facing string whose bytes are the external contract.
    // ===============================================================================================

    /**
     * The account-identifier emptiness refusal, from {@code app/cbl/COBIL00C.cbl} line 161.
     *
     * <p>The noun is <strong>abbreviated</strong> here and the negation is upper case. The very same
     * program spells the noun out in full in {@link #ACCOUNT_ID_NOT_FOUND}, and that asymmetry is
     * contractual: two operator-visible texts of the same program refer to the same field by two
     * different names, and unifying them would change what an operator and any downstream matcher see.
     */
    private static final String ACCT_ID_CAN_NOT_BE_EMPTY = "Acct ID can NOT be empty...";

    /** The unacceptable-confirmation refusal, from line 187. */
    private static final String INVALID_CONFIRMATION_VALUE = "Invalid value. Valid values are (Y/N)...";

    /** The non-positive-balance refusal, from line 201. */
    private static final String NOTHING_TO_PAY = "You have nothing to pay...";

    /** The prompt a turn that supplied no confirmation receives, from line 237. */
    private static final String CONFIRM_BILL_PAYMENT = "Confirm to make a bill payment...";

    /**
     * The record-absent refusal, from lines 361, 392 and 425 - three sites, one text.
     *
     * <p>The noun is <strong>spelled out in full</strong> here, against the abbreviation at line 161.
     * See {@link #ACCT_ID_CAN_NOT_BE_EMPTY}.
     */
    private static final String ACCOUNT_ID_NOT_FOUND = "Account ID NOT found...";

    /** The account-read catch-all, from line 368. */
    private static final String UNABLE_TO_LOOKUP_ACCOUNT = "Unable to lookup Account...";

    /** The account-rewrite catch-all, from line 399. Note the capital in the verb. */
    private static final String UNABLE_TO_UPDATE_ACCOUNT = "Unable to Update Account...";

    /** The cross-reference catch-all, from line 432. */
    private static final String UNABLE_TO_LOOKUP_XREF_AIX = "Unable to lookup XREF AIX file...";

    /** The browse not-found arm, from line 455. */
    private static final String TRANSACTION_ID_NOT_FOUND = "Transaction ID NOT found...";

    /** The browse catch-all, from lines 463 and 492 - two sites, one text. */
    private static final String UNABLE_TO_LOOKUP_TRANSACTION = "Unable to lookup Transaction...";

    /**
     * First half of the successful-payment text, from line 527.
     *
     * <p><strong>It ends with a space</strong>, and {@link #YOUR_TRANSACTION_ID_IS} begins with one, so
     * the composed sentence carries two consecutive spaces after the first full stop. The two halves are
     * declared separately here precisely so that neither can be tidied: a single pre-joined constant
     * would let the double space be silently normalised into one and the specification would still pass.
     */
    private static final String PAYMENT_SUCCESSFUL_PREFIX = "Payment successful. ";

    /**
     * Second half of the same text, from line 528.
     *
     * <p>Leading <em>and</em> trailing space, and the noun is <strong>spelled out</strong>. The
     * transaction-add screen composes a structurally identical sentence with the noun
     * <strong>abbreviated</strong>; the two texts are different bytes and must never share a constant.
     * {@link #ABBREVIATED_TRANSACTION_ID_FRAGMENT} holds the other form so the difference is asserted
     * rather than assumed.
     */
    private static final String YOUR_TRANSACTION_ID_IS = " Your Transaction ID is ";

    /**
     * The other screen's abbreviated fragment, held here only to be asserted different.
     *
     * <p>It belongs to the transaction-add screen and is never produced by this one. Its presence in this
     * file is the guard: if the two ever collapsed onto one constant, the assertion that they differ is
     * what would fail.
     */
    private static final String ABBREVIATED_TRANSACTION_ID_FRAGMENT = " Your Tran ID is ";

    /** The sentence terminator the composition appends, from line 530. */
    private static final String SENTENCE_TERMINATOR = ".";

    /**
     * The duplicate-key refusal, from line 536.
     *
     * <p>The noun is <strong>abbreviated</strong> and the verb is <strong>singular</strong>. Both are
     * source oddities and both are contract.
     */
    private static final String TRAN_ID_ALREADY_EXIST = "Tran ID already exist...";

    /**
     * The insert catch-all, from line 543.
     *
     * <p>Two words, mixed case, lower-case second initial. Preserved exactly.
     */
    private static final String UNABLE_TO_ADD_BILL_PAY_TRANSACTION =
            "Unable to Add Bill pay Transaction...";

    // ===============================================================================================
    // THE SCREEN AND RECORD CONTRACT, AS WIDTHS AND FIXED VALUES
    // ===============================================================================================

    /** Symbolic name of the account-identifier field the cursor returns to. */
    private static final String FIELD_ACCOUNT_ID = "ACTIDIN";

    /** Symbolic name of the confirmation field the cursor returns to. */
    private static final String FIELD_CONFIRM = "CONFIRM";

    /** Width of the account-identifier field on the map and of the column that stores it. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** Width of the transaction-identifier field and of the column that stores it. */
    private static final int TRANSACTION_ID_WIDTH = 16;

    /**
     * Width of the outbound message field the send paragraph fills at line 293.
     *
     * <p>The program's message work area is eighty characters and the screen's outbound field is
     * seventy-eight, so the move truncates into the narrower one and space fills a shorter text. That
     * width is the binding external one - it is what an operator actually saw - and it is therefore the
     * width every message assertion below is made at. The expectation is padded up to it and the received
     * value is never trimmed down to the expectation, which is the only direction that can catch a text
     * that lost or gained a character at its end.
     */
    private static final int OUTBOUND_MESSAGE_WIDTH = 78;

    /** The type code the payment stamps, from line 220. */
    private static final String PAYMENT_TRANSACTION_TYPE = "02";

    /**
     * The category code the payment stamps, from line 221.
     *
     * <p>A bounded four-character string and never the integer two: the source moves a numeric literal
     * into a four-character field, which zero-fills it, and the stored column is textual.
     */
    private static final String PAYMENT_TRANSACTION_CATEGORY = "0002";

    /**
     * The source code the payment stamps, from line 222, at the full ten-character width of its field.
     *
     * <p>The literal the source moves is eight characters and the receiving field is ten, so the stored
     * value carries <strong>two trailing spaces</strong>. It is asserted at that width and is never
     * trimmed on either side of the comparison.
     */
    private static final String PAYMENT_TRANSACTION_SOURCE = "POS TERM  ";

    /** Width of the source field on the transaction record layout and of the column that stores it. */
    private static final int PAYMENT_TRANSACTION_SOURCE_WIDTH = 10;

    /** The description the payment stamps, from line 223. */
    private static final String PAYMENT_TRANSACTION_DESCRIPTION = "BILL PAYMENT - ONLINE";

    /** The merchant identifier the payment stamps, from line 226. */
    private static final String PAYMENT_MERCHANT_ID = "999999999";

    /** The merchant name the payment stamps, from line 227. */
    private static final String PAYMENT_MERCHANT_NAME = "BILL PAYMENT";

    /** The merchant city the payment stamps, from line 228. */
    private static final String PAYMENT_MERCHANT_CITY = "N/A";

    /** The merchant postal code the payment stamps, from line 229. */
    private static final String PAYMENT_MERCHANT_ZIP = "N/A";

    /**
     * The single timestamp both of the posted transaction's timestamp fields carry.
     *
     * <p>The <em>online</em> twenty-six character form: a hyphenated date, one space, a colon-separated
     * time, a full stop and six fraction digits that the source overwrites with zeros. The batch tier
     * builds a differently shaped twenty-six character value and the two forms never share a constant or
     * a formatter. The value here is the pinned instant the shared base publishes, so it is exact rather
     * than a window.
     */
    private static final String PINNED_ONLINE_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /**
     * The <em>batch</em> twenty-six character form of the same instant, held only to be asserted
     * different from the online form above.
     *
     * <p>Never produced by this screen. Its presence is the guard against the two forms being unified.
     */
    private static final String PINNED_BATCH_TIMESTAMP = "2022-06-10-19.27.53.000000";

    /** Wire value of this screen's own route, which every re-presented turn nominates. */
    private static final String BILL_PAYMENT_ROUTE = "bill-payment";

    /** Transaction this screen is registered under in the resource definitions. */
    private static final String BILL_PAYMENT_TRANSACTION = "CB00";

    /** Program the transaction is bound to. */
    private static final String BILL_PAYMENT_PROGRAM = "COBIL00C";

    /** Presentation prefix an issued session travels behind. */
    private static final String BEARER_PREFIX = "Bearer ";

    /** Summary a refusal for want of a session carries. */
    private static final String AUTHENTICATION_REQUIRED = "Authentication required";

    // ===============================================================================================
    // THE FIXTURES THIS SPECIFICATION OWNS
    // ===============================================================================================

    /**
     * A delivered ordinary identity, whose credential seed is applied under this profile.
     *
     * <p>Used only as the subject of a minted session. No credential of this identity is submitted,
     * rendered, logged or named anywhere in this file: the route under test is not the sign-on route, so
     * a session is minted through the shipped provider rather than earned by presenting a credential.
     */
    private static final String DELIVERED_USER_IDENTITY = "USER0001";

    /** The one-character stored type code the delivered ordinary identity carries. */
    private static final String USER_ROLE_CODE = "U";

    /**
     * Account this specification pays in full. Reserved, eleven digits, far outside the seeded range.
     *
     * <p>Every owned key in this class begins with the same two leading digits so that a reader can see
     * at a glance which rows belong to it and the cleanup can be read against the same list.
     */
    private static final String OWNED_ACCOUNT_WITH_BALANCE = "97000000001";

    /** Account whose balance is exactly zero, for the boundary of the non-positive rejection. */
    private static final String OWNED_ACCOUNT_ZERO_BALANCE = "97000000002";

    /** Account whose balance is negative, for the other side of the same boundary. */
    private static final String OWNED_ACCOUNT_NEGATIVE_BALANCE = "97000000003";

    /** Account that exists but has no cross-reference row, for the cross-reference refusal. */
    private static final String OWNED_ACCOUNT_WITHOUT_CROSS_REFERENCE = "97000000004";

    /** Account paid twice, for the consecutive-identifier assertion. */
    private static final String OWNED_ACCOUNT_SECOND_PAYMENT = "97000000005";

    /** An eleven-digit identifier inside the reserved range that is never written. */
    private static final String ABSENT_ACCOUNT = "97000000099";

    /** Card number cross-referenced to {@link #OWNED_ACCOUNT_WITH_BALANCE}. */
    private static final String OWNED_CARD_NUMBER = "9700000000000001";

    /** Card number cross-referenced to {@link #OWNED_ACCOUNT_ZERO_BALANCE}. */
    private static final String OWNED_CARD_NUMBER_ZERO = "9700000000000002";

    /** Card number cross-referenced to {@link #OWNED_ACCOUNT_NEGATIVE_BALANCE}. */
    private static final String OWNED_CARD_NUMBER_NEGATIVE = "9700000000000003";

    /** Card number cross-referenced to {@link #OWNED_ACCOUNT_SECOND_PAYMENT}. */
    private static final String OWNED_CARD_NUMBER_SECOND = "9700000000000005";

    /**
     * Customer identifier every owned cross-reference names.
     *
     * <p>A <strong>delivered</strong> identifier, because the cross-reference table names an existing
     * customer and nothing here needs a customer of its own. Naming a delivered row in a foreign key
     * reads it and never writes it, so this is the one place a delivered row is touched at all, and it is
     * touched read-only. Writing a fifty-column customer of this class's own would add a row shaped like
     * personal data for no assertion's benefit.
     */
    private static final String DELIVERED_CUSTOMER_ID = "000000001";

    /**
     * The balance the paid account is opened with, at the scale its record field declares.
     *
     * <p>Chosen with a non-zero fraction and more than one integer digit so that a truncating store, a
     * plain rendering and an exactly-zero remainder are all separately observable.
     */
    private static final BigDecimal OWNED_OPENING_BALANCE = new BigDecimal("1234.56");

    /** The balance the second paid account is opened with, deliberately a different figure. */
    private static final BigDecimal SECOND_OPENING_BALANCE = new BigDecimal("77.01");

    /** Exactly zero, at the scale of the balance column: the inclusive edge of the rejection. */
    private static final BigDecimal ZERO_BALANCE = new BigDecimal("0.00");

    /** A negative balance, at the same scale: the far side of the same edge. */
    private static final BigDecimal NEGATIVE_BALANCE = new BigDecimal("-15.99");

    /** The balance a completed payment must leave behind, exactly. */
    private static final BigDecimal SETTLED_BALANCE = new BigDecimal("0.00");

    /** The first identifier the minting rule issues against an empty transaction master. */
    private static final String FIRST_TRANSACTION_ID = "0000000000000001";

    /** The identifier the second payment of a run issues. */
    private static final String SECOND_TRANSACTION_ID = "0000000000000002";

    /** Reads and writes JSON without binding to a published contract type. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The shipped servlet boundary, with the shipped filter chain in front of it. */
    @Autowired
    private MockMvc mockMvc;

    /** The account master, for writing and removing owned accounts and reading the settled balance. */
    @Autowired
    private AccountRepository accounts;

    /**
     * The same shipped account master, spied so that one specification can force the rewrite of line 235 to
     * fail.
     *
     * <p>A spy, not a mock: every call reaches the real repository and the real server unless a test has
     * explicitly arranged otherwise, so the graph this class boots is still the shipped one. The bean
     * override applies to the whole context, which is why {@link #accounts} and this field are the same
     * object - the second reference exists only so that a reader can see at the arrangement site that the
     * store is being made to fail deliberately.
     */
    @MockitoSpyBean
    private AccountRepository spiedAccounts;

    /**
     * The card master, for the owned card rows the cross-reference and the posted transaction both name.
     *
     * <p>Present because two of the schema's six foreign keys require it: a cross-reference row names an
     * existing card, and a posted transaction names one too. A specification that wrote only accounts and
     * cross-references would be refused by the first of those constraints, which is the schema doing its
     * job rather than an obstacle to work around.
     */
    @Autowired
    private CardRepository cards;

    /** The cross-reference path, for writing and removing owned cross-reference rows. */
    @Autowired
    private CardCrossReferenceRepository crossReferences;

    /** The transaction master, for reading the posted row back and removing it. */
    @Autowired
    private TransactionRepository transactions;

    /** The shipped session provider; nothing here re-implements signing or verification. */
    @Autowired
    private JwtTokenProvider sessions;

    /** Creates the specification. */
    public BillPaymentControllerIT() {
        super();
    }

    // ===============================================================================================
    // FIXTURE LIFECYCLE
    // ===============================================================================================

    /**
     * Writes the owned accounts and cross-references, and clears any transaction rows left over.
     *
     * <p>The transaction master is cleared first and unconditionally, because the identifier rule reads
     * the highest existing key and two of the assertions below are about what that read returns on an
     * empty table. Clearing is scoped to the whole table only because the reference seed deliberately
     * leaves it empty - there is no delivered row to preserve - and every row this class causes is
     * therefore also removed by the same call.
     */
    @BeforeEach
    void writeOwnedFixtures() {
        this.transactions.deleteAll();
        // Written in foreign-key order: an account, then the card that names it, then the
        // cross-reference row that names both.
        writeOwnedAccount(OWNED_ACCOUNT_WITH_BALANCE, OWNED_OPENING_BALANCE);
        writeOwnedAccount(OWNED_ACCOUNT_ZERO_BALANCE, ZERO_BALANCE);
        writeOwnedAccount(OWNED_ACCOUNT_NEGATIVE_BALANCE, NEGATIVE_BALANCE);
        writeOwnedAccount(OWNED_ACCOUNT_WITHOUT_CROSS_REFERENCE, OWNED_OPENING_BALANCE);
        writeOwnedAccount(OWNED_ACCOUNT_SECOND_PAYMENT, SECOND_OPENING_BALANCE);
        writeOwnedCard(OWNED_CARD_NUMBER, OWNED_ACCOUNT_WITH_BALANCE);
        writeOwnedCard(OWNED_CARD_NUMBER_ZERO, OWNED_ACCOUNT_ZERO_BALANCE);
        writeOwnedCard(OWNED_CARD_NUMBER_NEGATIVE, OWNED_ACCOUNT_NEGATIVE_BALANCE);
        writeOwnedCard(OWNED_CARD_NUMBER_SECOND, OWNED_ACCOUNT_SECOND_PAYMENT);
        writeOwnedCrossReference(OWNED_CARD_NUMBER, OWNED_ACCOUNT_WITH_BALANCE);
        writeOwnedCrossReference(OWNED_CARD_NUMBER_ZERO, OWNED_ACCOUNT_ZERO_BALANCE);
        writeOwnedCrossReference(OWNED_CARD_NUMBER_NEGATIVE, OWNED_ACCOUNT_NEGATIVE_BALANCE);
        writeOwnedCrossReference(OWNED_CARD_NUMBER_SECOND, OWNED_ACCOUNT_SECOND_PAYMENT);
    }

    /**
     * Removes everything this class wrote, so the shared server is left as it was found.
     *
     * <p>Removed in reverse foreign-key order and scoped to the owned keys by name. The delivered fifty
     * accounts, fifty cards, fifty cross-references, fifty customers and ten identities are untouched by
     * every method here, which is what lets sibling specifications keep asserting against them on this
     * same server.
     */
    @AfterEach
    void removeOwnedFixtures() {
        this.transactions.deleteAll();
        for (final String card : ownedCardNumbers()) {
            this.crossReferences.deleteById(card);
        }
        for (final String card : ownedCardNumbers()) {
            this.cards.deleteById(card);
        }
        for (final String account : ownedAccountIds()) {
            this.accounts.deleteById(account);
        }
    }

    /**
     * The card keys this class writes, in the order it writes them.
     *
     * @return the reserved card numbers
     */
    private static List<String> ownedCardNumbers() {
        return List.of(OWNED_CARD_NUMBER, OWNED_CARD_NUMBER_ZERO, OWNED_CARD_NUMBER_NEGATIVE,
                OWNED_CARD_NUMBER_SECOND);
    }

    /**
     * The account keys this class writes, in the order it writes them.
     *
     * @return the reserved account identifiers
     */
    private static List<String> ownedAccountIds() {
        return List.of(OWNED_ACCOUNT_WITH_BALANCE, OWNED_ACCOUNT_ZERO_BALANCE,
                OWNED_ACCOUNT_NEGATIVE_BALANCE, OWNED_ACCOUNT_WITHOUT_CROSS_REFERENCE,
                OWNED_ACCOUNT_SECOND_PAYMENT);
    }

    /**
     * Writes one owned account at a stated balance, through the repository and never through raw SQL.
     *
     * @param accountId the reserved identifier
     * @param balance the balance to open the row with, at the column's own scale
     */
    private void writeOwnedAccount(final String accountId, final BigDecimal balance) {
        this.accounts.save(TestDataFactory.account()
                .acctId(accountId)
                .currentBalance(balance)
                .build());
    }

    /**
     * Writes one owned card row through the repository, so the two constraints that name a card are met.
     *
     * @param cardNumber the reserved card number
     * @param accountId the account the card belongs to
     */
    private void writeOwnedCard(final String cardNumber, final String accountId) {
        this.cards.save(TestDataFactory.card()
                .cardNumber(cardNumber)
                .accountId(accountId)
                .build());
    }

    /**
     * Writes one owned cross-reference row through the repository.
     *
     * @param cardNumber the reserved card number
     * @param accountId the account the card resolves to
     */
    private void writeOwnedCrossReference(final String cardNumber, final String accountId) {
        this.crossReferences.save(TestDataFactory.cardCrossReference()
                .cardNumber(cardNumber)
                .customerId(DELIVERED_CUSTOMER_ID)
                .accountId(accountId)
                .build());
    }

    // ===============================================================================================
    // SUBMISSION HELPERS
    //
    // Each renders the request as a map so that a property can be OMITTED as distinct from sent null,
    // and so that a property the contract does not declare can be sent deliberately. Every property
    // name below is the published contract type's own component name, read from that type rather than
    // chosen here: a client speaks the name the boundary declares.
    // ===============================================================================================

    /**
     * Mints one session for the delivered ordinary identity.
     *
     * <p>Minted through the shipped provider over the shipped seeded record, so the token carries the
     * record's own fingerprint and the shipped filter accepts it. It is signed with the pinned clock, so
     * its issue and expiry instants are the same on every run and no assertion here depends on a wall
     * clock. No credential is presented, submitted, rendered or named to obtain it: the route under test
     * is not the sign-on route.
     *
     * @return the compact token, to be presented behind the bearer prefix
     */
    private String session() {
        return this.sessions.issue(DELIVERED_USER_IDENTITY, UserType.USER, USER_ROLE_CODE);
    }

    /**
     * The echoed navigation state of a re-submitted screen.
     *
     * <p>Sixteen members, of which this specification populates exactly one: the program-context flag,
     * set to the re-entry value. That single member is what makes the turn a re-submission rather than a
     * first entry, and a re-submission is the only turn on which the transmitted screen is read at all -
     * see the class comment. The identity members are left absent deliberately: the boundary replaces
     * them with the authenticated principal's own, and populating them here would assert against a value
     * the server overwrites.
     *
     * @return the echoed record, as a rendered map
     */
    private static Map<String, Object> reEntryContext() {
        final Map<String, Object> context = new LinkedHashMap<>();
        context.put("programContext", "REENTER");
        return context;
    }

    /**
     * Submits one re-entry turn of the screen with a session, and returns the whole result.
     *
     * @param accountId the account identifier to submit, or {@code null} to omit the property entirely
     * @param confirm the confirmation character to submit, or {@code null} to omit the property entirely
     * @return the result, so both the body and the status can be inspected
     * @throws Exception if the boundary cannot be reached
     */
    private MvcResult submitReEntry(final String accountId, final String confirm) throws Exception {
        return submit(accountId, confirm, KeyAction.ENTER, reEntryContext(), session());
    }

    /**
     * Submits one re-entry turn and parses its body, having asserted the status the contract fixes.
     *
     * @param accountId the account identifier to submit, or {@code null} to omit it
     * @param confirm the confirmation character to submit, or {@code null} to omit it
     * @return the parsed response body
     * @throws Exception if the boundary cannot be reached
     */
    private JsonNode reEntry(final String accountId, final String confirm) throws Exception {
        return bodyOf(submitReEntry(accountId, confirm));
    }

    /**
     * Submits one turn, with every component of the request under the caller's control.
     *
     * @param accountId the account identifier, or {@code null} to omit the property
     * @param confirm the confirmation character, or {@code null} to omit the property
     * @param keyAction the attention key, or {@code null} to omit the property
     * @param navigationContext the echoed navigation record, or {@code null} to omit the property
     * @param token the session to present, or {@code null} to present none at all
     * @return the completed result
     * @throws Exception if the boundary cannot be reached
     */
    private MvcResult submit(final String accountId, final String confirm, final KeyAction keyAction,
            final Map<String, Object> navigationContext, final String token) throws Exception {
        return submit(payload(accountId, confirm, keyAction, navigationContext), token);
    }

    /**
     * Submits an already-rendered body, so that a property the contract does not declare can be sent.
     *
     * @param payload the body to render and send
     * @param token the session to present, or {@code null} to present none at all
     * @return the completed result
     * @throws Exception if the boundary cannot be reached
     */
    private MvcResult submit(final Map<String, Object> payload, final String token) throws Exception {
        MockHttpServletRequestBuilder request =
                MockMvcRequestBuilders.post(BillPaymentController.BILL_PAYMENT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(payload));
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + token);
        }
        return this.mockMvc.perform(request).andReturn();
    }

    /**
     * Renders a request body, omitting every property the caller passed as absent.
     *
     * @param accountId the account identifier, or {@code null} to omit the property
     * @param confirm the confirmation character, or {@code null} to omit the property
     * @param keyAction the attention key, or {@code null} to omit the property
     * @param navigationContext the echoed navigation record, or {@code null} to omit the property
     * @return the body, as a map ready to render
     */
    private static Map<String, Object> payload(final String accountId, final String confirm,
            final KeyAction keyAction, final Map<String, Object> navigationContext) {
        final Map<String, Object> body = new LinkedHashMap<>();
        if (accountId != null) {
            body.put("accountId", accountId);
        }
        if (confirm != null) {
            body.put("confirm", confirm);
        }
        if (keyAction != null) {
            body.put("keyAction", keyAction.name());
        }
        if (navigationContext != null) {
            body.put("navigationContext", navigationContext);
        }
        return body;
    }

    /**
     * Parses the body of a completed turn, having first asserted the status the contract fixes.
     *
     * <p>The assertion is here rather than in each method because it is the same for every outcome the
     * legacy screen could compose, refusals included: the transaction completed and the screen was sent,
     * so the outcome is read from the body. A method that needs a different status asserts it itself.
     *
     * @param result the completed request
     * @return the parsed body
     * @throws Exception if the body cannot be read or parsed
     */
    private static JsonNode bodyOf(final MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("every screen the legacy program could compose is a completed turn, refusals "
                        + "included, so the outcome is read from the body and never from the status line")
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * Reads a textual member of a body, distinguishing an absent member from a null one.
     *
     * @param body the parsed body
     * @param member the member name
     * @return the text, or {@code null} when the member is absent or carries the null literal
     */
    private static String textOf(final JsonNode body, final String member) {
        final JsonNode value = body.get(member);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * Reads the raw JSON rendering of a member, so a numeric literal can be inspected as transmitted.
     *
     * @param body the parsed body
     * @param member the member name
     * @return the member's own JSON text, or {@code null} when absent
     */
    private static String rawOf(final JsonNode body, final String member) {
        final JsonNode value = body.get(member);
        return value == null ? null : value.toString();
    }

    /**
     * The member names a body carries, in the order it carries them.
     *
     * @param body the parsed body
     * @return the member names
     */
    private static List<String> memberNamesOf(final JsonNode body) {
        final List<String> names = new ArrayList<>();
        body.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * Reads the balance the account row currently holds.
     *
     * @param accountId the account to read
     * @return the stored balance
     */
    private BigDecimal storedBalanceOf(final String accountId) {
        final Optional<Account> stored = this.accounts.findById(accountId);
        assertThat(stored).as("the owned account row must still exist after the turn").isPresent();
        return stored.orElseThrow().getAcctCurrBal();
    }

    /**
     * Reads the single transaction row a completed payment posted.
     *
     * @return the posted row
     */
    private Transaction onlyPostedTransaction() {
        final List<Transaction> posted = this.transactions.findAll();
        assertThat(posted)
                .as("one confirmed payment posts exactly one transaction, whatever the legacy screen "
                        + "sent twice")
                .hasSize(1);
        return posted.getFirst();
    }

    /**
     * The successful-payment text, composed from the two separately declared halves exactly as the source
     * composes it.
     *
     * <p>Composed here, at the point of assertion, and never held as a joined constant: the join is what
     * produces the two consecutive spaces, and a constant that already carried them could be tidied
     * without any assertion noticing.
     *
     * @param transactionId the identifier the payment posted
     * @return the expected text
     */
    private static String successTextFor(final String transactionId) {
        return PAYMENT_SUCCESSFUL_PREFIX + YOUR_TRANSACTION_ID_IS + transactionId
                + SENTENCE_TERMINATOR;
    }

    /**
     * The expected form of a text carried in the outbound message field: the text exactly as composed.
     *
     * <p><strong>Bounded, not padded.</strong> {@link #OUTBOUND_MESSAGE_WIDTH} is a real property of the
     * field and this method still enforces it - a text wider than the field could not be carried whole and
     * fails here rather than silently - but the field's right-fill is not part of the value the contract
     * publishes. A message is composed rather than read from a record: the source moves a literal into a
     * work field wider than the literal, so the value is the literal.
     *
     * <p>This method space-filled until {@code docs/decision-log.md} DL-356, and the expectation it built
     * was met, which is exactly what made the inconsistency invisible: eleven of this surface's other
     * message-bearing fields emitted the literal bare, and a client comparing message text by equality had
     * to special-case this endpoint for trailing whitespace alone.
     *
     * <p>Nothing here trims the <em>received</em> value, which is the property the previous form was
     * written to protect and which is preserved: a received text that had lost or gained a trailing
     * character still differs from this expectation.
     *
     * @param text the text the source composes
     * @return the same text, unchanged
     * @throws IllegalArgumentException if the text is wider than the field, which would mean the width
     *     restated here no longer matches the field
     */
    private static String atOutboundWidth(final String text) {
        if (text.length() > OUTBOUND_MESSAGE_WIDTH) {
            throw new IllegalArgumentException("a screen message cannot exceed the "
                    + OUTBOUND_MESSAGE_WIDTH + " characters its outbound field declares, but this one "
                    + "needs " + text.length());
        }
        return text;
    }

    // ===============================================================================================
    // 1 - THE REQUEST CONTRACT
    // ===============================================================================================

    @Nested
    @DisplayName("Request contract - no balance, no amount")
    class RequestContractCarriesNoBalanceAndNoAmount {

        /** Creates the group. */
        RequestContractCarriesNoBalanceAndNoAmount() {
        }

        @Test
        @DisplayName("the request declares exactly four components, and not one of them is a balance, "
                + "an amount or a decimal of any kind")
        void requestDeclaresFourComponentsAndNoMonetaryOne() {
            // The symbolic map declares the balance field on its input side because the map has full
            // input and output parity, but the program only ever WRITES it - lines 193 and 194 move the
            // account's own figure onto it. Accepting it from a caller would let the caller dictate the
            // amount paid, which the legacy never permits.
            final JsonNode rendered = JSON.valueToTree(
                    new BillPaymentRequest("00000000001", "Y", KeyAction.ENTER, null));

            assertThat(memberNamesOf(rendered))
                    .as("the transmitted screen is an account identifier, a confirmation character, the "
                            + "attention key and the echoed navigation state - and nothing else")
                    .containsExactly("accountId", "confirm", "keyAction", "navigationContext");
            for (final String member : memberNamesOf(rendered)) {
                final String folded = member.toLowerCase(Locale.ROOT);
                assertThat(folded)
                        .as("no request component may name a balance, an amount or a partial payment")
                        .doesNotContain("balance")
                        .doesNotContain("amount")
                        .doesNotContain("partial");
            }
        }

        @Test
        @DisplayName("a payload that tries to dictate the amount is ignored: the balance settled is the "
                + "stored one, and the posted amount is the stored one")
        void payloadCannotDictateTheAmount() throws Exception {
            // Two members the contract does not declare, sent deliberately. The shared configuration does
            // not fail on an unknown property, so they are discarded rather than refused - and the point
            // of the method is that discarding them changes nothing about what is paid.
            final Map<String, Object> body = payload(OWNED_ACCOUNT_WITH_BALANCE, "Y", KeyAction.ENTER,
                    reEntryContext());
            body.put("currentBalance", "1.00");
            body.put("tranAmt", "1.00");

            final JsonNode response = bodyOf(submit(body, session()));

            assertThat(response.get("paymentAccepted").asBoolean())
                    .as("the turn settles, because the two undeclared members are discarded rather than "
                            + "refused")
                    .isTrue();
            assertThat(rawOf(response, "currentBalance"))
                    .as("the displayed balance is the account's own pre-payment figure, never the "
                            + "caller's")
                    .isEqualTo(OWNED_OPENING_BALANCE.toPlainString());
            assertThat(onlyPostedTransaction().getTranAmt())
                    .as("the posted amount is the whole stored balance, never the caller's figure")
                    .isEqualByComparingTo(OWNED_OPENING_BALANCE);
        }
    }

    // ===============================================================================================
    // 2 - THE FULL-BALANCE RULE
    // ===============================================================================================

    @Nested
    @DisplayName("Full-balance payment leaves exactly zero")
    class FullBalancePaymentLeavesExactlyZero {

        /** Creates the group. */
        FullBalancePaymentLeavesExactlyZero() {
        }

        @Test
        @DisplayName("the response shows the pre-payment balance while the stored row is left at exactly "
                + "0.00, at the scale its column declares")
        void displayedBalanceIsBeforeTheDeductionAndStoredBalanceIsExactlyZero() throws Exception {
            // Lines 193 and 194 place the balance on the screen BEFORE the payment stage at lines 208 to
            // 235 runs, and line 234 then deducts the whole of it.
            final JsonNode response = reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y");

            assertThat(response.get("currentBalance").decimalValue())
                    .as("the operator sees the balance as it stood before the deduction")
                    .isEqualByComparingTo(OWNED_OPENING_BALANCE);

            final BigDecimal settled = storedBalanceOf(OWNED_ACCOUNT_WITH_BALANCE);
            assertThat(settled)
                    .as("the amount paid is the whole balance, so the row is left at exactly zero")
                    .isEqualByComparingTo(SETTLED_BALANCE);
            assertThat(settled.scale())
                    .as("the balance column stores two decimal places and the value keeps that scale")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the balance is transmitted in plain notation through the shared configuration, "
                + "never in scientific form")
        void balanceIsTransmittedInPlainNotation() throws Exception {
            // No serializer is registered here and no Jackson setting is overridden: the plain rendering
            // is the shared configuration's, which is the one a client actually receives.
            final JsonNode response = reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y");

            final String transmitted = rawOf(response, "currentBalance");
            assertThat(transmitted)
                    .as("the figure crosses at its own scale, with no exponent and no quotation")
                    .isEqualTo(OWNED_OPENING_BALANCE.toPlainString())
                    .doesNotContain("E")
                    .doesNotContain("e")
                    .doesNotContain("\"");
        }

        @Test
        @DisplayName("there is no partial payment: no response component offers one, and the amount "
                + "posted is the entire pre-payment balance")
        void thereIsNoPartialPayment() throws Exception {
            final JsonNode response = reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y");

            for (final String member : memberNamesOf(response)) {
                final String folded = member.toLowerCase(Locale.ROOT);
                assertThat(folded)
                        .as("nothing in the reply implies a partial or remaining amount, because the "
                                + "transaction has no such capability")
                        .doesNotContain("partial")
                        .doesNotContain("remaining")
                        .doesNotContain("outstanding");
            }
            assertThat(onlyPostedTransaction().getTranAmt())
                    .as("one payment, one amount, and that amount is the whole balance")
                    .isEqualByComparingTo(OWNED_OPENING_BALANCE);
            assertThat(storedBalanceOf(OWNED_ACCOUNT_WITH_BALANCE))
                    .as("nothing is left behind to be paid a second time")
                    .isEqualByComparingTo(SETTLED_BALANCE);
        }

        @Test
        @DisplayName("the screen's fourteen-character balance display width never reaches the "
                + "transmitted decimal as a scale, a precision or a bound")
        void screenDisplayWidthDoesNotLeakOntoTheDecimal() throws Exception {
            // The edited screen field is a sign, ten zero-filled integer digits, a point and two fraction
            // digits - terminal geometry, and not the shape of the record field. Publishing it would put a
            // presentation decision into a machine contract.
            final JsonNode response = reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y");

            final String transmitted = rawOf(response, "currentBalance");
            assertThat(transmitted)
                    .as("the transmitted figure is neither sign-prefixed nor zero-filled to the display "
                            + "width")
                    .isEqualTo(OWNED_OPENING_BALANCE.toPlainString())
                    .doesNotStartWith("+")
                    .doesNotStartWith("0");
            assertThat(response.get("currentBalance").decimalValue().scale())
                    .as("the scale is the record field's two, not the display field's fourteen")
                    .isEqualTo(2);
        }
    }

    // ===============================================================================================
    // 3 - THE NON-POSITIVE-BALANCE REJECTION
    // ===============================================================================================

    @Nested
    @DisplayName("Nothing-to-pay requires both conditions")
    class NothingToPayRequiresBothConditions {

        /** Creates the group. */
        NothingToPayRequiresBothConditions() {
        }

        @Test
        @DisplayName("a balance of exactly 0.00 with an account identifier supplied is refused, and the "
                + "inclusive edge is what refuses it")
        void zeroBalanceWithSuppliedIdentifierIsRefused() throws Exception {
            // Lines 198 and 199: the test is <= zero, so zero itself is inside the rejection.
            final JsonNode response = reEntry(OWNED_ACCOUNT_ZERO_BALANCE, "Y");

            assertThat(textOf(response, "errorMessage")).isEqualTo(atOutboundWidth(NOTHING_TO_PAY));
            assertThat(response.get("generalError").asBoolean()).isTrue();
            assertThat(response.get("paymentAccepted").asBoolean()).isFalse();
            assertThat(textOf(response, "focusScreenFieldId")).isEqualTo(FIELD_ACCOUNT_ID);
            assertThat(transactionMasterIsEmpty()).as("a refused turn posts nothing").isTrue();
        }

        @Test
        @DisplayName("a negative balance with an account identifier supplied is refused by the same rule")
        void negativeBalanceWithSuppliedIdentifierIsRefused() throws Exception {
            final JsonNode response = reEntry(OWNED_ACCOUNT_NEGATIVE_BALANCE, "Y");

            assertThat(textOf(response, "errorMessage")).isEqualTo(atOutboundWidth(NOTHING_TO_PAY));
            assertThat(storedBalanceOf(OWNED_ACCOUNT_NEGATIVE_BALANCE))
                    .as("a refused turn changes no balance")
                    .isEqualByComparingTo(NEGATIVE_BALANCE);
            assertThat(transactionMasterIsEmpty()).as("a refused turn posts nothing").isTrue();
        }

        @Test
        @DisplayName("a blank account identifier answers the emptiness text instead, so the rejection "
                + "genuinely needs both conditions and not just the balance")
        void blankIdentifierAnswersTheEmptinessTextInsteadOfNothingToPay() throws Exception {
            // Lines 158 to 167 fault the empty field first, and the rejection at lines 198 and 199 also
            // requires the field to be supplied. Both facts point the same way: the emptiness text wins.
            final JsonNode response = reEntry(null, "Y");

            assertThat(textOf(response, "errorMessage"))
                    .as("the first refusal in the ordered cascade is the text the operator saw")
                    .isEqualTo(atOutboundWidth(ACCT_ID_CAN_NOT_BE_EMPTY))
                    .isNotEqualTo(atOutboundWidth(NOTHING_TO_PAY));
            assertThat(textOf(response, "focusScreenFieldId")).isEqualTo(FIELD_ACCOUNT_ID);
        }

        @Test
        @DisplayName("a positive balance is accepted by the same rule, which is what makes the two "
                + "refusals above about the balance rather than about the screen")
        void positiveBalanceIsAccepted() throws Exception {
            final JsonNode response = reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y");

            assertThat(textOf(response, "errorMessage"))
                    .as("a positive balance reaches the payment stage rather than the rejection")
                    .isNotEqualTo(atOutboundWidth(NOTHING_TO_PAY));
            assertThat(response.get("paymentAccepted").asBoolean()).isTrue();
        }

        /**
         * Reports whether the transaction master holds no rows at all.
         *
         * @return {@code true} when nothing was posted
         */
        private boolean transactionMasterIsEmpty() {
            return BillPaymentControllerIT.this.transactions.count() == 0L;
        }
    }

    // ===============================================================================================
    // 4 - MESSAGE FIDELITY
    // ===============================================================================================

    @Nested
    @DisplayName("Message fidelity including abbreviations and spacing")
    class MessageFidelityIncludingAbbreviationsAndSpacing {

        /** Creates the group. */
        MessageFidelityIncludingAbbreviationsAndSpacing() {
        }

        @Test
        @DisplayName("the successful-payment text is composed from two separately declared halves and "
                + "therefore carries two consecutive spaces")
        void successTextCarriesTwoConsecutiveSpaces() throws Exception {
            // Lines 527 to 531: the first half ends with a space and the second begins with one, so the
            // assembled sentence has two after the full stop. Never trimmed, never collapsed.
            final JsonNode response = reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y");

            final String expected = successTextFor(FIRST_TRANSACTION_ID);
            assertThat(expected)
                    .as("the composition itself is what produces the double space")
                    .contains("  ");
            assertThat(textOf(response, "errorMessage"))
                    .as("the operator-visible sentence crosses byte for byte, double space included")
                    .isEqualTo(atOutboundWidth(expected))
                    .contains(PAYMENT_SUCCESSFUL_PREFIX + YOUR_TRANSACTION_ID_IS);
        }

        @Test
        @DisplayName("the spelled-out identifier fragment this screen composes is a different text from "
                + "the abbreviated one the transaction-add screen composes")
        void spelledOutFragmentDiffersFromTheAbbreviatedOne() throws Exception {
            final JsonNode response = reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y");

            assertThat(YOUR_TRANSACTION_ID_IS)
                    .as("two screens, two texts; they must never share one constant")
                    .isNotEqualTo(ABBREVIATED_TRANSACTION_ID_FRAGMENT);
            assertThat(textOf(response, "errorMessage"))
                    .as("this screen spells the noun out and never abbreviates it")
                    .contains(YOUR_TRANSACTION_ID_IS)
                    .doesNotContain(ABBREVIATED_TRANSACTION_ID_FRAGMENT);
        }

        @Test
        @DisplayName("the same program abbreviates the field name in one refusal and spells it out in "
                + "another, and both texts are observed in the same run")
        void abbreviationAsymmetryIsPreserved() throws Exception {
            // Line 161 abbreviates; lines 361, 392 and 425 spell it out. Never unified.
            final String abbreviated = textOf(reEntry(null, "Y"), "errorMessage");
            final String spelledOut = textOf(reEntry(ABSENT_ACCOUNT, "Y"), "errorMessage");

            assertThat(abbreviated)
                    .isEqualTo(atOutboundWidth(ACCT_ID_CAN_NOT_BE_EMPTY))
                    .startsWith("Acct ID");
            assertThat(spelledOut)
                    .isEqualTo(atOutboundWidth(ACCOUNT_ID_NOT_FOUND))
                    .startsWith("Account ID");
            assertThat(abbreviated)
                    .as("the asymmetry between the two refusals is contract, not a defect to correct")
                    .isNotEqualTo(spelledOut);
        }

        @Test
        @DisplayName("the unacceptable-confirmation refusal names the two accepted answers exactly as "
                + "the screen named them")
        void unacceptableConfirmationRefusalIsByteExact() throws Exception {
            final JsonNode response = reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Q");

            assertThat(textOf(response, "errorMessage"))
                    .isEqualTo(atOutboundWidth(INVALID_CONFIRMATION_VALUE));
        }

        @Test
        @DisplayName("the prompt a turn without a confirmation receives is byte exact")
        void confirmationPromptIsByteExact() throws Exception {
            final JsonNode response = reEntry(OWNED_ACCOUNT_WITH_BALANCE, null);

            assertThat(textOf(response, "errorMessage"))
                    .isEqualTo(atOutboundWidth(CONFIRM_BILL_PAYMENT));
        }

        @Test
        @DisplayName("the insert catch-all keeps its two-word mixed-case wording, observed from a turn "
                + "the source's own unconditional continuation actually reaches")
        void insertCatchAllKeepsItsMixedCaseWording() throws Exception {
            // The payment stage at lines 211 to 235 tests no flag between its steps, so a cross-reference
            // read that found nothing does NOT stop the record from being assembled or the insert from
            // being attempted. The assembled record carries no card number, the insert cannot be made,
            // and the catch-all at lines 540 to 546 writes the last message the operator sees - which is
            // why this text is reachable at all.
            final JsonNode response = reEntry(OWNED_ACCOUNT_WITHOUT_CROSS_REFERENCE, "Y");

            assertThat(textOf(response, "errorMessage"))
                    .as("two words, mixed case, lower-case second initial - preserved exactly")
                    .isEqualTo(atOutboundWidth(UNABLE_TO_ADD_BILL_PAY_TRANSACTION));
            assertThat(response.get("paymentAccepted").asBoolean())
                    .as("nothing was posted, so nothing was settled")
                    .isFalse();
        }

        @Test
        @DisplayName("the four refusals the shipped rules make unreachable over this boundary are still "
                + "published at their exact legacy bytes")
        void unreachableRefusalsAreStillPublishedAtTheirExactBytes() {
            // Four arms of the source cannot be driven from a client against the migrated schema, and that
            // is the schema's doing rather than an omission: the balance column is declared NOT NULL so no
            // stored account can fail the account-read catch-all; a cross-reference row's card number is
            // its primary key so it can never be blank; the browse is a single aggregate read with no
            // not-found or failure response of its own to report; and the minted identifier is the
            // maximum plus one, which the allocation lock and the bounded re-mint make impossible to find
            // already taken without a second concurrent writer.
            //
            // Their texts are still external contract, so they are asserted where they can be: the
            // literals restated at the head of this class are compared against the constants the response
            // contract publishes. The comparison is between two independent statements of the same
            // contract and is the only honest assertion available - fabricating a path to reach them
            // would test a program this module does not ship.
            assertThat(BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_ACCOUNT)
                    .isEqualTo(UNABLE_TO_LOOKUP_ACCOUNT);
            assertThat(BillPaymentResponse.MSG_UNABLE_TO_UPDATE_ACCOUNT)
                    .as("the verb keeps its capital")
                    .isEqualTo(UNABLE_TO_UPDATE_ACCOUNT);
            assertThat(BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_XREF_AIX)
                    .isEqualTo(UNABLE_TO_LOOKUP_XREF_AIX);
            assertThat(BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_TRANSACTION)
                    .isEqualTo(UNABLE_TO_LOOKUP_TRANSACTION);
            assertThat(BillPaymentResponse.MSG_TRANSACTION_ID_NOT_FOUND)
                    .isEqualTo(TRANSACTION_ID_NOT_FOUND);
            assertThat(BillPaymentResponse.MSG_TRAN_ID_ALREADY_EXIST)
                    .as("the noun stays abbreviated and the verb stays singular")
                    .isEqualTo(TRAN_ID_ALREADY_EXIST)
                    .endsWith("exist...")
                    .doesNotContain("exists");
        }
    }

    // ===============================================================================================
    // 5 - IDENTIFIER GENERATION
    // ===============================================================================================

    @Nested
    @DisplayName("Id generation - first is 0000000000000001")
    class IdGenerationFirstIsSixteenCharactersOfZeroFilledOne {

        /** Creates the group. */
        IdGenerationFirstIsSixteenCharactersOfZeroFilledOne() {
        }

        @Test
        @DisplayName("the first identifier issued against the empty transaction master is the "
                + "sixteen-character zero-filled one, and not the digit one")
        void firstIdentifierIsSixteenCharactersOfZeroFilledOne() throws Exception {
            // The reference seed loads nine tables and leaves the transaction master empty, and the
            // backward read's end-of-file arm at line 488 seeds zero, so the first identifier is one -
            // moved back into a sixteen-character key, which zero-fills it.
            assertThat(BillPaymentControllerIT.this.transactions.count())
                    .as("the identifier rule is a read of this table, so the premise is established "
                            + "rather than assumed")
                    .isZero();

            final JsonNode response = reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y");

            assertThat(textOf(response, "newTransactionId"))
                    .as("sixteen characters, leading zeros intact")
                    .isEqualTo(FIRST_TRANSACTION_ID)
                    .hasSize(TRANSACTION_ID_WIDTH)
                    .isNotEqualTo("1");
            assertThat(onlyPostedTransaction().getTranId()).isEqualTo(FIRST_TRANSACTION_ID);
        }

        @Test
        @DisplayName("two consecutive payments carry consecutive identifiers, because the second one "
                + "reads the first as its maximum")
        void twoPaymentsCarryConsecutiveIdentifiers() throws Exception {
            final JsonNode first = reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y");
            final JsonNode second = reEntry(OWNED_ACCOUNT_SECOND_PAYMENT, "Y");

            assertThat(textOf(first, "newTransactionId")).isEqualTo(FIRST_TRANSACTION_ID);
            assertThat(textOf(second, "newTransactionId")).isEqualTo(SECOND_TRANSACTION_ID);
            assertThat(BillPaymentControllerIT.this.transactions.count()).isEqualTo(2L);
        }

        @Test
        @DisplayName("the rule reuses a gap, which is what proves it is the highest key plus one and not "
                + "a sequence")
        void theRuleReusesAGapAndIsThereforeNotASequence() throws Exception {
            // A sequence never re-issues a value it has handed out, so it would answer the second
            // identifier here. The legacy rule always reuses the gap, and the first rollback after an
            // identifier is consumed guarantees a gap - after which a sequence would diverge from the
            // legacy numbering permanently. The second payment is made against a different owned account
            // so that no row this method already settled has to be rewritten to set the scene.
            final JsonNode first = reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y");
            assertThat(textOf(first, "newTransactionId")).isEqualTo(FIRST_TRANSACTION_ID);

            BillPaymentControllerIT.this.transactions.deleteAll();

            final JsonNode again = reEntry(OWNED_ACCOUNT_SECOND_PAYMENT, "Y");
            assertThat(textOf(again, "newTransactionId"))
                    .as("the vacated identifier is minted again, which no sequence and no identity "
                            + "column would do")
                    .isEqualTo(FIRST_TRANSACTION_ID);
        }

        @Test
        @DisplayName("every identifier crosses the boundary as bounded text, so an eleven-digit account "
                + "identifier keeps its leading zeros on the way back")
        void identifiersCrossAsTextAndKeepLeadingZeros() throws Exception {
            // A delivered account, submitted without a confirmation: the turn reads the row, displays the
            // balance and prompts, so it mutates nothing at all and the echoed field can be read.
            final JsonNode response = reEntry("00000000001", null);

            assertThat(textOf(response, "accountId"))
                    .as("eleven characters, every leading zero intact - a numeric type would have lost "
                            + "them")
                    .isEqualTo("00000000001")
                    .hasSize(ACCOUNT_ID_WIDTH);
            assertThat(response.get("accountId").isTextual())
                    .as("the identifier is transmitted as text and never as a number")
                    .isTrue();
            assertThat(textOf(response, "errorMessage"))
                    .isEqualTo(atOutboundWidth(CONFIRM_BILL_PAYMENT));
        }

        @Test
        @DisplayName("the posted identifier is transmitted as text, and a turn that posted none reports "
                + "no identifier at all")
        void postedIdentifierIsTextAndIsAbsentWhenNothingWasPosted() throws Exception {
            final JsonNode settled = reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y");
            assertThat(settled.get("newTransactionId").isTextual()).isTrue();

            final JsonNode refused = reEntry(OWNED_ACCOUNT_ZERO_BALANCE, "Y");
            assertThat(textOf(refused, "newTransactionId"))
                    .as("a refused payment must never look like a completed one")
                    .isNull();
        }
    }

    // ===============================================================================================
    // 6 - THE SYNTHESIZED TRANSACTION
    // ===============================================================================================

    @Nested
    @DisplayName("Synthesized transaction shape and equal timestamps")
    class SynthesizedTransactionShapeAndEqualTimestamps {

        /** Creates the group. */
        SynthesizedTransactionShapeAndEqualTimestamps() {
        }

        @Test
        @DisplayName("the stored row carries the eleven fixed values the payment stamps, with the source "
                + "code at its padded ten-character width")
        void storedRowCarriesTheFixedValuesAtTheirDeclaredWidths() throws Exception {
            // Lines 219 to 229. Read back through the repository, so the assertion is about the row the
            // schema accepted rather than about the record the service assembled.
            reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y");
            final Transaction posted = onlyPostedTransaction();

            assertThat(posted.getTranTypeCd()).isEqualTo(PAYMENT_TRANSACTION_TYPE);
            assertThat(posted.getTranCatCd())
                    .as("a bounded four-character string, never the integer two")
                    .isEqualTo(PAYMENT_TRANSACTION_CATEGORY);
            assertThat(posted.getTranSource())
                    .as("the eight-character literal in its ten-character field, trailing spaces "
                            + "included and never trimmed")
                    .isEqualTo(PAYMENT_TRANSACTION_SOURCE)
                    .hasSize(PAYMENT_TRANSACTION_SOURCE_WIDTH);
            assertThat(posted.getTranDesc()).isEqualTo(PAYMENT_TRANSACTION_DESCRIPTION);
            assertThat(posted.getMerchantId()).isEqualTo(PAYMENT_MERCHANT_ID);
            assertThat(posted.getMerchantName()).isEqualTo(PAYMENT_MERCHANT_NAME);
            assertThat(posted.getMerchantCity()).isEqualTo(PAYMENT_MERCHANT_CITY);
            assertThat(posted.getMerchantZip()).isEqualTo(PAYMENT_MERCHANT_ZIP);
        }

        @Test
        @DisplayName("the amount is the account's whole pre-payment balance, at the scale its column "
                + "declares")
        void amountIsTheWholePrePaymentBalance() throws Exception {
            reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y");
            final Transaction posted = onlyPostedTransaction();

            assertThat(posted.getTranAmt()).isEqualByComparingTo(OWNED_OPENING_BALANCE);
            assertThat(posted.getTranAmt().scale())
                    .as("two decimal places, the amount column's own scale")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the card number is the one the cross-reference resolves the account to, and not "
                + "one the caller supplied")
        void cardNumberIsResolvedThroughTheCrossReference() throws Exception {
            // Line 225 moves the cross-referenced card number into the record; the caller never sends one.
            reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y");

            assertThat(onlyPostedTransaction().getTranCardNum()).isEqualTo(OWNED_CARD_NUMBER);
        }

        @Test
        @DisplayName("the origination and the processing timestamp are the same value, because one move "
                + "has two receiving fields")
        void bothTimestampsCarryTheSameValue() throws Exception {
            // Lines 230 to 232: the timestamp is built once and moved into both fields.
            reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y");
            final Transaction posted = onlyPostedTransaction();

            assertThat(posted.getTranOrigTs())
                    .as("the two stamps are equal, not merely close")
                    .isEqualTo(posted.getTranProcTs());
        }

        @Test
        @DisplayName("both timestamps carry the online twenty-six character form of the pinned instant, "
                + "which is a different shape from the batch tier's")
        void timestampsCarryTheOnlineFormOfThePinnedInstant() throws Exception {
            reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y");
            final Transaction posted = onlyPostedTransaction();

            assertThat(posted.getTranOrigTs())
                    .as("the graph reads the pinned clock, so this is an exact value and not a window")
                    .isEqualTo(PINNED_ONLINE_TIMESTAMP)
                    .hasSize(TestDataFactory.TIMESTAMP_TEXT_WIDTH);
            assertThat(PINNED_ONLINE_TIMESTAMP)
                    .as("the online and the batch twenty-six character forms are different shapes and "
                            + "must never share a constant or a formatter")
                    .isNotEqualTo(PINNED_BATCH_TIMESTAMP);
            assertThat(PINNED_BATCH_TIMESTAMP).hasSize(TestDataFactory.TIMESTAMP_TEXT_WIDTH);
        }
    }

    // ===============================================================================================
    // 7 - THE CONFIRMATION ARMS
    // ===============================================================================================

    @Nested
    @DisplayName("Confirmation arms")
    class ConfirmationArms {

        /** Creates the group. */
        ConfirmationArms() {
        }

        @Test
        @DisplayName("the upper-case affirmative settles the balance")
        void upperCaseAffirmativeSettles() throws Exception {
            // Lines 174 and 175.
            final JsonNode response = reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y");

            assertThat(response.get("paymentAccepted").asBoolean()).isTrue();
            assertThat(storedBalanceOf(OWNED_ACCOUNT_WITH_BALANCE))
                    .isEqualByComparingTo(SETTLED_BALANCE);
        }

        @Test
        @DisplayName("the lower-case affirmative settles the balance too, because the evaluation names "
                + "both cases explicitly")
        void lowerCaseAffirmativeSettles() throws Exception {
            // Lines 174 and 175 name both letter cases, so nothing case-folds the character.
            final JsonNode response = reEntry(OWNED_ACCOUNT_WITH_BALANCE, "y");

            assertThat(response.get("paymentAccepted").asBoolean()).isTrue();
            assertThat(storedBalanceOf(OWNED_ACCOUNT_WITH_BALANCE))
                    .isEqualByComparingTo(SETTLED_BALANCE);
        }

        @Test
        @DisplayName("an absent confirmation reads the account, shows the balance and asks again, "
                + "without settling anything")
        void absentConfirmationPromptsWithoutSettling() throws Exception {
            // Lines 182 to 184 read the account so the balance can be displayed; the confirmation flag is
            // still in its no state, so lines 236 to 240 ask again.
            final JsonNode response = reEntry(OWNED_ACCOUNT_WITH_BALANCE, null);

            assertThat(textOf(response, "errorMessage"))
                    .isEqualTo(atOutboundWidth(CONFIRM_BILL_PAYMENT));
            assertThat(response.get("generalError").asBoolean())
                    .as("asking again is not an error: the source raises no flag on this arm")
                    .isFalse();
            assertThat(textOf(response, "focusScreenFieldId")).isEqualTo(FIELD_CONFIRM);
            assertThat(response.get("currentBalance").decimalValue())
                    .as("the balance is displayed even though nothing is paid")
                    .isEqualByComparingTo(OWNED_OPENING_BALANCE);
            assertThat(storedBalanceOf(OWNED_ACCOUNT_WITH_BALANCE))
                    .as("nothing is settled")
                    .isEqualByComparingTo(OWNED_OPENING_BALANCE);
            assertThat(BillPaymentControllerIT.this.transactions.count()).isZero();
        }

        @Test
        @DisplayName("the upper-case negative clears the screen, raises the flag and leaves no message "
                + "at all - it does NOT ask again")
        void upperCaseNegativeClearsTheScreenAndLeavesNoMessage() throws Exception {
            // Lines 178 to 181, read in order: the screen is cleared FIRST - which blanks the message
            // work field at line 565 - and only THEN is the flag raised. The raised flag is what makes the
            // test at line 208 fail, so the prompt at lines 236 to 240 is never reached. A specification
            // that expected the prompt here would be describing a program this module does not ship.
            final JsonNode response = reEntry(OWNED_ACCOUNT_WITH_BALANCE, "N");

            assertThat(textOf(response, "errorMessage"))
                    .as("the cleared screen carries no text of any kind on this arm")
                    .isBlank();
            assertThat(textOf(response, "errorMessage"))
                    .isNotEqualTo(atOutboundWidth(CONFIRM_BILL_PAYMENT));
            assertThat(response.get("generalError").asBoolean()).isTrue();
            assertThat(response.get("paymentAccepted").asBoolean()).isFalse();
            assertThat(storedBalanceOf(OWNED_ACCOUNT_WITH_BALANCE))
                    .isEqualByComparingTo(OWNED_OPENING_BALANCE);
            assertThat(BillPaymentControllerIT.this.transactions.count()).isZero();
        }

        @Test
        @DisplayName("the lower-case negative reaches the same arm")
        void lowerCaseNegativeReachesTheSameArm() throws Exception {
            final JsonNode response = reEntry(OWNED_ACCOUNT_WITH_BALANCE, "n");

            assertThat(textOf(response, "errorMessage")).isBlank();
            assertThat(response.get("generalError").asBoolean()).isTrue();
            assertThat(BillPaymentControllerIT.this.transactions.count()).isZero();
        }

        @Test
        @DisplayName("a character that is neither answer is quoted back through the refusal that names "
                + "the two acceptable values")
        void anyOtherCharacterIsRefusedByName() throws Exception {
            // Lines 185 to 190. The character is carried as a one-character string rather than folded
            // onto a two-valued flag, which is exactly what lets this third outcome exist at all.
            final JsonNode response = reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Q");

            assertThat(textOf(response, "errorMessage"))
                    .isEqualTo(atOutboundWidth(INVALID_CONFIRMATION_VALUE));
            assertThat(response.get("generalError").asBoolean()).isTrue();
            assertThat(textOf(response, "focusScreenFieldId")).isEqualTo(FIELD_CONFIRM);
            assertThat(textOf(response, "confirm"))
                    .as("the operator's own keystroke survives intact so the screen can show it back")
                    .isEqualTo("Q");
            assertThat(BillPaymentControllerIT.this.transactions.count()).isZero();
        }
    }

    // ===============================================================================================
    // 8 - THE CLEARED-INPUT SUCCESS RESPONSE
    // ===============================================================================================

    @Nested
    @DisplayName("Cleared-input success response")
    class ClearedInputSuccessResponse {

        /** Creates the group. */
        ClearedInputSuccessResponse() {
        }

        @Test
        @DisplayName("a settled turn returns its echoed screen fields blank, which is the legacy reset "
                + "and not a lost value")
        void settledTurnReturnsBlankEchoedFields() throws Exception {
            // Line 524 performs the reset paragraph, which blanks all three screen fields at lines 561 to
            // 565 before the success text is composed. Blank echoed values here are valid and expected.
            final JsonNode response = reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y");

            assertThat(response.get("paymentAccepted").asBoolean()).isTrue();
            assertThat(textOf(response, "accountId"))
                    .as("the reset blanks the account field")
                    .isBlank();
            assertThat(textOf(response, "confirm"))
                    .as("the reset blanks the confirmation field")
                    .isBlank();
            assertThat(textOf(response, "errorMessage"))
                    .as("the message field is written after the reset, so it is the one field that "
                            + "carries text")
                    .isEqualTo(atOutboundWidth(successTextFor(FIRST_TRANSACTION_ID)));
        }

        @Test
        @DisplayName("a settled turn yields exactly one response body, although the legacy sends its "
                + "screen twice")
        void settledTurnYieldsExactlyOneResponseBody() throws Exception {
            // DOCUMENTED DIVERGENCE. The source sends the screen twice on this path - once inside the
            // insert paragraph at line 532 and once at line 242 - and a terminal send is idempotent
            // there, so an operator saw one screen either way. Over a request-response transport the turn
            // yields ONE response, assembled from the final state. No attempt is made to emulate a
            // double send: a second body would have no reader and no meaning.
            final MvcResult result = submitReEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y");

            final String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).as("one body, not two").isNotEmpty();
            assertThat(JSON.readTree(body).isObject())
                    .as("a single JSON object, never an array of screens and never two concatenated "
                            + "documents")
                    .isTrue();
            assertThat(JSON.readTree(body).get("paymentAccepted").asBoolean()).isTrue();
            assertThat(onlyPostedTransaction().getTranId())
                    .as("and one posted transaction, whatever the screen count was")
                    .isEqualTo(FIRST_TRANSACTION_ID);
        }

        @Test
        @DisplayName("a settled turn still nominates this screen's own route and its own header, because "
                + "the legacy re-arms rather than transfers")
        void settledTurnReArmsThisScreen() throws Exception {
            final JsonNode response = reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y");

            assertThat(textOf(response, "nextRoute")).isEqualTo(BILL_PAYMENT_ROUTE);
            assertThat(textOf(response, "transactionName")).isEqualTo(BILL_PAYMENT_TRANSACTION);
            assertThat(textOf(response, "programName")).isEqualTo(BILL_PAYMENT_PROGRAM);
        }
    }

    // ===============================================================================================
    // 9 - LEAKAGE AND NEGATIVES
    // ===============================================================================================

    // ===============================================================================================
    // THE PERSISTENCE BOUNDARY OF LINES 233 TO 235, OBSERVED OVER THE REST SURFACE
    // ===============================================================================================

    /**
     * Establishes over the shipped boundary that the transaction insert of line 233 is durable independently
     * of the account rewrite of line 235.
     *
     * <p>{@code app/csd/CARDDEMO.CSD} defines both files {@code RECOVERY(NONE)} with {@code JOURNAL(NO)}, so
     * a record write that completed cannot be undone, and the source performs lines 234 and 235 whether or
     * not the write at 233 succeeded, testing no flag between them. A {@code REWRITE} that fails after a
     * successful {@code WRITE} therefore leaves the transaction stored, the account unsettled, and the
     * operator told the account could not be updated - and the response body has to carry both facts.
     *
     * <p>The failure has no data-driven trigger, because the schema is what makes the rewrite succeed. A spy
     * on the shipped repository is therefore the seam: every other call in the turn reaches the real
     * repository and the real server, and the only altered thing is the one store whose failure is the
     * subject.
     *
     * <p>The <em>concurrency</em> half of this boundary - that two operators post one transaction and one
     * debit, that the loser waits on the row and then reaches the legacy nothing-to-pay message, and that the
     * allocation lock serialises - is established in {@code service/BillPaymentConcurrencyIT}, which runs the
     * shipped service from two threads. It is not duplicated here, because a mock servlet adds nothing to a
     * property of two simultaneous units of work.
     */
    @Nested
    @DisplayName("A refused account rewrite leaves the posted transaction stored")
    class RefusedRewriteLeavesThePostedTransaction {

        /** Creates the group. */
        RefusedRewriteLeavesThePostedTransaction() {
        }

        @Test
        @DisplayName("the response carries the STORED identifier and the update-failure text together, and "
                + "the row is still in the master while the balance is untouched")
        void theResponseCarriesTheStoredIdentifierAndTheUpdateFailureTogether() throws Exception {
            org.mockito.Mockito.doThrow(new IllegalStateException("the rewrite failed"))
                    .when(BillPaymentControllerIT.this.spiedAccounts)
                    .saveAndFlush(org.mockito.ArgumentMatchers.any(Account.class));

            final JsonNode response = reEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y");

            assertThat(textOf(response, "errorMessage"))
                    .as("the rewrite's catch-all arm is the last text written, so it is what the operator "
                            + "sees")
                    .isEqualTo(atOutboundWidth(UNABLE_TO_UPDATE_ACCOUNT));
            assertThat(response.get("generalError").asBoolean()).isTrue();
            assertThat(response.get("paymentAccepted").asBoolean())
                    .as("the account was not settled, so the turn did not complete a payment")
                    .isFalse();
            assertThat(textOf(response, "newTransactionId"))
                    .as("the insert's OWN arm ran first and published the identifier it stored; the "
                            + "operator is told which record exists as well as that the account does not "
                            + "reflect it")
                    .isNotNull();

            final String storedIdentifier = textOf(response, "newTransactionId");
            assertThat(BillPaymentControllerIT.this.transactions.findById(storedIdentifier))
                    .as("THE ROW IS STILL THERE. The insert committed in a unit of its own, and a legacy "
                            + "REWRITE failure over an unrecoverable file cannot undo a WRITE that already "
                            + "happened. A shared unit of work would have discarded it.")
                    .isPresent();
            assertThat(BillPaymentControllerIT.this.transactions.count()).isOne();

            assertThat(BillPaymentControllerIT.this.accounts.findById(OWNED_ACCOUNT_WITH_BALANCE))
                    .as("the balance is exactly as it was, which is why the operator is told the account "
                            + "could not be updated")
                    .isPresent()
                    .get(org.assertj.core.api.InstanceOfAssertFactories.type(Account.class))
                    .extracting(Account::getAcctCurrBal, org.assertj.core.api.Assertions.as(
                            org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL))
                    .isEqualByComparingTo(OWNED_OPENING_BALANCE);
        }
    }

    @Nested
    @DisplayName("Leakage and negatives")
    class LeakageAndNegatives {

        /** Creates the group. */
        LeakageAndNegatives() {
        }

        @Test
        @DisplayName("an account identifier no row carries answers the record-absent refusal")
        void absentAccountAnswersTheRecordAbsentRefusal() throws Exception {
            // Lines 359 to 364.
            final JsonNode response = reEntry(ABSENT_ACCOUNT, "Y");

            assertThat(textOf(response, "errorMessage"))
                    .isEqualTo(atOutboundWidth(ACCOUNT_ID_NOT_FOUND));
            assertThat(response.get("generalError").asBoolean()).isTrue();
            assertThat(response.get("paymentAccepted").asBoolean()).isFalse();
            assertThat(textOf(response, "focusScreenFieldId")).isEqualTo(FIELD_ACCOUNT_ID);
            assertThat(BillPaymentControllerIT.this.transactions.count()).isZero();
        }

        @Test
        @DisplayName("an account with no cross-reference row settles nothing and posts nothing")
        void accountWithoutCrossReferenceSettlesNothing() throws Exception {
            // The cross-reference read at line 211 finds nothing and the sequence carries on, which is the
            // source's own behaviour; the assembled record has no card number, so the insert cannot be
            // made and the catch-all reports it.
            final JsonNode response = reEntry(OWNED_ACCOUNT_WITHOUT_CROSS_REFERENCE, "Y");

            assertThat(response.get("paymentAccepted").asBoolean()).isFalse();
            assertThat(textOf(response, "newTransactionId")).isNull();
            assertThat(BillPaymentControllerIT.this.transactions.count()).isZero();
        }

        @Test
        @DisplayName("a request carrying no session is refused before the boundary is reached, and the "
                + "refusal discloses nothing")
        void unauthenticatedRequestIsRefused() throws Exception {
            final MvcResult result = submit(OWNED_ACCOUNT_WITH_BALANCE, "Y", KeyAction.ENTER,
                    reEntryContext(), null);

            assertThat(result.getResponse().getStatus())
                    .as("the closing rule of the shipped chain requires a verified session on every "
                            + "route but the sign-on one")
                    .isEqualTo(401);
            final String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body)
                    .as("the module's own envelope rather than an empty body or a container page")
                    .isNotEmpty();
            assertThat(textOf(JSON.readTree(body), "message")).isEqualTo(AUTHENTICATION_REQUIRED);
            assertThat(BillPaymentControllerIT.this.transactions.count())
                    .as("a refused request reaches no rule and therefore posts nothing")
                    .isZero();
        }

        @Test
        @DisplayName("no reply leaks a region response code, a file status, a stack trace, an exception "
                + "name, a statement, a path or a screen byte")
        void noReplyLeaksAnyInternalDetail() throws Exception {
            // The source displays its raw response and reason codes on six failure arms - lines 366, 397,
            // 430, 461, 490 and 541 - and those displays become structured log records rather than
            // anything a client can read.
            final List<String> bodies = List.of(
                    bodyTextOf(submitReEntry(OWNED_ACCOUNT_WITH_BALANCE, "Y")),
                    bodyTextOf(submitReEntry(OWNED_ACCOUNT_ZERO_BALANCE, "Y")),
                    bodyTextOf(submitReEntry(OWNED_ACCOUNT_WITHOUT_CROSS_REFERENCE, "Y")),
                    bodyTextOf(submitReEntry(ABSENT_ACCOUNT, "Y")),
                    bodyTextOf(submitReEntry(null, "Y")),
                    bodyTextOf(submitReEntry(OWNED_ACCOUNT_WITH_BALANCE, "Q")));

            for (final String body : bodies) {
                assertThat(body)
                        .as("no region response or reason code, and no raw file status")
                        .doesNotContain("RESP:")
                        .doesNotContain("REAS:")
                        .doesNotContain("DFHRESP")
                        .doesNotContain("EIBRESP");
                assertThat(body)
                        .as("no failure internals of any kind")
                        .doesNotContain("Exception")
                        .doesNotContain("Throwable")
                        .doesNotContain("stackTrace")
                        .doesNotContain("at com.carddemo")
                        .doesNotContain("org.springframework")
                        .doesNotContain("org.hibernate")
                        .doesNotContain("org.postgresql");
                assertThat(body)
                        .as("no statement text, no address and no path")
                        .doesNotContain("SELECT ")
                        .doesNotContain("INSERT ")
                        .doesNotContain("UPDATE ")
                        .doesNotContain("jdbc:")
                        .doesNotContain("/tmp/")
                        .doesNotContain("src/main");
                assertThat(body)
                        .as("no screen byte, no terminal attribute and no job-stream text")
                        .doesNotContain("DFHRED")
                        .doesNotContain("DFHGREEN")
                        .doesNotContain("DFHBMS")
                        .doesNotContain("EXEC PGM")
                        .doesNotContain("//STEP");
            }
        }

        @Test
        @DisplayName("a refusal names the field it is about without ever echoing a card number or a "
                + "customer identifier")
        void refusalNamesTheFieldWithoutEchoingProtectedValues() throws Exception {
            final String body = bodyTextOf(submitReEntry(OWNED_ACCOUNT_ZERO_BALANCE, "Y"));

            assertThat(body)
                    .as("the field the cursor returns to is named, because a client has to place it")
                    .contains(FIELD_ACCOUNT_ID);
            assertThat(body)
                    .as("the card the account resolves to is never published by this screen")
                    .doesNotContain(OWNED_CARD_NUMBER_ZERO)
                    .doesNotContain(DELIVERED_CUSTOMER_ID);
        }

        /**
         * Reads a completed turn's body as text, having asserted the status the contract fixes.
         *
         * @param result the completed request
         * @return the body text
         * @throws Exception if the body cannot be read
         */
        private String bodyTextOf(final MvcResult result) throws Exception {
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        }
    }

    // ===============================================================================================
    // THE GRAPH UNDER TEST
    // ===============================================================================================

    /**
     * The bill-payment surface: the shipped boundary, the shipped filter chain, the shipped transaction
     * and the masters it reads and writes.
     *
     * <p>Assembled explicitly rather than by scanning, which is this module's established practice for a
     * context-booting specification: a scan of the base package would sweep the test tree's own
     * configuration classes into the graph, and an explicit list lets a reader see in one place exactly
     * what took part.
     *
     * <p>The repository layer is enabled by package rather than by naming each interface, because the
     * filter chain needs the credential master to reconcile a presented session against the record it was
     * minted from, and listing four interfaces to reach a fifth reads as though the fifth were optional.
     * Every interface the package holds is a shipped one and none of them is stubbed.
     *
     * <p><strong>Nothing is stubbed.</strong> The transaction, the navigation vocabulary, the shared
     * message catalogue, the unit-of-work boundary, the filter chain, the session provider and every
     * repository are the shipped ones, and the rows come off a real server. What is deliberately absent
     * is every other screen: this specification asserts where a turn is directed, which is a value in the
     * response body, and not what answers at the destination.
     *
     * <p>The clock is the pinned instant the shared base publishes, so the posted transaction's two
     * timestamps and the minted session's validity window mean the same thing on every run.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({BillPaymentController.class, ModuleErrorController.class, ScreenStateAdapter.class,
        GlobalExceptionHandler.class, JsonRefusalBodyRenderer.class, BillPaymentService.class,
        NavigationService.class, MessageCatalogService.class, OnlineTransactionBoundary.class,
        SignOnStateService.class, SecurityConfig.class, JwtTokenProvider.class, WebMvcConfig.class})
    @EnableConfigurationProperties(JwtProperties.class)
    @EnableJpaRepositories(basePackageClasses = AccountRepository.class)
    @EntityScan(basePackageClasses = Account.class)
    static class BillPaymentContext {

        /** Creates the configuration. */
        BillPaymentContext() {
            // Intentionally empty: this slice contributes beans, not state.
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
