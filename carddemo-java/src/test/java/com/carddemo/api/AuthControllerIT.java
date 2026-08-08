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

import com.carddemo.api.dto.SignOnRequest;
import com.carddemo.api.dto.SignOnResponse;
import com.carddemo.config.JwtProperties;
import com.carddemo.config.JwtTokenProvider;
import com.carddemo.config.SecurityConfig;
import com.carddemo.config.WebMvcConfig;
import com.carddemo.domain.UserSecurity;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.AuthenticationService;
import com.carddemo.service.CredentialDigestService;
import com.carddemo.service.MessageCatalogService;
import com.carddemo.service.NavigationService;
import com.carddemo.service.SignOnStateService;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.TestDataFactory;
import com.carddemo.util.ApiRoutePaths;
import com.carddemo.util.CobolStringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The sign-on contract of transaction {@code CC00}, exercised over the shipped servlet boundary and the
 * shipped filter chain, against a real PostgreSQL server carrying the migrated schema.
 *
 * <p>This is the foundational integration specification of the {@code api} test package. It establishes
 * the pattern the other controller specifications reuse: the container comes from
 * {@link AbstractPostgresIT}, the graph is an explicit slice rather than a scan, the clock is pinned, the
 * identities this specification needs are written into a reserved key range and removed again, and a
 * session is obtained by signing on rather than by fabricating a token.
 *
 * <h2>What is asserted, and why each item is contract rather than detail</h2>
 * <ul>
 *   <li><strong>The five direct screen texts</strong> the transaction composes itself, character for
 *       character. An operator and any downstream tooling match on these, so a paraphrase reads
 *       correctly and is not the contract.</li>
 *   <li><strong>The two shared messages at their full fifty characters</strong>, trailing spaces intact.
 *       The shared catalogue declares a fifty-wide field and writes a forty-nine character literal into
 *       it, so the stored value is padded to exactly fifty. Nothing here trims, strips or matches a
 *       prefix of either, and each value assertion is accompanied by a length assertion so that a later
 *       refactor which trims fails loudly rather than silently.</li>
 *   <li><strong>The ordered blank cascade.</strong> One evaluation, three arms, first match wins. A
 *       submission with both entry fields empty answers the identifier prompt only - never the credential
 *       prompt and never both.</li>
 *   <li><strong>The error-flag asymmetry.</strong> A failed credential comparison composes a message and
 *       moves the cursor <em>without</em> raising the program's error switch, while the not-found and
 *       cannot-verify arms both raise it. The flag is a component of its own on the published contract and
 *       is never inferred from a message being present.</li>
 *   <li><strong>The two-way role split.</strong> The administrator code reaches the administrative menu;
 *       every other stored value, including an undeclared code and the administrator letter in lower
 *       case, reaches the user main menu. There is no third branch and no exception path.</li>
 *   <li><strong>The unconditional upper fold.</strong> The legacy fold of both the identifier and the
 *       submitted credential sits after the end of the cascade, so it runs on every submission. Its
 *       observable consequence is that the credential is effectively case-insensitive, which is preserved
 *       rather than corrected.</li>
 *   <li><strong>The security posture.</strong> The sign-on route is the one anonymous route; every other
 *       route refuses an absent or unusable session; the administrative prefix additionally refuses an
 *       ordinary session; and no refusal body discloses anything about the condition that produced it.
 *       </li>
 * </ul>
 *
 * <h2>Credential handling - the strictest constraint on this file</h2>
 * The legacy record holds an eight-character cleartext credential and the legacy sign-on path compares it
 * directly. The target hashes with BCrypt, which is a documented parity exception rather than a defect.
 * <strong>The legacy cleartext value appears nowhere in this file</strong> - not in a literal, a constant,
 * a comment, an identifier, a display name or an assertion message.
 *
 * <p>Where the credential is needed it is obtained at run time, by offset, from the class-path fixture,
 * through {@link TestDataFactory#fixtureCredentialWindow()}. Two measured facts recorded on that method
 * govern how it is used here, and both are the reason this specification writes its own identities rather
 * than signing on as a delivered one:
 * <ol>
 *   <li>The fixture's credential window carries a synthetic eight-character stand-in, deliberately not the
 *       legacy value, so the window is exercised at its full declared width without the legacy value
 *       existing inside this module.</li>
 *   <li>The ten digests the credential seed applies were produced from the legacy provisioning value, so
 *       they accept that value and refuse the window's. A delivered identity therefore cannot be
 *       authenticated from anything this module holds.</li>
 * </ol>
 * The honest consequence is the arrangement below. Every end-to-end credential assertion runs against an
 * identity this specification writes, whose stored digest the shipped {@link CredentialDigestService}
 * produced from the window value; every assertion about a <em>delivered</em> identity is limited to what a
 * module which does not hold the legacy value can answer - the stored role code, the digest's shape, and
 * the digest's refusal of a value it was not derived from. The divergence between this specification's
 * arrangement and the simpler one an earlier reading assumed belongs in {@code docs/decision-log.md},
 * which is owned elsewhere and is not edited from here.
 *
 * <p>No signing secret is written here either. The active profile supplies the suite fixture under the
 * {@code carddemo.security.jwt} prefix, and the two values restated in the annotation below are the
 * issuer and the lifetime - neither is a secret, and no secret is defaulted.
 *
 * <h2>Container ownership, determinism and reset</h2>
 * The database server belongs to {@link AbstractPostgresIT} and is shared by every integration
 * specification in the module, so this class declares no container, no container annotation, no data-source
 * property source and no context-discarding annotation. Time comes from that class's pinned clock, so
 * nothing here reads a wall clock, and no assertion anywhere depends on elapsed time, throughput or
 * memory. The identities this specification writes live under one reserved eight-character prefix which the
 * credential seed never occupies; they are removed before the first method and after the last, so the ten
 * delivered identities are exactly what every other specification finds.
 *
 * <h2>Where the expectations come from</h2>
 * Every text, route name, field identifier and role code below is written out as its own literal rather
 * than read from the component that also publishes it: an expectation which borrows its subject's own
 * constant proves only that the subject agrees with itself. Routes, verbs, media types, status codes and
 * property names, by contrast, are taken from the shipped boundary and its published contract types, because
 * those are the shape this specification has to speak to rather than claims it is making.
 *
 * <p>Provenance: {@code app/cbl/COSGN00C.cbl}, {@code app/cpy/CSMSG01Y.cpy}, {@code app/cpy/COTTL01Y.cpy},
 * {@code app/cpy-bms/COSGN00.CPY}, {@code app/cpy/CSUSR01Y.cpy}, {@code app/cpy/COCOM01Y.cpy},
 * {@code app/csd/CARDDEMO.CSD} and {@code app/jcl/DUSRSECJ.jcl}, all read as read-only reference. Message
 * texts, field widths, offsets, role codes, transaction identifiers and paragraph line numbers are contract
 * and metadata; no source line of any legacy member is transcribed, and nothing in the legacy tree is read
 * at run time.
 */
@SpringBootTest(classes = AuthControllerIT.SignOnContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            // The shared server was migrated to the head of the delivered set by the base class, so a
            // second migration from this context would be redundant work with no new state to apply.
            "spring.flyway.enabled=false",
            // validate, never none and never any generating value. The shipped profile declares validate and
            // this graph keeps it: a mapping that had drifted from the migrated schema must fail this
            // specification at refresh rather than be silently reconciled, and validate cannot emit DDL.
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.batch.job.enabled=false",
            "spring.main.banner-mode=off",
            "management.endpoint.health.validate-group-membership=false",
            "management.tracing.enabled=false",
            // The boundary here is a mock servlet inside this process, so there is no wire for a session
            // to be observed on and the transport requirement is relaxed exactly as the test profile
            // relaxes it. Nothing else is relaxed: the anonymous set and the administrative gate are the
            // shipped ones, which is the whole point of asserting against them.
            "carddemo.security.require-https=false",
            "carddemo.security.jwt.issuer=carddemo-java",
            "carddemo.security.jwt.expiration=PT15M"})
@AutoConfigureMockMvc
@DisplayName("Gate 5, contract two: the sign-on screen of transaction CC00 over the shipped boundary")
public class AuthControllerIT extends AbstractPostgresIT {

    // ===============================================================================================
    // THE CONTRACT, RESTATED HERE RATHER THAN IMPORTED
    // ===============================================================================================

    /**
     * First arm of the ordered cascade: the identifier arrived empty. Twenty-four characters, from
     * {@code app/cbl/COSGN00C.cbl} line 120.
     */
    private static final String PROMPT_FOR_USER_ID = "Please enter User ID ...";

    /**
     * Second arm of the same cascade, reached only when an identifier was supplied. Twenty-five
     * characters, from line 125.
     *
     * <p>The English word inside this text, and inside {@link #WRONG_CREDENTIAL_MESSAGE} below, is part of
     * an operator-visible sentence that the external interface fixes character for character - it is not,
     * and must not be read as, the eight-character field value the legacy record stored. That value appears
     * nowhere in this file in any form; where it is needed it is recovered by offset at run time and is
     * never held in a literal, a constant or an identifier.
     */
    private static final String PROMPT_FOR_CREDENTIAL = "Please enter Password ...";

