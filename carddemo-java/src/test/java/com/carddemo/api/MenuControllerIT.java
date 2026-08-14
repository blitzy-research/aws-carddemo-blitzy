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

import com.carddemo.api.dto.MenuResponse;
import com.carddemo.config.JwtProperties;
import com.carddemo.config.JwtTokenProvider;
import com.carddemo.config.MenuOptionCatalog;
import com.carddemo.config.SecurityConfig;
import com.carddemo.config.WebMvcConfig;
import com.carddemo.domain.UserSecurity;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.ConversationState;
import com.carddemo.service.MenuOptionSource;
import com.carddemo.service.MenuService;
import com.carddemo.service.MessageCatalogService;
import com.carddemo.service.NavigationService;
import com.carddemo.service.SignOnStateService;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.TestDataFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
 * The two menu transactions over the shipped boundary: {@code CM00}, the main menu every signed-on
 * operator reaches, and {@code CA00}, the menu only an administrator reaches.
 *
 * <h2>What this specification is about</h2>
 * Two endpoints, <strong>two option catalogues of deliberately different shape</strong>, and one
 * entitlement gate. Eight groups, each asserting one thing the estate fixes:
 * <ul>
 *   <li><strong>The user catalogue.</strong> Ten populated rows inside storage declared for twelve, in
 *       table order, each with its number, its label and - server-side only - its target program and a
 *       raw one-character user-type code. The surplus two slots are never rendered.</li>
 *   <li><strong>The administrative catalogue.</strong> Four populated rows inside storage declared for
 *       nine, each with three items and <em>no user-type item at all</em>. The surplus five slots are
 *       never rendered and the two shapes are never unified.</li>
 *   <li><strong>Option normalisation.</strong> A two-character right-justified field whose blank
 *       characters become the digit zero before any numeric test runs.</li>
 *   <li><strong>The rejection text</strong>, character for character, on four different rejections and
 *       on both menus.</li>
 *   <li><strong>The suppressed-dispatch divergence</strong>, which is two <em>different</em> texts that
 *       must never be merged into one.</li>
 *   <li><strong>The entitlement gate</strong>, which is one of exactly five administrative
 *       transactions the resource definitions declare.</li>
 *   <li><strong>Title and shared-message fidelity</strong>, at three widths that must never be
 *       confused: forty, fifty and thirty-three.</li>
 *   <li><strong>Navigation state</strong>, echoed as typed values and as bounded text that keeps its
 *       leading zeros.</li>
 * </ul>
 *
 * <h2>Where the expectations come from, and where the shape comes from</h2>
 * Every operator-facing text, label, route token, transaction identifier and role code below is
 * written out as its own literal. An expectation that borrows the constant its subject publishes proves
 * only that the subject agrees with itself, and each of these is an external contract whose bytes are
 * the contract. Routes, verbs, media types, status codes, JSON property names and the administrative
 * prefix are the opposite case and are taken from the shipped boundary and its published contract
 * types, because those are the shape this specification has to speak to rather than claims it makes.
 * Nothing here edits production to suit an expectation.
 *
 * <h2>What the wire deliberately does not carry</h2>
 * The published response projects an option row down to its number and its label. The target program
 * name and the user-type code are <em>not</em> published: the first is a dispatch target and the second
 * an authorization input, and the shipped contract type says so in as many words. Both are still
 * asserted here, against the service-layer catalogue contract {@link MenuOptionSource} that owns them -
 * which is a downward dependency from this package and therefore keeps the layering rule this module
 * asserts elsewhere. The four-item user entry and the three-item administrative entry are read there,
 * at their declared entry lengths of forty-six and forty-five bytes.
 *
 * <h2>Two paths the shipped catalogue cannot reach, and how they are still asserted</h2>
 * No shipped entry names a suppressing program and every shipped user entry carries the standard code,
 * so neither the suppressed-dispatch texts nor the administrator-only refusal can be produced through
 * an endpoint. Both are nonetheless part of the external contract, so both are asserted by composing
 * the shipped transaction over the container's own navigation authority, shared-message catalogue and
 * pinned clock together with a catalogue that answers one lookup differently - an explicit
 * implementation written out below, with no mocking framework and no reflection. The complementary
 * endpoint-level fact, that the shipped catalogue reaches neither text, is asserted directly.
 *
 * <h2>Container ownership, determinism and credentials</h2>
 * The database server belongs to {@link AbstractPostgresIT} and is shared by every integration
 * specification in the module, so this class declares no container, no container annotation, no
 * data-source property source and no context-discarding annotation. Time comes from that class's
 * pinned clock, so nothing here reads a wall clock and no assertion depends on elapsed time,
 * throughput or memory.
 *
 * <p><strong>No credential value enters this file in any form.</strong> A session is obtained the way
 * the sign-on path obtains one once it has already verified a credential: by asking the shipped issuer.
 * The issuer refuses to mint for an identity no record carries, so a session existing at all is
 * evidence the record does. The two identities this specification writes for the undeclared-code and
 * lower-case-code arms store the shared synthetic digest, which accepts nothing, because neither is
 * ever signed on with. No signing secret is written here either: the active profile supplies it.
 *
 * <p>The two written identities live under one reserved eight-character prefix the credential seed
 * never occupies, and are removed after every method, so the ten delivered identities are exactly what
 * every other specification finds.
 *
 * <p>Provenance: {@code app/cbl/COMEN01C.cbl}, {@code app/cbl/COADM01C.cbl}, their option tables
 * {@code app/cpy/COMEN02Y.cpy} and {@code app/cpy/COADM02Y.cpy}, their symbolic maps
 * {@code app/cpy-bms/COMEN01.CPY} and {@code app/cpy-bms/COADM01.CPY}, the title and shared-message
 * copybooks {@code app/cpy/COTTL01Y.cpy} and {@code app/cpy/CSMSG01Y.cpy}, the communication area
 * {@code app/cpy/COCOM01Y.cpy} and the resource definitions {@code app/csd/CARDDEMO.CSD} - all read as
 * read-only reference. Widths, offsets, counts, line numbers, transaction names, program names and
 * contract literals are metadata and contract; no source line of any legacy member is transcribed, and
 * nothing in the legacy tree is read at run time. The two option tables carry <em>different</em>
 * release stamps and no claim of a single estate-wide stamp is made or asserted anywhere below.
 */
@SpringBootTest(classes = MenuControllerIT.MenuContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            // The shared server was migrated to the head of the delivered set by the base class, so a
            // second migration from this context would be redundant work with no new state to apply.
            "spring.flyway.enabled=false",
            // validate, never none and never a generating value: a mapping that had drifted from the
            // migrated schema must fail this specification at refresh rather than be reconciled behind
            // it, and validate cannot emit DDL.
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.batch.job.enabled=false",
            "spring.main.banner-mode=off",
            "management.endpoint.health.validate-group-membership=false",
            "management.tracing.enabled=false",
            // The boundary here is a mock servlet inside this process, so there is no wire for a
            // session to be observed on and the transport requirement is relaxed exactly as the suite
            // profile relaxes it. Nothing else is relaxed: the entitlement gate is the shipped one,
            // which is the whole point of asserting against it.
            "carddemo.security.require-https=false"})
@AutoConfigureMockMvc
@DisplayName("The menu transactions CM00 and CA00 over the shipped boundary")
public class MenuControllerIT extends AbstractPostgresIT {

    // ===============================================================================================
    // THE CONTRACT, RESTATED HERE RATHER THAN IMPORTED FROM ITS SUBJECT
    // ===============================================================================================

    /**
     * The ten user-menu labels in table order, from the populated entries of
     * {@code app/cpy/COMEN02Y.cpy} lines 25 to 84.
     *
     * <p>The eighth is the <em>active</em> label of line 70. The alternative on line 69 is commented
     * out, stays commented out, and is asserted absent by {@link UserCatalogue}.
     */
    private static final List<String> USER_OPTION_LABELS = List.of(
            "Account View",
            "Account Update",
            "Credit Card List",
            "Credit Card View",
            "Credit Card Update",
            "Transaction List",
            "Transaction View",
            "Transaction Add",
            "Transaction Reports",
            "Bill Payment");

    /** The ten target programs those entries name, in the same order. */
    private static final List<String> USER_OPTION_PROGRAMS = List.of(
            "COACTVWC", "COACTUPC", "COCRDLIC", "COCRDSLC", "COCRDUPC",
            "COTRN00C", "COTRN01C", "COTRN02C", "CORPT00C", "COBIL00C");

    /** The ten destinations those entries dispatch to, in the same order. */
    private static final List<String> USER_OPTION_ROUTES = List.of(
            "account-view", "account-update", "card-list", "card-detail", "card-update",
            "transaction-list", "transaction-view", "transaction-add", "report-request",
            "bill-payment");

    /**
     * The four administrative labels in table order, from {@code app/cpy/COADM02Y.cpy} lines 24 to 42.
     */
    private static final List<String> ADMIN_OPTION_LABELS = List.of(
            "User List (Security)",
            "User Add (Security)",
            "User Update (Security)",
            "User Delete (Security)");

    /** The four target programs those entries name, in the same order. */
    private static final List<String> ADMIN_OPTION_PROGRAMS =
            List.of("COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C");

    /** The four destinations those entries dispatch to, in the same order. */
    private static final List<String> ADMIN_OPTION_ROUTES =
            List.of("user-list", "user-add", "user-update", "user-delete");

    /** Populated size of the user table, its count item's own value. */
    private static final int USER_OPTION_COUNT = 10;

    /** Populated size of the administrative table, its count item's own value. */
    private static final int ADMIN_OPTION_COUNT = 4;

    /**
     * Declared capacity of the user table's occurrence clause, which exceeds its population by two.
     * Held only so the two surplus positions can be named in an assertion that they are never rendered.
     */
    private static final int USER_TABLE_CAPACITY = 12;

    /** Declared capacity of the administrative table, which exceeds its population by five. */
    private static final int ADMIN_TABLE_CAPACITY = 9;

    /** Byte length of one user entry: number 2, label 35, program 8, user type 1. */
    private static final int USER_ENTRY_LENGTH = 46;

    /** Byte length of one administrative entry, which carries no user-type item: 2, 35 and 8. */
    private static final int ADMIN_ENTRY_LENGTH = 45;

    /** The one-character code every populated user entry carries. */
    private static final String STANDARD_TYPE_CODE = "U";

    /** The one-character code the administrator-only comparison tests for. */
    private static final String ADMIN_TYPE_CODE = "A";

    /**
     * The rejection text, from line 131 of <em>both</em> programs: thirty-seven characters, a lower-case
     * verb, and three dots with no space in front of them.
     */
    private static final String INVALID_OPTION_TEXT = "Please enter a valid option number...";

    /** Width of {@link #INVALID_OPTION_TEXT}, so the count is asserted rather than eyeballed. */
    private static final int INVALID_OPTION_TEXT_LENGTH = 37;

    /**
     * The administrator-only refusal, from {@code app/cbl/COMEN01C.cbl} line 140.
     *
     * <p>Thirty-three characters, <strong>and the thirty-third is a space</strong>. The trailing space is
     * part of the value the program moves, so it is part of the value asserted, and nothing here trims
     * it. The administrative menu has no counterpart: it applies no user-type gate at all.
     */
    private static final String ADMIN_ONLY_TEXT = "No access - Admin Only option... ";

    /** Width of {@link #ADMIN_ONLY_TEXT}, trailing space included. */
    private static final int ADMIN_ONLY_TEXT_LENGTH = 33;

    /**
     * The <em>main</em> menu's suppressed-dispatch text for option one, from
     * {@code app/cbl/COMEN01C.cbl} lines 159 to 162.
     *
     * <p><strong>There is no space between the option name and the word that follows it, and that is
     * faithful rather than broken.</strong> The program concatenates three operands and the middle one -
     * the option label - is transferred under a space delimiter, so the transfer stops at the label's
     * first blank and contributes no separator of its own. The label of option one is two words, so what
     * the operator saw was its first word butted directly against the suffix. Reproducing the missing
     * space is a faithful-over-idiomatic decision, recorded as such, and "correcting" it here would be a
     * behavioural change to an operator-facing external text.
     */
    private static final String USER_SUPPRESSED_TEXT = "This option Accountis coming soon ...";

