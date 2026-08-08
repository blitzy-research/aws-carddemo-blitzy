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

import com.carddemo.api.dto.PageMetadata;
import com.carddemo.api.dto.UserRequest;
import com.carddemo.api.dto.UserResponse;
import com.carddemo.config.JwtProperties;
import com.carddemo.config.JwtTokenProvider;
import com.carddemo.config.SecurityConfig;
import com.carddemo.config.WebMvcConfig;
import com.carddemo.domain.UserSecurity;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.repository.RecordWriter;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.AuthenticationService;
import com.carddemo.service.CredentialDigestService;
import com.carddemo.service.MessageCatalogService;
import com.carddemo.service.NavigationService;
import com.carddemo.service.OnlineTransactionBoundary;
import com.carddemo.service.SensitiveFieldEncryptionService;
import com.carddemo.service.SignOnAttemptGovernor;
import com.carddemo.service.SignOnStateService;
import com.carddemo.service.UserListPageTokenService;
import com.carddemo.service.UserManagementService;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.SensitiveValues;
import com.carddemo.support.TestDataFactory;
import com.carddemo.util.ApiRoutePaths;
import com.carddemo.util.CobolStringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The four administrative user-maintenance transactions - {@code CU00} list, {@code CU01} add,
 * {@code CU02} update and {@code CU03} delete - exercised over the shipped servlet boundary and the
 * shipped filter chain, against a real PostgreSQL server carrying the migrated schema and the delivered
 * sign-on seed.
 *
 * <h2>The four headline behaviours this specification exists to pin</h2>
 * <ul>
 *   <li><strong>All four routes are administrator-gated.</strong> This is the widest gate in the estate:
 *       of the eighteen registered transactions, exactly five are administratively classified, and four
 *       of the five are these. Every route is therefore asserted three ways - refused without a session,
 *       refused with an ordinary session, admitted with an administrative one - and the refusal bodies
 *       are asserted to disclose no identifier, name, role code or count.</li>
 *   <li><strong>The page presents ten rows, and a backward page is filled from the tenth slot
 *       downward.</strong> {@code app/cbl/COUSR00C.cbl} declares its row table {@code OCCURS 10 TIMES}
 *       at line 57; the forward walk at L298-L306 bounds itself at eleven and fills upward from slot
 *       one, while the backward walk at L352-L360 seeds the slot number at ten and decrements it. The
 *       consequence is developed at length on {@link BackwardFillDescendingFromTheTenthSlot}, because it
 *       is the single most likely defect in a pagination translation and asserting it correctly requires
 *       knowing which order is the observable one.</li>
 *   <li><strong>The add and the update screens run DIFFERENT ordered cascades.</strong> Add tests the
 *       given name first and the identifier third ({@code app/cbl/COUSR01C.cbl} L117-L151); update tests
 *       the identifier FIRST ({@code app/cbl/COUSR02C.cbl} L179-L213). Two tests submit bodies empty in
 *       the same two positions and assert that the two screens report different items. The orders are
 *       never unified.</li>
 *   <li><strong>The delete screen's failure text names the update operation.</strong>
 *       {@code app/cbl/COUSR03C.cbl} line 332, inside {@code DELETE-USER-SEC-FILE} at L305, moves
 *       {@code Unable to Update User...} on the removal path. That text is part of an externally
 *       observable contract, so it is asserted verbatim and is deliberately NOT corrected to name the
 *       delete operation.</li>
 * </ul>
 *
 * <h2>Credential handling - the strictest constraint on this file</h2>
 * The legacy record holds an eight-character cleartext credential; the target stores a BCrypt digest,
 * which is a documented parity exception rather than a defect. <strong>The legacy cleartext value appears
 * nowhere in this file</strong> - not in a literal, a constant, a comment, an identifier, a display name
 * or an assertion message. Where a credential is needed it is recovered at run time, by offset, from the
 * class-path fixture through {@link TestDataFactory#fixtureCredentialWindow()}, handed straight to an
 * encoder or a request body, and overwritten.
 *
 * <p>Two measured facts recorded on that method govern the arrangement below, and they are the reason
 * this specification signs on as identities it writes itself rather than as delivered ones. The fixture's
 * window carries a synthetic stand-in rather than the legacy value; and the ten digests the credential
 * seed applies were produced from the legacy provisioning value, so they accept that value and refuse the
 * window's. A delivered identity consequently cannot be authenticated from anything this module holds.
 * Assertions about a <em>delivered</em> identity are therefore limited to what a module which does not
 * hold the legacy value can answer - the stored role code, the digest's shape and the digest's refusal of
 * a value it was not derived from.
 *
 * <h2>Container ownership, determinism and reset</h2>
 * The database server belongs to {@link AbstractPostgresIT} and is shared by every integration
 * specification in the module, so this class declares no container, no container annotation, no
 * data-source property source and no context-discarding annotation. Time comes from that class's pinned
 * clock, so nothing here reads a wall clock and no assertion depends on elapsed time, throughput or
 * memory. The identities this specification writes live under one reserved eight-character prefix that
 * the credential seed never occupies and that sorts after every delivered identifier; they are restored
 * before every method and removed after it, so the ten delivered identities are exactly what every other
 * specification finds.
 *
 * <h2>Where the expectations come from</h2>
 * Every screen text, route value, field identifier and role code below is written out as its own literal
 * rather than read from the component that also publishes it: an expectation which borrows its subject's
 * own constant proves only that the subject agrees with itself. Routes, verbs, media types, status codes
 * and property names, by contrast, are taken from the shipped boundary and its published contract types,
 * because those are the shape this specification has to speak to rather than claims it is making.
 *
 * <p>Provenance: {@code app/cbl/COUSR00C.cbl}, {@code app/cbl/COUSR01C.cbl},
 * {@code app/cbl/COUSR02C.cbl} and {@code app/cbl/COUSR03C.cbl}; their symbolic maps
 * {@code app/cpy-bms/COUSR00.CPY} through {@code app/cpy-bms/COUSR03.CPY}; the shared copybooks
 * {@code app/cpy/CSUSR01Y.cpy}, {@code app/cpy/COCOM01Y.cpy} and {@code app/cpy/CSMSG01Y.cpy};
 * {@code app/csd/CARDDEMO.CSD}, whose definitions bind {@code CU00} through {@code CU03}; and
 * {@code app/jcl/DUSRSECJ.jcl}, whose in-stream card images name the ten delivered identities. All read
 * as read-only reference. Message texts, field widths, row counts, role codes, transaction identifiers
 * and paragraph line numbers are contract and metadata; no source line of any legacy member is
 * transcribed, and nothing here reads the legacy tree at run time.
 */
@SpringBootTest(classes = AdminUserControllerIT.UserAdministrationContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            // The shared server was migrated to the head of the delivered set by the base class, so a
            // second migration from this context would be redundant work with no new state to apply.
            "spring.flyway.enabled=false",
            // validate, never none and never a generating value. The shipped profile declares validate
            // and this graph keeps it: a mapping that had drifted from the migrated schema must fail at
            // refresh rather than be silently reconciled, and validate cannot emit DDL.
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
@DisplayName("Gate 5: the administrative user transactions CU00 to CU03 over the shipped boundary")
public class AdminUserControllerIT extends AbstractPostgresIT {

    /**
     * The text an unaccepted row marker composes, from {@code app/cbl/COUSR00C.cbl} line 212.
     *
     * <p>Forty-three characters, asserted whole. It is the one error arm of these four members that
     * emits a text without raising the error switch, so the page is still built beside it.
     */
    private static final String INVALID_SELECTION_MESSAGE = "Invalid selection. Valid values are U and D";

    /** The backward pager's refusal when the displayed page is the first, from L250-L254. */
    private static final String ALREADY_AT_TOP_MESSAGE = "You are already at the top of the page...";

    /** The forward pager's refusal when no further row follows, from L272-L276. */
    private static final String ALREADY_AT_BOTTOM_MESSAGE = "You are already at the bottom of the page...";

    private static final String UNABLE_TO_LOOKUP_MESSAGE = "Unable to lookup User...";

    /** First arm of the add cascade, from {@code app/cbl/COUSR01C.cbl} L118-L123. */
    private static final String ADD_FIRST_NAME_EMPTY_MESSAGE = "First Name can NOT be empty...";

    /** Second arm of the add cascade, from L124-L129. */
    private static final String ADD_LAST_NAME_EMPTY_MESSAGE = "Last Name can NOT be empty...";

    private static final String ADD_USER_ID_EMPTY_MESSAGE = "User ID can NOT be empty...";

    /** Fourth arm of the add cascade. The English word here is screen text, never a stored value. */
    private static final String ADD_CREDENTIAL_EMPTY_MESSAGE = "Password can NOT be empty...";

    /** Fifth arm of the add cascade, from L142-L147. */
    private static final String ADD_USER_TYPE_EMPTY_MESSAGE = "User Type can NOT be empty...";

    /** The duplicate-identifier arm of the write, which folds two store responses into one text. */
    private static final String ADD_DUPLICATE_MESSAGE = "User ID already exist...";

    /** First arm of the update cascade, from {@code app/cbl/COUSR02C.cbl} L180-L185. */
    private static final String UPDATE_USER_ID_EMPTY_MESSAGE = "User ID can NOT be empty...";

    /** Second arm of the update cascade, from L186-L191 - where the add screen tests it first. */
    private static final String UPDATE_FIRST_NAME_EMPTY_MESSAGE = "First Name can NOT be empty...";

    /** Third arm of the update cascade, from L192-L197. */
    private static final String UPDATE_LAST_NAME_EMPTY_MESSAGE = "Last Name can NOT be empty...";

    /**
     * Fourth arm of the update cascade, from L198-L203.
     *
     * <p>Declared separately from the add screen's fourth arm even though the two texts coincide,
     * because they are two arms of two different cascades in two different members: a change to one
     * must not silently propagate to the other, and the two screens treat the item differently - the
     * add screen requires it outright while this one requires it only once supplied.
     */
    private static final String UPDATE_CREDENTIAL_EMPTY_MESSAGE = "Password can NOT be empty...";

    /** Fifth arm of the update cascade, from L204-L209, declared separately for the same reason. */
    private static final String UPDATE_USER_TYPE_EMPTY_MESSAGE = "User Type can NOT be empty...";

    /** The only presence test the delete screen makes, from COUSR03C.cbl L177-L182. */
    private static final String DELETE_USER_ID_EMPTY_MESSAGE = "User ID can NOT be empty...";

    /** The text a submission that changed nothing composes, from L241-L243. */
    private static final String UPDATE_NO_CHANGE_MESSAGE = "Please modify to update ...";

    private static final String UPDATE_PRESS_TO_SAVE_MESSAGE = "Press PF5 key to save your updates ...";

    /** The prompt a successful retrieval composes on the delete screen, from COUSR03C L285-L289. */
    private static final String DELETE_PRESS_TO_DELETE_MESSAGE = "Press PF5 key to delete this user ...";

    private static final String USER_ID_NOT_FOUND_MESSAGE = "User ID NOT found...";

    /**
     * ★ THE PRESERVED LEGACY DEFECT. The removal path's failure text, from
     * {@code app/cbl/COUSR03C.cbl} line 332 inside {@code DELETE-USER-SEC-FILE} at L305.
     *
     * <p>It names the <em>update</em> operation on a screen that deletes, and it is byte-identical to the
     * update screen's own failure text at {@code app/cbl/COUSR02C.cbl} line 386. Correcting it would
     * change an externally observable message contract, so it is reproduced exactly and asserted exactly.
     * Nothing in this file spells the corrected form, deliberately, so that a grep for the correction
     * finds nothing.
     */
    private static final String DELETE_FAILURE_MESSAGE = "Unable to Update User...";

    private static final String SUCCESS_PREFIX = "User ";

    /** Suffix the add screen's success text carries, from COUSR01C L255-L258. */
    private static final String ADD_SUCCESS_SUFFIX = " has been added ...";

    private static final String UPDATE_SUCCESS_SUFFIX = " has been updated ...";

    /** Suffix the delete screen's success text carries, from COUSR03C L314-L322. */
    private static final String DELETE_SUCCESS_SUFFIX = " has been deleted ...";

    /**
     * Rows the list screen presents: exactly ten.
     *
     * <p>The shape of a 3270 screen rather than a tunable page size - {@code app/cbl/COUSR00C.cbl}
     * declares its row table {@code OCCURS 10 TIMES} at line 57, the forward loop bounds itself above ten
     * at L300 and the backward loop seeds itself at ten at L352. Written out here rather than read from
     * the contract type, because the point of the assertion is that the contract agrees with the screen.
     */
    private static final int USER_LIST_ROWS = 10;

    private static final int USER_ID_WIDTH = 8;

    private static final int NAME_PART_WIDTH = 20;

    private static final int USER_TYPE_WIDTH = 1;

    /**
     * Width of the credential item on the two maps that declare one: 8.
     *
     * <p>The width of the value a client may submit, and no implication for how it is stored: the
     * persisted column is wider so that it can hold a one-way digest instead of eight cleartext
     * characters. Declared separately from {@link #USER_ID_WIDTH} even though the two coincide, because
     * a credential and an identifier are unrelated items whose widths happen to agree.
     */
    private static final int CREDENTIAL_WIDTH = 8;

    private static final int SELECTOR_WIDTH = 1;

    private static final String USER_UPDATE_ROUTE = "user-update";

    private static final String USER_DELETE_ROUTE = "user-delete";

    private static final String FIELD_LIST_USER_ID = "USRIDIN";

    private static final String FIELD_ADD_USER_ID = "USERID";

    private static final String FIELD_FIRST_NAME = "FNAME";

    private static final String FIELD_LAST_NAME = "LNAME";

    private static final String FIELD_CREDENTIAL_ITEM = "PASSWD";

    private static final String FIELD_USER_TYPE = "USRTYPE";

    private static final String ADMIN_ROLE_CODE = "A";

    private static final String USER_ROLE_CODE = "U";

    private static final String BEARER_PREFIX = "Bearer ";

    private static final String AUTHENTICATION_REQUIRED = "Authentication required";

    private static final String ACCESS_DENIED = "Access denied";

    private static final String MARKER_UPDATE_UPPER = "U";

    private static final String MARKER_UPDATE_LOWER = "u";

    private static final String MARKER_DELETE_UPPER = "D";

    private static final String MARKER_DELETE_LOWER = "d";

    /** A blank selection, which is a value and not an omission: it means "this row was not marked". */
    private static final String MARKER_BLANK = " ";

    // ===============================================================================================
    // THE DELIVERED POPULATION, AS METADATA
    //
    // Identifier, given name, family name and role code are ordinary reference data and may be named.
    // The credential is not, and is not: it appears nowhere in this file in any form.
    // ===============================================================================================

    private static final int DELIVERED_IDENTITY_COUNT = 10;

    private static final int DELIVERED_ADMINISTRATOR_COUNT = 5;

    private static final int DELIVERED_ORDINARY_COUNT = 5;

    private static final String DELIVERED_ADMIN_IDENTITY = "ADMIN001";

    private static final String DELIVERED_USER_IDENTITY = "USER0001";

    /**
     * The ten delivered identifiers in ascending key order, which is the order the seed loads them in.
     *
     * <p>Written out as literals rather than read from the shared factory that also publishes them, for
     * the reason given on this class: an expectation which borrows its subject's own list proves only
     * that the subject agrees with itself. These ten are also exactly the first page the list screen
     * presents, because the reserved range below sorts after all of them.
     */
    private static final List<String> DELIVERED_IDENTIFIERS_ASCENDING = List.of(
            "ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005",
            "USER0001", "USER0002", "USER0003", "USER0004", "USER0005");

    /**
     * ★ The order the backward walk fills the ten slots in: the tenth slot first, the first slot last.
     *
     * <p>This is the sequence the previous-record verb hands rows out in when the walk is anchored just
     * after the delivered ten - the highest key first, the lowest last - and therefore the order the ten
     * slots are populated in, because {@code app/cbl/COUSR00C.cbl} L352 seeds the slot number at ten and
     * L354-L360 decrements it.
     *
     * <p>Stated as its own literal rather than derived from {@link #DELIVERED_IDENTIFIERS_ASCENDING} by
     * reversal, so that the assertion which compares the two is comparing two independent statements
     * rather than a list against itself.
     */
    private static final List<String> FIRST_PAGE_FILL_ORDER_TEN_DOWN_TO_ONE = List.of(
            "USER0005", "USER0004", "USER0003", "USER0002", "USER0001",
            "ADMIN005", "ADMIN004", "ADMIN003", "ADMIN002", "ADMIN001");

    /**
     * Prefix of the eight-character identifier range this specification reserves.
     *
     * <p>Two properties, both load-bearing. The credential seed never occupies it, so nothing written
     * here can collide with a delivered row - and several specifications in this module assert against
     * those rows on the same shared server, so mutating one would be worse than untidy. And it sorts
     * strictly after every delivered identifier, so the delivered ten are the whole of the first page
     * and everything this specification writes falls on the second. Both facts are what let the paging
     * expectations below be written as fixed lists.
     */
    private static final String RESERVED_PREFIX = "ZZUSER";

    private static final String OWNED_ADMIN = RESERVED_PREFIX + "01";

    private static final String OWNED_ORDINARY = RESERVED_PREFIX + "02";

    /**
     * The identity the update turns amend.
     *
     * <p>Deliberately neither of the two session identities. The filter chain re-reads the sign-on
     * record on every request and compares a fingerprint of its identifier, role code and stored
     * digest, so amending a session's own record would end that session mid-test and the failure would
     * name authentication rather than the contract under test.
     */
    private static final String UPDATE_SUBJECT = RESERVED_PREFIX + "03";

    private static final String DELETE_SUBJECT = RESERVED_PREFIX + "04";

    /** A fifth written identity, so the population exceeds one page and a second page genuinely exists. */
    private static final String PAGE_FILLER = RESERVED_PREFIX + "05";

    private static final String ADDED_IDENTITY = RESERVED_PREFIX + "06";

    private static final String SECOND_ADDED_IDENTITY = RESERVED_PREFIX + "07";

    private static final String ABSENT_IDENTITY = RESERVED_PREFIX + "99";

    /** Given name every identity this specification writes carries; identity, never a secret. */
    private static final String RESERVED_FIRST_NAME = "INTEGRATION";

    private static final String RESERVED_LAST_NAME = "USERADMIN";

    /** A given name an update turn stores, distinct from the written one so a change is observable. */
    private static final String AMENDED_FIRST_NAME = "AMENDED";

    /**
     * A submitted value that no digest in this specification was derived from.
     *
     * <p>Three properties, each load-bearing. It is <strong>exactly eight characters</strong>, because
     * the credential item is declared at that width and a wider value would be answered as a malformed
     * request before it reached the transaction at all. It is <strong>upper case throughout</strong>, so
     * the fold the write path applies cannot turn it into anything else and an assertion about what was
     * stored is genuinely about the hashing rather than about the fold. And it is <strong>obviously
     * synthetic</strong> and unrelated both to the fixture's window value and to the legacy one: the
     * assertions that use it prove the stored digest changed <em>because</em> the submission was
     * genuinely new, which is only meaningful for a value the stored digest did not already accept.
     */
    private static final String A_VALUE_NO_STORED_DIGEST_WAS_DERIVED_FROM = "NOTVALID";

    /**
     * A given name carrying an embedded space, which these screens must accept.
     *
     * <p>Neither the add nor the update screen applies any edit beyond emptiness - no length rule, no
     * character class, no permitted-value list. The estate's alphabetic edit elsewhere blanks letters
     * and then tests the remainder, so embedded spaces pass it too; inventing a stricter rule here
     * would reject input the legacy stores.
     */
    private static final String NAME_WITH_EMBEDDED_SPACE = "MARY ANN";

    /**
     * The five identifiers this specification writes before every method, in ascending key order.
     *
     * <p>Also the whole of the second page, for the reason given on the reserved prefix.
     */
    private static final List<String> OWNED_IDENTIFIERS_ASCENDING = List.of(
            OWNED_ADMIN, OWNED_ORDINARY, UPDATE_SUBJECT, DELETE_SUBJECT, PAGE_FILLER);

    /** Everything this specification may leave behind, written or created, removed after every method. */
    private static final List<String> REMOVABLE_IDENTIFIERS = List.of(
            OWNED_ADMIN, OWNED_ORDINARY, UPDATE_SUBJECT, DELETE_SUBJECT, PAGE_FILLER,
            ADDED_IDENTITY, SECOND_ADDED_IDENTITY);

    /** Reads and writes JSON without binding to a published contract type. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The shipped hashing service, held here so the owned digest is produced once per run.
     *
     * <p>Instantiated directly rather than injected because it is used during class initialisation,
     * before any context exists. It needs no configuration: a digest carries its own salt and the type
     * fixes its own cost factor. Everything else verifies through the injected encoder, so nothing here
     * checks a digest against a differently configured verifier than the boundary uses.
     */
    private static final CredentialDigestService OWNED_DIGEST_SOURCE = new CredentialDigestService();

    /**
     * Digest of the folded contents of the fixture's credential window: what an owned identity stores so
     * that it can genuinely be signed on with.
     *
     * <p>Folded rather than raw because the shipped sign-on path folds every submission before it
     * compares, so a digest of the unfolded characters could never be matched by any submission at all.
     * Neither the window value nor its folded form is returned, logged, named or asserted on anywhere.
     */
    private static final String DIGEST_OF_FOLDED_WINDOW = digestOfFoldedCredentialWindow();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserSecurityRepository users;

    /** The shipped encoder, used only to verify what a write stored - never to produce what it stores. */
    @Autowired
    private PasswordEncoder passwordEncoder;

    public AdminUserControllerIT() {
        super();
    }

    /**
     * Returns the shared server to its delivered state, so the ten delivered identities are present and
     * the reserved range is empty however the run that preceded this one ended.
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
     * <p>Writing rather than reusing is deliberate: the delete turns remove a row and the add turns
     * create one, so a method that did either would otherwise change what the next method finds. The
     * digest was produced once at class initialisation, so this is five cheap merges rather than five
     * hashes.
     */
    @BeforeEach
    void writeOwnedIdentities() {
        writeOwnedIdentity(OWNED_ADMIN, ADMIN_ROLE_CODE);
        writeOwnedIdentity(OWNED_ORDINARY, USER_ROLE_CODE);
        writeOwnedIdentity(UPDATE_SUBJECT, USER_ROLE_CODE);
        writeOwnedIdentity(DELETE_SUBJECT, USER_ROLE_CODE);
        writeOwnedIdentity(PAGE_FILLER, USER_ROLE_CODE);
    }

    /**
     * Removes only the rows this specification wrote or created.
     *
     * <p>Scoped to the reserved identifiers by name, so the ten delivered identities survive every
     * method and every specification that runs after this one finds exactly the seed. Absence is
     * tolerated, because a delete turn is expected to have removed its own subject already.
     */
    @AfterEach
    void clearOwnedIdentities() {
        for (final String removable : REMOVABLE_IDENTIFIERS) {
            if (this.users.findById(removable).isPresent()) {
                this.users.deleteById(removable);
            }
        }
    }

    /**
     * Writes one owned identity through the credential master.
     *
     * <p>Built through the shared factory, so the stored value is a digest by construction: the entity
     * refuses anything that is not shaped like one, which is what makes it impossible for this
     * specification to store a cleartext value even by accident.
     *
     * @param userId   the reserved identifier
     * @param roleCode the one-character role code to store
     */
    private void writeOwnedIdentity(final String userId, final String roleCode) {
        final UserSecurity identity = TestDataFactory.userSecurity()
                .userId(userId)
                .firstName(RESERVED_FIRST_NAME)
                .lastName(RESERVED_LAST_NAME)
                .userTypeCode(roleCode)
                .storedDigest(DIGEST_OF_FOLDED_WINDOW)
                .build();
        this.users.save(identity);
    }

    /**
     * Produces a digest of the folded contents of the class-path fixture's credential window.
     *
     * <p>The window is read by offset, folded with the shipped fold, handed to the shipped hashing
     * service and then overwritten.
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
     * Signs on as an owned identity and returns the session it was issued.
     *
     * @param  userId the owned identity to sign on as
     * @return the bare session token, without its presentation prefix
     * @throws Exception if the boundary cannot be reached
     */
    private String sessionFor(final String userId) throws Exception {
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        // The property name is the published sign-on contract's own component name, read from it rather
        // than chosen here: a client speaks the name the boundary declares.
        payload.put("password", keyedCredential());
        payload.put("keyAction", KeyAction.ENTER.name());
        final MvcResult result = this.mockMvc
                .perform(MockMvcRequestBuilders.post(AuthController.SIGN_ON_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(payload)))
                .andReturn();
        final String header = result.getResponse().getHeader(HttpHeaders.AUTHORIZATION);
        assertThat(header)
                .as("an admitted turn issues a session in the authorization header; %s was not admitted",
                        userId)
                .isNotNull()
                .startsWith(BEARER_PREFIX);
        return header.substring(BEARER_PREFIX.length());
    }

    /**
     * Submits one turn of one of the four routes and returns the whole result.
     *
     * @param  subPath the route's sub-path, taken from the shipped boundary's own constant
     * @param  payload the body to send, whose property names are the published contract's own
     * @param  session the session to present, or {@code null} to present none
     * @return the result, so both the body and the status line can be inspected
     * @throws Exception if the boundary cannot be reached
     */
    private MvcResult submit(final String subPath, final Map<String, Object> payload,
            final String session) throws Exception {
        var request = MockMvcRequestBuilders.post(AdminUserController.USERS_PATH + subPath)
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .accept(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(payload));
        if (session != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + session);
        }
        return this.mockMvc.perform(request).andReturn();
    }

    /**
     * Submits one turn and parses its body, having first asserted the status the contract fixes.
     *
     * @param  subPath the route's sub-path
     * @param  payload the body to send
     * @param  session the session to present
     * @return the parsed body
     * @throws Exception if the boundary cannot be reached
     */
    private JsonNode turn(final String subPath, final Map<String, Object> payload,
            final String session) throws Exception {
        return bodyOf(submit(subPath, payload, session));
    }

    /**
     * Parses the body of a completed turn, having first asserted the status the contract fixes.
     *
     * <p>Every outcome of these four transactions is a screen the legacy program composed and
     * transmitted, rejections included, so the turn completes with the same status in all of them.
     *
     * @param  result the completed turn
     * @return the parsed body
     * @throws Exception if the body cannot be parsed
     */
    private static JsonNode bodyOf(final MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("a rejection is a screen the legacy program composed and sent, so it completes with "
                        + "the same status a success does and the outcome is read from the body")
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * Reads a textual component of a body, distinguishing absent from present-and-null.
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
     * Reads a boolean component, which is always an explicit component and never inferred.
     *
     * @param  body the parsed body
     * @param  name the component name
     * @return the value the boundary published
     */
    private static boolean flagOf(final JsonNode body, final String name) {
        final JsonNode node = body.get(name);
        assertThat(node)
                .as("%s is an explicit component of the published contract, so a body that omitted it "
                        + "would leave every caller inferring it from something else", name)
                .isNotNull();
        return node.asBoolean();
    }

    /**
     * Reads the ordered identifiers of the page a list turn published.
     *
     * <p>Order is preserved exactly as the body carried it, because the order of the page is the whole
     * subject of the paging assertions.
     *
     * @param  body the parsed body of a list turn
     * @return the row identifiers in published order
     */
    private static List<String> rowIdentifiersOf(final JsonNode body) {
        final JsonNode rows = body.get("rows");
        assertThat(rows).as("a list turn publishes its page under its own component").isNotNull();
        final List<String> identifiers = new ArrayList<>(rows.size());
        for (final JsonNode row : rows) {
            identifiers.add(textOf(row, "userId"));
        }
        return List.copyOf(identifiers);
    }

    /**
     * Reads the paging metadata a list turn published.
     *
     * @param  body the parsed body of a list turn
     * @return the metadata node, which is absent when the turn walked nothing
     */
    private static JsonNode pageMetadataOf(final JsonNode body) {
        final JsonNode metadata = body.get("pageMetadata");
        assertThat(metadata)
                .as("a turn that walked the key sequence publishes where the page sits")
                .isNotNull();
        assertThat(metadata.isNull()).as("the metadata is populated rather than null").isFalse();
        return metadata;
    }

    /**
     * Builds the ten positional selections with exactly one of them marked.
     *
     * <p>The collection is positional: element <em>n</em> is the marker typed against screen row
     * <em>n</em>, so the unmarked positions are blanks rather than omissions and the length is the
     * screen's row count rather than the number of marks.
     *
     * @param  oneBasedPosition the row to mark
     * @param  marker           the marker to type into it
     * @return ten positional selections
     */
    private static List<String> selectionsMarking(final int oneBasedPosition, final String marker) {
        final List<String> selections = new ArrayList<>(USER_LIST_ROWS);
        for (int position = 1; position <= USER_LIST_ROWS; position++) {
            selections.add(position == oneBasedPosition ? marker : MARKER_BLANK);
        }
        return List.copyOf(selections);
    }

    /**
     * Builds the echoed navigation state in the re-entered program context.
     *
     * <p>Every screen here treats a first entry differently from a later one: the add screen merely
     * presents its blank map, and the update and delete screens merely place the cursor. The submitting
     * turns therefore have to echo the re-entered context, which is precisely what the legacy
     * conversation carried in the communication area between two turns of the same transaction.
     *
     * @return the navigation state a submitting turn echoes
     */
    private static Map<String, Object> reEnteredContext() {
        final Map<String, Object> context = new LinkedHashMap<>();
        context.put("programContext", "REENTER");
        return context;
    }

    /**
     * Builds a list-screen body, omitting every component the caller did not supply.
     *
     * <p>Omission and blankness are different submissions and both are meaningful, so nothing is
     * defaulted here: a component the caller passed as {@code null} is absent from the body entirely.
     *
     * @param  searchUserId      the browse start key, or {@code null} to omit it
     * @param  rowSelections     the ten positional markers, or {@code null} to omit them
     * @param  firstUserIdOnPage the retained first key of the displayed page, or {@code null}
     * @param  lastUserIdOnPage  the retained last key of the displayed page, or {@code null}
     * @param  rowSnapshotToken  the page snapshot echoed from the displayed rows, or {@code null}
     * @param  keyAction         the attention key
     * @return the body to submit
     */
    private static Map<String, Object> listBody(final String searchUserId,
            final List<String> rowSelections, final String firstUserIdOnPage,
            final String lastUserIdOnPage, final String rowSnapshotToken, final KeyAction keyAction) {
        final Map<String, Object> payload = new LinkedHashMap<>();
        if (searchUserId != null) {
            payload.put("searchUserId", searchUserId);
        }
        if (rowSelections != null) {
            payload.put("rowSelections", rowSelections);
        }
        if (firstUserIdOnPage != null) {
            payload.put("firstUserIdOnPage", firstUserIdOnPage);
        }
        if (lastUserIdOnPage != null) {
            payload.put("lastUserIdOnPage", lastUserIdOnPage);
        }
        if (rowSnapshotToken != null) {
            payload.put("rowSnapshotToken", rowSnapshotToken);
        }
        payload.put("keyAction", keyAction.name());
        payload.put("navigationContext", reEnteredContext());
        return payload;
    }

    /**
     * Builds a body for one of the three single-record screens, omitting what the caller did not supply.
     *
     * <p>One builder serves all three because one request type serves all three; which components each
     * operation may carry is the published contract's own rule, and a component this builder omits is a
     * component the client did not send rather than one it sent empty.
     *
     * @param  userId    the identifier, or {@code null} to omit it
     * @param  firstName the given name, or {@code null} to omit it
     * @param  lastName  the family name, or {@code null} to omit it
     * @param  password  the credential, or {@code null} to omit it
     * @param  userType  the raw role code, or {@code null} to omit it
     * @param  keyAction the attention key
     * @return the body to submit
     */
    private static Map<String, Object> recordBody(final String userId, final String firstName,
            final String lastName, final String password, final String userType,
            final KeyAction keyAction) {
        final Map<String, Object> payload = new LinkedHashMap<>();
        if (userId != null) {
            payload.put("userId", userId);
        }
        if (firstName != null) {
            payload.put("firstName", firstName);
        }
        if (lastName != null) {
            payload.put("lastName", lastName);
        }
        if (password != null) {
            payload.put("password", password);
        }
        if (userType != null) {
            payload.put("userType", userType);
        }
        payload.put("keyAction", keyAction.name());
        payload.put("navigationContext", reEnteredContext());
        return payload;
    }

    /**
     * Fetches the first page and returns it, so a turn that marks a row can echo the page snapshot the
     * server minted for it.
     *
     * @param  session the administrative session
     * @return the parsed body of the first page
     * @throws Exception if the boundary cannot be reached
     */
    private JsonNode firstPage(final String session) throws Exception {
        return turn(AdminUserController.LIST_SUBPATH,
                listBody(null, null, null, null, null, KeyAction.ENTER), session);
    }

    /**
     * The four sub-paths this controller maps, taken from the shipped boundary's own constants.
     *
     * @return the four sub-paths in the order the transactions are numbered
     */
    private static List<String> allFourSubPaths() {
        return List.of(AdminUserController.LIST_SUBPATH, AdminUserController.ADD_SUBPATH,
                AdminUserController.UPDATE_SUBPATH, AdminUserController.DELETE_SUBPATH);
    }

    /**
     * Builds a body that reaches the named transaction and leaves the store untouched.
     *
     * <p>Used by the entitlement assertions, which are about who may reach a route rather than about
     * what a route does. Every one of these bodies stops on a presence test or builds a page, so an
     * admitted turn writes nothing and the assertions can be made against all four routes without one
     * of them quietly creating or removing a record.
     *
     * @param  subPath the route's sub-path
     * @return a body that mutates nothing
     */
    private static Map<String, Object> nonMutatingBodyFor(final String subPath) {
        if (AdminUserController.LIST_SUBPATH.equals(subPath)) {
            return listBody(null, null, null, null, null, KeyAction.ENTER);
        }
        return recordBody(null, null, null, null, null, KeyAction.ENTER);
    }

    /**
     * Reads the summary a refusal body carries.
     *
     * @param  result the completed request
     * @return the summary text, or {@code null} when the body carries none
     * @throws Exception if the body cannot be parsed
     */
    private static String summaryOf(final MvcResult result) throws Exception {
        final String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .as("a refusal carries the module's own envelope rather than an empty body")
                .isNotEmpty();
        return textOf(JSON.readTree(body), "message");
    }

    /**
     * Returns the whole body of a completed request as text, for the disclosure assertions.
     *
     * @param  result the completed request
     * @return the body as text
     * @throws Exception if the body cannot be read
     */
    private static String rawBodyOf(final MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Collects every property name a body carries, at every depth.
     *
     * <p>Recursive on purpose: a credential leaking through a nested object would not be found by
     * inspecting the top level, and the point of the negative assertions is that no depth carries one.
     *
     * @param  node the parsed body
     * @return every property name in the document
     */
    private static List<String> propertyNamesOf(final JsonNode node) {
        final List<String> names = new ArrayList<>();
        collectPropertyNames(node, names);
        return List.copyOf(names);
    }

    private static void collectPropertyNames(final JsonNode node, final List<String> names) {
        if (node.isObject()) {
            for (final Map.Entry<String, JsonNode> property : node.properties()) {
                names.add(property.getKey());
                collectPropertyNames(property.getValue(), names);
            }
        } else if (node.isArray()) {
            for (final JsonNode element : node) {
                collectPropertyNames(element, names);
            }
        }
    }

    /**
     * Returns a copy of a list in reverse order, so an ordered expectation can be stated in either
     * direction without mutating what it was derived from.
     *
     * @param  values the list to reverse
     * @return a reversed copy
     */
    private static List<String> reversedCopy(final List<String> values) {
        return List.copyOf(values.reversed());
    }

    /**
     * Returns a field of the given width holding nothing but spaces.
     *
     * <p>Spaces at the declared width are precisely what a screen field holds when it was never keyed, so
     * this is the literal shape of the legacy empty-item condition rather than a generous reading of it.
     *
     * @param  width the field's declared width
     * @return that many spaces
     */
    private static String blank(final int width) {
        return " ".repeat(width);
    }

    /**
     * Asserts that a turn reported exactly one per-field finding, naming the item the cascade stopped on.
     *
     * <p>Exactly one, because the legacy cascades stop at their first empty item: a turn reporting several
     * findings would be a screen the legacy cannot compose. The finding is asserted whole - the property
     * name a client binds to, the screen item the cursor lands on, the two-state classification, and the
     * text - because each of the four is separately consumable.
     *
     * @param body         the parsed body
     * @param property     the request property the finding names
     * @param screenFieldId the screen item the finding names
     * @param message      the text the finding carries
     */
    private static void assertOneFieldErrorNaming(final JsonNode body, final String property,
            final String screenFieldId, final String message) {
        final JsonNode fieldErrors = body.get("fieldErrors");
        assertThat(fieldErrors).as("the findings are a component of their own").isNotNull();
        assertThat(fieldErrors.size())
                .as("the cascade stops at its first empty item, so exactly one finding is reported")
                .isEqualTo(1);
        final JsonNode only = fieldErrors.get(0);
        assertThat(textOf(only, "fieldName"))
                .as("the name a client binds the finding to, taken from the published contract's own "
                        + "component rather than chosen here")
                .isEqualTo(property);
        assertThat(textOf(only, "screenFieldId"))
                .as("the screen item the cursor lands on - an opaque correlation label, never a byte, a "
                        + "coordinate or a presentation value")
                .isEqualTo(screenFieldId);
        assertThat(textOf(only, "state"))
                .as("an absent value is MISSING; a present and unusable one is INVALID, and the two are "
                        + "separate states rather than one boolean")
                .isEqualTo("MISSING");
        assertThat(textOf(only, "message")).isEqualTo(message);
    }

    /**
     * Reports whether a stored digest accepts the fixture's credential window as the terminal would have
     * keyed it, without that value entering the caller.
     *
     * <p>The window is read here, folded here, matched here and overwritten here, so it appears in no
     * assertion message and in no log line. The fold is applied because the shipped write path folds
     * every submitted credential before it hashes one.
     *
     * @param  digest the stored digest to test
     * @return {@code true} when the digest accepts the folded window value
     */
    private boolean digestAcceptsTheKeyedCredential(final String digest) {
        final char[] window = TestDataFactory.fixtureCredentialWindow();
        try {
            return this.passwordEncoder.matches(CobolStringUtils.asciiUpperFold(new String(window)),
                    digest);
        } finally {
            Arrays.fill(window, ' ');
        }
    }

    /**
     * Reports whether a stored value discloses the fixture's credential window in any form this file can
     * test for - as the value itself, as its folded form, or as a substring of either.
     *
     * <p>Answered inside this method so the value never reaches an assertion message. A digest is a
     * one-way function of its input, so the honest expectation is {@code false} for every stored value.
     *
     * @param  stored the value a write actually stored
     * @return {@code true} when the stored value reveals the window value
     */
    private static boolean revealsTheKeyedCredential(final String stored) {
        final char[] window = TestDataFactory.fixtureCredentialWindow();
        try {
            final String keyed = new String(window);
            final String folded = CobolStringUtils.asciiUpperFold(keyed);
            return stored == null
                    || stored.equals(keyed)
                    || stored.equals(folded)
                    || stored.contains(keyed)
                    || stored.contains(folded);
        } finally {
            Arrays.fill(window, ' ');
        }
    }

    @Nested
    @DisplayName("Admin gating on all four routes: refused without a session, refused with an ordinary "
            + "one, admitted with an administrative one")
    class AdminGatingOnAllFourRoutes {

        AdminGatingOnAllFourRoutes() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("all four routes hang beneath the one administrative prefix the chain gates")
        void allFourRoutesHangBeneathTheAdministrativePrefix() {
            // The gate is one rule over the prefix rather than four rules over four routes, so a route
            // added beneath it is administrator-only from the moment it exists. That property is what the
            // three assertions below are actually testing, and it only holds while this is true.
            assertThat(AdminUserController.USERS_PATH)
                    .as("the shared prefix is what makes the single chain rule cover all four routes")
                    .startsWith(ApiRoutePaths.ADMIN_PATH_PREFIX);
            for (final String subPath : allFourSubPaths()) {
                assertThat(AdminUserController.USERS_PATH + subPath)
                        .startsWith(ApiRoutePaths.ADMIN_PATH_PREFIX);
            }
        }

        @Test
        @DisplayName("★ every route refuses a request that presents no session, and says only that a "
                + "session is required")
        void everyRouteRefusesARequestPresentingNoSession() throws Exception {
            for (final String subPath : allFourSubPaths()) {
                final MvcResult result =
                        submit(subPath, nonMutatingBodyFor(subPath), null);

                assertThat(result.getResponse().getStatus())
                        .as("%s must refuse an absent session for want of a session", subPath)
                        .isEqualTo(401);
                assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                        .as("the refusal names the scheme a caller should present, which is what makes it "
                                + "actionable without describing the rule that refused")
                        .isNotNull();
                assertThat(summaryOf(result))
                        .as("the summary distinguishes a missing session from an insufficient one")
                        .isEqualTo(AUTHENTICATION_REQUIRED);
            }
        }

        @Test
        @DisplayName("★ every route refuses an ordinary session for want of the entitlement, and leaks no "
                + "record while doing so")
        void everyRouteRefusesAnOrdinarySessionForWantOfTheEntitlement() throws Exception {
            final String ordinarySession = sessionFor(OWNED_ORDINARY);

            for (final String subPath : allFourSubPaths()) {
                final MvcResult result =
                        submit(subPath, nonMutatingBodyFor(subPath), ordinarySession);

                assertThat(result.getResponse().getStatus())
                        .as("%s admits only the administrative entitlement; the caller here is known and "
                                + "is not entitled", subPath)
                        .isEqualTo(403);
                assertThat(summaryOf(result)).isEqualTo(ACCESS_DENIED);
                assertThat(rawBodyOf(result))
                        .as("a refusal discloses no user record: no identifier, no name, no role code and "
                                + "no count")
                        .doesNotContain(DELIVERED_ADMIN_IDENTITY)
                        .doesNotContain(DELIVERED_USER_IDENTITY)
                        .doesNotContain(OWNED_ADMIN)
                        .doesNotContain(OWNED_ORDINARY)
                        .doesNotContain(RESERVED_FIRST_NAME)
                        .doesNotContain(RESERVED_LAST_NAME)
                        .doesNotContain("rows")
                        .doesNotContain("userId")
                        .doesNotContain("userType")
                        .doesNotContain("pageMetadata");
            }
        }

        @Test
        @DisplayName("★ every route admits an administrative session and composes a screen")
        void everyRouteAdmitsAnAdministrativeSession() throws Exception {
            final String adminSession = sessionFor(OWNED_ADMIN);

            for (final String subPath : allFourSubPaths()) {
                final MvcResult result = submit(subPath, nonMutatingBodyFor(subPath), adminSession);

                assertThat(result.getResponse().getStatus())
                        .as("%s admits an administrative session, and every screen these transactions "
                                + "compose - rejections included - completes with the same status",
                                subPath)
                        .isEqualTo(200);
            }
        }

        @Test
        @DisplayName("every route refuses a session that cannot be read at all")
        void everyRouteRefusesASessionThatCannotBeRead() throws Exception {
            // Deliberately not a truncated real token: an unreadable value proves the filter verifies
            // rather than merely inspects, and a truncated one would additionally depend on where it was
            // cut.
            for (final String subPath : allFourSubPaths()) {
                final MvcResult result = submit(subPath, nonMutatingBodyFor(subPath),
                        "not-a-readable-session");

                assertThat(result.getResponse().getStatus()).isEqualTo(401);
                assertThat(summaryOf(result)).isEqualTo(AUTHENTICATION_REQUIRED);
            }
        }

        @Test
        @DisplayName("an administrative session is not what a role code claimed in the echoed navigation "
                + "state can manufacture")
        void anEchoedRoleCodeCannotManufactureAnEntitlement() throws Exception {
            // The navigation state is client-echoed and therefore never authorization input. An ordinary
            // session that claims the administrative code in the state it echoes is still refused.
            final String ordinarySession = sessionFor(OWNED_ORDINARY);
            final Map<String, Object> body = listBody(null, null, null, null, null, KeyAction.ENTER);
            final Map<String, Object> context = new LinkedHashMap<>();
            context.put("programContext", "REENTER");
            context.put("userId", OWNED_ADMIN);
            context.put("userType", ADMIN_ROLE_CODE);
            body.put("navigationContext", context);

            final MvcResult result = submit(AdminUserController.LIST_SUBPATH, body, ordinarySession);

            assertThat(result.getResponse().getStatus())
                    .as("the entitlement comes from the session the chain established, never from the "
                            + "state the client echoed")
                    .isEqualTo(403);
        }
    }

    @Nested
    @DisplayName("List page size 10: the shape of the screen, not a tunable figure")
    class ListPageSizeIsTen {

        ListPageSizeIsTen() {
        }

        @Test
        @DisplayName("★ the published page size for this screen is ten, and is nobody else's figure")
        void thePublishedPageSizeForThisScreenIsTen() {
            // COUSR00C.cbl L57 declares the row table OCCURS 10 TIMES; L300 bounds the forward loop above
            // ten and L352 seeds the backward loop at ten. Ten is therefore the screen, not a default and
            // not a caller's choice.
            assertThat(PageMetadata.USER_LIST_PAGE_SIZE)
                    .as("the paging contract names the user list's own row count")
                    .isEqualTo(USER_LIST_ROWS);
            assertThat(UserRequest.ROW_SELECTION_COUNT)
                    .as("the request contract admits exactly one marker per row the screen presents")
                    .isEqualTo(USER_LIST_ROWS);
            assertThat(PageMetadata.USER_LIST_PAGE_SIZE)
                    .as("the card list offers seven rows and the two figures are deliberately never "
                            + "reconciled, so borrowing one for the other would be wrong even when they "
                            + "happened to agree")
                    .isNotEqualTo(PageMetadata.CARD_LIST_PAGE_SIZE);
        }

        @Test
        @DisplayName("a full page carries exactly ten rows and reports the same figure in its metadata")
        void aFullPageCarriesExactlyTenRows() throws Exception {
            final JsonNode body = firstPage(sessionFor(OWNED_ADMIN));

            assertThat(rowIdentifiersOf(body))
                    .as("the delivered population fills the screen exactly once, so the first page is a "
                            + "full one")
                    .hasSize(USER_LIST_ROWS);
            assertThat(pageMetadataOf(body).get("pageSize").asInt())
                    .as("the metadata describes the page it travels with")
                    .isEqualTo(USER_LIST_ROWS);
        }

        @Test
        @DisplayName("an eleventh marker corresponds to no row and is refused rather than truncated")
        void anEleventhMarkerIsRefused() throws Exception {
            final List<String> tooMany = new ArrayList<>(selectionsMarking(1, MARKER_UPDATE_UPPER));
            tooMany.add(MARKER_BLANK);

            final MvcResult result = submit(AdminUserController.LIST_SUBPATH,
                    listBody(null, List.copyOf(tooMany), null, null, null, KeyAction.ENTER),
                    sessionFor(OWNED_ADMIN));

            assertThat(result.getResponse().getStatus())
                    .as("no legacy submission could carry an eleventh marker, because the screen has ten "
                            + "rows; refusing is deliberate and silently dropping one would shift every "
                            + "later marker onto the wrong row")
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("the page indicator is text, so its leading zeros survive")
        void thePageIndicatorIsTextAndKeepsItsLeadingZeros() throws Exception {
            final JsonNode metadata = pageMetadataOf(firstPage(sessionFor(OWNED_ADMIN)));

            assertThat(textOf(metadata, "displayedPageNumber"))
                    .as("the indicator is an eight-character zero-filled value, exactly as the map's own "
                            + "item carried it; a numeric type would have dropped the seven zeros")
                    .isEqualTo("00000001")
                    .hasSize(8);
        }
    }

    @Nested
    @DisplayName("Forward fill ascending: rows one through ten, in key order")
    class ForwardFillAscending {

        ForwardFillAscending() {
        }

        @Test
        @DisplayName("★ the first page fills rows one through ten in ascending key order")
        void theFirstPageFillsRowsOneThroughTenAscending() throws Exception {
            // PROCESS-PAGE-FORWARD, COUSR00C.cbl L282: L298-L306 seeds the slot number at one and
            // increments it, so ascending reads land in ascending slots.
            final JsonNode body = firstPage(sessionFor(OWNED_ADMIN));

            assertThat(rowIdentifiersOf(body))
                    .as("the page is an ordered sequence of rows and not a set of them, so it is asserted "
                            + "element by element")
                    .containsExactlyElementsOf(DELIVERED_IDENTIFIERS_ASCENDING);
        }

        @Test
        @DisplayName("the first page retains its own first and last keys and reports that a page follows")
        void theFirstPageRetainsItsBoundaryKeys() throws Exception {
            final JsonNode metadata = pageMetadataOf(firstPage(sessionFor(OWNED_ADMIN)));

            // L389 retains the first slot's identifier and L435 the tenth's; those two are what the
            // paging keys later position on.
            assertThat(textOf(metadata, "previousCursorKey"))
                    .as("the first slot's identifier, which a backward walk repositions on")
                    .isEqualTo(DELIVERED_IDENTIFIERS_ASCENDING.getFirst());
            assertThat(textOf(metadata, "nextCursorKey"))
                    .as("the tenth slot's identifier, which a forward walk repositions on")
                    .isEqualTo(DELIVERED_IDENTIFIERS_ASCENDING.getLast());
            assertThat(textOf(metadata, "direction")).isEqualTo("FORWARD");
            assertThat(metadata.get("hasMorePages").asBoolean())
                    .as("one read past the page discovered a further row")
                    .isTrue();
            assertThat(metadata.get("hasPreviousPages").asBoolean())
                    .as("nothing precedes the first page")
                    .isFalse();
        }

        @Test
        @DisplayName("the forward pager continues from the retained last key onto the following page")
        void theForwardPagerContinuesFromTheRetainedLastKey() throws Exception {
            // PROCESS-PF8-KEY, COUSR00C.cbl L260: L262-L266 positions on the retained last identifier and
            // L288-L290 consumes it, so the page that follows starts strictly after it.
            final JsonNode body = turn(AdminUserController.LIST_SUBPATH,
                    listBody(null, null, null, DELIVERED_IDENTIFIERS_ASCENDING.getLast(), null,
                            KeyAction.PFK08),
                    sessionFor(OWNED_ADMIN));

            assertThat(rowIdentifiersOf(body))
                    .as("the second page is the remainder of the sequence, in key order")
                    .containsExactlyElementsOf(OWNED_IDENTIFIERS_ASCENDING);
            final JsonNode metadata = pageMetadataOf(body);
            assertThat(textOf(metadata, "displayedPageNumber")).isEqualTo("00000002");
            assertThat(metadata.get("hasPreviousPages").asBoolean()).isTrue();
            assertThat(metadata.get("hasMorePages").asBoolean())
                    .as("nothing follows the last page")
                    .isFalse();
        }

        @Test
        @DisplayName("the forward pager refuses to walk past the end and asks for the displayed page to "
                + "be kept")
        void theForwardPagerRefusesToWalkPastTheEnd() throws Exception {
            // L272-L276: the already-at-the-bottom arm emits its text, clears the erase flag so the screen
            // is overwritten in place, and deliberately leaves the error switch clear.
            final JsonNode body = turn(AdminUserController.LIST_SUBPATH,
                    listBody(null, null, null, OWNED_IDENTIFIERS_ASCENDING.getLast(), null,
                            KeyAction.PFK08),
                    sessionFor(OWNED_ADMIN));

            assertThat(textOf(body, "message")).isEqualTo(ALREADY_AT_BOTTOM_MESSAGE);
            assertThat(flagOf(body, "generalError"))
                    .as("this arm emits a text without raising the switch, so a caller must read the two "
                            + "independently")
                    .isFalse();
            assertThat(rowIdentifiersOf(body))
                    .as("the arm returns no rows because the operator's own page is being kept")
                    .isEmpty();
            assertThat(flagOf(body, "preserveDisplayedPage"))
                    .as("without this instruction the reply would be indistinguishable from an empty page "
                            + "and a client would blank a screen the legacy keeps")
                    .isTrue();
            assertThat(body.has("pageMetadata"))
                    .as("the arm walked nothing, and the paging contract admits no default direction, so "
                            + "no metadata travels with the answer at all")
                    .isFalse();
        }
    }

    // ===============================================================================================
    // 4. THE BACKWARD WALK FILLS THE TENTH SLOT FIRST AND THE FIRST SLOT LAST
    // ===============================================================================================

    /**
     * The backward pager, whose fill order is the single most consequential detail in this translation.
     *
     * <h2>Read this before changing an assertion here</h2>
     * The legacy backward walk at {@code app/cbl/COUSR00C.cbl} L336 seeds its slot number at ten
     * (L352) and <strong>decrements</strong> it (L354-L360) while reading with the previous-record verb.
     * So the rows arrive in <em>descending</em> key order and are placed into <em>descending</em> slots:
     * the highest key of the page lands in slot ten and the lowest in slot one.
     *
     * <p>Slot order is screen order, and the screen presents slot one at the top. The consequence is
     * therefore that a page reached backward is <strong>presented in the same ascending order</strong> as
     * a page reached forward, and needs no separate reversing step - reading the slots in slot order
     * <em>is</em> the reversal of the read order. That is what the shipped paging carrier states about
     * itself, and it is why the published page below is asserted ascending.
     *
     * <p><strong>The defect this group exists to catch</strong> is a translation that filled slots one
     * upward from a descending read - the obvious thing to write, and wrong. Such a page would be
     * published in descending key order and would additionally retain the two boundary keys the wrong way
     * round, because L389 retains slot one's identifier as the page's first key and L435 retains slot
     * ten's as its last. Both consequences are asserted, so either mistake fails here:
     * <ol>
     *   <li>the published page is asserted element by element against the ascending window;</li>
     *   <li>the page read from its last element back to its first is asserted element by element against
     *       the descending sequence the previous-record verb actually hands out - which is the fill order
     *       ten down to one, stated as its own literal;</li>
     *   <li>the two retained boundary keys are asserted to be slot one's and slot ten's identifiers
     *       rather than the reverse.</li>
     * </ol>
     */
    @Nested
    @DisplayName("Backward PF7 fill descending 10 to 1: the tenth slot is filled first and the first slot "
            + "last, so the page presents in screen order")
    class BackwardFillDescendingFromTheTenthSlot {

        BackwardFillDescendingFromTheTenthSlot() {
        }

        @Test
        @DisplayName("★ the backward page is the ten rows preceding the anchor, in the order the screen "
                + "presented them")
        void theBackwardPageIsTheTenRowsPrecedingTheAnchor() throws Exception {
            // PROCESS-PF7-KEY, COUSR00C.cbl L237: L239-L243 positions on the retained first identifier of
            // the page being left, and L248 walks only when a page precedes this one.
            final JsonNode body = backwardFromTheSecondPage();

            assertThat(rowIdentifiersOf(body))
                    .as("an ORDERED assertion, element by element, and never set membership: a page whose "
                            + "members are right and whose sequence is wrong is a parity failure")
                    .containsExactlyElementsOf(DELIVERED_IDENTIFIERS_ASCENDING);
            assertThat(textOf(pageMetadataOf(body), "direction"))
                    .as("the direction the walk took is published, because a client repositions with it")
                    .isEqualTo("BACKWARD");
        }

        @Test
        @DisplayName("★ the fill order is the tenth slot down to the first: reading the page backwards "
                + "reproduces the previous-record sequence exactly")
        void theFillOrderIsTheTenthSlotDownToTheFirst() throws Exception {
            // L352-L360: the backward walk starts at the bottom slot and steps upward one slot per
            // previous record read. The sequence below is the order those reads hand rows out - slot ten
            // first, slot one last - and it is stated as its own literal rather than derived from the
            // answer.
            final List<String> page = rowIdentifiersOf(backwardFromTheSecondPage());

            assertThat(reversedCopy(page))
                    .as("slot ten holds the highest key of the page and slot one the lowest, which is what "
                            + "a fill running ten down to one produces and what a fill running one up to "
                            + "ten could not")
                    .containsExactlyElementsOf(FIRST_PAGE_FILL_ORDER_TEN_DOWN_TO_ONE);
            assertThat(page)
                    .as("and the page is therefore NOT published in the order it was read: the screen "
                            + "presents slot one at the top, so the reversal is the fill itself rather "
                            + "than a separate step")
                    .isNotEqualTo(FIRST_PAGE_FILL_ORDER_TEN_DOWN_TO_ONE);
        }

        @Test
        @DisplayName("★ the two retained boundary keys are the first and tenth slots, not the reverse")
        void theTwoRetainedBoundaryKeysAreTheFirstAndTenthSlots() throws Exception {
            final JsonNode metadata = pageMetadataOf(backwardFromTheSecondPage());

            // L389 and L435 are the only two arms of POPULATE-USER-DATA that do anything beyond moving the
            // four items, and they are what make these two keys positional rather than directional.
            assertThat(textOf(metadata, "previousCursorKey"))
                    .as("slot one, filled LAST by the backward walk, carries the lowest key of the page")
                    .isEqualTo(DELIVERED_IDENTIFIERS_ASCENDING.getFirst());
            assertThat(textOf(metadata, "nextCursorKey"))
                    .as("slot ten, filled FIRST by the backward walk, carries the highest key of the page")
                    .isEqualTo(DELIVERED_IDENTIFIERS_ASCENDING.getLast());
        }

        @Test
        @DisplayName("the backward pager refuses to walk before the first page and asks for the displayed "
                + "page to be kept")
        void theBackwardPagerRefusesToWalkBeforeTheFirstPage() throws Exception {
            // L250-L254: the already-at-the-top arm, which like its forward counterpart emits a text and
            // leaves the error switch clear.
            final JsonNode body = turn(AdminUserController.LIST_SUBPATH,
                    listBody(null, null, DELIVERED_IDENTIFIERS_ASCENDING.getFirst(), null, null,
                            KeyAction.PFK07),
                    sessionFor(OWNED_ADMIN));

            assertThat(textOf(body, "message")).isEqualTo(ALREADY_AT_TOP_MESSAGE);
            assertThat(flagOf(body, "generalError")).isFalse();
            assertThat(rowIdentifiersOf(body)).isEmpty();
            assertThat(flagOf(body, "preserveDisplayedPage")).isTrue();
        }

        /**
         * Walks backward out of the second page, which is the only way a backward walk is reachable here.
         *
         * <p>The backward pager derives which page it is on from the position of its own anchor and walks
         * only when a page precedes that one, so an anchor inside the first page takes the
         * already-at-the-top arm instead. Anchoring on the first identifier of the second page is
         * therefore what exercises the walk itself.
         *
         * @return the parsed body of the backward page
         * @throws Exception if the boundary cannot be reached
         */
        private JsonNode backwardFromTheSecondPage() throws Exception {
            return turn(AdminUserController.LIST_SUBPATH,
                    listBody(null, null, OWNED_IDENTIFIERS_ASCENDING.getFirst(), null, null,
                            KeyAction.PFK07),
                    sessionFor(OWNED_ADMIN));
        }
    }

    @Nested
    @DisplayName("Selector first-non-blank U/D and the invalid-selection message")
    class SelectorFirstNonBlankWins {

        SelectorFirstNonBlankWins() {
        }

        @Test
        @DisplayName("★ an upper-case update marker nominates the update screen and carries the marked "
                + "identifier forward")
        void anUpperCaseUpdateMarkerNominatesTheUpdateScreen() throws Exception {
            // The dispatch at COUSR00C.cbl L189-L215 records the originating transaction, carries the
            // selected identifier forward and transfers - which is what a nominated route amounts to here.
            final JsonNode body = markRow(2, MARKER_UPDATE_UPPER);

            assertThat(textOf(body, "nextRoute")).isEqualTo(USER_UPDATE_ROUTE);
            assertThat(textOf(body, "userId"))
                    .as("the identifier standing in the marked row, resolved from the page the operator "
                            + "was looking at")
                    .isEqualTo(DELIVERED_IDENTIFIERS_ASCENDING.get(1));
            assertThat(rowIdentifiersOf(body))
                    .as("an accepted marker transfers instead of rebuilding the page, so no page comes "
                            + "back with it")
                    .isEmpty();
        }

        @Test
        @DisplayName("a lower-case update marker nominates the same screen, because the source lists both "
                + "cases")
        void aLowerCaseUpdateMarkerNominatesTheSameScreen() throws Exception {
            final JsonNode body = markRow(2, MARKER_UPDATE_LOWER);

            assertThat(textOf(body, "nextRoute")).isEqualTo(USER_UPDATE_ROUTE);
            assertThat(textOf(body, "userId")).isEqualTo(DELIVERED_IDENTIFIERS_ASCENDING.get(1));
        }

        @Test
        @DisplayName("an upper-case delete marker nominates the delete screen")
        void anUpperCaseDeleteMarkerNominatesTheDeleteScreen() throws Exception {
            final JsonNode body = markRow(3, MARKER_DELETE_UPPER);

            assertThat(textOf(body, "nextRoute")).isEqualTo(USER_DELETE_ROUTE);
            assertThat(textOf(body, "userId")).isEqualTo(DELIVERED_IDENTIFIERS_ASCENDING.get(2));
        }

        @Test
        @DisplayName("a lower-case delete marker nominates the same screen")
        void aLowerCaseDeleteMarkerNominatesTheSameScreen() throws Exception {
            final JsonNode body = markRow(3, MARKER_DELETE_LOWER);

            assertThat(textOf(body, "nextRoute")).isEqualTo(USER_DELETE_ROUTE);
            assertThat(textOf(body, "userId")).isEqualTo(DELIVERED_IDENTIFIERS_ASCENDING.get(2));
        }

        @Test
        @DisplayName("★ the first non-blank marker wins and a later one is never examined")
        void theFirstNonBlankMarkerWinsAndALaterOneIsIgnored() throws Exception {
            // The scan at L151-L185 is an EVALUATE TRUE over the ten row items in row order, and EVALUATE
            // stops at its first true condition - so marking several rows marks the earliest of them.
            final String session = sessionFor(OWNED_ADMIN);
            final JsonNode page = firstPage(session);
            final List<String> markers = new ArrayList<>(USER_LIST_ROWS);
            for (int position = 1; position <= USER_LIST_ROWS; position++) {
                if (position == 2) {
                    markers.add(MARKER_UPDATE_UPPER);
                } else if (position == 5) {
                    markers.add(MARKER_DELETE_UPPER);
                } else {
                    markers.add(MARKER_BLANK);
                }
            }

            final JsonNode body = turn(AdminUserController.LIST_SUBPATH,
                    listBody(null, List.copyOf(markers), null, null,
                            textOf(page, "rowSnapshotToken"), KeyAction.ENTER),
                    session);

            assertThat(textOf(body, "nextRoute"))
                    .as("the earlier marker decides the destination; the later one is not reached")
                    .isEqualTo(USER_UPDATE_ROUTE);
            assertThat(textOf(body, "userId"))
                    .as("and the identifier carried forward is the earlier row's, not the later row's")
                    .isEqualTo(DELIVERED_IDENTIFIERS_ASCENDING.get(1));
        }

        @Test
        @DisplayName("★ an unaccepted marker reports the fixed text and the page is still returned")
        void anUnacceptedMarkerReportsTheFixedTextAndKeepsThePage() throws Exception {
            // L210-L214 is the default arm of the dispatch: text only. Uniquely among these programs'
            // error arms it does NOT raise the error switch, so control falls through and the page is
            // still built - a response carrying this text together with ten rows is therefore correct.
            final JsonNode body = markRow(1, "X");

            assertThat(textOf(body, "message"))
                    .as("forty-three characters, asserted whole and never trimmed, reworded or "
                            + "re-punctuated")
                    .isEqualTo(INVALID_SELECTION_MESSAGE);
            assertThat(flagOf(body, "generalError"))
                    .as("this arm leaves the switch clear, which is why the page survives it")
                    .isFalse();
            assertThat(rowIdentifiersOf(body))
                    .as("the page is rebuilt beside the message rather than suppressed by it")
                    .containsExactlyElementsOf(DELIVERED_IDENTIFIERS_ASCENDING);
            assertThat(textOf(body, "nextRoute"))
                    .as("an unaccepted marker nominates nothing")
                    .isNull();
        }

        @Test
        @DisplayName("a marked row without the page snapshot is refused rather than resolved by re-reading")
        void aMarkedRowWithoutThePageSnapshotIsRefused() throws Exception {
            // The legacy read the marked row's identifier out of the echoed map. A REST client echoes a
            // server-minted snapshot instead, and resolving by re-reading would reintroduce the race the
            // snapshot closes: an insert or delete before the page's anchor moves a different identity
            // into the marked position, and a delete is not recoverable.
            final JsonNode body = turn(AdminUserController.LIST_SUBPATH,
                    listBody(null, selectionsMarking(1, MARKER_DELETE_UPPER), null, null, null,
                            KeyAction.ENTER),
                    sessionFor(OWNED_ADMIN));

            assertThat(textOf(body, "message")).isEqualTo(UNABLE_TO_LOOKUP_MESSAGE);
            assertThat(flagOf(body, "generalError"))
                    .as("the refusal takes the catch-all browse arm, which does raise the switch")
                    .isTrue();
            assertThat(textOf(body, "nextRoute"))
                    .as("nothing is nominated, so nothing can be acted on")
                    .isNull();
        }

        @Test
        @DisplayName("a marker is one character wide, and a wider one is refused before the scan runs")
        void aMarkerIsOneCharacterWide() throws Exception {
            final MvcResult result = submit(AdminUserController.LIST_SUBPATH,
                    listBody(null, selectionsMarking(1, MARKER_UPDATE_UPPER.repeat(SELECTOR_WIDTH + 1)),
                            null, null, null, KeyAction.ENTER),
                    sessionFor(OWNED_ADMIN));

            assertThat(result.getResponse().getStatus())
                    .as("each of the ten selection items is one character wide, so a wider one is "
                            + "malformed rather than merely unaccepted")
                    .isEqualTo(400);
        }

        /**
         * Fetches the first page, then marks one of its rows and submits the result.
         *
         * <p>Two turns rather than one, because a marker names a position on a page the operator is
         * looking at: the first turn is what produces that page and the snapshot it is resolved against.
         *
         * @param  oneBasedPosition the row to mark
         * @param  marker           the marker to type into it
         * @return the parsed body of the marking turn
         * @throws Exception if the boundary cannot be reached
         */
        private JsonNode markRow(final int oneBasedPosition, final String marker) throws Exception {
            final String session = sessionFor(OWNED_ADMIN);
            final JsonNode page = firstPage(session);
            return turn(AdminUserController.LIST_SUBPATH,
                    listBody(null, selectionsMarking(oneBasedPosition, marker), null, null,
                            textOf(page, "rowSnapshotToken"), KeyAction.ENTER),
                    session);
        }
    }

    @Nested
    @DisplayName("Add cascade order: given name, family name, identifier, credential, role code")
    class AddCascadeOrder {

        AddCascadeOrder() {
        }

        @Test
        @DisplayName("★ with the identifier AND the given name empty, the add screen reports the GIVEN "
                + "NAME - because it tests the identifier third")
        void theAddCascadeReportsTheGivenNameWhenTheIdentifierIsAlsoEmpty() throws Exception {
            // PROCESS-ENTER-KEY, COUSR01C.cbl L115: the EVALUATE TRUE at L117-L151 tests the given name
            // first (L118) and the identifier third (L130). This is the divergence from the update screen.
            final JsonNode body = addTurn(blank(USER_ID_WIDTH), blank(NAME_PART_WIDTH),
                    RESERVED_LAST_NAME, keyedCredential(), USER_ROLE_CODE);

            assertThat(textOf(body, "message")).isEqualTo(ADD_FIRST_NAME_EMPTY_MESSAGE);
            assertThat(textOf(body, "message"))
                    .as("the identifier is empty too, and is deliberately NOT what this screen reports")
                    .isNotEqualTo(ADD_USER_ID_EMPTY_MESSAGE);
            assertThat(textOf(body, "focusScreenFieldId")).isEqualTo(FIELD_FIRST_NAME);
            assertOneFieldErrorNaming(body, "firstName", FIELD_FIRST_NAME,
                    ADD_FIRST_NAME_EMPTY_MESSAGE);
        }

        @Test
        @DisplayName("the second arm is the family name, reached only once a given name was supplied")
        void theSecondArmIsTheFamilyName() throws Exception {
            final JsonNode body = addTurn(blank(USER_ID_WIDTH), RESERVED_FIRST_NAME,
                    blank(NAME_PART_WIDTH), keyedCredential(), USER_ROLE_CODE);

            assertThat(textOf(body, "message")).isEqualTo(ADD_LAST_NAME_EMPTY_MESSAGE);
            assertOneFieldErrorNaming(body, "lastName", FIELD_LAST_NAME, ADD_LAST_NAME_EMPTY_MESSAGE);
        }

        @Test
        @DisplayName("the third arm is the identifier, reached only once both names were supplied")
        void theThirdArmIsTheIdentifier() throws Exception {
            final JsonNode body = addTurn(blank(USER_ID_WIDTH), RESERVED_FIRST_NAME,
                    RESERVED_LAST_NAME, keyedCredential(), USER_ROLE_CODE);

            assertThat(textOf(body, "message")).isEqualTo(ADD_USER_ID_EMPTY_MESSAGE);
            assertThat(textOf(body, "focusScreenFieldId"))
                    .as("this screen's own identifier field, which is not the one the list and "
                            + "maintenance screens use")
                    .isEqualTo(FIELD_ADD_USER_ID);
            assertOneFieldErrorNaming(body, "userId", FIELD_ADD_USER_ID, ADD_USER_ID_EMPTY_MESSAGE);
        }

        @Test
        @DisplayName("the fourth arm is the credential, which this screen requires outright")
        void theFourthArmIsTheCredential() throws Exception {
            // A new record has no stored digest to carry forward, which is why the add screen requires the
            // credential where the update screen only requires it once supplied.
            final JsonNode body = addTurn(ADDED_IDENTITY, RESERVED_FIRST_NAME, RESERVED_LAST_NAME,
                    blank(CREDENTIAL_WIDTH), USER_ROLE_CODE);

            assertThat(textOf(body, "message")).isEqualTo(ADD_CREDENTIAL_EMPTY_MESSAGE);
            assertThat(textOf(body, "focusScreenFieldId")).isEqualTo(FIELD_CREDENTIAL_ITEM);
            assertOneFieldErrorNaming(body, "password", FIELD_CREDENTIAL_ITEM,
                    ADD_CREDENTIAL_EMPTY_MESSAGE);
            assertThat(AdminUserControllerIT.this.users.findById(ADDED_IDENTITY))
                    .as("a refused submission writes nothing")
                    .isEmpty();
        }

        @Test
        @DisplayName("the fifth arm is the role code, which is tested for emptiness and not for value")
        void theFifthArmIsTheRoleCode() throws Exception {
            final JsonNode body = addTurn(ADDED_IDENTITY, RESERVED_FIRST_NAME, RESERVED_LAST_NAME,
                    keyedCredential(), blank(USER_TYPE_WIDTH));

            assertThat(textOf(body, "message")).isEqualTo(ADD_USER_TYPE_EMPTY_MESSAGE);
            assertThat(textOf(body, "focusScreenFieldId")).isEqualTo(FIELD_USER_TYPE);
            assertOneFieldErrorNaming(body, "userType", FIELD_USER_TYPE, ADD_USER_TYPE_EMPTY_MESSAGE);
        }

        @Test
        @DisplayName("★ exactly one item is reported however many are empty")
        void exactlyOneItemIsReportedHoweverManyAreEmpty() throws Exception {
            // Five independent tests would report all five at once, which is a screen the legacy cannot
            // produce: EVALUATE TRUE stops at its first true condition.
            final JsonNode body = addTurn(blank(USER_ID_WIDTH), blank(NAME_PART_WIDTH),
                    blank(NAME_PART_WIDTH), blank(CREDENTIAL_WIDTH), blank(USER_TYPE_WIDTH));

            assertThat(textOf(body, "message")).isEqualTo(ADD_FIRST_NAME_EMPTY_MESSAGE);
            assertThat(body.get("fieldErrors").size())
                    .as("one finding, not five")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a complete submission stores the record and reports it by name")
        void aCompleteSubmissionStoresTheRecord() throws Exception {
            final JsonNode body = addTurn(ADDED_IDENTITY, RESERVED_FIRST_NAME, RESERVED_LAST_NAME,
                    keyedCredential(), USER_ROLE_CODE);

            assertThat(flagOf(body, "actionSucceeded")).isTrue();
            assertThat(textOf(body, "message"))
                    .as("the text is built by concatenating a fixed prefix, the identifier up to its "
                            + "first space, and a fixed suffix")
                    .isEqualTo(SUCCESS_PREFIX + ADDED_IDENTITY + ADD_SUCCESS_SUFFIX);
            final Optional<UserSecurity> stored =
                    AdminUserControllerIT.this.users.findById(ADDED_IDENTITY);
            assertThat(stored).isPresent();
            assertThat(stored.orElseThrow().getSecUsrFname()).isEqualTo(RESERVED_FIRST_NAME);
            assertThat(stored.orElseThrow().getSecUsrLname()).isEqualTo(RESERVED_LAST_NAME);
            assertThat(stored.orElseThrow().getSecUsrType()).isEqualTo(USER_ROLE_CODE);
        }

        @Test
        @DisplayName("a given name carrying an embedded space is accepted, because this screen applies no "
                + "character rule at all")
        void aGivenNameCarryingAnEmbeddedSpaceIsAccepted() throws Exception {
            // The estate's alphabetic edit elsewhere blanks every letter and then tests the remainder, so
            // embedded spaces pass it; this screen applies no edit whatever beyond emptiness. Rejecting
            // such a name would refuse input the legacy stores.
            final JsonNode body = addTurn(ADDED_IDENTITY, NAME_WITH_EMBEDDED_SPACE, RESERVED_LAST_NAME,
                    keyedCredential(), USER_ROLE_CODE);

            assertThat(flagOf(body, "actionSucceeded")).isTrue();
            assertThat(AdminUserControllerIT.this.users.findById(ADDED_IDENTITY).orElseThrow()
                    .getSecUsrFname())
                    .as("stored exactly as submitted, embedded space included")
                    .isEqualTo(NAME_WITH_EMBEDDED_SPACE);
        }

        @Test
        @DisplayName("a duplicate identifier is a screen message rather than a conflict status")
        void aDuplicateIdentifierIsAScreenMessage() throws Exception {
            // L260-L266 folds the two duplicate store responses into one arm, emits the already-exists
            // text and places the cursor on the identifier field. Reporting it as a conflict status would
            // both modernise an observable contract and disclose which rejection occurred to anything
            // reading only the status line.
            final JsonNode body = addTurn(UPDATE_SUBJECT, RESERVED_FIRST_NAME, RESERVED_LAST_NAME,
                    keyedCredential(), USER_ROLE_CODE);

            assertThat(textOf(body, "message")).isEqualTo(ADD_DUPLICATE_MESSAGE);
            assertThat(flagOf(body, "generalError")).isTrue();
            assertThat(flagOf(body, "actionSucceeded")).isFalse();
            assertThat(textOf(body, "focusScreenFieldId")).isEqualTo(FIELD_ADD_USER_ID);
            final JsonNode fieldErrors = body.get("fieldErrors");
            assertThat(fieldErrors.size()).isEqualTo(1);
            assertThat(textOf(fieldErrors.get(0), "state"))
                    .as("a value that is present and unusable is INVALID, where an absent one is MISSING")
                    .isEqualTo("INVALID");
        }

        @Test
        @DisplayName("a first entry presents the blank screen without validating anything")
        void aFirstEntryPresentsTheBlankScreenWithoutValidating() throws Exception {
            // L83-L87: the first entry blanks the map and places the cursor on the given-name field. The
            // cascade belongs to a later turn, which is why every submitting turn here echoes the
            // re-entered context.
            final Map<String, Object> body = recordBody(null, null, null, null, null, KeyAction.ENTER);
            final Map<String, Object> firstEntry = new LinkedHashMap<>();
            firstEntry.put("programContext", "ENTER");
            body.put("navigationContext", firstEntry);

            final JsonNode presented = turn(AdminUserController.ADD_SUBPATH, body,
                    sessionFor(OWNED_ADMIN));

            assertThat(textOf(presented, "message"))
                    .as("nothing was submitted yet, so nothing is reported")
                    .isNull();
            assertThat(textOf(presented, "focusScreenFieldId")).isEqualTo(FIELD_FIRST_NAME);
            assertThat(presented.get("fieldErrors").size()).isZero();
        }

        /**
         * Submits one add turn under an administrative session.
         *
         * @param  userId    the identifier to submit, or {@code null} to omit it
         * @param  firstName the given name to submit, or {@code null} to omit it
         * @param  lastName  the family name to submit, or {@code null} to omit it
         * @param  password  the credential to submit, or {@code null} to omit it
         * @param  userType  the role code to submit, or {@code null} to omit it
         * @return the parsed body
         * @throws Exception if the boundary cannot be reached
         */
        private JsonNode addTurn(final String userId, final String firstName, final String lastName,
                final String password, final String userType) throws Exception {
            return turn(AdminUserController.ADD_SUBPATH,
                    recordBody(userId, firstName, lastName, password, userType, KeyAction.ENTER),
                    sessionFor(OWNED_ADMIN));
        }
    }

    @Nested
    @DisplayName("Update cascade order - divergent: identifier, given name, family name, credential, "
            + "role code")
    class UpdateCascadeOrderIsDivergent {

        UpdateCascadeOrderIsDivergent() {
        }

        @Test
        @DisplayName("★ with the identifier AND the given name empty, the update screen reports the "
                + "IDENTIFIER - because it tests the identifier first")
        void theUpdateCascadeReportsTheIdentifierWhenTheGivenNameIsAlsoEmpty() throws Exception {
            // UPDATE-USER-INFO, COUSR02C.cbl L177: the EVALUATE TRUE at L179-L213 tests the identifier
            // first (L180) and the given name second (L186) - the opposite way round from the add screen.
            final JsonNode body = saveTurn(blank(USER_ID_WIDTH), blank(NAME_PART_WIDTH),
                    RESERVED_LAST_NAME, keyedCredential(), USER_ROLE_CODE);

            assertThat(textOf(body, "message")).isEqualTo(UPDATE_USER_ID_EMPTY_MESSAGE);
            assertThat(textOf(body, "message"))
                    .as("the given name is empty too, and is deliberately NOT what this screen reports")
                    .isNotEqualTo(UPDATE_FIRST_NAME_EMPTY_MESSAGE);
            assertThat(textOf(body, "focusScreenFieldId")).isEqualTo(FIELD_LIST_USER_ID);
            assertOneFieldErrorNaming(body, "userId", FIELD_LIST_USER_ID, UPDATE_USER_ID_EMPTY_MESSAGE);
        }

        @Test
        @DisplayName("★ THE DIVERGENCE ITSELF: one and the same submission is answered differently by the "
                + "two screens, and the two orders are never unified")
        void theTwoCascadeOrdersDivergeOnOneAndTheSameSubmission() throws Exception {
            // The submission is identical in every component; only the route differs. The add screen
            // reaches the given name first and the update screen reaches the identifier first, so the two
            // answers must differ - and if a later refactor unified the orders, this is where it fails.
            final String session = sessionFor(OWNED_ADMIN);
            final String emptyIdentifier = blank(USER_ID_WIDTH);
            final String emptyGivenName = blank(NAME_PART_WIDTH);

            final JsonNode added = turn(AdminUserController.ADD_SUBPATH,
                    recordBody(emptyIdentifier, emptyGivenName, RESERVED_LAST_NAME, keyedCredential(),
                            USER_ROLE_CODE, KeyAction.ENTER),
                    session);
            final JsonNode updated = turn(AdminUserController.UPDATE_SUBPATH,
                    recordBody(emptyIdentifier, emptyGivenName, RESERVED_LAST_NAME, keyedCredential(),
                            USER_ROLE_CODE, KeyAction.PFK05),
                    session);

            assertThat(textOf(added, "message"))
                    .as("the add screen tests the given name first")
                    .isEqualTo(ADD_FIRST_NAME_EMPTY_MESSAGE);
            assertThat(textOf(updated, "message"))
                    .as("the update screen tests the identifier first")
                    .isEqualTo(UPDATE_USER_ID_EMPTY_MESSAGE);
            assertThat(textOf(added, "message"))
                    .as("the two orders are the contract, so the two answers are not interchangeable")
                    .isNotEqualTo(textOf(updated, "message"));
            assertThat(textOf(added, "focusScreenFieldId"))
                    .as("and the cursor lands on a different item on each screen")
                    .isNotEqualTo(textOf(updated, "focusScreenFieldId"));
        }

        @Test
        @DisplayName("the second arm is the given name, where the add screen's second arm is the family "
                + "name")
        void theSecondArmIsTheGivenName() throws Exception {
            final JsonNode body = saveTurn(UPDATE_SUBJECT, blank(NAME_PART_WIDTH), RESERVED_LAST_NAME,
                    keyedCredential(), USER_ROLE_CODE);

            assertThat(textOf(body, "message")).isEqualTo(UPDATE_FIRST_NAME_EMPTY_MESSAGE);
            assertOneFieldErrorNaming(body, "firstName", FIELD_FIRST_NAME,
                    UPDATE_FIRST_NAME_EMPTY_MESSAGE);
        }

        @Test
        @DisplayName("the third arm is the family name")
        void theThirdArmIsTheFamilyName() throws Exception {
            final JsonNode body = saveTurn(UPDATE_SUBJECT, RESERVED_FIRST_NAME, blank(NAME_PART_WIDTH),
                    keyedCredential(), USER_ROLE_CODE);

            assertThat(textOf(body, "message")).isEqualTo(UPDATE_LAST_NAME_EMPTY_MESSAGE);
            assertOneFieldErrorNaming(body, "lastName", FIELD_LAST_NAME, UPDATE_LAST_NAME_EMPTY_MESSAGE);
        }

        @Test
        @DisplayName("the fifth arm is the role code")
        void theFifthArmIsTheRoleCode() throws Exception {
            final JsonNode body = saveTurn(UPDATE_SUBJECT, RESERVED_FIRST_NAME, RESERVED_LAST_NAME,
                    keyedCredential(), blank(USER_TYPE_WIDTH));

            assertThat(textOf(body, "message")).isEqualTo(UPDATE_USER_TYPE_EMPTY_MESSAGE);
            assertOneFieldErrorNaming(body, "userType", FIELD_USER_TYPE, UPDATE_USER_TYPE_EMPTY_MESSAGE);
        }

        @Test
        @DisplayName("a credential that is present but blank is the legacy empty-item condition and is "
                + "reported as missing")
        void aCredentialPresentButBlankIsReportedAsMissing() throws Exception {
            final JsonNode body = saveTurn(UPDATE_SUBJECT, RESERVED_FIRST_NAME, RESERVED_LAST_NAME,
                    blank(CREDENTIAL_WIDTH), USER_ROLE_CODE);

            assertThat(textOf(body, "message")).isEqualTo(UPDATE_CREDENTIAL_EMPTY_MESSAGE);
            assertOneFieldErrorNaming(body, "password", FIELD_CREDENTIAL_ITEM,
                    UPDATE_CREDENTIAL_EMPTY_MESSAGE);
        }

        @Test
        @DisplayName("an omitted credential leaves it unchanged rather than empty, which is a third state "
                + "the fixed-width map could not express")
        void anOmittedCredentialLeavesItUnchanged() throws Exception {
            final String storedBefore = AdminUserControllerIT.this.users.findById(UPDATE_SUBJECT)
                    .orElseThrow().credentialDigest();

            final JsonNode body = saveTurn(UPDATE_SUBJECT, AMENDED_FIRST_NAME, RESERVED_LAST_NAME,
                    null, USER_ROLE_CODE);

            assertThat(flagOf(body, "actionSucceeded"))
                    .as("the amendment is saved without the operator being made to retype, or to know, "
                            + "the credential")
                    .isTrue();
            assertThat(AdminUserControllerIT.this.users.findById(UPDATE_SUBJECT).orElseThrow()
                    .credentialDigest())
                    .as("the stored digest is carried forward untouched; re-hashing a digest would lock "
                            + "the identity out")
                    .isEqualTo(storedBefore);
        }

        @Test
        @DisplayName("a retrieval loads the record and asks for the saving key, without the credential")
        void aRetrievalLoadsTheRecordAndAsksForTheSavingKey() throws Exception {
            final JsonNode body = turn(AdminUserController.UPDATE_SUBPATH,
                    recordBody(UPDATE_SUBJECT, null, null, null, null, KeyAction.ENTER),
                    sessionFor(OWNED_ADMIN));

            assertThat(textOf(body, "message")).isEqualTo(UPDATE_PRESS_TO_SAVE_MESSAGE);
            assertThat(textOf(body, "firstName")).isEqualTo(RESERVED_FIRST_NAME);
            assertThat(textOf(body, "lastName")).isEqualTo(RESERVED_LAST_NAME);
            assertThat(textOf(body, "userType")).isEqualTo(USER_ROLE_CODE);
            assertThat(propertyNamesOf(body))
                    .as("the legacy moved the stored credential onto the screen because it was eight "
                            + "cleartext characters; the column holds a one-way digest now, so the item "
                            + "is simply not there")
                    .doesNotContain("password");
        }

        @Test
        @DisplayName("a submission that changes nothing is reported as such rather than saved")
        void aSubmissionThatChangesNothingIsReportedAsSuch() throws Exception {
            final JsonNode body = saveTurn(UPDATE_SUBJECT, RESERVED_FIRST_NAME, RESERVED_LAST_NAME,
                    null, USER_ROLE_CODE);

            assertThat(textOf(body, "message")).isEqualTo(UPDATE_NO_CHANGE_MESSAGE);
            assertThat(flagOf(body, "actionSucceeded")).isFalse();
        }

        @Test
        @DisplayName("an absent record is reported as a screen message rather than a not-found status")
        void anAbsentRecordIsReportedAsAScreenMessage() throws Exception {
            final JsonNode body = saveTurn(ABSENT_IDENTITY, RESERVED_FIRST_NAME, RESERVED_LAST_NAME,
                    null, USER_ROLE_CODE);

            assertThat(textOf(body, "message")).isEqualTo(USER_ID_NOT_FOUND_MESSAGE);
            assertThat(flagOf(body, "generalError")).isTrue();
        }

        @Test
        @DisplayName("a saved amendment is stored and reported by name")
        void aSavedAmendmentIsStoredAndReportedByName() throws Exception {
            final JsonNode body = saveTurn(UPDATE_SUBJECT, AMENDED_FIRST_NAME, RESERVED_LAST_NAME,
                    null, USER_ROLE_CODE);

            assertThat(textOf(body, "message"))
                    .isEqualTo(SUCCESS_PREFIX + UPDATE_SUBJECT + UPDATE_SUCCESS_SUFFIX);
            assertThat(AdminUserControllerIT.this.users.findById(UPDATE_SUBJECT).orElseThrow()
                    .getSecUsrFname())
                    .isEqualTo(AMENDED_FIRST_NAME);
        }

        /**
         * Submits one saving turn of the update screen under an administrative session.
         *
         * @param  userId    the identifier to submit, or {@code null} to omit it
         * @param  firstName the given name to submit, or {@code null} to omit it
         * @param  lastName  the family name to submit, or {@code null} to omit it
         * @param  password  the credential to submit, or {@code null} to omit it
         * @param  userType  the role code to submit, or {@code null} to omit it
         * @return the parsed body
         * @throws Exception if the boundary cannot be reached
         */
        private JsonNode saveTurn(final String userId, final String firstName, final String lastName,
                final String password, final String userType) throws Exception {
            return turn(AdminUserController.UPDATE_SUBPATH,
                    recordBody(userId, firstName, lastName, password, userType, KeyAction.PFK05),
                    sessionFor(OWNED_ADMIN));
        }
    }

    // ===============================================================================================
    // 8. THE CREDENTIAL IS HASHED ON BOTH WRITE PATHS AND IS NEVER DISCLOSED
    // ===============================================================================================

    @Nested
    @DisplayName("Password hashing and non-disclosure: a digest on create and on update, and nothing "
            + "readable anywhere")
    class PasswordHashingAndNonDisclosure {

        PasswordHashingAndNonDisclosure() {
        }

        @Test
        @DisplayName("★ a created record stores a digest and not the submitted value")
        void aCreatedRecordStoresADigestAndNotTheSubmittedValue() throws Exception {
            // COUSR01C.cbl L157 moved eight cleartext characters into the record. The target hashes
            // instead - a documented parity exception - so what a write stores is a one-way digest and the
            // submitted value survives nowhere.
            final JsonNode body = turn(AdminUserController.ADD_SUBPATH,
                    recordBody(SECOND_ADDED_IDENTITY, RESERVED_FIRST_NAME, RESERVED_LAST_NAME,
                            keyedCredential(), USER_ROLE_CODE, KeyAction.ENTER),
                    sessionFor(OWNED_ADMIN));

            assertThat(flagOf(body, "actionSucceeded")).isTrue();
            final String stored = AdminUserControllerIT.this.users.findById(SECOND_ADDED_IDENTITY)
                    .orElseThrow().credentialDigest();
            assertThat(TestDataFactory.hasStoredDigestShape(stored))
                    .as("sixty characters, a recognised version marker and the module's own cost factor")
                    .isTrue();
            // Asserted on the length rather than over the digest: hasSize prints its subject, and the
            // subject is stored credential material.
            assertThat(stored.length())
                    .as("sixty characters, the width the one intentionally widened column carries")
                    .isEqualTo(TestDataFactory.BCRYPT_DIGEST_LENGTH);
            assertThat(revealsTheKeyedCredential(stored))
                    .as("what was stored is not the submitted value, is not its folded form, and does not "
                            + "contain either")
                    .isFalse();
            assertThat(digestAcceptsTheKeyedCredential(stored))
                    .as("and it is nevertheless a digest OF that value, so the identity can still sign on")
                    .isTrue();
            assertThat(TestDataFactory.digestRefusesOtherValues(
                    AdminUserControllerIT.this.passwordEncoder, stored))
                    .as("a digest that accepted everything would satisfy an acceptance check and be "
                            + "worthless")
                    .isTrue();
        }

        @Test
        @DisplayName("★ a changed credential is re-hashed on update rather than stored, and carries its "
                + "own salt")
        void aChangedCredentialIsRehashedOnUpdate() throws Exception {
            // COUSR02C.cbl L227-L230 compares the credential item against the stored one. The comparison
            // here is a verification rather than an equality test, because each digest carries its own
            // salt and comparing two digests would report every credential as changed.
            final String before = AdminUserControllerIT.this.users.findById(UPDATE_SUBJECT)
                    .orElseThrow().credentialDigest();

            final JsonNode body = turn(AdminUserController.UPDATE_SUBPATH,
                    recordBody(UPDATE_SUBJECT, RESERVED_FIRST_NAME, RESERVED_LAST_NAME,
                            A_VALUE_NO_STORED_DIGEST_WAS_DERIVED_FROM, USER_ROLE_CODE, KeyAction.PFK05),
                    sessionFor(OWNED_ADMIN));

            assertThat(flagOf(body, "actionSucceeded")).isTrue();
            final String after = AdminUserControllerIT.this.users.findById(UPDATE_SUBJECT)
                    .orElseThrow().credentialDigest();
            assertThat(after)
                    .as("a genuinely new credential replaces the stored digest")
                    .isNotEqualTo(before);
            assertThat(TestDataFactory.hasStoredDigestShape(after)).isTrue();
            assertThat(after)
                    .as("and what replaced it is a digest rather than the submitted value in any form")
                    .isNotEqualTo(A_VALUE_NO_STORED_DIGEST_WAS_DERIVED_FROM)
                    .doesNotContain(A_VALUE_NO_STORED_DIGEST_WAS_DERIVED_FROM);
            assertThat(AdminUserControllerIT.this.passwordEncoder
                    .matches(A_VALUE_NO_STORED_DIGEST_WAS_DERIVED_FROM, after))
                    .as("it is nevertheless a digest of what was submitted")
                    .isTrue();
        }

        @Test
        @DisplayName("★ no response from any of the four routes carries a credential, a digest, a salt or "
                + "a cost factor")
        void noResponseCarriesACredentialInAnyForm() throws Exception {
            final String session = sessionFor(OWNED_ADMIN);
            final List<JsonNode> everyScreen = List.of(
                    firstPage(session),
                    turn(AdminUserController.ADD_SUBPATH,
                            recordBody(SECOND_ADDED_IDENTITY, RESERVED_FIRST_NAME, RESERVED_LAST_NAME,
                                    keyedCredential(), USER_ROLE_CODE, KeyAction.ENTER), session),
                    turn(AdminUserController.UPDATE_SUBPATH,
                            recordBody(UPDATE_SUBJECT, null, null, null, null, KeyAction.ENTER), session),
                    turn(AdminUserController.DELETE_SUBPATH,
                            recordBody(DELETE_SUBJECT, null, null, null, null, KeyAction.ENTER), session));

            for (final JsonNode screen : everyScreen) {
                assertThat(propertyNamesOf(screen))
                        .as("the credential is accepted inbound only: no response in this package has a "
                                + "component for it, at any depth")
                        .doesNotContain("password")
                        .doesNotContain("credential")
                        .doesNotContain("credentialDigest")
                        .doesNotContain("digest")
                        .doesNotContain("salt")
                        .doesNotContain("workFactor")
                        .doesNotContain("hash");
                assertThat(screen.toString())
                        .as("and no digest text leaks through a value either, whichever version marker it "
                                + "would carry")
                        .doesNotContain("$2a$")
                        .doesNotContain("$2b$")
                        .doesNotContain("$2y$");
                assertThat(revealsTheKeyedCredential(screen.toString()))
                        .as("nor does the submitted value come back")
                        .isFalse();
            }
        }

        @Test
        @DisplayName("★ the request contract never renders the credential in a diagnostic")
        void theRequestContractNeverRendersTheCredentialInADiagnostic() {
            // A diagnostic sink and a serializer are two separate routes out, closed separately: this one
            // is closed by the rendering override, the other by the write-only component. Neither
            // substitutes for the other.
            final UserRequest submitted = new UserRequest(UPDATE_SUBJECT, null, RESERVED_FIRST_NAME,
                    RESERVED_LAST_NAME, A_VALUE_NO_STORED_DIGEST_WAS_DERIVED_FROM, USER_ROLE_CODE,
                    List.of(), null, null, null, KeyAction.PFK05, null);

            assertThat(submitted.toString())
                    .as("the credential is withheld from every rendering, and so is every other personal "
                            + "component: two names, three identifiers and a role code on one line are a "
                            + "personal-data record whether or not a credential sits beside them")
                    .doesNotContain(A_VALUE_NO_STORED_DIGEST_WAS_DERIVED_FROM)
                    .doesNotContain(RESERVED_FIRST_NAME)
                    .doesNotContain(RESERVED_LAST_NAME)
                    .doesNotContain(UPDATE_SUBJECT);
            assertThat(submitted.password())
                    .as("the accessor still carries it, because the transaction has to be able to read it")
                    .isEqualTo(A_VALUE_NO_STORED_DIGEST_WAS_DERIVED_FROM);
        }

        @Test
        @DisplayName("the role code is one raw character with two authoritative values and no third type")
        void theRoleCodeIsOneRawCharacterWithNoThirdType() {
            // COCOM01Y.cpy L27-L28 declare the two codes. The programs test only that the item is
            // non-blank and store whatever arrived, so an unrecognised character is representable and must
            // not blow up a lookup or a construction.
            assertThat(UserType.values())
                    .as("two types, and no role hierarchy or permission model beyond them")
                    .hasSize(2);
            assertThat(UserType.ADMIN.getCode()).isEqualTo(ADMIN_ROLE_CODE).hasSize(USER_TYPE_WIDTH);
            assertThat(UserType.USER.getCode()).isEqualTo(USER_ROLE_CODE).hasSize(USER_TYPE_WIDTH);
            assertThat(UserType.fromCode("X"))
                    .as("an undeclared code resolves to no type rather than raising")
                    .isEmpty();
            assertThat(UserType.fromCode(ADMIN_ROLE_CODE.toLowerCase(Locale.ROOT)))
                    .as("the vocabulary folds nothing, so the administrator letter in lower case is not "
                            + "the administrative code")
                    .isEmpty();
            final UserRequest undeclared = new UserRequest(UPDATE_SUBJECT, null, RESERVED_FIRST_NAME,
                    RESERVED_LAST_NAME, null, "X", List.of(), null, null, null, KeyAction.PFK05, null);
            assertThat(undeclared.userType())
                    .as("the contract carries the raw character, because a value restriction here would "
                            + "reject a character the legacy record already holds")
                    .isEqualTo("X");
        }
    }

    @Nested
    @DisplayName("Delete - no password field and the preserved Update message")
    class DeleteHasNoCredentialAndKeepsTheUpdateMessage {

        DeleteHasNoCredentialAndKeepsTheUpdateMessage() {
        }

        @Test
        @DisplayName("★ the delete map declares no credential item, so a credential is refused rather "
                + "than accepted and ignored")
        void theDeleteMapDeclaresNoCredentialItem() throws Exception {
            final MvcResult result = submit(AdminUserController.DELETE_SUBPATH,
                    recordBody(DELETE_SUBJECT, null, null, keyedCredential(), null, KeyAction.ENTER),
                    sessionFor(OWNED_ADMIN));

            assertThat(result.getResponse().getStatus())
                    .as("accepting one would transport a credential the legacy screen had no field for")
                    .isEqualTo(400);
            assertThat(rawBodyOf(result))
                    .as("and the refusal discloses no internal detail")
                    .doesNotContain("Exception")
                    .doesNotContain("org.springframework");
        }

        @Test
        @DisplayName("★ the removal path's failure text names the UPDATE operation, and is preserved "
                + "rather than corrected")
        void theRemovalFailureTextNamesTheUpdateOperation() {
            // app/cbl/COUSR03C.cbl line 332, in the default arm of DELETE-USER-SEC-FILE at L305, moves the
            // update screen's failure text on a screen that deletes. It is byte-identical to
            // app/cbl/COUSR02C.cbl line 386.
            //
            // WHY THIS IS ASSERTED AGAINST THE PUBLISHED CONSTANT RATHER THAN DRIVEN OVER THE BOUNDARY:
            // the arm is reached only when the store refuses the removal, and the honest way to reach it
            // from here would be to fabricate a store failure - which an integration specification running
            // against a real server must not do. The text is a published component of the response
            // contract, so it is asserted there, against a literal transcribed from the member rather than
            // borrowed from the subject.
            assertThat(UserResponse.MSG_DELETE_UNABLE_TO_UPDATE_USER)
                    .as("asserted verbatim, at its full twenty-four characters, and never trimmed")
                    .isEqualTo(DELETE_FAILURE_MESSAGE)
                    .hasSize(DELETE_FAILURE_MESSAGE.length());
            assertThat(UserResponse.MSG_DELETE_UNABLE_TO_UPDATE_USER)
                    .as("★ NOT corrected to name the delete operation: correcting it would change an "
                            + "externally observable message contract")
                    .doesNotContain("Delet")
                    .doesNotContain("delet");
            assertThat(UserResponse.MSG_DELETE_UNABLE_TO_UPDATE_USER)
                    .as("the two screens' failure texts are one and the same text, which is the defect "
                            + "itself rather than a coincidence")
                    .isEqualTo(UserResponse.MSG_UPDATE_UNABLE_TO_UPDATE_USER);
            assertThat(UserResponse.MSG_DELETE_SUCCESS_SUFFIX)
                    .as("the success arm of the same screen does name the operation, so the failure arm's "
                            + "wording is not a convention of the screen")
                    .isEqualTo(DELETE_SUCCESS_SUFFIX);
        }

        @Test
        @DisplayName("a retrieval shows the record with the prompt to confirm, and removes nothing")
        void aRetrievalShowsTheRecordAndRemovesNothing() throws Exception {
            // PROCESS-ENTER-KEY, COUSR03C.cbl L142: L156-L162 blanks three displayed items - three, not
            // four, because this screen has no credential item - and L164-L169 moves the record on.
            final JsonNode body = turn(AdminUserController.DELETE_SUBPATH,
                    recordBody(DELETE_SUBJECT, null, null, null, null, KeyAction.ENTER),
                    sessionFor(OWNED_ADMIN));

            assertThat(textOf(body, "message")).isEqualTo(DELETE_PRESS_TO_DELETE_MESSAGE);
            assertThat(textOf(body, "firstName")).isEqualTo(RESERVED_FIRST_NAME);
            assertThat(textOf(body, "lastName")).isEqualTo(RESERVED_LAST_NAME);
            assertThat(textOf(body, "userType")).isEqualTo(USER_ROLE_CODE);
            assertThat(flagOf(body, "actionSucceeded")).isFalse();
            assertThat(AdminUserControllerIT.this.users.findById(DELETE_SUBJECT))
                    .as("nothing is removed implicitly: a retrieval is a retrieval")
                    .isPresent();
            assertThat(propertyNamesOf(body))
                    .as("and the answer has no credential component, because the map has no credential "
                            + "item")
                    .doesNotContain("password");
        }

        @Test
        @DisplayName("★ the confirming key removes the row, and a later read finds nothing")
        void theConfirmingKeyRemovesTheRowAndALaterReadFindsNothing() throws Exception {
            // The subject is a disposable identity this specification wrote, never one of the two session
            // identities and never a delivered one: removing a record ends that operator's sessions,
            // because the filter chain re-reads the sign-on record on every request.
            final String session = sessionFor(OWNED_ADMIN);

            final JsonNode removed = turn(AdminUserController.DELETE_SUBPATH,
                    recordBody(DELETE_SUBJECT, null, null, null, null, KeyAction.PFK05), session);

            assertThat(flagOf(removed, "actionSucceeded")).isTrue();
            assertThat(textOf(removed, "message"))
                    .isEqualTo(SUCCESS_PREFIX + DELETE_SUBJECT + DELETE_SUCCESS_SUFFIX);
            assertThat(AdminUserControllerIT.this.users.findById(DELETE_SUBJECT))
                    .as("the row is gone from the store")
                    .isEmpty();

            final JsonNode readAgain = turn(AdminUserController.DELETE_SUBPATH,
                    recordBody(DELETE_SUBJECT, null, null, null, null, KeyAction.ENTER), session);

            assertThat(textOf(readAgain, "message"))
                    .as("and a subsequent read reports the not-found arm rather than the record")
                    .isEqualTo(USER_ID_NOT_FOUND_MESSAGE);
            assertThat(flagOf(readAgain, "generalError")).isTrue();
        }

        @Test
        @DisplayName("neither the exit key nor the cancel key removes the row")
        void neitherTheExitNorTheCancelKeyRemovesTheRow() throws Exception {
            // L112-L118 and L120-L124: no delete on the way out, and none on a cancel. Only the confirming
            // key deletes.
            final String session = sessionFor(OWNED_ADMIN);

            for (final KeyAction key : List.of(KeyAction.PFK03, KeyAction.PFK04, KeyAction.PFK12)) {
                turn(AdminUserController.DELETE_SUBPATH,
                        recordBody(DELETE_SUBJECT, null, null, null, null, key), session);

                assertThat(AdminUserControllerIT.this.users.findById(DELETE_SUBJECT))
                        .as("%s must not remove anything", key)
                        .isPresent();
            }
        }

        @Test
        @DisplayName("the identifier is the only item this screen reads, and an empty one is reported")
        void theIdentifierIsTheOnlyItemThisScreenReads() throws Exception {
            final JsonNode body = turn(AdminUserController.DELETE_SUBPATH,
                    recordBody(blank(USER_ID_WIDTH), null, null, null, null, KeyAction.PFK05),
                    sessionFor(OWNED_ADMIN));

            assertThat(textOf(body, "message")).isEqualTo(DELETE_USER_ID_EMPTY_MESSAGE);
            assertOneFieldErrorNaming(body, "userId", FIELD_LIST_USER_ID, DELETE_USER_ID_EMPTY_MESSAGE);
            assertThat(AdminUserControllerIT.this.users.findById(DELETE_SUBJECT)).isPresent();
        }

        @Test
        @DisplayName("a name or a role code submitted to this screen is refused, because it displays them "
                + "rather than reading them")
        void aNameSubmittedToThisScreenIsRefused() throws Exception {
            // COUSR03C never reads the three displayed items: L157-L159 clear them, L165-L167 populate
            // them from the record it fetched and L353-L355 clear them again. They are values the server
            // produces, not values a client supplies.
            final MvcResult result = submit(AdminUserController.DELETE_SUBPATH,
                    recordBody(DELETE_SUBJECT, RESERVED_FIRST_NAME, null, null, null, KeyAction.ENTER),
                    sessionFor(OWNED_ADMIN));

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("Seeded ten-user baseline: five administrative identities and five ordinary ones")
    class SeededTenUserBaseline {

        SeededTenUserBaseline() {
        }

        @Test
        @DisplayName("★ the credential seed loads exactly ten identities, five of each role")
        void theCredentialSeedLoadsExactlyTenIdentities() {
            // The reference-data seed leaves this table empty and the credential seed owns it, so the ten
            // identities below are the whole delivered population. Rows this specification wrote are
            // excluded by their reserved prefix rather than by count, so the assertion stays true however
            // many this class writes.
            final Page<UserSecurityRepository.AdminEntry> everyRow = AdminUserControllerIT.this.users
                    .findAllProjectedBy(PageRequest.of(0, 100));
            final List<String> delivered = new ArrayList<>();
            int administrators = 0;
            int ordinary = 0;
            for (final UserSecurityRepository.AdminEntry row : everyRow) {
                if (row.getSecUsrId().startsWith(RESERVED_PREFIX)) {
                    continue;
                }
                delivered.add(row.getSecUsrId());
                if (ADMIN_ROLE_CODE.equals(row.getSecUsrType())) {
                    administrators++;
                } else if (USER_ROLE_CODE.equals(row.getSecUsrType())) {
                    ordinary++;
                }
            }

            assertThat(delivered)
                    .as("ten delivered identities, in ascending key order")
                    .containsExactlyElementsOf(DELIVERED_IDENTIFIERS_ASCENDING)
                    .hasSize(DELIVERED_IDENTITY_COUNT);
            assertThat(administrators).isEqualTo(DELIVERED_ADMINISTRATOR_COUNT);
            assertThat(ordinary).isEqualTo(DELIVERED_ORDINARY_COUNT);
        }

        @Test
        @DisplayName("every delivered credential is stored as a digest with its own salt, and refuses a "
                + "value it was not derived from")
        void everyDeliveredCredentialIsStoredAsADistinctDigest() {
            // The legacy record held eight cleartext characters. The one intentional width change in the
            // whole migration is this column, which is wide enough for a digest instead - and ten records
            // of one shared value produce ten different digests precisely because each carries its own
            // salt, which is what makes replacing any one of them observable.
            final List<String> digests = new ArrayList<>(DELIVERED_IDENTITY_COUNT);
            for (final String identifier : DELIVERED_IDENTIFIERS_ASCENDING) {
                final String digest = AdminUserControllerIT.this.users.findById(identifier)
                        .orElseThrow().credentialDigest();
                assertThat(TestDataFactory.hasStoredDigestShape(digest))
                        .as("%s carries a digest at the module's own cost factor", identifier)
                        .isTrue();
                assertThat(digest.length())
                        .as("%s carries a digest of the stored width", identifier)
                        .isEqualTo(TestDataFactory.BCRYPT_DIGEST_LENGTH);
                assertThat(TestDataFactory.digestRefusesOtherValues(
                        AdminUserControllerIT.this.passwordEncoder, digest))
                        .as("%s refuses a value its digest was not derived from", identifier)
                        .isTrue();
                digests.add(digest);
            }

            // Counted rather than compared. doesNotHaveDuplicates prints every member it holds, so this
            // one assertion would have put all ten stored digests into the build log.
            assertThat(SensitiveValues.distinctCount(digests))
                    .as("ten distinct digests, because ten distinct salts")
                    .isEqualTo(TestDataFactory.SEEDED_DIGEST_COUNT);
            assertThat(digests.size())
                    .as("and one digest per delivered identity")
                    .isEqualTo(TestDataFactory.SEEDED_DIGEST_COUNT);
        }

        @Test
        @DisplayName("a delivered identity's role code is one raw character, and no check constraint is "
                + "assumed to police it")
        void aDeliveredRoleCodeIsOneRawCharacter() {
            for (final String identifier : DELIVERED_IDENTIFIERS_ASCENDING) {
                final String roleCode = AdminUserControllerIT.this.users.findById(identifier)
                        .orElseThrow().getSecUsrType();

                assertThat(roleCode)
                        .as("%s stores one character, and the column polices its width rather than its "
                                + "vocabulary", identifier)
                        .hasSize(USER_TYPE_WIDTH);
            }
        }

        @Test
        @DisplayName("a delivered identifier occupies the full eight characters the record declares")
        void aDeliveredIdentifierOccupiesEightCharacters() {
            for (final String identifier : DELIVERED_IDENTIFIERS_ASCENDING) {
                assertThat(AdminUserControllerIT.this.users.findById(identifier).orElseThrow()
                        .getSecUsrId())
                        .as("the identifier is text at a fixed width, so its shape survives exactly")
                        .isEqualTo(identifier)
                        .hasSize(USER_ID_WIDTH);
            }
        }
    }

    @Nested
    @DisplayName("Leakage negatives: the published row is the map's five items and never the program's "
            + "internal staging shape")
    class LeakageNegatives {

        LeakageNegatives() {
        }

        @Test
        @DisplayName("★ a published row carries the map's five items and nothing else")
        void aPublishedRowCarriesTheMapsFiveItems() throws Exception {
            // The row contract comes from the SYMBOLIC MAP: a one-character selector, an eight-character
            // identifier, two twenty-character name parts and a one-character role code.
            final JsonNode rows = firstPage(sessionFor(OWNED_ADMIN)).get("rows");

            assertThat(rows.size())
                    .as("the delivered population fills the screen, so there are rows to inspect")
                    .isPositive();
            for (final JsonNode row : rows) {
                assertThat(propertyNamesOf(row))
                        .as("the four items the server produces, named as the map names them, and nothing "
                                + "besides")
                        .containsExactlyInAnyOrder("userId", "firstName", "lastName", "userType");
                assertThat(textOf(row, "selector"))
                        .as("the selection item is the operator's own keystroke rather than a value the "
                                + "server writes, so a page the server composed carries none and the "
                                + "shared serialization omits it; its declared width is still one "
                                + "character, which is what a client may submit")
                        .isNull();
                assertThat(UserResponse.SELECTOR_LENGTH).isEqualTo(SELECTOR_WIDTH);
                assertThat(textOf(row, "userId")).hasSize(USER_ID_WIDTH);
                assertThat(textOf(row, "userType")).hasSize(USER_TYPE_WIDTH);
                assertThat(textOf(row, "firstName").length()).isLessThanOrEqualTo(NAME_PART_WIDTH);
                assertThat(textOf(row, "lastName").length()).isLessThanOrEqualTo(NAME_PART_WIDTH);
            }
        }

        @Test
        @DisplayName("★ the program's internal staging shape - a combined twenty-five-character name and "
                + "an eight-character role code - is absent from the contract")
        void theInternalStagingShapeIsAbsentFromTheContract() throws Exception {
            // app/cbl/COUSR00C.cbl L56-L64 declares a ten-occurrence staging area whose name item is
            // twenty-five characters and whose type item is eight. That is the program's own working
            // storage, NOT the screen contract, and modelling it would publish a shape no map declares.
            final JsonNode page = firstPage(sessionFor(OWNED_ADMIN));

            assertThat(propertyNamesOf(page))
                    .as("no combined name component, under any of the names such a component would carry")
                    .doesNotContain("name")
                    .doesNotContain("fullName")
                    .doesNotContain("combinedName")
                    .doesNotContain("userName");
            for (final JsonNode row : page.get("rows")) {
                assertThat(textOf(row, "userType"))
                        .as("the role code is one character on the contract, never the staging area's "
                                + "eight")
                        .hasSize(USER_TYPE_WIDTH);
                assertThat(textOf(row, "firstName") + textOf(row, "lastName"))
                        .as("the two name parts stay two parts; nothing concatenates them to the staging "
                                + "width")
                        .isNotEqualTo(textOf(row, "firstName"));
            }
        }

        @Test
        @DisplayName("no answer carries a terminal attribute, a map coordinate or any other screen "
                + "artefact")
        void noAnswerCarriesATerminalArtefact() throws Exception {
            final String session = sessionFor(OWNED_ADMIN);
            final String page = firstPage(session).toString();

            assertThat(page)
                    .as("the 3270 presentation layer has no representation in a REST payload: the field "
                            + "contract crossed, the attribute bytes did not")
                    .doesNotContain("DFHRED")
                    .doesNotContain("DFHGREEN")
                    .doesNotContain("DFHBMS")
                    .doesNotContain("attributeByte")
                    .doesNotContain("cursorPosition");
        }

        @Test
        @DisplayName("no refusal body discloses a stack trace, an exception name, a query, a store object "
                + "or a legacy artefact")
        void noRefusalBodyDisclosesAnything() throws Exception {
            final String ordinarySession = sessionFor(OWNED_ORDINARY);
            final MvcResult unauthenticated = submit(AdminUserController.LIST_SUBPATH,
                    nonMutatingBodyFor(AdminUserController.LIST_SUBPATH), null);
            final MvcResult unentitled = submit(AdminUserController.LIST_SUBPATH,
                    nonMutatingBodyFor(AdminUserController.LIST_SUBPATH), ordinarySession);

            for (final MvcResult refusal : List.of(unauthenticated, unentitled)) {
                assertThat(rawBodyOf(refusal))
                        .as("a refusal summary is the module's own neutral text and nothing else")
                        .doesNotContain("Exception")
                        .doesNotContain("java.")
                        .doesNotContain("org.springframework")
                        .doesNotContain("at com.carddemo")
                        .doesNotContain("SELECT")
                        .doesNotContain("select ")
                        .doesNotContain("user_security")
                        .doesNotContain("jdbc:")
                        .doesNotContain("COUSR00C")
                        .doesNotContain("EXEC CICS")
                        .doesNotContain("$2");
            }
        }

        @Test
        @DisplayName("a rejected turn discloses no store detail either")
        void aRejectedTurnDisclosesNoStoreDetail() throws Exception {
            final JsonNode body = turn(AdminUserController.UPDATE_SUBPATH,
                    recordBody(ABSENT_IDENTITY, RESERVED_FIRST_NAME, RESERVED_LAST_NAME, null,
                            USER_ROLE_CODE, KeyAction.PFK05),
                    sessionFor(OWNED_ADMIN));

            assertThat(body.toString())
                    .as("the screen message is the whole of what a rejected turn discloses")
                    .doesNotContain("Exception")
                    .doesNotContain("org.postgresql")
                    .doesNotContain("user_security")
                    .doesNotContain("$2");
            assertThat(textOf(body, "message"))
                    .as("the operator-visible text is the legacy one, which is the external contract")
                    .isEqualTo(USER_ID_NOT_FOUND_MESSAGE);
        }
    }

    /**
     * The user-administration surface: the shipped boundary, the shipped filter chain, the shipped
     * transaction and the credential master the identities live in.
     *
     * <p>Assembled explicitly rather than by scanning, which is this module's established practice for a
     * context-booting specification: a scan of the base package would sweep the test tree's own
     * configuration classes into the graph, and an explicit list lets a reader see in one place exactly
     * what took part.
     *
     * <p><strong>Nothing is stubbed.</strong> The transaction, the hashing service, the token provider,
     * the message catalogue, the navigation vocabulary, the page-snapshot service, the contract adapters,
     * the write primitive and the filter chain are all the shipped ones, and the identities come off a
     * real server. The sign-on boundary is present for one reason only: a session has to be obtained by
     * signing on rather than by fabricating a token, because a fabricated token would prove nothing about
     * the gate.
     *
     * <p>The clock is the pinned instant the shared base publishes, so the header date and time a screen
     * carries mean the same thing on every run and no assertion here can depend on a wall clock.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({AdminUserController.class, UserContractAdapter.class, ScreenStateAdapter.class,
        AuthController.class, SignOnContractAdapter.class, ModuleErrorController.class,
        GlobalExceptionHandler.class, JsonRefusalBodyRenderer.class, UserManagementService.class,
        AuthenticationService.class, SignOnAttemptGovernor.class, SignOnStateService.class,
        CredentialDigestService.class,
        MessageCatalogService.class, NavigationService.class, UserListPageTokenService.class,
        SensitiveFieldEncryptionService.class, OnlineTransactionBoundary.class, RecordWriter.class,
        SecurityConfig.class, JwtTokenProvider.class, WebMvcConfig.class})
    @EnableConfigurationProperties(JwtProperties.class)
    @EnableJpaRepositories(basePackageClasses = UserSecurityRepository.class)
    @EntityScan(basePackageClasses = UserSecurity.class)
    static class UserAdministrationContext {

        UserAdministrationContext() {
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