    /**
     * The record was found and the comparison failed. Twenty-nine characters, from line 242 - the one
     * failure arm which leaves the error switch lowered.
     */
    private static final String WRONG_CREDENTIAL_MESSAGE = "Wrong Password. Try again ...";

    /** The keyed read reported no such record. Twenty-nine characters, from line 249. */
    private static final String USER_NOT_FOUND_MESSAGE = "User not found. Try again ...";

    /** Every read failure other than a missing record. Twenty-nine characters, from line 254. */
    private static final String UNABLE_TO_VERIFY_MESSAGE = "Unable to verify the User ...";

    /**
     * The shared courtesy message the exit key composes, at its full stored width.
     *
     * <p>{@code app/cpy/CSMSG01Y.cpy} declares a fifty-character field and writes a forty-nine character
     * literal into it, so the stored value is the forty-three character text followed by exactly seven
     * spaces. Written out in full, trailing spaces included, and asserted at full width.
     */
    private static final String THANK_YOU_MESSAGE_50 =
            "Thank you for using CardDemo application...       ";

    /**
     * The shared message any unmapped attention key composes, at its full stored width: the forty
     * character text followed by exactly ten spaces, from the same fifty-character field.
     */
    private static final String INVALID_KEY_MESSAGE_50 =
            "Invalid key pressed. Please see below...          ";

    /**
     * A third, entirely distinct courtesy value, declared forty characters wide in
     * {@code app/cpy/COTTL01Y.cpy}: the thirty-nine character text followed by one space.
     *
     * <p>It names a different product token and occupies a different width, so it is never a substitute
     * for either fifty-character value and is never asserted interchangeably with them. It is asserted
     * separately, at width forty, and asserted to differ from both.
     */
    private static final String CCDA_COURTESY_TITLE_40 = "Thank you for using CCDA application... ";

    /** Width of the two shared messages, as their common field declares it. */
    private static final int COMMON_MESSAGE_WIDTH = 50;

    /** Width of the screen title items, as their own field declares it. */
    private static final int SCREEN_TITLE_WIDTH = 40;

    /** Width of the identifier field on the sign-on map and of the identifier column that stores it. */
    private static final int USER_ID_WIDTH = 8;

    /** Symbolic name of the identifier field the cursor returns to. */
    private static final String FIELD_USER_ID = "USERID";

    /** Symbolic name of the credential field the cursor returns to. */
    private static final String FIELD_CREDENTIAL_ITEM = "PASSWD";

    /** Wire value of the destination an administrative identity reaches. */
    private static final String ADMIN_MENU_ROUTE = "admin-menu";

    /** Wire value of the destination every other identity reaches. */
    private static final String USER_MENU_ROUTE = "user-menu";

    /** Transaction the administrative menu is registered under in the resource definitions. */
    private static final String ADMIN_MENU_TRANSACTION = "CA00";

    /** Transaction the user main menu is registered under in the same definitions. */
    private static final String USER_MENU_TRANSACTION = "CM00";

    /** Transaction the sign-on screen itself is registered under. */
    private static final String SIGN_ON_TRANSACTION = "CC00";

    /** Program the sign-on transaction is bound to. */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /** The role code the administrative alternative tests for. */
    private static final String ADMIN_ROLE_CODE = "A";

    /** The role code an ordinary identity carries. */
    private static final String USER_ROLE_CODE = "U";

    /** Presentation prefix an issued session travels behind. */
    private static final String BEARER_PREFIX = "Bearer ";

    /** Summary a refusal for want of a session carries. */
    private static final String AUTHENTICATION_REQUIRED = "Authentication required";

    /** Summary a refusal for want of an entitlement carries. */
    private static final String ACCESS_DENIED = "Access denied";

    // ===============================================================================================
    // THE IDENTITIES THIS SPECIFICATION OWNS
    // ===============================================================================================

    /**
     * Prefix of the eight-character identifier range this specification reserves.
     *
     * <p>The credential seed applies under this profile, so the table is not empty when a method starts:
     * it holds the ten delivered identities. Everything written here is keyed inside a range the seed
     * never occupies, and the cleanup is scoped to that range, so nothing here deletes, counts or mutates
     * a delivered row. Mutating one would be worse than untidy: several specifications in this module
     * assert against the delivered rows on the same shared server.
     */
    private static final String RESERVED_PREFIX = "ITAUTH";

    /** Administrative identity, for the first arm of the role split. */
    private static final String ADMIN_IDENTITY = RESERVED_PREFIX + "01";

    /** Ordinary identity, for the alternative arm. */
    private static final String USER_IDENTITY = RESERVED_PREFIX + "02";

    /** Identity whose stored role code the estate never declared, for the fall-through. */
    private static final String UNDECLARED_ROLE_IDENTITY = RESERVED_PREFIX + "03";

    /** Identity whose stored role code is the administrator letter in lower case. */
    private static final String LOWER_CASE_ROLE_IDENTITY = RESERVED_PREFIX + "04";

    /**
     * Identity whose stored digest was produced from the unfolded window value.
     *
     * <p>It exists to prove that the fold is unconditional rather than incidental: because the shipped
     * path folds every submission before it compares, this identity is refused even when the exact
     * characters its digest was derived from are submitted.
     */
    private static final String UNFOLDED_DIGEST_IDENTITY = RESERVED_PREFIX + "05";

    /** An identifier inside the reserved range that is never written, for the not-found arm. */
    private static final String ABSENT_IDENTITY = RESERVED_PREFIX + "99";

    /** A delivered administrative identity, named for what its stored role code is asserted to be. */
    private static final String DELIVERED_ADMIN_IDENTITY = "ADMIN001";

    /** A delivered ordinary identity, in the same terms. */
    private static final String DELIVERED_USER_IDENTITY = "USER0001";

    /** Given name every identity this specification writes carries; identity, never a secret. */
    private static final String RESERVED_FIRST_NAME = "INTEGRATION";

    /** Family name every identity this specification writes carries. */
    private static final String RESERVED_LAST_NAME = "SIGNONCONTRACT";

    /**
     * A value the shipped digest of an owned identity was demonstrably not derived from.
     *
     * <p>Three properties, each load-bearing. It is <strong>exactly eight characters</strong>, because the
     * map declares the credential field at that width and the published contract enforces it - a wider
     * value is answered as a malformed request and would never reach the comparison at all, so it would
     * test the validator instead of the arm under test. It is <strong>upper case throughout</strong>, so
     * the shipped fold cannot turn it into anything else and the refusal is genuinely about the comparison
     * rather than about the fold. And it is <strong>obviously synthetic</strong> and unrelated to the
     * window value and to the legacy one alike - the fixture's window carries digits, so the folded value
     * an owned digest was derived from cannot coincide with this, and the behavioural refusal below proves
     * it does not.
     */
    private static final String NOT_THE_STORED_CREDENTIAL = "NOTVALID";

    /** Reads and writes JSON without binding to a published contract type. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The shipped hashing service, held here so the two owned digests are produced once per run.
     *
     * <p>Instantiated directly rather than injected because it is used during class initialisation, before
     * any context exists. It needs no configuration: a digest carries its own salt and its own cost, and
     * the type fixes the cost itself. The injected instance is used for everything else, so nothing here
     * verifies against a differently configured verifier than the boundary uses.
     */
    private static final CredentialDigestService OWNED_DIGEST_SOURCE = new CredentialDigestService();

    /**
     * Digest of the folded window value: what an owned identity stores so that it can genuinely be signed
     * on with.
     *
     * <p>Folded rather than raw because the shipped path folds every submission before it compares, so a
     * digest of the raw characters could never be matched by any submission at all. Producing this from
     * the folded form is what makes the owned identities usable and is not a workaround for the fold - the
     * fold is separately asserted, by {@link #UNFOLDED_DIGEST_IDENTITY}, which stores the raw form and is
     * therefore refused.
     */
    private static final String DIGEST_OF_FOLDED_WINDOW = digestOfFoldedCredentialWindow();

    /** Digest of the unfolded window value, stored by the one identity that exists to be refused. */
    private static final String DIGEST_OF_UNFOLDED_WINDOW = digestOfRawCredentialWindow();

    /** The shipped servlet boundary, with the shipped filter chain in front of it. */
    @Autowired
    private MockMvc mockMvc;

    /** The credential master, for writing and removing owned identities. */
    @Autowired
    private UserSecurityRepository users;

    /** The shipped hashing and verification service; nothing here re-implements either. */
    @Autowired
    private CredentialDigestService credentials;

    /** The shipped shared-message catalogue, for the assertions that are about the catalogue itself. */
    @Autowired
    private MessageCatalogService messages;

    /** The shipped projection from a served turn onto the published contract. */
    @Autowired
    private SignOnContractAdapter contractAdapter;

    /** Creates the specification. */
    public AuthControllerIT() {
        super();
    }

    // ===============================================================================================
    // LIFECYCLE
    // ===============================================================================================

    /**
     * Returns the shared server to its delivered state, so the ten delivered identities are present and the
     * reserved range is empty however the run that preceded this one ended.
     *
     * @throws SQLException if the delivered state cannot be restored
     */
    @BeforeAll
    static void restoreDeliveredIdentities() throws SQLException {
        restoreSeededState();
    }