    /** Width of {@link #USER_SUPPRESSED_TEXT}: twelve, plus the label's first word, plus eighteen. */
    private static final int USER_SUPPRESSED_TEXT_LENGTH = 37;

    /**
     * The <em>administrative</em> menu's suppressed-dispatch text, from {@code app/cbl/COADM01C.cbl}
     * lines 149 to 152, where the label operand is commented out and contributes nothing.
     *
     * <p>A different value from {@link #USER_SUPPRESSED_TEXT} and never interchangeable with it. The two
     * are deliberately separate constants: one shared constant would erase the divergence that the two
     * source members actually carry.
     */
    private static final String ADMIN_SUPPRESSED_TEXT = "This option is coming soon ...";

    /** Width of {@link #ADMIN_SUPPRESSED_TEXT}: twelve plus eighteen, with no label between them. */
    private static final int ADMIN_SUPPRESSED_TEXT_LENGTH = 30;

    /** The fragment both suppressed-dispatch texts end with: eighteen characters. */
    private static final String SUPPRESSED_TEXT_SUFFIX = "is coming soon ...";

    /** Width of {@link #SUPPRESSED_TEXT_SUFFIX}. */
    private static final int SUPPRESSED_TEXT_SUFFIX_LENGTH = 18;

    /** Prefix of a program name the dispatch guard treats as no program at all. */
    private static final String SUPPRESSING_PROGRAM_PREFIX = "DUMMY";

    /**
     * A suppressing program name at the full declared width of the program-name item, so the guard is
     * exercised on a value the field could actually have held.
     */
    private static final String SUPPRESSING_PROGRAM = "DUMMYPGM";

    /**
     * First screen title, {@code CCDA-TITLE01} at {@code app/cpy/COTTL01Y.cpy} line 19: six leading
     * spaces, twenty-seven characters of text, seven trailing spaces, forty in all.
     */
    private static final String TITLE01_40 = "      AWS Mainframe Modernization       ";

    /**
     * Second screen title, the <em>active</em> value of {@code CCDA-TITLE02} at line 22: fourteen
     * leading spaces, eight characters of text, eighteen trailing spaces, forty in all.
     */
    private static final String TITLE02_40 = "              CardDemo                  ";

    /**
     * The alternative second title commented out at line 21 of the same copybook.
     *
     * <p>Held solely so it can be asserted <strong>absent</strong>. It is inactive in the source and is
     * inactive in the migration; activating it would be a change to a screen an operator reads.
     */
    private static final String INACTIVE_TITLE02_ALTERNATIVE = "  Credit Card Demo Application (CCDA)   ";

    /**
     * The courtesy title {@code CCDA-THANK-YOU} at line 24: thirty-nine characters of text and one
     * trailing space, forty in all.
     *
     * <p>A third and entirely distinct value. It names a different product token and occupies a
     * different width from the fifty-character courtesy message, so the two are never substituted for
     * one another and are asserted to differ.
     */
    private static final String COURTESY_TITLE_40 = "Thank you for using CCDA application... ";

    /** Declared width of all three title items. */
    private static final int TITLE_WIDTH = 40;

    /**
     * The shared courtesy message {@code CCDA-MSG-THANK-YOU} at {@code app/cpy/CSMSG01Y.cpy} line 19,
     * at its full stored width: forty-three characters of text followed by exactly seven spaces.
     */
    private static final String THANK_YOU_MESSAGE_50 = "Thank you for using CardDemo application...       ";

    /**
     * The shared unmapped-key message {@code CCDA-MSG-INVALID-KEY} at line 21, at its full stored
     * width: forty characters of text followed by exactly ten spaces.
     */
    private static final String INVALID_KEY_MESSAGE_50 = "Invalid key pressed. Please see below...          ";

    /** Declared width of both shared messages. */
    private static final int COMMON_MESSAGE_WIDTH = 50;

    /** Transaction the main menu is registered under in the resource definitions. */
    private static final String USER_MENU_TRANSACTION = "CM00";

    /** Program that transaction is bound to. */
    private static final String USER_MENU_PROGRAM = "COMEN01C";

    /** Transaction the administrative menu is registered under. */
    private static final String ADMIN_MENU_TRANSACTION = "CA00";

    /** Program that transaction is bound to. */
    private static final String ADMIN_MENU_PROGRAM = "COADM01C";

    /** Wire value of the main menu's own destination, which a re-presented screen nominates. */
    private static final String USER_MENU_ROUTE = "user-menu";

    /** Wire value of the administrative menu's own destination. */
    private static final String ADMIN_MENU_ROUTE = "admin-menu";

    /** Wire value of the destination a turn carrying no prior state is directed to. */
    private static final String SIGN_ON_ROUTE = "sign-on";

    /** Symbolic name of the option field input focus returns to. */
    private static final String OPTION_FIELD_ID = "OPTION";

    /** Rendered date the pinned clock produces, in the header format both programs assemble. */
    private static final String PINNED_HEADER_DATE = "06/10/22";

    /** Rendered time the same clock produces, at the eight characters the menu maps declare. */
    private static final String PINNED_HEADER_TIME = "19:27:53";

    /** Presentation prefix an issued session travels behind. */
    private static final String BEARER_PREFIX = "Bearer ";

    /** Summary a refusal for want of a session carries. */
    private static final String AUTHENTICATION_REQUIRED = "Authentication required";

    /** Summary a refusal for want of an entitlement carries. */
    private static final String ACCESS_DENIED = "Access denied";

    /** The four administrative transactions besides {@code CA00} that the definitions gate. */
    private static final List<String> OTHER_GATED_TRANSACTIONS =
            List.of("CU00", "CU01", "CU02", "CU03");

    // ===============================================================================================
    // THE IDENTITIES
    // ===============================================================================================

    /** A delivered administrative identity: eight characters, stored code {@code A}. */
    private static final String DELIVERED_ADMIN = "ADMIN001";

    /** A delivered ordinary identity: eight characters, stored code {@code U}. */
    private static final String DELIVERED_USER = "USER0001";

    /**
     * Prefix of the eight-character identifier range this specification reserves.
     *
     * <p>The credential seed never occupies it, so nothing written here touches a delivered row -
     * which matters, because several specifications on this same shared server assert against those
     * rows.
     */
    private static final String RESERVED_PREFIX = "ITMENU";

    /** Identity whose stored code the estate never declared, for the fall-through arm. */
    private static final String UNDECLARED_CODE_IDENTITY = RESERVED_PREFIX + "01";

    /** Identity whose stored code is the administrator letter in lower case. */
    private static final String LOWER_CASE_CODE_IDENTITY = RESERVED_PREFIX + "02";

    /** A single character the estate never declared as a user type. */
    private static final String UNDECLARED_TYPE_CODE = "X";

    /** The administrator letter in lower case, which the type vocabulary does not fold. */
    private static final String LOWER_CASE_ADMIN_CODE = "a";

    /** Given name both written identities carry; identity, never a secret. */
    private static final String RESERVED_FIRST_NAME = "INTEGRATION";

    /** Family name both written identities carry. */
    private static final String RESERVED_LAST_NAME = "MENUCONTRACT";

    /** Reads and writes JSON without binding to a published contract type. */
    private static final ObjectMapper JSON = new ObjectMapper();

    // ===============================================================================================
    // THE INJECTED BOUNDARY
    // ===============================================================================================

    /** The shipped servlet boundary, with the shipped filter chain in front of it. */
    @Autowired
    private MockMvc mockMvc;

    /** The shipped session issuer, asked directly exactly as the sign-on path asks it. */
    @Autowired
    private JwtTokenProvider tokenProvider;

    /** The credential master, for writing and removing the two identities this class owns. */
    @Autowired
    private UserSecurityRepository users;

    /** The service-layer catalogue contract, which owns the items the wire does not publish. */
    @Autowired
    private MenuOptionSource catalogue;

    /** The shipped shared-message catalogue, for the assertions that are about the catalogue itself. */
    @Autowired
    private MessageCatalogService messages;

    /** The shipped navigation authority, for composing the transaction over a different catalogue. */
    @Autowired
    private NavigationService navigation;

    /** The pinned clock the graph reads, for the same purpose. */
    @Autowired
    private Clock clock;

    /** Creates the specification. */
    public MenuControllerIT() {
        super();
    }

    // ===============================================================================================
    // LIFECYCLE
    // ===============================================================================================

    /**
     * Returns the shared server to its delivered state, so the ten delivered identities are present and
     * the reserved range is empty however the run that preceded this one ended.
     *
     * @throws SQLException if the delivered state cannot be restored
     */
    @BeforeAll
    static void restoreDeliveredState() throws SQLException {
        restoreSeededState();
    }

    /**
     * Writes the two identities this specification owns, immediately before every method.
     *
     * <p>Both store the shared synthetic digest, which accepts nothing at all. That is sufficient and is
     * the point: neither identity is ever signed on with, and a session for either is minted by asking
     * the shipped issuer, which reads the record rather than a credential. No credential value is
     * therefore needed anywhere in this class.
     */
    @BeforeEach
    void writeOwnedIdentities() {
        // The column is one character wide and carries no check constraint, so a code the estate never
        // declared is representable exactly as the legacy record made it representable. No schema change
        // is made and no raw statement is issued: both rows go in through the repository.
        writeOwnedIdentity(UNDECLARED_CODE_IDENTITY, UNDECLARED_TYPE_CODE);
        writeOwnedIdentity(LOWER_CASE_CODE_IDENTITY, LOWER_CASE_ADMIN_CODE);
    }

    /**
     * Removes only the rows this specification wrote, by name, so the ten delivered identities survive
     * every method and every specification that runs afterwards finds exactly the seed.
     */
    @AfterEach
    void clearOwnedIdentities() {
        for (final String owned : List.of(UNDECLARED_CODE_IDENTITY, LOWER_CASE_CODE_IDENTITY)) {
            this.users.deleteById(owned);
        }
    }

    // ===============================================================================================
    // HELPERS
    // ===============================================================================================

    /**
     * Writes one owned identity through the credential master.
     *
     * <p>Built through the shared factory, so the stored value is a digest by construction: the entity
     * refuses to hold anything that is not shaped like one, which makes it impossible for this
     * specification to store a cleartext value even by accident.
     *
     * @param userId   the reserved eight-character identifier
     * @param typeCode the one-character code to store
     */
    private void writeOwnedIdentity(final String userId, final String typeCode) {
        final UserSecurity identity = TestDataFactory.userSecurity()
                .userId(userId)
                .firstName(RESERVED_FIRST_NAME)
                .lastName(RESERVED_LAST_NAME)
                .userTypeCode(typeCode)
                .storedDigest(TestDataFactory.SYNTHETIC_BCRYPT_DIGEST)
                .build();
        this.users.save(identity);
    }

    /**
     * Mints a session for an identity, without naming a credential.
     *
     * <p>The shipped issuer is asked directly, which is what the sign-on path itself does once it has
     * verified a credential. The issuer refuses to mint for an identity no record carries, so a session
     * existing at all is evidence the record does; the chain then re-establishes, on every request, that
     * the record still matches what the session claims.
     *
     * @param userId   the identity to mint for
     * @param typeCode the stored one-character code, which becomes the session's record claim
     * @return the value of the authorization header, presentation prefix included
     */
    private String sessionFor(final String userId, final String typeCode) {
        // The authority a session carries is the one the stored code resolves to under the legacy split:
        // the administrator letter reaches the administrative authority and every other value, declared
        // or not, reaches the standard one. That resolution is the shipped vocabulary's, read here rather
        // than restated, because it is the shape of the call and not a claim being made.
        final UserType authority = UserType.fromCode(typeCode).orElse(UserType.USER);
        return BEARER_PREFIX + this.tokenProvider.issue(userId, authority, typeCode);
    }

    /** @return a session for the delivered administrative identity */
    private String administrativeSession() {
        return sessionFor(DELIVERED_ADMIN, ADMIN_TYPE_CODE);
    }

    /** @return a session for the delivered ordinary identity */
    private String ordinarySession() {
        return sessionFor(DELIVERED_USER, STANDARD_TYPE_CODE);
    }

    /**
     * Submits one turn of a menu and returns the whole result.
     *
     * @param  path      the endpoint address, taken from the shipped boundary
     * @param  session   the authorization header value, or {@code null} to present none
     * @param  state     the navigation state to echo, as the properties the contract declares
     * @param  keyAction the attention key to report, or {@code null} to report none
     * @param  option    the option field as the operator keyed it, or {@code null} to omit it
     * @return the result, so both the body and the status can be inspected
     * @throws Exception if the boundary cannot be reached
     */
    private MvcResult submit(final String path, final String session, final Map<String, Object> state,
                             final KeyAction keyAction, final String option) throws Exception {
        var request = MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .accept(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(state));
        if (session != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, session);
        }
        if (keyAction != null) {
            // The parameter name is the boundary's own, read from it rather than chosen here.
            request = request.param(MenuController.KEY_ACTION_PARAMETER, keyAction.name());
        }
        if (option != null) {
            request = request.param(MenuController.OPTION_PARAMETER, option);
        }
        return this.mockMvc.perform(request).andReturn();
    }

    /**
     * Renders a menu screen without submitting anything: the first-entry turn, which both programs
     * answer by sending the screen rather than by reading the option field.
     *
     * @param  path    the endpoint address
     * @param  session the authorization header value
     * @return the parsed body
     * @throws Exception if the boundary cannot be reached
     */
    private JsonNode firstEntry(final String path, final String session) throws Exception {
        return bodyOf(submit(path, session, firstEntryState(), null, null));
    }

    /**
     * Submits one option entry on a re-entry turn, which is the only turn that reads the option field.
     *
     * @param  path    the endpoint address
     * @param  session the authorization header value
     * @param  option  the option field as the operator keyed it, or {@code null} to omit it
     * @return the parsed body
     * @throws Exception if the boundary cannot be reached
     */
    private JsonNode enterOption(final String path, final String session, final String option)
            throws Exception {
        return bodyOf(submit(path, session, reEntryState(), KeyAction.ENTER, option));
    }

    /**
     * The navigation state of a turn that has not yet presented its screen.
     *
     * @return a mutable map carrying only the entry-mode property, at its typed value
     */
    private static Map<String, Object> firstEntryState() {
        final Map<String, Object> state = new LinkedHashMap<>();
        state.put("fromTransactionId", "CC00");
        state.put("fromProgram", "COSGN00C");
        state.put("programContext", "ENTER");
        return state;
    }

    /**
     * The navigation state of a turn whose screen has been presented and is being submitted back.
     *
     * @return a mutable map carrying only the entry-mode property, at its typed value
     */
    private static Map<String, Object> reEntryState() {
        final Map<String, Object> state = new LinkedHashMap<>();
        state.put("fromTransactionId", "CC00");
        state.put("fromProgram", "COSGN00C");
        state.put("programContext", "REENTER");
        return state;
    }

    /**
     * Parses the body of a completed turn, having first asserted the status the contract fixes.
     *
     * @param  result the completed turn
     * @return the parsed body
     * @throws Exception if the body cannot be parsed
     */
    private static JsonNode bodyOf(final MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("every outcome either menu program could compose is a screen it successfully sent, "
                        + "a rejected option and a refused option included, so the turn completes with "
                        + "the same status in all of them")
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * Reads a textual component of a response body, distinguishing absent from present-and-null.
     *
     * @param  body the parsed body
     * @param  name the component name, as the published contract type declares it
     * @return the value, or {@code null} when the component is absent or null
     */
    private static String textOf(final JsonNode body, final String name) {
        final JsonNode node = body.get(name);
        return node == null || node.isNull() ? null : node.asText();
    }

    /**
     * Reads the label of every rendered option row, in the order the payload carries them.
     *
     * @param  body       the parsed body
     * @param  collection the option component's name
     * @return the labels in payload order
     */
    private static List<String> labelsOf(final JsonNode body, final String collection) {
        final List<String> labels = new ArrayList<>();
        for (final JsonNode row : body.get(collection)) {
            labels.add(row.get("label").asText());
        }
        return List.copyOf(labels);
    }

    /**
     * Reads the number of every rendered option row, in the order the payload carries them.
     *
     * @param  body       the parsed body
     * @param  collection the option component's name
     * @return the numbers in payload order
     */
    private static List<Integer> numbersOf(final JsonNode body, final String collection) {
        final List<Integer> numbers = new ArrayList<>();
        for (final JsonNode row : body.get(collection)) {
            numbers.add(row.get("number").asInt());
        }
        return List.copyOf(numbers);
    }

    /**
     * Composes the shipped transaction over the container's own collaborators and a catalogue that
     * answers one lookup differently.
     *
     * <p>The navigation authority, the shared-message catalogue and the clock are the graph's own beans,
     * so nothing here substitutes behaviour: only the table content differs, which is exactly the one
     * variable the two unreachable paths turn on. No mocking framework and no reflection is used - the
     * substitute is an explicit implementation written out below and built through its constructor.
     *
     * @param  substitute the catalogue to read
     * @return the transaction, wired to the shipped collaborators
     */
    private MenuService transactionOver(final MenuOptionSource substitute) {
        return new MenuService(this.navigation, this.messages, substitute, this.clock);
    }

    /** @return a carried state marked as a re-entry, which is what makes a turn read the option field */
    private static ConversationState carriedReEntry() {
        return ConversationState.empty().withReEntry();
    }

    // ===============================================================================================
    // GROUP 1 - THE USER CATALOGUE
    // ===============================================================================================

    /** Ten populated rows inside storage declared for twelve, and the surplus two never appear. */
    @Nested
    @DisplayName("User catalog (10 options)")
    class UserCatalogue {

        /** Creates the group. */
        UserCatalogue() {
            super();
        }

        // app/cpy/COMEN02Y.cpy L21 declares the population; app/cbl/COMEN01C.cbl rebuilds the whole
        // table on every send, so a screen never shows fewer rows than the count item states.
        @Test
        @DisplayName("a first-entry turn renders exactly ten rows and no administrative collection")
        void aFirstEntryTurnRendersExactlyTenRows() throws Exception {
            final JsonNode body = firstEntry(MenuController.USER_MENU_PATH, ordinarySession());

            assertThat(body.get("userMenuOptions")).hasSize(USER_OPTION_COUNT);
            assertThat(body.has("adminMenuOptions"))
                    .as("a menu screen is one menu, so the collection belonging to the other menu is "
                            + "absent from the payload rather than present and empty")
                    .isFalse();
            assertThat(textOf(body, "transactionName")).isEqualTo(USER_MENU_TRANSACTION);
            assertThat(textOf(body, "programName")).isEqualTo(USER_MENU_PROGRAM);
            assertThat(textOf(body, "focusScreenFieldId")).isEqualTo(OPTION_FIELD_ID);
            assertThat(body.get("errorFlag").asBoolean()).isFalse();
        }

        // app/cpy/COMEN02Y.cpy L25-L84: ten entries, numbered and labelled in declaration order. The
        // order is contractual because the operator selects by the number printed beside the row.
        @Test
        @DisplayName("the ten rows carry the declared numbers and labels in table order")
        void theTenRowsCarryTheDeclaredNumbersAndLabelsInTableOrder() throws Exception {
            final JsonNode body = firstEntry(MenuController.USER_MENU_PATH, ordinarySession());

            assertThat(numbersOf(body, "userMenuOptions"))
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            assertThat(labelsOf(body, "userMenuOptions"))
                    .containsExactlyElementsOf(USER_OPTION_LABELS);
        }

        // The occurrence clause at L88 declares twelve positions and ten are populated. Rendering the
        // surplus two would put two blank rows on a screen the estate never shows them on.
        @Test
        @DisplayName("neither surplus storage position is rendered, so no blank row reaches a client")
        void neitherSurplusStoragePositionIsRendered() throws Exception {
            final JsonNode body = firstEntry(MenuController.USER_MENU_PATH, ordinarySession());

            assertThat(numbersOf(body, "userMenuOptions"))
                    .as("positions %d and %d of the declared capacity are unpopulated",
                            USER_OPTION_COUNT + 1, USER_TABLE_CAPACITY)
                    .doesNotContain(USER_OPTION_COUNT + 1, USER_TABLE_CAPACITY);
            assertThat(labelsOf(body, "userMenuOptions")).doesNotContainNull()
                    .allSatisfy(label -> assertThat(label).isNotBlank());
            assertThat(USER_TABLE_CAPACITY - USER_OPTION_COUNT)
                    .as("the capacity exceeds the population by exactly two positions")
                    .isEqualTo(2);
        }

        // The number and the label are what the mapset renders. The program name is a dispatch target
        // and the user-type code an authorization input, so neither is published on the wire.
        @Test
        @DisplayName("the wire row publishes the number and the label only, never the dispatch target "
                + "and never the authorization code")
        void theWireRowPublishesTheNumberAndLabelOnly() throws Exception {
            final JsonNode body = firstEntry(MenuController.USER_MENU_PATH, ordinarySession());

            for (final JsonNode row : body.get("userMenuOptions")) {
                assertThat(row.properties()).extracting(Map.Entry::getKey)
                        .containsExactlyInAnyOrder("number", "label");
            }
            final String payload = body.toString();
            assertThat(payload).doesNotContain(USER_OPTION_PROGRAMS);
        }

        // Row three of the migration's construct mapping is discharged where the full entries live: the
        // service-layer catalogue contract, which this package may read because it sits below it.
        @Test
        @DisplayName("every catalogued user entry carries four items, and the fourth is a raw "
                + "one-character code rather than the enumerated user type")
        void everyCataloguedUserEntryCarriesFourItems() {
            final List<MenuOptionSource.UserMenuOption> entries = catalogue.userMenuOptions();

            assertThat(entries).hasSize(USER_OPTION_COUNT);
            assertThat(entries).extracting(MenuOptionSource.UserMenuOption::programName)
                    .containsExactlyElementsOf(USER_OPTION_PROGRAMS);
            for (final MenuOptionSource.UserMenuOption entry : entries) {
                // A raw one-character String, deliberately not the enumerated type: the table item is
                // one alphanumeric byte and an unrecognised code has to remain representable.
                assertThat(entry.userType()).as("option %d", entry.number())
                        .isInstanceOf(String.class)
                        .isEqualTo(STANDARD_TYPE_CODE)
                        .hasSize(1);
            }
            assertThat(MenuOptionSource.USER_MENU_ENTRY_LENGTH).isEqualTo(USER_ENTRY_LENGTH);
        }

        // app/cpy/COMEN02Y.cpy L69 holds an alternative label that is commented out; L70 holds the
        // active one. Activating the alternative would be feature expansion, so it stays inactive.
        @Test
        @DisplayName("the eighth row carries the active label, and the commented alternative appears "
                + "nowhere in the payload")
        void theEighthRowCarriesTheActiveLabel() throws Exception {
            final JsonNode body = firstEntry(MenuController.USER_MENU_PATH, ordinarySession());
            final JsonNode eighth = body.get("userMenuOptions").get(7);

            assertThat(eighth.get("number").asInt()).isEqualTo(8);
            assertThat(eighth.get("label").asText()).isEqualTo("Transaction Add");
            assertThat(eighth.get("label").asText()).doesNotContain("(Admin Only)");
            assertThat(body.toString()).doesNotContain("(Admin Only)");
        }

        // The eighth entry carries the standard code, so the administrator-only gate cannot fire on it.
        // The row is therefore selectable by an ordinary operator and dispatches like any other.
        @Test
        @DisplayName("the eighth option is reachable by a non-administrator, because it carries no gate")
        void theEighthOptionIsReachableByANonAdministrator() throws Exception {
            final JsonNode body = enterOption(MenuController.USER_MENU_PATH, ordinarySession(), "8");

            assertThat(textOf(body, "nextRoute")).isEqualTo("transaction-add");
            assertThat(textOf(body, "message")).isNull();
            assertThat(body.get("errorFlag").asBoolean()).isFalse();
            assertThat(catalogue.findUserOption(8))
                    .get()
                    .extracting(MenuOptionSource.UserMenuOption::userType)
                    .isEqualTo(STANDARD_TYPE_CODE);
        }

        // Every one of the ten dispatches, in table order, so no row is selectable in principle and
        // unreachable in practice.
        @Test
        @DisplayName("each of the ten options dispatches to its own destination")
        void eachOfTheTenOptionsDispatchesToItsOwnDestination() throws Exception {
            final String session = ordinarySession();
            for (int number = 1; number <= USER_OPTION_COUNT; number++) {
                final JsonNode body = enterOption(MenuController.USER_MENU_PATH, session,
                        Integer.toString(number));

                assertThat(textOf(body, "nextRoute")).as("option %d", number)
                        .isEqualTo(USER_OPTION_ROUTES.get(number - 1));
                assertThat(textOf(body, "message")).as("option %d", number).isNull();
            }
        }
    }

    // ===============================================================================================
    // GROUP 2 - THE ADMINISTRATIVE CATALOGUE
    // ===============================================================================================

    /** Four populated rows inside storage declared for nine, of a shape that is not the user shape. */
    @Nested
    @DisplayName("Admin catalog (4 options)")
    class AdminCatalogue {

        /** Creates the group. */
        AdminCatalogue() {
            super();
        }

        // app/cpy/COADM02Y.cpy L20 declares the population as four.
        @Test
        @DisplayName("a first-entry turn renders exactly four rows and no user collection")
        void aFirstEntryTurnRendersExactlyFourRows() throws Exception {
            final JsonNode body = firstEntry(MenuController.ADMIN_MENU_PATH, administrativeSession());

            assertThat(body.get("adminMenuOptions")).hasSize(ADMIN_OPTION_COUNT);
            assertThat(body.has("userMenuOptions"))
                    .as("the administrative screen is not the main screen, so the main menu's "
                            + "collection is absent rather than present and empty")
                    .isFalse();
            assertThat(textOf(body, "transactionName")).isEqualTo(ADMIN_MENU_TRANSACTION);
            assertThat(textOf(body, "programName")).isEqualTo(ADMIN_MENU_PROGRAM);
        }

        // app/cpy/COADM02Y.cpy L24-L42, in declaration order.
        @Test
        @DisplayName("the four rows carry the declared numbers and labels in table order")
        void theFourRowsCarryTheDeclaredNumbersAndLabelsInTableOrder() throws Exception {
            final JsonNode body = firstEntry(MenuController.ADMIN_MENU_PATH, administrativeSession());

            assertThat(numbersOf(body, "adminMenuOptions")).containsExactly(1, 2, 3, 4);
            assertThat(labelsOf(body, "adminMenuOptions"))
                    .containsExactlyElementsOf(ADMIN_OPTION_LABELS);
        }

        // The occurrence clause at L45 declares nine positions and four are populated.
        @Test
        @DisplayName("none of the five surplus storage positions is rendered")
        void noneOfTheFiveSurplusStoragePositionsIsRendered() throws Exception {
            final JsonNode body = firstEntry(MenuController.ADMIN_MENU_PATH, administrativeSession());

            assertThat(numbersOf(body, "adminMenuOptions"))
                    .doesNotContain(5, 6, 7, 8, ADMIN_TABLE_CAPACITY);
            assertThat(labelsOf(body, "adminMenuOptions")).doesNotContainNull()
                    .allSatisfy(label -> assertThat(label).isNotBlank());
            assertThat(ADMIN_TABLE_CAPACITY - ADMIN_OPTION_COUNT)
                    .as("the capacity exceeds the population by exactly five positions")
                    .isEqualTo(5);
        }

        // The administrative table view declares three items and no user-type item. The absence is a
        // property of the copybook, so no defaulted or permanently-absent stand-in is invented for it.
        @Test
        @DisplayName("every catalogued administrative entry carries three items and no user-type item, "
                + "which is the one-byte difference between the two entry lengths")
        void everyCataloguedAdministrativeEntryCarriesThreeItems() {
            final List<MenuOptionSource.AdminMenuOption> entries = catalogue.adminMenuOptions();

            assertThat(entries).hasSize(ADMIN_OPTION_COUNT);
            assertThat(entries).extracting(MenuOptionSource.AdminMenuOption::programName)
                    .containsExactlyElementsOf(ADMIN_OPTION_PROGRAMS);
            // The absence of a user-type item is a compile-time property of the shipped shape: there is
            // no accessor for one, so no call in this file could read one. What is asserted here is the
            // measurable consequence - the administrative entry is one byte shorter, and that byte is
            // exactly the width of the user entry's user-type item.
            assertThat(MenuOptionSource.ADMIN_MENU_ENTRY_LENGTH).isEqualTo(ADMIN_ENTRY_LENGTH);
            assertThat(MenuOptionSource.USER_MENU_ENTRY_LENGTH
                    - MenuOptionSource.ADMIN_MENU_ENTRY_LENGTH)
                    .isEqualTo(MenuOptionSource.USER_OPTION_USER_TYPE_WIDTH);
        }

        // Two record types with no shared supertype and no conversion between them. Asserted by value
        // rather than by inspecting either type, because records of different types are never equal.
        @Test
        @DisplayName("the two wire row shapes are distinct types and are never interchangeable")
        void theTwoWireRowShapesAreDistinctTypes() {
            final MenuResponse.UserMenuOption userRow =
                    new MenuResponse.UserMenuOption(1, USER_OPTION_LABELS.get(0));
            final MenuResponse.AdminMenuOption adminRow =
                    new MenuResponse.AdminMenuOption(1, USER_OPTION_LABELS.get(0));

            assertThat(userRow).as("identical components, and still not the same value, because the two "
                            + "menus are not one menu")
                    .isNotEqualTo(adminRow);
            assertThat(adminRow).isNotEqualTo(userRow);
        }

        // Each of the four dispatches to its own destination.
        @Test
        @DisplayName("each of the four options dispatches to its own destination")
        void eachOfTheFourOptionsDispatchesToItsOwnDestination() throws Exception {
            final String session = administrativeSession();
            for (int number = 1; number <= ADMIN_OPTION_COUNT; number++) {
                final JsonNode body = enterOption(MenuController.ADMIN_MENU_PATH, session,
                        Integer.toString(number));

                assertThat(textOf(body, "nextRoute")).as("option %d", number)
                        .isEqualTo(ADMIN_OPTION_ROUTES.get(number - 1));
                assertThat(textOf(body, "message")).as("option %d", number).isNull();
            }
        }

        // The programs read different count items, so the two menus reject at different boundaries.
        @Test
        @DisplayName("the two populated sizes are different figures, so the range boundaries differ")
        void theTwoPopulatedSizesAreDifferentFigures() {
            assertThat(catalogue.userMenuOptionCount()).isEqualTo(USER_OPTION_COUNT);
            assertThat(catalogue.adminMenuOptionCount()).isEqualTo(ADMIN_OPTION_COUNT);
            assertThat(catalogue.userMenuOptionCount()).isNotEqualTo(catalogue.adminMenuOptionCount());
        }
    }

    // ===============================================================================================
    // GROUP 3 - OPTION NORMALISATION
    // ===============================================================================================

    /**
     * The option field is two characters, right-justified, and its blank characters become the digit
     * zero before any numeric test runs.
     *
     * <p>None of that is performed here. Every assertion below submits what an operator would have keyed
     * and reads what the endpoint answered, so the normalisation asserted is the endpoint's.
     */
    @Nested
    @DisplayName("Option normalisation")
    class OptionNormalisation {

        /** Creates the group. */
        OptionNormalisation() {
            super();
        }

        // app/cbl/COADM01C.cbl L45-L46 declare the field and its numeric counterpart; L123 replaces the
        // blank characters with the digit zero. app/cbl/COMEN01C.cbl L123 does the same. A single digit
        // therefore reaches the range test as the two-digit zero-filled value, and selects that option.
        @Test
        @DisplayName("a single digit selects the option of that number")
        void aSingleDigitSelectsTheOptionOfThatNumber() throws Exception {
            final JsonNode body = enterOption(MenuController.USER_MENU_PATH, ordinarySession(), "3");

            assertThat(textOf(body, "nextRoute")).isEqualTo(USER_OPTION_ROUTES.get(2));
            assertThat(textOf(body, "message")).isNull();
            assertThat(body.get("errorFlag").asBoolean()).isFalse();
        }

        // The normalised field is observable wherever the program re-presents its own screen. A single
        // digit beyond the administrative table's population of four is such a turn, and it shows the
        // field arriving at the range test already zero-filled to two digits rather than as one character.
        @Test
        @DisplayName("a single digit is zero-filled to the two-digit form before the range test reads it")
        void aSingleDigitIsZeroFilledToTheTwoDigitForm() throws Exception {
            final JsonNode body = enterOption(MenuController.ADMIN_MENU_PATH, administrativeSession(),
                    "9");

            assertThat(textOf(body, "selectedOption")).isEqualTo("09")
                    .hasSize(2)
                    .startsWith("0");
            assertThat(textOf(body, "message")).isEqualTo(INVALID_OPTION_TEXT);
        }

        // A dispatch transfers control instead of sending the menu map, so the option the program moved
        // into the map's echo field is never transmitted. Recorded because it is faithful rather than
        // accidental: the field is echoed on every re-presented screen and on no dispatched one.
        @Test
        @DisplayName("a dispatched turn echoes no option, because the program sends no screen of its own")
        void aDispatchedTurnEchoesNoOption() throws Exception {
            final JsonNode dispatched =
                    enterOption(MenuController.USER_MENU_PATH, ordinarySession(), "3");
            final JsonNode rePresented =
                    enterOption(MenuController.USER_MENU_PATH, ordinarySession(), "13");

            assertThat(textOf(dispatched, "selectedOption")).isNull();
            assertThat(textOf(dispatched, "nextRoute")).isEqualTo(USER_OPTION_ROUTES.get(2));
            assertThat(textOf(rePresented, "selectedOption"))
                    .as("the same field on a turn that does send a screen carries the normalised value")
                    .isEqualTo("13");
        }

        // Both characters blank, so both become the digit zero and the numeric value is zero - which the
        // range test at L127-L129 refuses.
        @Test
        @DisplayName("an entirely blank submission normalises to zero and is therefore invalid")
        void anEntirelyBlankSubmissionNormalisesToZero() throws Exception {
            final JsonNode body = enterOption(MenuController.USER_MENU_PATH, ordinarySession(), "  ");

            assertThat(textOf(body, "selectedOption")).isEqualTo("00");
            assertThat(textOf(body, "message")).isEqualTo(INVALID_OPTION_TEXT);
            assertThat(body.get("errorFlag").asBoolean()).isTrue();
        }

        // A field the terminal transmitted empty is the same state as a field of blanks, so an omitted
        // parameter reaches the same arm.
        @Test
        @DisplayName("an omitted option field is the blank field the terminal would have transmitted")
        void anOmittedOptionFieldIsTheBlankField() throws Exception {
            final JsonNode body = enterOption(MenuController.USER_MENU_PATH, ordinarySession(), null);

            assertThat(textOf(body, "selectedOption")).isEqualTo("00");
            assertThat(textOf(body, "message")).isEqualTo(INVALID_OPTION_TEXT);
        }

        // The field is right-justified first and only then has its blank characters replaced, so a single
        // character lands in the rightmost position and the fill digit lands in front of it rather than
        // behind it. A non-digit is used so the turn re-presents the screen and the field is observable.
        @Test
        @DisplayName("a single character is right-justified first, so the fill digit lands in front of it")
        void aSingleCharacterIsRightJustifiedFirst() throws Exception {
            final JsonNode body = enterOption(MenuController.USER_MENU_PATH, ordinarySession(), "z");

            assertThat(textOf(body, "selectedOption")).isEqualTo("0z")
                    .startsWith("0")
                    .endsWith("z");
            assertThat(textOf(body, "message")).isEqualTo(INVALID_OPTION_TEXT);
        }

        // A two-character entry fills the field, so there is no blank character to replace and the value
        // reaches the range test exactly as keyed.
        @Test
        @DisplayName("a two-character entry needs no fill and is echoed exactly as keyed")
        void aTwoCharacterEntryNeedsNoFill() throws Exception {
            final JsonNode body = enterOption(MenuController.USER_MENU_PATH, ordinarySession(), "12");

            assertThat(textOf(body, "selectedOption")).isEqualTo("12").doesNotStartWith("0");
            assertThat(textOf(body, "message")).isEqualTo(INVALID_OPTION_TEXT);
        }

        // app/cbl/COADM01C.cbl L45-L46 and L123 are the same declarations and the same statement, so the
        // administrative menu normalises identically over its own smaller table.
        @Test
        @DisplayName("the administrative menu normalises identically over its own table")
        void theAdministrativeMenuNormalisesIdentically() throws Exception {
            final String session = administrativeSession();

            final JsonNode selected = enterOption(MenuController.ADMIN_MENU_PATH, session, "2");
            assertThat(textOf(selected, "nextRoute")).isEqualTo(ADMIN_OPTION_ROUTES.get(1));

            final JsonNode blank = enterOption(MenuController.ADMIN_MENU_PATH, session, "  ");
            assertThat(textOf(blank, "selectedOption")).isEqualTo("00");
            assertThat(textOf(blank, "message")).isEqualTo(INVALID_OPTION_TEXT);

            final JsonNode single = enterOption(MenuController.ADMIN_MENU_PATH, session, "x");
            assertThat(textOf(single, "selectedOption")).isEqualTo("0x");
            assertThat(textOf(single, "message")).isEqualTo(INVALID_OPTION_TEXT);
        }

        // Only a re-entry turn reads the field. A first-entry turn sends the screen and echoes nothing,
        // which is the two-armed test at app/cbl/COMEN01C.cbl L87-L92.
        @Test
        @DisplayName("a first-entry turn does not read the option field at all, so nothing is echoed")
        void aFirstEntryTurnDoesNotReadTheOptionField() throws Exception {
            final JsonNode body = bodyOf(submit(MenuController.USER_MENU_PATH, ordinarySession(),
                    firstEntryState(), KeyAction.ENTER, "9"));

            assertThat(textOf(body, "selectedOption"))
                    .as("the field is read only once the screen has been presented, so a first-entry "
                            + "turn carrying an entry still answers with the screen and echoes nothing")
                    .isNull();
            assertThat(textOf(body, "message")).isNull();
            assertThat(textOf(body, "nextRoute")).isEqualTo(USER_MENU_ROUTE);
        }
    }

    // ===============================================================================================
    // GROUP 4 - THE REJECTION TEXT
    // ===============================================================================================

    /** One text, four rejections, both menus, and thirty-seven characters exactly. */
    @Nested
    @DisplayName("Invalid option")
    class InvalidOption {

        /** Creates the group. */
        InvalidOption() {
            super();
        }

        // app/cbl/COMEN01C.cbl L128 compares against the count item, so eleven exceeds a table of ten.
        @Test
        @DisplayName("a number beyond the populated count is rejected, and the entry is echoed unchanged")
        void aNumberBeyondThePopulatedCountIsRejected() throws Exception {
            final JsonNode body = enterOption(MenuController.USER_MENU_PATH, ordinarySession(), "11");

            assertThat(textOf(body, "message")).isEqualTo(INVALID_OPTION_TEXT);
            assertThat(textOf(body, "selectedOption")).isEqualTo("11");
            assertThat(body.get("errorFlag").asBoolean()).isTrue();
            assertThat(textOf(body, "messageSeverity")).isEqualTo("ERROR");
        }

        // L129 refuses zero explicitly, as its own arm of the same evaluation.
        @Test
        @DisplayName("zero is rejected by its own arm of the same evaluation")
        void zeroIsRejectedByItsOwnArm() throws Exception {
            final JsonNode body = enterOption(MenuController.USER_MENU_PATH, ordinarySession(), "00");

            assertThat(textOf(body, "message")).isEqualTo(INVALID_OPTION_TEXT);
            assertThat(textOf(body, "selectedOption")).isEqualTo("00");
            assertThat(body.get("errorFlag").asBoolean()).isTrue();
        }

        // L127 refuses a field that is not numeric, which is the first arm of the evaluation.
        @Test
        @DisplayName("a non-numeric value is rejected and is echoed exactly as keyed")
        void aNonNumericValueIsRejected() throws Exception {
            final JsonNode body = enterOption(MenuController.USER_MENU_PATH, ordinarySession(), "1A");

            assertThat(textOf(body, "message")).isEqualTo(INVALID_OPTION_TEXT);
            assertThat(textOf(body, "selectedOption")).isEqualTo("1A");
            assertThat(body.get("errorFlag").asBoolean()).isTrue();
        }

        // A blank submission reaches the zero arm, because the fill has already run by then.
        @Test
        @DisplayName("a blank submission is rejected with the same text, through the zero arm")
        void aBlankSubmissionIsRejectedWithTheSameText() throws Exception {
            final JsonNode body = enterOption(MenuController.USER_MENU_PATH, ordinarySession(), " ");

            assertThat(textOf(body, "message")).isEqualTo(INVALID_OPTION_TEXT);
            assertThat(body.get("errorFlag").asBoolean()).isTrue();
        }

        // The same literal is moved at L131 of both programs, and the administrative table is smaller, so
        // five is out of range there and in range on the main menu.
        @Test
        @DisplayName("the administrative menu emits the same text at its own smaller boundary")
        void theAdministrativeMenuEmitsTheSameTextAtItsOwnBoundary() throws Exception {
            final JsonNode refused = enterOption(MenuController.ADMIN_MENU_PATH,
                    administrativeSession(), "5");
            assertThat(textOf(refused, "message")).isEqualTo(INVALID_OPTION_TEXT);
            assertThat(textOf(refused, "selectedOption")).isEqualTo("05");

            final JsonNode admitted = enterOption(MenuController.USER_MENU_PATH, ordinarySession(), "5");
            assertThat(textOf(admitted, "message"))
                    .as("the very same entry is in range on the main menu, which is what makes the two "
                            + "boundaries observably different rather than nominally different")
                    .isNull();
        }

        // A rejection re-presents the menu rather than transferring control, which is why the response
        // nominates the menu's own destination and still carries the whole table.
        @Test
        @DisplayName("a rejection re-presents the menu: its own destination, and all ten rows again")
        void aRejectionRePresentsTheMenu() throws Exception {
            final JsonNode body = enterOption(MenuController.USER_MENU_PATH, ordinarySession(), "99");

            assertThat(textOf(body, "nextRoute")).isEqualTo(USER_MENU_ROUTE);
            assertThat(body.get("userMenuOptions")).hasSize(USER_OPTION_COUNT);
            assertThat(textOf(body, "focusScreenFieldId")).isEqualTo(OPTION_FIELD_ID);
        }

        // The text is an operator-facing external contract, so its bytes are asserted rather than its
        // shape: a lower-case verb, and three dots with no space in front of them.
        @Test
        @DisplayName("the text is thirty-seven characters, lower-cased and dotted exactly as the source "
                + "moves it")
        void theTextIsThirtySevenCharactersExactly() throws Exception {
            final JsonNode body = enterOption(MenuController.USER_MENU_PATH, ordinarySession(), "11");

            assertThat(textOf(body, "message")).isEqualTo(INVALID_OPTION_TEXT)
                    .hasSize(INVALID_OPTION_TEXT_LENGTH)
                    .endsWith("...")
                    .doesNotContain(" ...")
                    .doesNotContain("Enter");
        }
    }

    // ===============================================================================================
    // GROUP 5 - THE SUPPRESSED-DISPATCH DIVERGENCE
    // ===============================================================================================

    /**
     * Two <strong>different</strong> texts that must never be merged, and one destination that is not a
     * destination.
     *
     * <p>No shipped entry names a suppressing program, so neither text can be produced through an
     * endpoint. That fact is asserted first, and then each text is asserted by composing the shipped
     * transaction over the container's own collaborators and a catalogue that answers one lookup with a
     * suppressing entry.
     */
    @Nested
    @DisplayName("Coming-soon divergence")
    class SuppressedDispatch {

        /** Creates the group. */
        SuppressedDispatch() {
            super();
        }

        // Every shipped entry names a real program, so the guard at app/cbl/COMEN01C.cbl L146 and
        // app/cbl/COADM01C.cbl L138 never fires against the delivered tables.
        @Test
        @DisplayName("no shipped option names a suppressing program, so neither text is reachable "
                + "through an endpoint")
        void noShippedOptionNamesASuppressingProgram() throws Exception {
            for (final MenuOptionSource.UserMenuOption entry : catalogue.userMenuOptions()) {
                assertThat(entry.programName()).as("user option %d", entry.number())
                        .doesNotStartWith(SUPPRESSING_PROGRAM_PREFIX);
            }
            for (final MenuOptionSource.AdminMenuOption entry : catalogue.adminMenuOptions()) {
                assertThat(entry.programName()).as("administrative option %d", entry.number())
                        .doesNotStartWith(SUPPRESSING_PROGRAM_PREFIX);
            }

            final JsonNode body = enterOption(MenuController.USER_MENU_PATH, ordinarySession(), "1");
            assertThat(textOf(body, "message")).isNull();
            assertThat(body.toString()).doesNotContain(SUPPRESSED_TEXT_SUFFIX);
        }

        // app/cbl/COMEN01C.cbl L159-L162 concatenates three operands and transfers the middle one under
        // a space delimiter, so the transfer stops at the label's first blank and contributes no
        // separator. The missing space is faithfully preserved and is NOT a defect to be corrected: it is
        // what the operator saw, and the text is an external contract.
        @Test
        @DisplayName("the main menu's text carries the label's first word only, with no space before the "
                + "word that follows it")
        void theMainMenuTextCarriesTheFirstWordOnly() {
            final MenuService transaction = transactionOver(new SubstitutedCatalogue(catalogue,
                    new MenuOptionSource.UserMenuOption(1, USER_OPTION_LABELS.get(0),
                            SUPPRESSING_PROGRAM, STANDARD_TYPE_CODE),
                    null));

            final MenuService.MenuScreen screen =
                    transaction.userMenu(carriedReEntry(), KeyAction.ENTER, "1", UserType.USER);

            assertThat(screen.message()).isEqualTo(USER_SUPPRESSED_TEXT)
                    .hasSize(USER_SUPPRESSED_TEXT_LENGTH)
                    .doesNotContain("Account is")
                    .doesNotContain(USER_OPTION_LABELS.get(0));
            assertThat(screen.severity()).isEqualTo(MenuService.MessageSeverity.INFORMATIONAL);
            assertThat(screen.errorFlag())
                    .as("a suppressed dispatch is an outcome and not a failure, so the error switch "
                            + "stays lowered even though a message is composed")
                    .isFalse();
        }

        // app/cbl/COADM01C.cbl L149-L152: the label operand is commented out on L150-L151, so the
        // administrative text carries no label at all. A separate value from the main menu's, and a
        // separate constant, because one shared constant would erase the divergence.
        @Test
        @DisplayName("the administrative menu's text carries no label at all, because the source comments "
                + "the operand out")
        void theAdministrativeMenuTextCarriesNoLabel() {
            final MenuService transaction = transactionOver(new SubstitutedCatalogue(catalogue, null,
                    new MenuOptionSource.AdminMenuOption(2, ADMIN_OPTION_LABELS.get(1),
                            SUPPRESSING_PROGRAM)));

            final MenuService.MenuScreen screen =
                    transaction.adminMenu(carriedReEntry(), KeyAction.ENTER, "2");

            assertThat(screen.message()).isEqualTo(ADMIN_SUPPRESSED_TEXT)
                    .hasSize(ADMIN_SUPPRESSED_TEXT_LENGTH)
                    .doesNotContain("User")
                    .doesNotContain(ADMIN_OPTION_LABELS.get(1));
            assertThat(screen.severity()).isEqualTo(MenuService.MessageSeverity.INFORMATIONAL);
            assertThat(screen.errorFlag()).isFalse();
        }

        // The two texts are different values sharing one eighteen-character suffix. Asserting the
        // difference is what keeps a later simplification from folding them together.
        @Test
        @DisplayName("the two texts are different values that share only their suffix")
        void theTwoTextsAreDifferentValues() {
            assertThat(USER_SUPPRESSED_TEXT).isNotEqualTo(ADMIN_SUPPRESSED_TEXT)
                    .hasSize(USER_SUPPRESSED_TEXT_LENGTH);
            assertThat(ADMIN_SUPPRESSED_TEXT).hasSize(ADMIN_SUPPRESSED_TEXT_LENGTH);
            assertThat(SUPPRESSED_TEXT_SUFFIX).hasSize(SUPPRESSED_TEXT_SUFFIX_LENGTH);
            assertThat(USER_SUPPRESSED_TEXT).endsWith(SUPPRESSED_TEXT_SUFFIX);
            assertThat(ADMIN_SUPPRESSED_TEXT).endsWith(SUPPRESSED_TEXT_SUFFIX);
            assertThat(USER_SUPPRESSED_TEXT_LENGTH - ADMIN_SUPPRESSED_TEXT_LENGTH)
                    .as("the main menu's text is longer by the label's first word alone, with no "
                            + "separator between it and the suffix")
                    .isEqualTo(USER_OPTION_LABELS.get(0).indexOf(' '));
        }

        // The placeholder name is not a destination: the program re-presents its own screen instead of
        // transferring control, so no navigable route is produced and the name never reaches a client.
        @Test
        @DisplayName("a suppressed option nominates no navigable destination and attempts no transfer")
        void aSuppressedOptionNominatesNoNavigableDestination() {
            final MenuService userTransaction = transactionOver(new SubstitutedCatalogue(catalogue,
                    new MenuOptionSource.UserMenuOption(1, USER_OPTION_LABELS.get(0),
                            SUPPRESSING_PROGRAM, STANDARD_TYPE_CODE),
                    null));
            final MenuService adminTransaction = transactionOver(new SubstitutedCatalogue(catalogue, null,
                    new MenuOptionSource.AdminMenuOption(2, ADMIN_OPTION_LABELS.get(1),
                            SUPPRESSING_PROGRAM)));

            final MenuService.MenuScreen userScreen =
                    userTransaction.userMenu(carriedReEntry(), KeyAction.ENTER, "1", UserType.USER);
            final MenuService.MenuScreen adminScreen =
                    adminTransaction.adminMenu(carriedReEntry(), KeyAction.ENTER, "2");

            assertThat(userScreen.nextRoute()).isEqualTo(USER_MENU_ROUTE)
                    .isNotIn(USER_OPTION_ROUTES)
                    .doesNotContain(SUPPRESSING_PROGRAM_PREFIX);
            assertThat(adminScreen.nextRoute()).isEqualTo(ADMIN_MENU_ROUTE)
                    .isNotIn(ADMIN_OPTION_ROUTES)
                    .doesNotContain(SUPPRESSING_PROGRAM_PREFIX);
            assertThat(userScreen.rows()).hasSize(USER_OPTION_COUNT);
            assertThat(adminScreen.rows()).hasSize(ADMIN_OPTION_COUNT);
        }
    }

    // ===============================================================================================
    // GROUP 6 - THE ENTITLEMENT GATE
    // ===============================================================================================

    /**
     * The administrative menu is one of exactly five administrative transactions the resource
     * definitions declare, and the main menu is none of them.
     *
     * <p>The refusal statuses below are the shipped chain's own and were read from the security
     * configuration rather than assumed: an established identity that is not entitled is refused with one
     * status, and an absent or unusable session with another.
     */
    @Nested
    @DisplayName("Admin gating")
    class AdminGating {

        /** Creates the group. */
        AdminGating() {
            super();
        }

        // app/csd/CARDDEMO.CSD L327-L328 binds CA00 to COADM01C. The administrative set is exactly CA00
        // and the four user-administration transactions; it is not broadened here.
        @Test
        @DisplayName("an administrator reaches the administrative menu and receives the four-option "
                + "catalogue")
        void anAdministratorReachesTheAdministrativeMenu() throws Exception {
            final JsonNode body = firstEntry(MenuController.ADMIN_MENU_PATH, administrativeSession());

            assertThat(body.get("adminMenuOptions")).hasSize(ADMIN_OPTION_COUNT);
            assertThat(labelsOf(body, "adminMenuOptions"))
                    .containsExactlyElementsOf(ADMIN_OPTION_LABELS);
        }

        // An established identity without the administrative authority is refused by the chain before
        // the handler runs, at the status the configuration installs for that condition.
        @Test
        @DisplayName("a non-administrator is refused the administrative menu at the status the chain "
                + "declares for an unentitled identity")
        void aNonAdministratorIsRefusedTheAdministrativeMenu() throws Exception {
            final MvcResult result = submit(MenuController.ADMIN_MENU_PATH, ordinarySession(),
                    firstEntryState(), null, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(summaryOf(result)).isEqualTo(ACCESS_DENIED);
        }

        // A request with no session at all is a different condition and is answered differently, which is
        // the distinction the estate drew between refusing a sign-on and routing a signed-on operator away.
        @Test
        @DisplayName("a request presenting no session is refused before the handler, at the other status")
        void aRequestPresentingNoSessionIsRefused() throws Exception {
            final MvcResult onAdmin = submit(MenuController.ADMIN_MENU_PATH, null, firstEntryState(),
                    null, null);
            final MvcResult onUser = submit(MenuController.USER_MENU_PATH, null, firstEntryState(),
                    null, null);

            assertThat(onAdmin.getResponse().getStatus()).isEqualTo(401);
            assertThat(summaryOf(onAdmin)).isEqualTo(AUTHENTICATION_REQUIRED);
            assertThat(onUser.getResponse().getStatus())
                    .as("the main menu carries no entitlement rule, but it still requires a session")
                    .isEqualTo(401);
            assertThat(summaryOf(onUser)).isEqualTo(AUTHENTICATION_REQUIRED);
        }

        // An unreadable session establishes nothing, so it reaches the same arm as no session at all.
        @Test
        @DisplayName("an unreadable session establishes nothing and is refused the same way")
        void anUnreadableSessionEstablishesNothing() throws Exception {
            final MvcResult result = submit(MenuController.ADMIN_MENU_PATH,
                    BEARER_PREFIX + "not-a-readable-session", firstEntryState(), null, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(summaryOf(result)).isEqualTo(AUTHENTICATION_REQUIRED);
        }

        // CM00 is not in the administrative set, so every signed-on operator reaches it whatever code the
        // record carries - the administrative one included, because no rule excludes it.
        @Test
        @DisplayName("both declared user types reach the main menu, because CM00 is not gated")
        void bothDeclaredUserTypesReachTheMainMenu() throws Exception {
            final JsonNode asUser = firstEntry(MenuController.USER_MENU_PATH, ordinarySession());
            final JsonNode asAdmin = firstEntry(MenuController.USER_MENU_PATH, administrativeSession());

            assertThat(asUser.get("userMenuOptions")).hasSize(USER_OPTION_COUNT);
            assertThat(asAdmin.get("userMenuOptions")).hasSize(USER_OPTION_COUNT);
            assertThat(textOf(asAdmin, "transactionName")).isEqualTo(USER_MENU_TRANSACTION);
        }

        // The stored code column is one character wide and carries no check constraint, so a code the
        // estate never declared is representable. It resolves to no declared type, and a caller carrying
        // no declared type is not an administrator.
        @Test
        @DisplayName("a stored code outside the declared vocabulary is treated as a non-administrator")
        void aStoredCodeOutsideTheDeclaredVocabularyIsNonAdministrator() throws Exception {
            final String session = sessionFor(UNDECLARED_CODE_IDENTITY, UNDECLARED_TYPE_CODE);

            final JsonNode onUserMenu = firstEntry(MenuController.USER_MENU_PATH, session);
            assertThat(onUserMenu.get("userMenuOptions")).hasSize(USER_OPTION_COUNT);

            final MvcResult onAdminMenu = submit(MenuController.ADMIN_MENU_PATH, session,
                    firstEntryState(), null, null);
            assertThat(onAdminMenu.getResponse().getStatus()).isEqualTo(403);
            assertThat(summaryOf(onAdminMenu)).isEqualTo(ACCESS_DENIED);
        }

        // The type vocabulary matches its two codes exactly and folds nothing, so the administrator letter
        // in lower case is not the administrator code.
        @Test
        @DisplayName("the administrator letter in lower case is not the administrator code, because the "
                + "vocabulary performs no case folding")
        void theAdministratorLetterInLowerCaseIsNotTheAdministratorCode() throws Exception {
            assertThat(UserType.fromCode(LOWER_CASE_ADMIN_CODE))
                    .as("the lookup is an exact match over the two declared codes")
                    .isEmpty();
            assertThat(UserType.fromCode(ADMIN_TYPE_CODE)).contains(UserType.ADMIN);

            final String session = sessionFor(LOWER_CASE_CODE_IDENTITY, LOWER_CASE_ADMIN_CODE);

            assertThat(firstEntry(MenuController.USER_MENU_PATH, session).get("userMenuOptions"))
                    .hasSize(USER_OPTION_COUNT);
            final MvcResult onAdminMenu = submit(MenuController.ADMIN_MENU_PATH, session,
                    firstEntryState(), null, null);
            assertThat(onAdminMenu.getResponse().getStatus()).isEqualTo(403);
        }

        // The gate is one rule over one prefix and everything beneath it. The two addresses are read from
        // the boundary and the prefix from the configuration, so neither is guessed here.
        @Test
        @DisplayName("the administrative address sits beneath the gated prefix and the main menu's does "
                + "not, and the gated set is not broadened")
        void theAdministrativeAddressSitsBeneathTheGatedPrefix() {
            assertThat(MenuController.ADMIN_MENU_PATH)
                    .startsWith(SecurityConfig.ADMIN_PATH_PREFIX + "/");
            assertThat(MenuController.USER_MENU_PATH)
                    .doesNotStartWith(SecurityConfig.ADMIN_PATH_PREFIX);
            // The definitions declare eighteen transactions and gate five of them. The other four are
            // user administration and are answered elsewhere; naming them here records that this
            // specification adds no sixth.
            assertThat(OTHER_GATED_TRANSACTIONS).hasSize(4).doesNotContain(ADMIN_MENU_TRANSACTION)
                    .doesNotContain(USER_MENU_TRANSACTION);
        }

        // A refusal is actionable without describing the rule that produced it. Nothing about the
        // condition, the graph, the schema, the configuration or the legacy estate may leak.
        @Test
        @DisplayName("no refusal body discloses a stack trace, a type name, a statement, a secret, an "
                + "address or any legacy artefact")
        void noRefusalBodyDisclosesAnything() throws Exception {
            final List<MvcResult> refusals = List.of(
                    submit(MenuController.ADMIN_MENU_PATH, ordinarySession(), firstEntryState(), null,
                            null),
                    submit(MenuController.ADMIN_MENU_PATH, null, firstEntryState(), null, null));

            for (final MvcResult refusal : refusals) {
                final String body = refusal.getResponse().getContentAsString(StandardCharsets.UTF_8);
                assertThat(body).isNotEmpty()
                        .doesNotContain("Exception")
                        .doesNotContain("Throwable")
                        .doesNotContain("com.carddemo")
                        .doesNotContain("org.springframework")
                        .doesNotContain("\tat ")
                        .doesNotContain("select ")
                        .doesNotContain("SELECT ")
                        .doesNotContain("insert into")
                        .doesNotContain("user_security")
                        .doesNotContain("jdbc:")
                        .doesNotContain("postgres")
                        .doesNotContain("carddemo.security")
                        .doesNotContain("Bearer ey")
                        .doesNotContain(MenuController.ADMIN_MENU_PATH)
                        .doesNotContain(SecurityConfig.ADMIN_PATH_PREFIX)
                        .doesNotContain(DELIVERED_USER)
                        .doesNotContain(ADMIN_MENU_PROGRAM)
                        .doesNotContain(ADMIN_MENU_TRANSACTION)
                        .doesNotContain("EXEC CICS")
                        .doesNotContain("DFH")
                        .doesNotContain("//STEP")
                        .doesNotContain("PIC ")
                        .doesNotContain("RESP");
                assertThat(body.toLowerCase(Locale.ROOT))
                        .doesNotContain("secret")
                        .doesNotContain("stack");
            }
        }

        /**
         * Reads the summary a refusal carries, in the module's own envelope.
         *
         * @param  result the completed request
         * @return the summary text, or {@code null} when the body carries none
         * @throws Exception if the body cannot be parsed
         */
        private String summaryOf(final MvcResult result) throws Exception {
            final String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).as("a refusal carries the module's own envelope rather than an empty body")
                    .isNotEmpty();
            return textOf(JSON.readTree(body), "message");
        }
    }

    // ===============================================================================================
    // GROUP 7 - TITLE AND SHARED-MESSAGE FIDELITY
    // ===============================================================================================

    /**
     * Three widths that must never be confused: three forty-character titles, two fifty-character shared
     * messages, and one thirty-three-character refusal whose last byte is a space.
     *
     * <p>Every one is asserted at its full stored width, with a length assertion beside it. Nothing here
     * trims, strips, or compares whitespace-insensitively: the fields these values come from are
     * fixed-width and space-significant, so a trailing space is part of the value and not formatting.
     */
    @Nested
    @DisplayName("Title and common-message fidelity")
    class TitleAndMessageFidelity {

        /** Creates the group. */
        TitleAndMessageFidelity() {
            super();
        }

        // app/cpy/COTTL01Y.cpy L19, carried onto both menu screens by their header paragraphs.
        @Test
        @DisplayName("the first title crosses at forty characters with its leading and trailing spaces "
                + "intact, on both menus")
        void theFirstTitleCrossesAtFortyCharacters() throws Exception {
            final JsonNode onUserMenu = firstEntry(MenuController.USER_MENU_PATH, ordinarySession());
            final JsonNode onAdminMenu =
                    firstEntry(MenuController.ADMIN_MENU_PATH, administrativeSession());

            assertThat(textOf(onUserMenu, "title01")).isEqualTo(TITLE01_40).hasSize(TITLE_WIDTH);
            assertThat(textOf(onAdminMenu, "title01")).isEqualTo(TITLE01_40).hasSize(TITLE_WIDTH);
            assertThat(messages.screenTitle01()).isEqualTo(TITLE01_40).hasSize(TITLE_WIDTH);
        }

        // L22 holds the active value and L21 an alternative that is commented out. The alternative stays
        // inactive: activating it would change a screen an operator reads.
        @Test
        @DisplayName("the second title is the active value, and the commented alternative appears nowhere")
        void theSecondTitleIsTheActiveValue() throws Exception {
            final JsonNode onUserMenu = firstEntry(MenuController.USER_MENU_PATH, ordinarySession());
            final JsonNode onAdminMenu =
                    firstEntry(MenuController.ADMIN_MENU_PATH, administrativeSession());

            assertThat(textOf(onUserMenu, "title02")).isEqualTo(TITLE02_40).hasSize(TITLE_WIDTH);
            assertThat(textOf(onAdminMenu, "title02")).isEqualTo(TITLE02_40).hasSize(TITLE_WIDTH);
            assertThat(messages.screenTitle02()).isEqualTo(TITLE02_40).hasSize(TITLE_WIDTH);
            assertThat(INACTIVE_TITLE02_ALTERNATIVE).hasSize(TITLE_WIDTH);
            assertThat(onUserMenu.toString()).doesNotContain(INACTIVE_TITLE02_ALTERNATIVE);
            assertThat(onAdminMenu.toString()).doesNotContain(INACTIVE_TITLE02_ALTERNATIVE);
            assertThat(messages.screenTitle02()).isNotEqualTo(INACTIVE_TITLE02_ALTERNATIVE);
        }

        // L24 declares a third and entirely distinct forty-character value. It names a different product
        // token from the fifty-character courtesy message and is never substituted for it.
        @Test
        @DisplayName("the courtesy title is a third distinct forty-character value")
        void theCourtesyTitleIsAThirdDistinctValue() {
            assertThat(messages.screenTitleThankYou()).isEqualTo(COURTESY_TITLE_40)
                    .hasSize(TITLE_WIDTH);
            assertThat(COURTESY_TITLE_40).isNotEqualTo(TITLE01_40).isNotEqualTo(TITLE02_40);
            assertThat(COURTESY_TITLE_40).isNotEqualTo(THANK_YOU_MESSAGE_50);
        }

        // app/cpy/CSMSG01Y.cpy L19 and L21 declare fifty-character fields and write shorter literals into
        // them, so each stored value carries its own count of trailing spaces.
        @Test
        @DisplayName("both shared messages are fifty-character padded values, at their full width")
        void bothSharedMessagesAreFiftyCharacterPaddedValues() {
            assertThat(messages.thankYouMessage()).isEqualTo(THANK_YOU_MESSAGE_50)
                    .hasSize(COMMON_MESSAGE_WIDTH)
                    .endsWith(" ");
            assertThat(messages.invalidKeyMessage()).isEqualTo(INVALID_KEY_MESSAGE_50)
                    .hasSize(COMMON_MESSAGE_WIDTH)
                    .endsWith(" ");
            assertThat(THANK_YOU_MESSAGE_50).isNotEqualTo(INVALID_KEY_MESSAGE_50);
        }

        // The any-other-key alternative of the key evaluation at app/cbl/COMEN01C.cbl L99-L102 moves the
        // shared unmapped-key message, so the whole padded value crosses the wire untouched.
        @Test
        @DisplayName("an unmapped key carries the shared message across the wire at its full fifty "
                + "characters, padding included")
        void anUnmappedKeyCarriesTheSharedMessageAtFullWidth() throws Exception {
            final JsonNode body = bodyOf(submit(MenuController.USER_MENU_PATH, ordinarySession(),
                    reEntryState(), KeyAction.PFK05, null));

            assertThat(textOf(body, "message")).isEqualTo(INVALID_KEY_MESSAGE_50)
                    .hasSize(COMMON_MESSAGE_WIDTH);
            assertThat(body.get("errorFlag").asBoolean()).isTrue();
            assertThat(textOf(body, "messageSeverity")).isEqualTo("ERROR");
            assertThat(textOf(body, "nextRoute")).isEqualTo(USER_MENU_ROUTE);
        }

        // The administrative menu shares the same key evaluation and therefore the same padded value.
        @Test
        @DisplayName("the administrative menu carries the identical padded value for an unmapped key")
        void theAdministrativeMenuCarriesTheIdenticalPaddedValue() throws Exception {
            final JsonNode body = bodyOf(submit(MenuController.ADMIN_MENU_PATH,
                    administrativeSession(), reEntryState(), KeyAction.PFK05, null));

            assertThat(textOf(body, "message")).isEqualTo(INVALID_KEY_MESSAGE_50)
                    .hasSize(COMMON_MESSAGE_WIDTH);
            assertThat(body.get("errorFlag").asBoolean()).isTrue();
        }

        // app/cbl/COMEN01C.cbl L140. Thirty-three characters and the thirty-third is a space, which is
        // part of the value the program moves and therefore part of what is asserted.
        @Test
        @DisplayName("the administrator-only refusal is thirty-three characters, trailing space included")
        void theAdministratorOnlyRefusalIsThirtyThreeCharacters() {
            final MenuService transaction = transactionOver(new SubstitutedCatalogue(catalogue,
                    // The gate compares the entry's own code against the administrator letter, so an
                    // entry carrying that letter is what makes the gate observable at all.
                    new MenuOptionSource.UserMenuOption(1, USER_OPTION_LABELS.get(0),
                            USER_OPTION_PROGRAMS.get(0), ADMIN_TYPE_CODE),
                    null));

            final MenuService.MenuScreen screen =
                    transaction.userMenu(carriedReEntry(), KeyAction.ENTER, "1", UserType.USER);

            assertThat(screen.message()).isEqualTo(ADMIN_ONLY_TEXT)
                    .hasSize(ADMIN_ONLY_TEXT_LENGTH)
                    .endsWith("... ");
            assertThat(screen.severity()).isEqualTo(MenuService.MessageSeverity.ERROR);
            assertThat(screen.errorFlag()).isTrue();
            assertThat(screen.nextRoute())
                    .as("the refusal re-presents the menu instead of transferring control")
                    .isEqualTo(USER_MENU_ROUTE);
        }

        // The gate's first condition is the standard-user condition and not "anything but an
        // administrator", so an administrator selecting the same entry is not refused by it.
        @Test
        @DisplayName("the refusal is unreachable with the shipped table, because every shipped entry "
                + "carries the standard code")
        void theRefusalIsUnreachableWithTheShippedTable() throws Exception {
            assertThat(catalogue.userMenuOptions())
                    .extracting(MenuOptionSource.UserMenuOption::userType)
                    .containsOnly(STANDARD_TYPE_CODE)
                    .doesNotContain(ADMIN_TYPE_CODE);

            final JsonNode body = enterOption(MenuController.USER_MENU_PATH, ordinarySession(), "1");
            assertThat(textOf(body, "message")).isNull();
            assertThat(body.toString()).doesNotContain("No access");
        }

        // Five values, three widths, no two of them interchangeable. Recorded as one assertion so a later
        // consolidation cannot quietly substitute one for another.
        @Test
        @DisplayName("the three forty-character titles and the two fifty-character messages are five "
                + "distinct values at two distinct widths")
        void theFiveValuesAreDistinctAtTwoWidths() {
            final List<String> titles = List.of(TITLE01_40, TITLE02_40, COURTESY_TITLE_40);
            final List<String> commonMessages = List.of(THANK_YOU_MESSAGE_50, INVALID_KEY_MESSAGE_50);

            assertThat(titles).doesNotHaveDuplicates()
                    .allSatisfy(title -> assertThat(title).hasSize(TITLE_WIDTH));
            assertThat(commonMessages).doesNotHaveDuplicates()
                    .allSatisfy(message -> assertThat(message).hasSize(COMMON_MESSAGE_WIDTH));
            assertThat(titles).doesNotContainAnyElementsOf(commonMessages);
            assertThat(ADMIN_ONLY_TEXT).hasSize(ADMIN_ONLY_TEXT_LENGTH)
                    .isNotIn(titles).isNotIn(commonMessages);
        }
    }

    // ===============================================================================================
    // GROUP 8 - NAVIGATION STATE
    // ===============================================================================================

    /**
     * The echoed navigation state, which is the REST-era replacement for the state every online
     * transaction carried across a turn and which seventeen textual inclusions collapse into.
     *
     * <p>Two module-wide properties are asserted here: every identifier crosses as bounded text that
     * keeps its leading zeros, never as a number; and the entry mode crosses as a typed value, never as
     * the one raw digit the legacy field held.
     */
    @Nested
    @DisplayName("Navigation state")
    class NavigationState {

        /** Creates the group. */
        NavigationState() {
            super();
        }

        // app/cpy/COCOM01Y.cpy L25-L26 declare an eight-character identifier and a one-character type.
        @Test
        @DisplayName("the response echoes the authenticated identifier at eight characters and the "
                + "matching type code")
        void theResponseEchoesTheAuthenticatedIdentity() throws Exception {
            final JsonNode asUser = firstEntry(MenuController.USER_MENU_PATH, ordinarySession());
            final JsonNode asAdmin = firstEntry(MenuController.ADMIN_MENU_PATH, administrativeSession());

            final JsonNode userState = asUser.get("navigationContext");
            assertThat(textOf(userState, "userId")).isEqualTo(DELIVERED_USER).hasSize(8);
            assertThat(textOf(userState, "userType")).isEqualTo(STANDARD_TYPE_CODE).hasSize(1);

            final JsonNode adminState = asAdmin.get("navigationContext");
            assertThat(textOf(adminState, "userId")).isEqualTo(DELIVERED_ADMIN).hasSize(8);
            assertThat(textOf(adminState, "userType")).isEqualTo(ADMIN_TYPE_CODE).hasSize(1);
        }

        // The state is echoed by the client, so none of it may be trusted: the identity members are
        // reconciled against the established principal before the response carries them back.
        @Test
        @DisplayName("an echoed identity is replaced by the authenticated one, so a client-supplied "
                + "identity never survives a turn")
        void anEchoedIdentityIsReplacedByTheAuthenticatedOne() throws Exception {
            final Map<String, Object> claimed = reEntryState();
            claimed.put("userId", "CLAIMED1");
            claimed.put("userType", ADMIN_TYPE_CODE);

            final JsonNode body = bodyOf(submit(MenuController.USER_MENU_PATH, ordinarySession(),
                    claimed, KeyAction.ENTER, "11"));
            final JsonNode state = body.get("navigationContext");

            assertThat(textOf(state, "userId")).isEqualTo(DELIVERED_USER);
            assertThat(textOf(state, "userType")).isEqualTo(STANDARD_TYPE_CODE);
            assertThat(body.toString()).doesNotContain("CLAIMED1");
        }

        // app/cpy/COCOM01Y.cpy L29-L31 hold the entry mode as one digit with two condition names. The
        // migration carries the two states and not the digit, so a client can never send 0 or 1.
        @Test
        @DisplayName("the entry mode crosses as a typed two-state value and never as the raw digit the "
                + "legacy field held")
        void theEntryModeCrossesAsATypedValue() throws Exception {
            final JsonNode firstTurn = firstEntry(MenuController.USER_MENU_PATH, ordinarySession());
            final JsonNode secondTurn = enterOption(MenuController.USER_MENU_PATH, ordinarySession(),
                    "11");

            final JsonNode firstMode = firstTurn.get("navigationContext").get("programContext");
            assertThat(firstMode.isTextual())
                    .as("a typed value crosses as its constant name; a raw digit would cross as a number")
                    .isTrue();
            assertThat(firstMode.isNumber()).isFalse();
            assertThat(firstMode.asText())
                    .as("presenting the screen arms the next turn, which is what the legacy program does "
                            + "before it sends")
                    .isEqualTo("REENTER")
                    .isNotEqualTo("0")
                    .isNotEqualTo("1");
            assertThat(secondTurn.get("navigationContext").get("programContext").asText())
                    .isEqualTo("REENTER");
            assertThat(List.of("ENTER", "REENTER")).contains(firstMode.asText());
        }

        // Every identifier the estate declares as digits crosses as bounded text, so a leading zero is
        // preserved rather than dropped by a numeric type.
        @Test
        @DisplayName("a bounded-text identifier round-trips unchanged, leading zeros preserved")
        void aBoundedTextIdentifierRoundTripsUnchanged() throws Exception {
            final Map<String, Object> carried = reEntryState();
            carried.put("customerId", "000000042");

            final JsonNode state = bodyOf(submit(MenuController.USER_MENU_PATH, ordinarySession(),
                    carried, KeyAction.ENTER, "11")).get("navigationContext");
            final JsonNode customerId = state.get("customerId");

            assertThat(customerId.isTextual())
                    .as("a numeric type would have dropped the leading zeros and changed the value")
                    .isTrue();
            assertThat(customerId.isNumber()).isFalse();
            assertThat(customerId.asText()).isEqualTo("000000042").hasSize(9).startsWith("000");
        }

        // The identifier the response echoes is text for the same reason, and the assertion is made on
        // the node rather than on a converted value so a numeric type could not pass it.
        @Test
        @DisplayName("the identifier and the type code are both textual nodes, never numeric ones")
        void theIdentifierAndTypeCodeAreBothTextualNodes() throws Exception {
            final JsonNode state = firstEntry(MenuController.USER_MENU_PATH, ordinarySession())
                    .get("navigationContext");

            assertThat(state.get("userId").isTextual()).isTrue();
            assertThat(state.get("userId").isNumber()).isFalse();
            assertThat(state.get("userType").isTextual()).isTrue();
        }

        // The routing members record where the turn came from and where it goes; a dispatch stamps the
        // responding transaction and program as the origin of the next screen.
        @Test
        @DisplayName("a dispatch stamps the responding transaction and program as the next screen's origin")
        void aDispatchStampsTheRespondingTransactionAndProgram() throws Exception {
            final JsonNode state = enterOption(MenuController.USER_MENU_PATH, ordinarySession(), "1")
                    .get("navigationContext");

            assertThat(textOf(state, "fromTransactionId")).isEqualTo(USER_MENU_TRANSACTION);
            assertThat(textOf(state, "fromProgram")).isEqualTo(USER_MENU_PROGRAM);
        }

        // A turn arriving with no prior state at all is directed to sign-on, unconditionally, which is
        // the zero-length branch at app/cbl/COMEN01C.cbl L82-L84 and app/cbl/COADM01C.cbl L82-L84.
        @Test
        @DisplayName("a turn carrying no prior state is directed to sign-on, on both menus")
        void aTurnCarryingNoPriorStateIsDirectedToSignOn() throws Exception {
            final JsonNode onUserMenu = bodyOf(submit(MenuController.USER_MENU_PATH, ordinarySession(),
                    new LinkedHashMap<>(), null, null));
            final JsonNode onAdminMenu = bodyOf(submit(MenuController.ADMIN_MENU_PATH,
                    administrativeSession(), new LinkedHashMap<>(), null, null));

            assertThat(textOf(onUserMenu, "nextRoute")).isEqualTo(SIGN_ON_ROUTE);
            assertThat(textOf(onAdminMenu, "nextRoute")).isEqualTo(SIGN_ON_ROUTE);
        }

        // The header items the programs compute per interaction come from the injected clock, which the
        // shared base pins, so they are the same on every run and no wall clock is read anywhere.
        @Test
        @DisplayName("the header date and time are the pinned clock's, at the widths the maps declare")
        void theHeaderDateAndTimeArePinned() throws Exception {
            final JsonNode body = firstEntry(MenuController.USER_MENU_PATH, ordinarySession());

            assertThat(textOf(body, "currentDate")).isEqualTo(PINNED_HEADER_DATE).hasSize(8);
            assertThat(textOf(body, "currentTime")).isEqualTo(PINNED_HEADER_TIME).hasSize(8);
        }

        // The screens carry field lengths, attribute bytes, cursor placement, highlighting and a
        // terminal-area prefix. None of it is modelled, and the focus hint is an opaque label rather than
        // a coordinate or an attribute.
        @Test
        @DisplayName("no screen-presentation artefact reaches the payload: no attribute byte, no control "
                + "group, no coordinate and no terminal-area filler")
        void noScreenPresentationArtefactReachesThePayload() throws Exception {
            final String payload =
                    firstEntry(MenuController.USER_MENU_PATH, ordinarySession()).toString();

            assertThat(payload)
                    .doesNotContain("DFHRED")
                    .doesNotContain("DFHGREEN")
                    .doesNotContain("DFHBMS")
                    .doesNotContain("OPTIONL")
                    .doesNotContain("OPTIONF")
                    .doesNotContain("OPTIONA")
                    .doesNotContain("OPTIONC")
                    .doesNotContain("OPTIONP")
                    .doesNotContain("OPTIONH")
                    .doesNotContain("OPTIONV")
                    .doesNotContain("COMEN1A")
                    .doesNotContain("ERRMSG");
            assertThat(textOf(firstEntry(MenuController.USER_MENU_PATH, ordinarySession()),
                    "focusScreenFieldId"))
                    .as("an opaque label at most as wide as the widest symbolic field name, and never a "
                            + "coordinate")
                    .isEqualTo(OPTION_FIELD_ID)
                    .hasSizeLessThanOrEqualTo(7);
        }
    }

    /**
     * A catalogue that renders the delivered rows but answers a single lookup with a substituted entry.
     *
     * <p>The rendered collections and both counts are the delivered ones, so a screen composed over this
     * catalogue is the screen the estate renders and the range test is the estate's range test. Only the
     * entry a chosen number resolves to differs, which is the single variable the suppressed-dispatch
     * guard and the administrator-only gate turn on.
     *
     * <p>Written out as a record implementing the shipped contract rather than produced by a mocking
     * framework: a record is deeply immutable, needs no reflection, and states in one place exactly what
     * it substitutes.
     *
     * @param delivered     the delivered catalogue, consulted for everything not substituted
     * @param userEntry     the user entry to answer with, or {@code null} to substitute none
     * @param adminEntry    the administrative entry to answer with, or {@code null} to substitute none
     */
    private record SubstitutedCatalogue(MenuOptionSource delivered,
                                        MenuOptionSource.UserMenuOption userEntry,
                                        MenuOptionSource.AdminMenuOption adminEntry)
            implements MenuOptionSource {

        @Override
        public List<MenuOptionSource.UserMenuOption> userMenuOptions() {
            return this.delivered.userMenuOptions();
        }

        @Override
        public List<MenuOptionSource.AdminMenuOption> adminMenuOptions() {
            return this.delivered.adminMenuOptions();
        }

        @Override
        public int userMenuOptionCount() {
            return this.delivered.userMenuOptionCount();
        }

        @Override
        public int adminMenuOptionCount() {
            return this.delivered.adminMenuOptionCount();
        }

        @Override
        public Optional<MenuOptionSource.UserMenuOption> findUserOption(final int number) {
            if (this.userEntry != null && this.userEntry.number() == number) {
                return Optional.of(this.userEntry);
            }
            return this.delivered.findUserOption(number);
        }

        @Override
        public Optional<MenuOptionSource.AdminMenuOption> findAdminOption(final int number) {
            if (this.adminEntry != null && this.adminEntry.number() == number) {
                return Optional.of(this.adminEntry);
            }
            return this.delivered.findAdminOption(number);
        }
    }

    // ===============================================================================================
    // THE GRAPH UNDER TEST
    // ===============================================================================================

    /**
     * The menu surface: the shipped boundary, the shipped filter chain, the shipped transaction, the two
     * delivered option tables and the credential master the identities live in.
     *
     * <p>Assembled explicitly rather than by scanning, which is this module's established practice for a
     * context-booting specification: a scan of the base package would sweep the test tree's own
     * configuration classes into the graph, and an explicit list lets a reader see in one place exactly
     * what took part.
     *
     * <p><strong>Nothing is stubbed.</strong> The option catalogue, the navigation vocabulary, the
     * shared-message catalogue, the two boundary adapters, the session issuer, the shared failure surface
     * and the filter chain are all the shipped ones, and the identities come off a real server. Only the
     * two groups that assert a path the shipped catalogue cannot reach compose the transaction over a
     * substituted table, and they build it from these same beans.
     *
     * <p>The clock is the pinned instant the shared base publishes, so the header date and time mean the
     * same thing on every run and no assertion here can depend on a wall clock.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {PrometheusExemplarsAutoConfiguration.class,
            BatchAutoConfiguration.class})
    @Import({MenuController.class, ModuleErrorController.class, MenuResponseAdapter.class,
        ConversationStateAdapter.class, ScreenStateAdapter.class, GlobalExceptionHandler.class,
        JsonRefusalBodyRenderer.class, MenuService.class, NavigationService.class,
        MessageCatalogService.class, MenuOptionCatalog.class, SignOnStateService.class,
        SecurityConfig.class, JwtTokenProvider.class, WebMvcConfig.class})
    @EnableConfigurationProperties(JwtProperties.class)
    @EnableJpaRepositories(basePackageClasses = UserSecurityRepository.class)
    @EntityScan(basePackageClasses = UserSecurity.class)
    static class MenuContext {

        /** Creates the slice. */
        MenuContext() {
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