    /**
     * Writes the five identities this specification owns, immediately before every method.
     *
     * <p>Writing rather than reusing is deliberate: a method that removed a row would otherwise change what
     * the next method finds, and the delivered rows must not be borrowed for this because none of them can
     * be signed on with from inside this module. The two digests were produced once at class
     * initialisation, so this is five cheap merges rather than five hashes.
     */
    @BeforeEach
    void writeOwnedIdentities() {
        writeOwnedIdentity(ADMIN_IDENTITY, ADMIN_ROLE_CODE, DIGEST_OF_FOLDED_WINDOW);
        writeOwnedIdentity(USER_IDENTITY, USER_ROLE_CODE, DIGEST_OF_FOLDED_WINDOW);
        // A single character the estate never declared. The column is one character wide and carries no
        // check constraint, so an undeclared code is representable exactly as the legacy record made it
        // representable, and no schema change is needed to write one.
        writeOwnedIdentity(UNDECLARED_ROLE_IDENTITY, "X", DIGEST_OF_FOLDED_WINDOW);
        // The administrator letter in lower case. The role vocabulary matches exactly and folds nothing,
        // so this must not be read as the administrative code.
        writeOwnedIdentity(LOWER_CASE_ROLE_IDENTITY, "a", DIGEST_OF_FOLDED_WINDOW);
        writeOwnedIdentity(UNFOLDED_DIGEST_IDENTITY, USER_ROLE_CODE, DIGEST_OF_UNFOLDED_WINDOW);
    }

    /**
     * Removes only the rows this specification wrote.
     *
     * <p>Scoped to the five reserved identifiers by name, so the ten delivered identities survive every
     * method and every specification that runs after this one finds exactly the seed.
     */
    @AfterEach
    void clearOwnedIdentities() {
        for (final String owned : ownedIdentities()) {
            this.users.deleteById(owned);
        }
    }

    // ===============================================================================================
    // HELPERS
    // ===============================================================================================

    /**
     * The five identifiers this specification writes, in the order it writes them.
     *
     * @return the reserved identifiers
     */
    private static List<String> ownedIdentities() {
        return List.of(ADMIN_IDENTITY, USER_IDENTITY, UNDECLARED_ROLE_IDENTITY,
                LOWER_CASE_ROLE_IDENTITY, UNFOLDED_DIGEST_IDENTITY);
    }

    /**
     * Writes one owned identity through the credential master.
     *
     * <p>Built with the entity's own five-argument constructor by way of the shared factory, so the stored
     * value is a digest by construction: the entity refuses anything that is not shaped like one, which is
     * what makes it impossible for this specification to store a cleartext value even by accident.
     *
     * @param userId   the reserved identifier
     * @param roleCode the one-character role code to store
     * @param digest   the digest to store
     */
    private void writeOwnedIdentity(final String userId, final String roleCode, final String digest) {
        final UserSecurity identity = TestDataFactory.userSecurity()
                .userId(userId)
                .firstName(RESERVED_FIRST_NAME)
                .lastName(RESERVED_LAST_NAME)
                .userTypeCode(roleCode)
                .storedDigest(digest)
                .build();
        this.users.save(identity);
    }

    /**
     * Produces a digest of the folded contents of the class-path fixture's credential window.
     *
     * <p>The window is read by offset, folded with the shipped fold, handed to the shipped hashing service
     * and then overwritten. Neither the window value nor its folded form is returned, logged, printed or
     * asserted on anywhere.
     *
     * @return a digest the shipped verifier accepts for a folded submission of the window value
     */
    private static String digestOfFoldedCredentialWindow() {
        final char[] window = TestDataFactory.fixtureCredentialWindow();
        try {
            return OWNED_DIGEST_SOURCE.encode(CobolStringUtils.asciiUpperFold(new String(window)));
        } finally {
            Arrays.fill(window, ' ');
        }
    }

    /**
     * Produces a digest of the unfolded contents of the same window.
     *
     * @return a digest no submission can match, because every submission is folded before comparison
     */
    private static String digestOfRawCredentialWindow() {
        final char[] window = TestDataFactory.fixtureCredentialWindow();
        try {
            return OWNED_DIGEST_SOURCE.encode(CharBuffer.wrap(window));
        } finally {
            Arrays.fill(window, ' ');
        }
    }

    /**
     * Returns the contents of the credential window as the operator would key them.
     *
     * <p><strong>The return value is a credential stand-in and is treated as one:</strong> it is passed
     * straight into a request body and is never logged, printed, named or asserted on.
     *
     * @return the eight characters the fixture carries in its credential window
     */
    private static String keyedCredential() {
        final char[] window = TestDataFactory.fixtureCredentialWindow();
        try {
            return new String(window);
        } finally {
            Arrays.fill(window, ' ');
        }
    }

    /**
     * Submits one turn of the sign-on screen and returns the whole result.
     *
     * @param  userId    the identifier to submit, or {@code null} to omit the property entirely
     * @param  credential the credential to submit, or {@code null} to omit the property entirely
     * @param  keyAction the attention key to submit, or {@code null} to omit the property entirely
     * @return the result, so both the body and the headers can be inspected
     * @throws Exception if the boundary cannot be reached
     */
    private MvcResult submit(final String userId, final String credential, final KeyAction keyAction)
            throws Exception {
        final Map<String, Object> payload = new LinkedHashMap<>();
        if (userId != null) {
            payload.put("userId", userId);
        }
        if (credential != null) {
            // The property name is the published contract type's own component name, read from it rather
            // than chosen here: a client speaks the name the boundary declares, and inventing one would
            // make this specification pass against a contract nothing ships.
            payload.put("password", credential);
        }
        if (keyAction != null) {
            payload.put("keyAction", keyAction.name());
        }
        return this.mockMvc.perform(MockMvcRequestBuilders.post(AuthController.SIGN_ON_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(payload)))
                .andReturn();
    }

    /**
     * Submits one turn with the attention key that submits a screen.
     *
     * @param  userId   the identifier to submit
     * @param  credential the credential to submit
     * @return the parsed response body
     * @throws Exception if the boundary cannot be reached
     */
    private JsonNode enter(final String userId, final String credential) throws Exception {
        return bodyOf(submit(userId, credential, KeyAction.ENTER));
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
                .as("every outcome of this transaction is a screen the legacy program composed and sent, "
                        + "rejections included, so the turn completes with the same status in all of them")
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
     * Reads the error switch, which is a component of its own and is never inferred from anything else.
     *
     * @param  body the parsed body
     * @return the value the boundary published
     */
    private static boolean generalErrorOf(final JsonNode body) {
        final JsonNode node = body.get("generalError");
        assertThat(node)
                .as("the error switch is an explicit component of the published contract, so a body that "
                        + "omits it would leave every caller inferring it from a message being present - "
                        + "which is exactly the inference the wrong-credential arm defeats")
                .isNotNull();
        return node.asBoolean();
    }

    /**
     * Signs on as an owned identity and returns the session it was issued.
     *
     * @param  userId the owned identity to sign on as
     * @return the bare session token, without its presentation prefix
     * @throws Exception if the boundary cannot be reached
     */
    private String sessionFor(final String userId) throws Exception {
        final MvcResult result = submit(userId, keyedCredential(), KeyAction.ENTER);
        final String header = result.getResponse().getHeader(HttpHeaders.AUTHORIZATION);
        assertThat(header)
                .as("an admitted turn issues a session in the authorization header; %s was not admitted",
                        userId)
                .isNotNull()
                .startsWith(BEARER_PREFIX);
        return header.substring(BEARER_PREFIX.length());
    }

    // ===============================================================================================
    // BLANK-FIELD CASCADE
    // ===============================================================================================

    /**
     * The ordered presence evaluation of {@code PROCESS-ENTER-KEY}, lines 118 to 131.
     *
     * <p>One evaluation, three arms, evaluated top to bottom and stopping at the first match. The order is
     * the contract and not a preference, because the two conditions overlap: a submission with both fields
     * empty satisfies both arms and must answer only the first.
     */
    @Nested
    @DisplayName("Blank-field cascade: one ordered evaluation, first match wins")
    class BlankFieldCascade {

        /** Creates the nested specification. */
        BlankFieldCascade() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("both fields empty answers the identifier prompt ONLY, never the credential prompt "
                + "and never both")
        void bothFieldsEmptyAnswersTheIdentifierPromptAlone() throws Exception {
            // Lines 118 to 123 against lines 124 to 129: the construct stops at its first matching clause,
            // so the second arm is unreachable on this submission however plainly its own condition holds.
            final JsonNode body = enter("", "");

            assertThat(textOf(body, "message"))
                    .as("the first arm of the cascade, character for character")
                    .isEqualTo(PROMPT_FOR_USER_ID);
            assertThat(textOf(body, "message"))
                    .as("the second arm must not be reached, and its text must not appear even as a "
                            + "fragment of a combined message")
                    .doesNotContain(PROMPT_FOR_CREDENTIAL);
            assertThat(textOf(body, "focusScreenFieldId"))
                    .as("the cursor returns to the field the first arm singled out")
                    .isEqualTo(FIELD_USER_ID);
            assertThat(generalErrorOf(body))
                    .as("the first arm raises the error switch")
                    .isTrue();
        }

        @Test
        @DisplayName("an empty identifier with a credential supplied answers the identifier prompt")
        void anEmptyIdentifierAnswersTheIdentifierPrompt() throws Exception {
            // Line 120, reached with the second arm's condition unsatisfied.
            final JsonNode body = enter("", NOT_THE_STORED_CREDENTIAL);

            assertThat(textOf(body, "message")).isEqualTo(PROMPT_FOR_USER_ID);
            assertThat(textOf(body, "focusScreenFieldId")).isEqualTo(FIELD_USER_ID);
            assertThat(generalErrorOf(body)).isTrue();
        }

        @Test
        @DisplayName("an empty credential with an identifier supplied answers the credential prompt and "
                + "moves the cursor to the credential field")
        void anEmptyCredentialAnswersTheCredentialPrompt() throws Exception {
            // Lines 124 to 126: the second arm, which is the only arm that nominates the credential field.
            final JsonNode body = enter(ADMIN_IDENTITY, "");

            assertThat(textOf(body, "message")).isEqualTo(PROMPT_FOR_CREDENTIAL);
            assertThat(textOf(body, "focusScreenFieldId")).isEqualTo(FIELD_CREDENTIAL_ITEM);
            assertThat(generalErrorOf(body)).isTrue();
        }

        @Test
        @DisplayName("omitting both properties entirely is the same state as submitting them empty")
        void omittingBothPropertiesIsTheSameStateAsSubmittingThemEmpty() throws Exception {
            // The legacy test is against spaces or low values, so an absent value and an empty one are one
            // state. A fixed-width screen field the operator left alone arrives as spaces, and a client
            // that omits the property is expressing the same thing.
            final JsonNode body = bodyOf(submit(null, null, KeyAction.ENTER));

            assertThat(textOf(body, "message")).isEqualTo(PROMPT_FOR_USER_ID);
            assertThat(textOf(body, "focusScreenFieldId")).isEqualTo(FIELD_USER_ID);
            assertThat(generalErrorOf(body)).isTrue();
        }

        @Test
        @DisplayName("a field holding only spaces is blank, at the full declared width")
        void aFieldHoldingOnlySpacesIsBlank() throws Exception {
            // Eight spaces is precisely what the screen field holds when it was never keyed, so this is the
            // literal shape of the legacy condition rather than a generous reading of it.
            final JsonNode body = enter(" ".repeat(USER_ID_WIDTH), NOT_THE_STORED_CREDENTIAL);

            assertThat(textOf(body, "message")).isEqualTo(PROMPT_FOR_USER_ID);
            assertThat(generalErrorOf(body)).isTrue();
        }

        @Test
        @DisplayName("a credential holding only spaces reaches the second arm rather than the read")
        void aCredentialHoldingOnlySpacesReachesTheSecondArm() throws Exception {
            final JsonNode body = enter(ADMIN_IDENTITY, " ".repeat(USER_ID_WIDTH));

            assertThat(textOf(body, "message")).isEqualTo(PROMPT_FOR_CREDENTIAL);
            assertThat(textOf(body, "focusScreenFieldId")).isEqualTo(FIELD_CREDENTIAL_ITEM);
            assertThat(generalErrorOf(body)).isTrue();
        }

        @Test
        @DisplayName("neither prompt nominates a destination or issues a session")
        void neitherPromptNominatesADestinationOrIssuesASession() throws Exception {
            final MvcResult identifierPrompt = submit("", "", KeyAction.ENTER);
            final MvcResult credentialPrompt = submit(ADMIN_IDENTITY, "", KeyAction.ENTER);

            assertThat(textOf(bodyOf(identifierPrompt), "nextRoute"))
                    .as("a rejected turn nominates no destination")
                    .isNull();
            assertThat(textOf(bodyOf(credentialPrompt), "nextRoute")).isNull();
            assertThat(identifierPrompt.getResponse().getHeader(HttpHeaders.AUTHORIZATION))
                    .as("a rejected turn is issued no session, which is the property a client can rely on")
                    .isNull();
            assertThat(credentialPrompt.getResponse().getHeader(HttpHeaders.AUTHORIZATION)).isNull();
        }
    }

    // ===============================================================================================
    // CREDENTIAL VERIFICATION
    // ===============================================================================================

    /**
     * The keyed read and the credential comparison of {@code READ-USER-SEC-FILE}, lines 209 to 257, and the
     * unconditional fold of lines 132 to 136 that precedes them.
     */
    @Nested
    @DisplayName("Credential verification: the read, the comparison, and the fold that precedes both")
    class CredentialVerification {

        /** Creates the nested specification. */
        CredentialVerification() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("the stored credential is accepted and the turn is issued a session")
        void theStoredCredentialIsAcceptedAndTheTurnIsIssuedASession() throws Exception {
            // Line 223: the record was found and the comparison succeeded.
            final MvcResult result = submit(ADMIN_IDENTITY, keyedCredential(), KeyAction.ENTER);
            final JsonNode body = bodyOf(result);

            assertThat(textOf(body, "message"))
                    .as("an admitted turn composes no screen message")
                    .isNull();
            assertThat(generalErrorOf(body))
                    .as("an admitted turn leaves the error switch lowered")
                    .isFalse();
            assertThat(result.getResponse().getHeader(HttpHeaders.AUTHORIZATION))
                    .as("only an admitted turn is issued a session")
                    .isNotNull()
                    .startsWith(BEARER_PREFIX);
        }

        @Test
        @DisplayName("a credential the stored digest was not derived from answers the wrong-credential "
                + "message and is issued no session")
        void aCredentialTheDigestWasNotDerivedFromIsRefused() throws Exception {
            // Lines 241 to 245: the alternative of the comparison.
            final MvcResult result =
                    submit(ADMIN_IDENTITY, NOT_THE_STORED_CREDENTIAL, KeyAction.ENTER);
            final JsonNode body = bodyOf(result);

            assertThat(textOf(body, "message")).isEqualTo(WRONG_CREDENTIAL_MESSAGE);
            assertThat(textOf(body, "focusScreenFieldId"))
                    .as("line 244 moves the cursor to the credential field on this arm")
                    .isEqualTo(FIELD_CREDENTIAL_ITEM);
            assertThat(result.getResponse().getHeader(HttpHeaders.AUTHORIZATION))
                    .as("a refused comparison earns no session")
                    .isNull();
        }

        @Test
        @DisplayName("the same credential in a different letter case is accepted, because the legacy fold "
                + "makes the credential effectively case-insensitive")
        void theSameCredentialInADifferentLetterCaseIsAccepted() throws Exception {
            // Lines 132 to 136 fold the submitted credential unconditionally, which makes the comparison
            // case-insensitive as an observable consequence. Preserved rather than corrected; the
            // divergence from what a modern design would do is recorded in docs/decision-log.md.
            final MvcResult result = submit(ADMIN_IDENTITY,
                    keyedCredential().toLowerCase(Locale.ROOT), KeyAction.ENTER);

            assertThat(generalErrorOf(bodyOf(result)))
                    .as("a case-shifted credential is the same credential to this transaction")
                    .isFalse();
            assertThat(result.getResponse().getHeader(HttpHeaders.AUTHORIZATION))
                    .as("the case-shifted submission is admitted, not merely un-errored")
                    .isNotNull();
        }

        @Test
        @DisplayName("the fold is unconditional: an identity whose digest was produced from the unfolded "
                + "value is refused even when exactly that value is submitted")
        void theFoldIsUnconditional() throws Exception {
            // The strongest available statement that the fold at lines 132 to 136 genuinely runs on every
            // submission. This identity's digest was derived from the unfolded characters, so a path that
            // compared what the client sent would admit it; the shipped path folds first and refuses.
            final MvcResult result =
                    submit(UNFOLDED_DIGEST_IDENTITY, keyedCredential(), KeyAction.ENTER);
            final JsonNode body = bodyOf(result);

            assertThat(textOf(body, "message"))
                    .as("the fold happens before the comparison, so the unfolded digest cannot match")
                    .isEqualTo(WRONG_CREDENTIAL_MESSAGE);
            assertThat(result.getResponse().getHeader(HttpHeaders.AUTHORIZATION)).isNull();
        }

        @Test
        @DisplayName("the identifier is folded too, so a lower-case identifier finds the stored record")
        void theIdentifierIsFoldedToo() throws Exception {
            // The same two statements fold the identifier into both the work field and the communication
            // area, so the keyed read at line 215 is performed with the folded value.
            final MvcResult result = submit(ADMIN_IDENTITY.toLowerCase(Locale.ROOT),
                    keyedCredential(), KeyAction.ENTER);

            assertThat(result.getResponse().getHeader(HttpHeaders.AUTHORIZATION))
                    .as("a lower-case identifier names the same operator as its folded form")
                    .isNotNull();
        }

        @Test
        @DisplayName("an identifier the credential master does not hold answers the not-found message")
        void anAbsentIdentifierAnswersTheNotFoundMessage() throws Exception {
            // Lines 247 to 251: the not-found arm of the read.
            final JsonNode body = enter(ABSENT_IDENTITY, NOT_THE_STORED_CREDENTIAL);

            assertThat(textOf(body, "message")).isEqualTo(USER_NOT_FOUND_MESSAGE);
            assertThat(textOf(body, "focusScreenFieldId"))
                    .as("line 250 returns the cursor to the identifier field on this arm")
                    .isEqualTo(FIELD_USER_ID);
        }

        @Test
        @DisplayName("the eight-character identifier round-trips into the echo unchanged, with no leading "
                + "zero lost and no padding added")
        void theIdentifierRoundTripsIntoTheEchoUnchanged() throws Exception {
            // The map's own output item is echoed so a client can rebuild the redisplayed screen. Every
            // identifier in this estate crosses the boundary as text of a bounded width rather than as a
            // number, which is what keeps a leading zero.
            final JsonNode body = enter(DELIVERED_USER_IDENTITY, NOT_THE_STORED_CREDENTIAL);

            assertThat(textOf(body, "userId"))
                    .as("the echo is the identifier as keyed, at the width the map declares")
                    .isEqualTo(DELIVERED_USER_IDENTITY)
                    .hasSize(USER_ID_WIDTH);
        }

        @Test
        @DisplayName("the response never carries the submitted credential, under any component name and at "
                + "any depth")
        void theResponseNeverCarriesTheSubmittedCredential() throws Exception {
            final String submitted = keyedCredential();
            final MvcResult result = submit(ADMIN_IDENTITY, submitted, KeyAction.ENTER);

            final String whole = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(whole)
                    .as("the screen contract declares fifteen components and none of them is a credential")
                    .doesNotContain(submitted);
            assertThat(whole)
                    .as("nor does it carry a case-shifted form of it")
                    .doesNotContain(submitted.toUpperCase(Locale.ROOT));
            assertThat(result.getResponse().getHeader(HttpHeaders.AUTHORIZATION))
                    .as("nor does the issued session")
                    .doesNotContain(submitted.toUpperCase(Locale.ROOT));
        }

        @Test
        @DisplayName("the request type redacts the credential when rendered for a diagnostic")
        void theRequestTypeRedactsTheCredentialWhenRendered() throws Exception {
            // The request type overrides its rendering for exactly this reason: a diagnostic, a log line or
            // a failure report must be able to name the attempt without disclosing what was attempted with.
            final String submitted = keyedCredential();
            final SignOnRequest request =
                    new SignOnRequest(ADMIN_IDENTITY, submitted, KeyAction.ENTER);

            assertThat(request.toString())
                    .as("the identifier and the attention key are retained, because neither is a secret")
                    .contains(ADMIN_IDENTITY)
                    .contains(KeyAction.ENTER.name());
            assertThat(request.toString())
                    .as("the credential is replaced by a fixed placeholder, so nothing about it - not its "
                            + "value, not a prefix and not its length - survives the rendering")
                    .doesNotContain(submitted)
                    .doesNotContain(submitted.toUpperCase(Locale.ROOT))
                    .doesNotContain(submitted.toLowerCase(Locale.ROOT));
        }

        @Test
        @DisplayName("every delivered credential is stored as a BCrypt digest of the declared width, and "
                + "refuses a value it was not derived from")
        void everyDeliveredCredentialIsStoredAsADigest() {
            // The one deliberate parity exception: the legacy record holds eight cleartext characters and
            // the legacy path compares them directly. These are the two properties a module which does not
            // hold the legacy value can answer about a delivered digest, and both were measured to hold for
            // all ten. Nothing here names the value any of them was derived from.
            for (final TestDataFactory.SeededIdentity delivered : TestDataFactory.SEEDED_IDENTITIES) {
                final UserSecurity stored = AuthControllerIT.this.users.findById(delivered.userId())
                        .orElseThrow(() -> new AssertionError("the credential seed must have applied "
                                + delivered.userId() + "; without it the role split below would be "
                                + "asserting against an absent row"));

                assertThat(stored.credentialDigest())
                        .as("%s stores a digest at the declared width, never a cleartext or truncated "
                                + "value", delivered.userId())
                        .startsWith("$2")
                        .hasSize(TestDataFactory.BCRYPT_DIGEST_LENGTH);
                assertThat(AuthControllerIT.this.credentials
                        .matches(NOT_THE_STORED_CREDENTIAL, stored.credentialDigest()))
                        .as("%s must refuse a value it was not derived from; a digest that accepted "
                                + "everything would satisfy an acceptance check and be worthless",
                                delivered.userId())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("a digest is never the identifier or a personal name, which is the classic "
                + "placeholder substitution")
        void aDigestIsNeverTheIdentifierOrAName() {
            for (final TestDataFactory.SeededIdentity delivered : TestDataFactory.SEEDED_IDENTITIES) {
                final String digest = AuthControllerIT.this.users.findById(delivered.userId())
                        .orElseThrow().credentialDigest();

                assertThat(digest)
                        .as("%s must not store its own identifier or either of its names in the "
                                + "credential column", delivered.userId())
                        .isNotEqualTo(delivered.userId())
                        .isNotEqualTo(delivered.firstName())
                        .isNotEqualTo(delivered.lastName());
            }
        }
    }

    // ===============================================================================================
    // ERROR-FLAG ASYMMETRY
    // ===============================================================================================

    /**
     * The error switch, arm by arm - the highest-value assertions in this specification.
     *
     * <p>The switch is lowered once at line 75 and raised by five of the eight arms. It is <em>not</em>
     * raised by the arm that composes the wrong-credential message, and it is not raised by the exit key.
     * Every arm therefore has to be asserted independently, because the switch cannot be derived from
     * whether a message is present: two arms carry a message with the switch lowered, and one arm carries
     * no message with the switch lowered.
     */
    @Nested
    @DisplayName("Error-flag asymmetry: the switch is its own component and is never inferred")
    class ErrorFlagAsymmetry {

        /** Creates the nested specification. */
        ErrorFlagAsymmetry() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("★ a refused credential carries its message AND leaves the switch lowered, in the "
                + "same turn - the one arm a tidied-up implementation would break")
        void aRefusedCredentialCarriesItsMessageAndLeavesTheSwitchLowered() throws Exception {
            // Lines 240 to 245 compose a message and move the cursor without assigning the error switch,
            // while lines 246 to 251 and 252 to 257 both assign it. An implementation that set the switch
            // wherever it set a message would be indistinguishable from this one on every other arm and
            // wrong on this one, which is why both halves are asserted together rather than in two tests.
            final JsonNode body = enter(ADMIN_IDENTITY, NOT_THE_STORED_CREDENTIAL);

            assertThat(textOf(body, "message"))
                    .as("the message is present on this arm")
                    .isEqualTo(WRONG_CREDENTIAL_MESSAGE);
            assertThat(generalErrorOf(body))
                    .as("and the switch is nonetheless lowered, because this arm never assigns it - the "
                            + "asymmetry is the contract, not an oversight to be tidied away")
                    .isFalse();
        }

        @Test
        @DisplayName("an empty identifier raises the switch")
        void anEmptyIdentifierRaisesTheSwitch() throws Exception {
            // Lines 118 to 123.
            assertThat(generalErrorOf(enter("", ""))).isTrue();
        }

        @Test
        @DisplayName("an empty credential raises the switch")
        void anEmptyCredentialRaisesTheSwitch() throws Exception {
            // Lines 124 to 129.
            assertThat(generalErrorOf(enter(ADMIN_IDENTITY, ""))).isTrue();
        }

        @Test
        @DisplayName("an identifier that is not on file raises the switch")
        void anIdentifierThatIsNotOnFileRaisesTheSwitch() throws Exception {
            // Lines 246 to 251 - the arm immediately adjacent to the refused-credential arm, and the
            // contrast that makes that arm's lowered switch meaningful rather than accidental.
            final JsonNode body = enter(ABSENT_IDENTITY, NOT_THE_STORED_CREDENTIAL);

            assertThat(textOf(body, "message")).isEqualTo(USER_NOT_FOUND_MESSAGE);
            assertThat(generalErrorOf(body)).isTrue();
        }

        @Test
        @DisplayName("an unmapped attention key raises the switch")
        void anUnmappedAttentionKeyRaisesTheSwitch() throws Exception {
            // Lines 91 to 95 - the catch-all of the attention-key evaluation, which does assign the switch.
            final JsonNode body = bodyOf(submit(ADMIN_IDENTITY, null, KeyAction.CLEAR));

            assertThat(generalErrorOf(body)).isTrue();
        }

        @Test
        @DisplayName("the exit key leaves the switch lowered even though it carries a message")
        void theExitKeyLeavesTheSwitchLowered() throws Exception {
            // Lines 88 to 90 compose the shared courtesy message and write plain text. The switch is not
            // assigned, so it keeps the value line 75 gave it - the second arm that carries a message with
            // the switch lowered.
            final JsonNode body = bodyOf(submit(null, null, KeyAction.PFK03));

            assertThat(textOf(body, "message"))
                    .as("the exit key does carry a message")
                    .isNotNull();
            assertThat(generalErrorOf(body))
                    .as("and still leaves the switch lowered")
                    .isFalse();
        }

        @Test
        @DisplayName("first entry carries no message, leaves the switch lowered, and focuses the "
                + "identifier field")
        void firstEntryCarriesNoMessageAndLeavesTheSwitchLowered() throws Exception {
            // Lines 80 to 83: the empty-communication-area state, which is tested before the attention key
            // is evaluated at all. Over this boundary it is the request that submits no body.
            final MvcResult result = this.enterFirstTime();
            final JsonNode body = bodyOf(result);

            assertThat(textOf(body, "message"))
                    .as("first entry composes no message; the output area is cleared")
                    .isNull();
            assertThat(generalErrorOf(body))
                    .as("and the switch is at the value line 75 sets")
                    .isFalse();
            assertThat(textOf(body, "focusScreenFieldId"))
                    .as("line 82 places the cursor on the identifier field")
                    .isEqualTo(FIELD_USER_ID);
            assertThat(result.getResponse().getHeader(HttpHeaders.AUTHORIZATION))
                    .as("first entry earns no session")
                    .isNull();
        }

        @Test
        @DisplayName("a submitted turn naming no attention key is the unmapped-key arm rather than first "
                + "entry")
        void aTurnNamingNoAttentionKeyIsTheUnmappedKeyArm() throws Exception {
            // The attention-key evaluation has no clause that substitutes a key, and its catch-all already
            // covers an absent one. First entry is a distinct state and is reached by the request that
            // submits no body at all, so the two can never be confused.
            final JsonNode body = bodyOf(submit(ADMIN_IDENTITY, null, null));

            assertThat(textOf(body, "message"))
                    .isEqualTo(INVALID_KEY_MESSAGE_50)
                    .hasSize(COMMON_MESSAGE_WIDTH);
            assertThat(generalErrorOf(body)).isTrue();
        }

        @Test
        @DisplayName("the cannot-verify arm carries its own message and raises the switch")
        void theCannotVerifyArmCarriesItsOwnMessageAndRaisesTheSwitch() {
            // Lines 252 to 257: the catch-all of the read. It is reached only when the credential store
            // itself fails, which cannot be induced over this boundary against a healthy server without
            // sabotaging the one every other integration specification shares - and the published contract
            // bounds the identifier to the map's width, so no submission can provoke a store failure
            // either. The arm is therefore asserted where it is observable: through the shipped projection,
            // given a turn that reached that decision. Nothing is stubbed - the adapter, the catalogue and
            // the screen type are all the shipped ones.
            final AuthenticationService.SignOnScreen screen = new AuthenticationService.SignOnScreen(
                    AuthenticationService.Decision.UNABLE_TO_VERIFY,
                    null, ADMIN_IDENTITY, null, null, null,
                    true, FIELD_USER_ID, CCDA_COURTESY_TITLE_40, CCDA_COURTESY_TITLE_40,
                    "06/10/22", "19:27:53");

            final SignOnResponse published = AuthControllerIT.this.contractAdapter.toResponse(screen);

            assertThat(published.message())
                    .as("the catch-all arm's own text, character for character")
                    .isEqualTo(UNABLE_TO_VERIFY_MESSAGE);
            assertThat(published.generalError())
                    .as("unlike the refused-credential arm, this one raises the switch")
                    .isTrue();
            assertThat(published.nextRoute())
                    .as("a turn that did not admit the operator nominates no destination")
                    .isNull();
        }

        /**
         * Reaches the sign-on screen for the first time, which over this boundary is the request that
         * submits no body.
         *
         * @return the completed turn
         * @throws Exception if the boundary cannot be reached
         */
        private MvcResult enterFirstTime() throws Exception {
            return AuthControllerIT.this.mockMvc.perform(
                            MockMvcRequestBuilders.get(AuthController.SIGN_ON_PATH)
                                    .accept(MediaType.APPLICATION_JSON))
                    .andReturn();
        }
    }

    // ===============================================================================================
    // ROLE ROUTING
    // ===============================================================================================

    /**
     * The two-way role split of lines 230 to 240.
     *
     * <p>The administrative condition tests the stored code for one exact value and transfers to the
     * administrative menu; a plain alternative transfers to the user main menu. There is no third branch
     * and no exception path, so any code the estate never declared - and the administrative letter in lower
     * case - must be admitted and must reach the main menu.
     */
    @Nested
    @DisplayName("Role routing: two branches, no third, and no exception path")
    class RoleRouting {

        /** Creates the nested specification. */
        RoleRouting() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("the administrative code reaches the administrative menu")
        void theAdministrativeCodeReachesTheAdministrativeMenu() throws Exception {
            // Lines 230 to 234.
            final JsonNode body = enter(ADMIN_IDENTITY, keyedCredential());

            assertThat(textOf(body, "nextRoute")).isEqualTo(ADMIN_MENU_ROUTE);
            assertThat(textOf(body, "userType"))
                    .as("the stored code travels raw, never a resolved role")
                    .isEqualTo(ADMIN_ROLE_CODE);
        }

        @Test
        @DisplayName("an ordinary code reaches the user main menu")
        void anOrdinaryCodeReachesTheUserMainMenu() throws Exception {
            // Lines 235 to 239: the plain alternative.
            final JsonNode body = enter(USER_IDENTITY, keyedCredential());

            assertThat(textOf(body, "nextRoute")).isEqualTo(USER_MENU_ROUTE);
            assertThat(textOf(body, "userType")).isEqualTo(USER_ROLE_CODE);
        }

        @Test
        @DisplayName("★ a stored code the estate never declared is admitted, does not fail, and reaches "
                + "the user main menu")
        void anUndeclaredCodeIsAdmittedAndReachesTheMainMenu() throws Exception {
            // The alternative at line 235 is unconditional, so it absorbs every value other than the one
            // the administrative condition names. The column is one character wide and carries no check
            // constraint, exactly as the legacy record's own field did, so such a row is representable
            // without altering the schema - and the turn must neither fail nor be refused.
            final MvcResult result = submit(UNDECLARED_ROLE_IDENTITY, keyedCredential(), KeyAction.ENTER);
            final JsonNode body = bodyOf(result);

            assertThat(generalErrorOf(body))
                    .as("an undeclared code is not an error condition on any arm of this transaction")
                    .isFalse();
            assertThat(textOf(body, "message"))
                    .as("nor does it compose a screen message")
                    .isNull();
            assertThat(textOf(body, "nextRoute"))
                    .as("it follows the unconditional alternative to the main menu")
                    .isEqualTo(USER_MENU_ROUTE);
            assertThat(textOf(body, "userType"))
                    .as("and the undeclared code survives the round trip rather than being normalised")
                    .isEqualTo("X");
            assertThat(result.getResponse().getHeader(HttpHeaders.AUTHORIZATION))
                    .as("the operator is genuinely admitted and is issued a session")
                    .isNotNull();
        }

        @Test
        @DisplayName("★ the administrative letter in lower case is NOT the administrative code and reaches "
                + "the user main menu")
        void theAdministrativeLetterInLowerCaseIsNotAdministrative() throws Exception {
            // The role vocabulary matches its codes exactly and folds nothing. The two entry fields are
            // folded at lines 132 to 136; the stored role code read at line 227 is not, so a lower-case
            // letter is simply a different code and takes the unconditional alternative.
            final JsonNode body = enter(LOWER_CASE_ROLE_IDENTITY, keyedCredential());

            assertThat(textOf(body, "nextRoute")).isEqualTo(USER_MENU_ROUTE);
            assertThat(textOf(body, "userType"))
                    .as("the stored code is echoed exactly as stored, in the case it was stored in")
                    .isEqualTo("a");
        }

        @Test
        @DisplayName("the role vocabulary declares exactly two codes and folds neither")
        void theRoleVocabularyDeclaresExactlyTwoCodesAndFoldsNeither() {
            assertThat(UserType.values())
                    .as("exactly two codes are declared, which is what makes the alternative at line 235 "
                            + "the destination of everything else")
                    .hasSize(2);
            assertThat(UserType.fromCode(ADMIN_ROLE_CODE))
                    .as("the administrative code resolves")
                    .contains(UserType.ADMIN);
            assertThat(UserType.fromCode(USER_ROLE_CODE))
                    .as("the ordinary code resolves")
                    .contains(UserType.USER);
            assertThat(UserType.fromCode(ADMIN_ROLE_CODE.toLowerCase(Locale.ROOT)))
                    .as("no case folding is applied, so the lower-case letter resolves to nothing at all")
                    .isEmpty();
            assertThat(UserType.fromCode("X"))
                    .as("an undeclared code resolves to nothing, which the caller turns into the "
                            + "unconditional alternative rather than into a failure")
                    .isEmpty();
        }

        @Test
        @DisplayName("each destination names the transaction the resource definitions register it under")
        void eachDestinationNamesItsRegisteredTransaction() {
            // The wire values above are role-named and carry no legacy identifier, which is deliberate. The
            // binding back to the resource definitions is asserted here so the two facts are connected in
            // one place rather than assumed.
            assertThat(NavigationService.Route.ADMIN_MENU.getRouteValue()).isEqualTo(ADMIN_MENU_ROUTE);
            assertThat(NavigationService.Route.ADMIN_MENU.getLegacyTransactionId())
                    .isEqualTo(ADMIN_MENU_TRANSACTION);
            assertThat(NavigationService.Route.USER_MENU.getRouteValue()).isEqualTo(USER_MENU_ROUTE);
            assertThat(NavigationService.Route.USER_MENU.getLegacyTransactionId())
                    .isEqualTo(USER_MENU_TRANSACTION);
        }

        @Test
        @DisplayName("the delivered identities carry the two codes the split turns on")
        void theDeliveredIdentitiesCarryTheTwoCodesTheSplitTurnsOn() {
            // Asserted against the delivered rows rather than around them: if the seed's codes were flipped
            // the routing assertions above would still pass while the shipped system routed every delivered
            // operator to the wrong menu.
            assertThat(AuthControllerIT.this.users.findById(DELIVERED_ADMIN_IDENTITY)
                    .orElseThrow().getSecUsrType())
                    .as("%s is a delivered administrative identity", DELIVERED_ADMIN_IDENTITY)
                    .isEqualTo(ADMIN_ROLE_CODE);
            assertThat(AuthControllerIT.this.users.findById(DELIVERED_USER_IDENTITY)
                    .orElseThrow().getSecUsrType())
                    .as("%s is a delivered ordinary identity", DELIVERED_USER_IDENTITY)
                    .isEqualTo(USER_ROLE_CODE);
            assertThat(TestDataFactory.SEEDED_IDENTITIES.stream()
                    .filter(TestDataFactory.SeededIdentity::isAdministrator)
                    .count())
                    .as("five of the ten delivered identities hold the administrative code")
                    .isEqualTo(5L);
        }

        @Test
        @DisplayName("an admitted turn echoes the sign-on transaction and program it was served by")
        void anAdmittedTurnEchoesItsTransactionAndProgram() throws Exception {
            final JsonNode body = enter(USER_IDENTITY, keyedCredential());

            assertThat(textOf(body, "transactionName"))
                    .as("the header items the screen carried")
                    .isEqualTo(SIGN_ON_TRANSACTION);
            assertThat(textOf(body, "programName")).isEqualTo(SIGN_ON_PROGRAM);
        }
    }

    // ===============================================================================================
    // COMMON-MESSAGE FIDELITY
    // ===============================================================================================

    /**
     * The two shared messages, at their full stored width, and the third value that is not one of them.
     *
     * <p>The shared catalogue declares each of the two at fifty characters and writes a forty-nine
     * character literal into it, so each stored value is padded to exactly fifty. A trimmed comparison
     * would pass against a trimmed implementation and would have silently changed a value an operator
     * reads, so nothing here trims, strips, matches a prefix, or admits a regular expression that
     * tolerates trailing space. A separate copybook declares a third courtesy value at forty characters
     * under a different product token; it is asserted at its own width and asserted to differ from both.
     */
    @Nested
    @DisplayName("Common-message fidelity: fifty characters, trailing spaces intact, never trimmed")
    class CommonMessageFidelity {

        /** Creates the nested specification. */
        CommonMessageFidelity() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("the exit key carries the courtesy message at its full fifty characters")
        void theExitKeyCarriesTheCourtesyMessageAtFullWidth() throws Exception {
            // Lines 88 to 90 move the shared value into the eighty-character message field, so the fifty
            // stored characters travel unaltered.
            final JsonNode body = bodyOf(submit(null, null, KeyAction.PFK03));

            assertThat(textOf(body, "message"))
                    .as("the stored value, trailing spaces included")
                    .isEqualTo(THANK_YOU_MESSAGE_50);
            assertThat(textOf(body, "message"))
                    .as("and at its full declared width, so a later refactor that trimmed would fail here")
                    .hasSize(COMMON_MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("an unmapped attention key carries the invalid-key message at its full fifty "
                + "characters")
        void anUnmappedKeyCarriesTheInvalidKeyMessageAtFullWidth() throws Exception {
            // Lines 91 to 94.
            final JsonNode body = bodyOf(submit(ADMIN_IDENTITY, keyedCredential(), KeyAction.PA1));

            assertThat(textOf(body, "message")).isEqualTo(INVALID_KEY_MESSAGE_50);
            assertThat(textOf(body, "message")).hasSize(COMMON_MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("every unmapped key reaches the same value, at the same width")
        void everyUnmappedKeyReachesTheSameValue() throws Exception {
            // The catch-all covers every key other than the two the evaluation names, so a key that is
            // neither the submit key nor the exit key must not be given a clause of its own here either.
            for (final KeyAction unmapped : List.of(KeyAction.CLEAR, KeyAction.PA2,
                    KeyAction.PFK01, KeyAction.PFK12)) {
                final JsonNode body = bodyOf(submit(ADMIN_IDENTITY, keyedCredential(), unmapped));

                assertThat(textOf(body, "message"))
                        .as("%s is not a mapped key on this screen", unmapped.name())
                        .isEqualTo(INVALID_KEY_MESSAGE_50);
                assertThat(textOf(body, "message")).hasSize(COMMON_MESSAGE_WIDTH);
                assertThat(generalErrorOf(body)).isTrue();
            }
        }

        @Test
        @DisplayName("the shared catalogue holds both values at fifty characters, padded rather than "
                + "trimmed")
        void theSharedCatalogueHoldsBothValuesAtFiftyCharacters() {
            assertThat(AuthControllerIT.this.messages.thankYouMessage())
                    .isEqualTo(THANK_YOU_MESSAGE_50)
                    .hasSize(COMMON_MESSAGE_WIDTH);
            assertThat(AuthControllerIT.this.messages.invalidKeyMessage())
                    .isEqualTo(INVALID_KEY_MESSAGE_50)
                    .hasSize(COMMON_MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("★ the forty-character courtesy title is a DIFFERENT value and is never substituted "
                + "for either fifty-character message")
        void theFortyCharacterCourtesyTitleIsADifferentValue() {
            // A different copybook, a different product token and a different declared width. Merging the
            // two would change what an operator reads on the exit key, and asserting them
            // interchangeably would let such a merge pass.
            assertThat(AuthControllerIT.this.messages.screenTitleThankYou())
                    .as("asserted at its own width, on its own")
                    .isEqualTo(CCDA_COURTESY_TITLE_40)
                    .hasSize(SCREEN_TITLE_WIDTH);
            assertThat(CCDA_COURTESY_TITLE_40)
                    .as("and it is neither of the two fifty-character values, at full width or trimmed")
                    .isNotEqualTo(THANK_YOU_MESSAGE_50)
                    .isNotEqualTo(INVALID_KEY_MESSAGE_50);
            assertThat(THANK_YOU_MESSAGE_50)
                    .as("the two fifty-character values are distinct from one another as well")
                    .isNotEqualTo(INVALID_KEY_MESSAGE_50);
        }

        @Test
        @DisplayName("the message is carried at the width the program declares, not at the narrower width "
                + "the map renders")
        void theMessageIsCarriedAtTheWidthTheProgramDeclares() {
            // The program's own message field is eighty characters wide and the map's rendered field is
            // seventy-eight, so the carried width is the wider of the two. Carrying it at the narrower one
            // would clip a fifty-character value's trailing spaces on the way out and change nothing
            // visible until an assertion compared full widths - which is what the two above do.
            assertThat(SignOnResponse.MESSAGE_LENGTH)
                    .as("the carried width is the program's own")
                    .isEqualTo(80);
            assertThat(SignOnResponse.SCREEN_MESSAGE_LENGTH)
                    .as("the rendered width is the map's, and is narrower")
                    .isEqualTo(78);
            assertThat(THANK_YOU_MESSAGE_50.length())
                    .as("both fifty-character values fit the carried width intact")
                    .isLessThanOrEqualTo(SignOnResponse.MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("the two screen titles are carried at forty characters on a rejected turn")
        void theTwoScreenTitlesAreCarriedAtFortyCharacters() throws Exception {
            // The redisplay path populates the header before it sends, so a rejected turn carries the
            // titles. They are asserted for width only: their text is the subject of the catalogue's own
            // specification, not of this one.
            final JsonNode body = enter(ABSENT_IDENTITY, NOT_THE_STORED_CREDENTIAL);

            assertThat(textOf(body, "title01")).hasSize(SCREEN_TITLE_WIDTH);
            assertThat(textOf(body, "title02")).hasSize(SCREEN_TITLE_WIDTH);
        }
    }

    // ===============================================================================================
    // SECURITY NEGATIVES
    // ===============================================================================================

    /**
     * What the shipped filter chain refuses, and what it discloses when it refuses.
     *
     * <p>The resource definitions register eighteen transactions, of which exactly one is classified
     * anonymous - the sign-on transaction, because it is the one that issues sessions - and exactly five
     * are classified administrative: the administrative menu and the four user-maintenance transactions.
     * This specification asserts the anonymous classification of its own route and the administrative gate
     * on one of those five. It deliberately broadens neither, and it asserts no gate on any other route.
     */
    @Nested
    @DisplayName("Security negatives: what the shipped chain refuses, and what it does not disclose")
    class SecurityNegatives {

        /** Creates the nested specification. */
        SecurityNegatives() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("the sign-on route is reachable without a session, because it is the route that "
                + "issues them")
        void theSignOnRouteIsReachableWithoutASession() throws Exception {
            final MvcResult result = submit(ADMIN_IDENTITY, keyedCredential(), KeyAction.ENTER);

            assertThat(result.getResponse().getStatus())
                    .as("the one anonymous transaction the definitions register")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a protected route refuses a request that presents no session, and says only that a "
                + "session is required")
        void aProtectedRouteRefusesARequestWithNoSession() throws Exception {
            final MvcResult result = AuthControllerIT.this.mockMvc
                    .perform(MockMvcRequestBuilders.get(ApiRoutePaths.ADMIN_USERS_PATH)
                            .accept(MediaType.APPLICATION_JSON))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                    .as("the refusal names the scheme a caller should present, which is what makes it "
                            + "actionable without disclosing anything")
                    .isNotNull();
            assertThat(summaryOf(result))
                    .as("the summary distinguishes a missing session from an insufficient one")
                    .isEqualTo(AUTHENTICATION_REQUIRED);
        }

        @Test
        @DisplayName("a protected route refuses a session that cannot be read at all")
        void aProtectedRouteRefusesAnUnreadableSession() throws Exception {
            // Deliberately not a truncated real token: an unreadable value proves the filter verifies
            // rather than merely inspects, and a truncated real one would additionally depend on where it
            // was cut.
            final MvcResult result = AuthControllerIT.this.mockMvc
                    .perform(MockMvcRequestBuilders.get(ApiRoutePaths.ADMIN_USERS_PATH)
                            .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + "not-a-readable-session")
                            .accept(MediaType.APPLICATION_JSON))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(summaryOf(result)).isEqualTo(AUTHENTICATION_REQUIRED);
        }

        @Test
        @DisplayName("a protected route refuses a session presented without its scheme prefix")
        void aProtectedRouteRefusesASessionWithoutItsSchemePrefix() throws Exception {
            final String session = sessionFor(ADMIN_IDENTITY);

            final MvcResult result = AuthControllerIT.this.mockMvc
                    .perform(MockMvcRequestBuilders.get(ApiRoutePaths.ADMIN_USERS_PATH)
                            .header(HttpHeaders.AUTHORIZATION, session)
                            .accept(MediaType.APPLICATION_JSON))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("a valid session presented outside the scheme is not a presented session")
                    .isEqualTo(401);
        }

        @Test
        @DisplayName("★ an ordinary session reaches an administrative route and is refused for want of the "
                + "entitlement, not for want of a session")
        void anOrdinarySessionIsRefusedTheAdministrativeRoute() throws Exception {
            // One of the five administratively classified transactions. The distinction between the two
            // refusals is the one the estate drew: the caller is known and is not entitled.
            final String ordinarySession = sessionFor(USER_IDENTITY);

            final MvcResult result = AuthControllerIT.this.mockMvc
                    .perform(MockMvcRequestBuilders.get(ApiRoutePaths.ADMIN_USERS_PATH)
                            .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + ordinarySession)
                            .accept(MediaType.APPLICATION_JSON))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(summaryOf(result)).isEqualTo(ACCESS_DENIED);
        }

        @Test
        @DisplayName("a session minted for an undeclared role code is an ordinary session and is refused "
                + "the administrative route too")
        void aSessionForAnUndeclaredRoleCodeIsOrdinary() throws Exception {
            // The fall-through of line 235 decides the destination; the entitlement a session carries must
            // agree with it, or an undeclared code would route to the main menu while holding the
            // administrative entitlement.
            final String session = sessionFor(UNDECLARED_ROLE_IDENTITY);

            final MvcResult result = AuthControllerIT.this.mockMvc
                    .perform(MockMvcRequestBuilders.get(ApiRoutePaths.ADMIN_USERS_PATH)
                            .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + session)
                            .accept(MediaType.APPLICATION_JSON))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("an undeclared code carries the ordinary entitlement, matching where it routes")
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("an administrative session is not refused by the entitlement gate")
        void anAdministrativeSessionIsNotRefusedByTheGate() throws Exception {
            final String adminSession = sessionFor(ADMIN_IDENTITY);

            final MvcResult result = AuthControllerIT.this.mockMvc
                    .perform(MockMvcRequestBuilders.get(ApiRoutePaths.ADMIN_USERS_PATH)
                            .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + adminSession)
                            .accept(MediaType.APPLICATION_JSON))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("the gate admits this caller; whatever answers beyond it is another "
                            + "specification's subject, so only the two refusal statuses are excluded here")
                    .isNotIn(401, 403);
        }

        @Test
        @DisplayName("no refusal body discloses a stack trace, an exception name, a query, a path, a "
                + "legacy artefact or a credential")
        void noRefusalBodyDisclosesAnything() throws Exception {
            final String ordinarySession = sessionFor(USER_IDENTITY);
            final MvcResult unauthenticated = AuthControllerIT.this.mockMvc
                    .perform(MockMvcRequestBuilders.get(ApiRoutePaths.ADMIN_USERS_PATH)
                            .accept(MediaType.APPLICATION_JSON))
                    .andReturn();
            final MvcResult unentitled = AuthControllerIT.this.mockMvc
                    .perform(MockMvcRequestBuilders.get(ApiRoutePaths.ADMIN_USERS_PATH)
                            .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + ordinarySession)
                            .accept(MediaType.APPLICATION_JSON))
                    .andReturn();

            for (final MvcResult refusal : List.of(unauthenticated, unentitled)) {
                final String body = refusal.getResponse().getContentAsString(StandardCharsets.UTF_8);

                assertThat(body)
                        .as("a refusal summary is the module's own neutral text and nothing else")
                        .doesNotContain("Exception")
                        .doesNotContain("java.")
                        .doesNotContain("org.springframework")
                        .doesNotContain("at com.carddemo")
                        .doesNotContain("SELECT")
                        .doesNotContain("select ")
                        .doesNotContain("user_security")
                        .doesNotContain("jdbc:")
                        .doesNotContain("/tmp")
                        .doesNotContain("COSGN00C")
                        .doesNotContain("EXEC CICS")
                        .doesNotContain("DFHRED")
                        .doesNotContain("$2");
            }
        }

        @Test
        @DisplayName("a rejected sign-on body discloses no store detail either, and reveals nothing about "
                + "whether the identifier exists")
        void aRejectedSignOnBodyDisclosesNoStoreDetail() throws Exception {
            final String absent = bodyOf(submit(ABSENT_IDENTITY, NOT_THE_STORED_CREDENTIAL,
                    KeyAction.ENTER)).toString();

            assertThat(absent)
                    .as("the screen message is the whole of what a rejected turn discloses")
                    .doesNotContain("Exception")
                    .doesNotContain("org.postgresql")
                    .doesNotContain("user_security")
                    .doesNotContain("$2");
            assertThat(absent)
                    .as("the operator-visible text is the legacy one, which does distinguish the two "
                            + "rejections - that disclosure is the external contract and is preserved "
                            + "deliberately rather than closed")
                    .contains(USER_NOT_FOUND_MESSAGE);
        }

        @Test
        @DisplayName("a submission wider than the map declares is refused before the transaction runs")
        void aSubmissionWiderThanTheMapDeclaresIsRefused() throws Exception {
            // The two entry fields are eight characters wide on the map and eight in the stored record, so a
            // wider submission is malformed rather than merely wrong. Declarative validation answers it,
            // which is a different outcome from any of the nine screens the transaction composes.
            final MvcResult result = submit("A".repeat(USER_ID_WIDTH + 1),
                    NOT_THE_STORED_CREDENTIAL, KeyAction.ENTER);

            assertThat(result.getResponse().getStatus())
                    .as("a malformed request is not one of the nine screens, so it does not answer 200")
                    .isEqualTo(400);
            assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .as("and the refusal discloses no internal detail")
                    .doesNotContain("Exception")
                    .doesNotContain("org.springframework");
        }

        /**
         * Reads the summary a refusal body carries.
         *
         * @param  result the completed request
         * @return the summary text, or {@code null} when the body carries none
         * @throws Exception if the body cannot be parsed
         */
        private String summaryOf(final MvcResult result) throws Exception {
            final String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body)
                    .as("a refusal carries the module's own envelope rather than an empty body")
                    .isNotEmpty();
            return textOf(JSON.readTree(body), "message");
        }
    }

    // ===============================================================================================
    // THE GRAPH UNDER TEST
    // ===============================================================================================

    /**
     * The sign-on surface: the shipped boundary, the shipped filter chain, and the credential master the
     * identities live in.
     *
     * <p>Assembled explicitly rather than by scanning, which is this module's established practice for a
     * context-booting specification: a scan of the base package would sweep the test tree's own
     * configuration classes into the graph, and an explicit list lets a reader see in one place exactly what
     * took part.
     *
     * <p><strong>Nothing is stubbed.</strong> The hashing service, the token provider, the message
     * catalogue, the navigation vocabulary, the contract adapter and the filter chain are all the shipped
     * ones, and the identities come off a real server. The menu boundary is deliberately absent: this
     * specification asserts where a turn is directed, which is a value in the response body, and not what
     * answers at the destination.
     *
     * <p>The clock is the pinned instant the shared base publishes, so a session's issued-at and expiry
     * images mean the same thing on every run and no assertion here can depend on a wall clock.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({AuthController.class, ModuleErrorController.class, SignOnContractAdapter.class,
        GlobalExceptionHandler.class, JsonRefusalBodyRenderer.class, AuthenticationService.class,
        NavigationService.class, MessageCatalogService.class, CredentialDigestService.class,
        SignOnStateService.class, SecurityConfig.class, JwtTokenProvider.class, WebMvcConfig.class})
    @EnableConfigurationProperties(JwtProperties.class)
    @EnableJpaRepositories(basePackageClasses = UserSecurityRepository.class)
    @EntityScan(basePackageClasses = UserSecurity.class)
    static class SignOnContext {

        /** Creates the configuration. */
        SignOnContext() {
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
