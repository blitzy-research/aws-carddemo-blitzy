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
package com.carddemo.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.api.dto.PageMetadata;
import com.carddemo.api.dto.UserRequest;
import com.carddemo.api.dto.UserResponse;
import com.carddemo.domain.UserSecurity;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.util.CobolStringUtils;

/**
 * The four administrative user transactions of the legacy estate, translated into one service.
 *
 * <h2>What this class is</h2>
 *
 * <p>One service carries all four because all four are views of the same eighty-byte user-security
 * record and differ only in the subset they present and the messages they emit. The four legacy
 * members, with the counts this translation is measured against:</p>
 *
 * <table border="1">
 *   <caption>Legacy authorities</caption>
 *   <tr><th>Transaction</th><th>Member</th><th>Lines</th><th>Paragraphs</th><th>Operation</th></tr>
 *   <tr><td>{@code CU00}</td><td>{@code app/cbl/COUSR00C.cbl}</td><td>695</td><td>16</td>
 *       <td>list</td></tr>
 *   <tr><td>{@code CU01}</td><td>{@code app/cbl/COUSR01C.cbl}</td><td>299</td><td>9</td>
 *       <td>add</td></tr>
 *   <tr><td>{@code CU02}</td><td>{@code app/cbl/COUSR02C.cbl}</td><td>414</td><td>11</td>
 *       <td>update</td></tr>
 *   <tr><td>{@code CU03}</td><td>{@code app/cbl/COUSR03C.cbl}</td><td>359</td><td>11</td>
 *       <td>delete</td></tr>
 * </table>
 *
 * <p><strong>Forty-seven paragraphs in total, and every one has a named method here.</strong> The
 * mapping is set out in full in the table further down, including the two places where an identical
 * paragraph in more than one member is served by a single shared method.</p>
 *
 * <p>None of the four members includes the attention-key copybook and none installs an abend
 * handler - those belong to the five-program family that includes the card-and-account screens - so
 * this class routes no key through the utility-layer key translator and raises no abend. Each
 * member's own key decision is reproduced in that member's own clause order instead.</p>
 *
 * <h2>The record this service maintains</h2>
 *
 * <p>{@code app/cpy/CSUSR01Y.cpy} declares an eighty-byte record: the identifier {@code X(08)} at
 * offset 0, the given name {@code X(20)} at offset 8, the family name {@code X(20)} at offset 28,
 * the credential {@code X(08)} at offset 48, the type {@code X(01)} at offset 56 and a
 * {@code X(23)} filler at offset 57. Those offsets appear here as provenance only: this class never
 * slices a record image, because fixed-width layout knowledge belongs exclusively to the mapper in
 * the utility layer.</p>
 *
 * <p><strong>The credential column is sixty characters wide, and that is the one deliberate width
 * divergence in the whole eleven-table schema.</strong> The legacy field is eight cleartext
 * characters that sign-on compared directly; the column holds a BCrypt digest instead, so this
 * service hashes on write and never reads a credential back. The legacy shared cleartext value that
 * the provisioning job stream carried does not appear anywhere in this module, and the ten seeded
 * identities are the only thing carried across from it.</p>
 *
 * <p>The table has no cluster definition in the legacy estate and <strong>starts empty</strong>
 * after the reference-data migration. The ten identities - five of type {@code A} and five of type
 * {@code U} - arrive in the fourth migration, which reaches local and test execution only because
 * the shared and production configurations pin the migration target below its version. Nothing here
 * depends on their presence.</p>
 *
 * <h2>Paragraph-to-method mapping, all forty-seven</h2>
 *
 * <table border="1">
 *   <caption>{@code CU00} list - {@code app/cbl/COUSR00C.cbl}, 16 paragraphs</caption>
 *   <tr><th>Paragraph</th><th>Line</th><th>Method</th></tr>
 *   <tr><td>{@code MAIN-PARA}</td><td>98</td><td>{@code listUsers}</td></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY}</td><td>149</td><td>{@code processListEnterKey}</td></tr>
 *   <tr><td>{@code PROCESS-PF7-KEY}</td><td>237</td><td>{@code processPf7Key}</td></tr>
 *   <tr><td>{@code PROCESS-PF8-KEY}</td><td>260</td><td>{@code processPf8Key}</td></tr>
 *   <tr><td>{@code PROCESS-PAGE-FORWARD}</td><td>282</td><td>{@code processPageForward}</td></tr>
 *   <tr><td>{@code PROCESS-PAGE-BACKWARD}</td><td>336</td><td>{@code processPageBackward}</td></tr>
 *   <tr><td>{@code POPULATE-USER-DATA}</td><td>384</td><td>{@code populateUserData}</td></tr>
 *   <tr><td>{@code INITIALIZE-USER-DATA}</td><td>446</td><td>{@code initializeUserData}</td></tr>
 *   <tr><td>{@code RETURN-TO-PREV-SCREEN}</td><td>506</td><td>{@code returnToPrevScreen} (shared)</td></tr>
 *   <tr><td>{@code SEND-USRLST-SCREEN}</td><td>522</td><td>{@code sendUsrlstScreen}</td></tr>
 *   <tr><td>{@code RECEIVE-USRLST-SCREEN}</td><td>549</td><td>{@code receiveUsrlstScreen}</td></tr>
 *   <tr><td>{@code POPULATE-HEADER-INFO}</td><td>562</td><td>{@code populateHeaderInfo} (shared)</td></tr>
 *   <tr><td>{@code STARTBR-USER-SEC-FILE}</td><td>586</td><td>{@code startbrUserSecFile}</td></tr>
 *   <tr><td>{@code READNEXT-USER-SEC-FILE}</td><td>619</td><td>{@code readnextUserSecFile}</td></tr>
 *   <tr><td>{@code READPREV-USER-SEC-FILE}</td><td>653</td><td>{@code readprevUserSecFile}</td></tr>
 *   <tr><td>{@code ENDBR-USER-SEC-FILE}</td><td>687</td><td>{@code endbrUserSecFile}</td></tr>
 * </table>
 *
 * <table border="1">
 *   <caption>{@code CU01} add - {@code app/cbl/COUSR01C.cbl}, 9 paragraphs</caption>
 *   <tr><th>Paragraph</th><th>Line</th><th>Method</th></tr>
 *   <tr><td>{@code MAIN-PARA}</td><td>71</td><td>{@code addUser}</td></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY}</td><td>115</td><td>{@code processAddEnterKey}</td></tr>
 *   <tr><td>{@code RETURN-TO-PREV-SCREEN}</td><td>165</td><td>{@code returnToPrevScreen} (shared)</td></tr>
 *   <tr><td>{@code SEND-USRADD-SCREEN}</td><td>184</td><td>{@code sendUsraddScreen}</td></tr>
 *   <tr><td>{@code RECEIVE-USRADD-SCREEN}</td><td>201</td><td>{@code receiveUsraddScreen}</td></tr>
 *   <tr><td>{@code POPULATE-HEADER-INFO}</td><td>214</td><td>{@code populateHeaderInfo} (shared)</td></tr>
 *   <tr><td>{@code WRITE-USER-SEC-FILE}</td><td>238</td><td>{@code writeUserSecFile}</td></tr>
 *   <tr><td>{@code CLEAR-CURRENT-SCREEN}</td><td>279</td><td>{@code clearAddScreen}</td></tr>
 *   <tr><td>{@code INITIALIZE-ALL-FIELDS}</td><td>287</td><td>{@code initializeAddFields}</td></tr>
 * </table>
 *
 * <table border="1">
 *   <caption>{@code CU02} update - {@code app/cbl/COUSR02C.cbl}, 11 paragraphs</caption>
 *   <tr><th>Paragraph</th><th>Line</th><th>Method</th></tr>
 *   <tr><td>{@code MAIN-PARA}</td><td>82</td><td>{@code updateUser}</td></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY}</td><td>143</td><td>{@code processUpdateEnterKey}</td></tr>
 *   <tr><td>{@code UPDATE-USER-INFO}</td><td>177</td><td>{@code updateUserInfo}</td></tr>
 *   <tr><td>{@code RETURN-TO-PREV-SCREEN}</td><td>250</td><td>{@code returnToPrevScreen} (shared)</td></tr>
 *   <tr><td>{@code SEND-USRUPD-SCREEN}</td><td>266</td><td>{@code sendUsrupdScreen}</td></tr>
 *   <tr><td>{@code RECEIVE-USRUPD-SCREEN}</td><td>283</td><td>{@code receiveUsrupdScreen}</td></tr>
 *   <tr><td>{@code POPULATE-HEADER-INFO}</td><td>296</td><td>{@code populateHeaderInfo} (shared)</td></tr>
 *   <tr><td>{@code READ-USER-SEC-FILE}</td><td>320</td><td>{@code readUserSecFileForUpdate}</td></tr>
 *   <tr><td>{@code UPDATE-USER-SEC-FILE}</td><td>358</td><td>{@code updateUserSecFile}</td></tr>
 *   <tr><td>{@code CLEAR-CURRENT-SCREEN}</td><td>395</td><td>{@code clearUpdateScreen}</td></tr>
 *   <tr><td>{@code INITIALIZE-ALL-FIELDS}</td><td>403</td><td>{@code initializeUpdateFields}</td></tr>
 * </table>
 *
 * <table border="1">
 *   <caption>{@code CU03} delete - {@code app/cbl/COUSR03C.cbl}, 11 paragraphs</caption>
 *   <tr><th>Paragraph</th><th>Line</th><th>Method</th></tr>
 *   <tr><td>{@code MAIN-PARA}</td><td>82</td><td>{@code deleteUser}</td></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY}</td><td>142</td><td>{@code processDeleteEnterKey}</td></tr>
 *   <tr><td>{@code DELETE-USER-INFO}</td><td>174</td><td>{@code deleteUserInfo}</td></tr>
 *   <tr><td>{@code RETURN-TO-PREV-SCREEN}</td><td>197</td><td>{@code returnToPrevScreen} (shared)</td></tr>
 *   <tr><td>{@code SEND-USRDEL-SCREEN}</td><td>213</td><td>{@code sendUsrdelScreen}</td></tr>
 *   <tr><td>{@code RECEIVE-USRDEL-SCREEN}</td><td>230</td><td>{@code receiveUsrdelScreen}</td></tr>
 *   <tr><td>{@code POPULATE-HEADER-INFO}</td><td>243</td><td>{@code populateHeaderInfo} (shared)</td></tr>
 *   <tr><td>{@code READ-USER-SEC-FILE}</td><td>267</td><td>{@code readUserSecFileForDelete}</td></tr>
 *   <tr><td>{@code DELETE-USER-SEC-FILE}</td><td>305</td><td>{@code deleteUserSecFile}</td></tr>
 *   <tr><td>{@code CLEAR-CURRENT-SCREEN}</td><td>341</td><td>{@code clearDeleteScreen}</td></tr>
 *   <tr><td>{@code INITIALIZE-ALL-FIELDS}</td><td>349</td><td>{@code initializeDeleteFields}</td></tr>
 * </table>
 *
 * <p><strong>Two methods are shared, and the sharing is stated rather than hidden.</strong>
 * {@code returnToPrevScreen} carries four paragraphs - {@code COUSR00C.cbl} L506,
 * {@code COUSR01C.cbl} L165, {@code COUSR02C.cbl} L250 and {@code COUSR03C.cbl} L197 - which are
 * identical in all four members: each substitutes the sign-on program when the nominated
 * destination is blank, records the originating transaction and program, zeroes the program context
 * and transfers. {@code populateHeaderInfo} likewise carries {@code COUSR00C.cbl} L562,
 * {@code COUSR01C.cbl} L214, {@code COUSR02C.cbl} L296 and {@code COUSR03C.cbl} L243, which differ
 * only in the output map they write to and are therefore one behaviour parameterised by the screen.
 * Every other paragraph has a method of its own, including the pairs that look alike but are not -
 * the three field-initialisation paragraphs clear different field sets, and the two record-read
 * paragraphs emit different confirmation text.</p>
 *
 * <h2>Decisions a reader will want justified</h2>
 *
 * <p><strong>Hashing on both write paths, and never re-hashing a digest.</strong> Adding a user
 * hashes the submitted credential. Updating one hashes only a genuinely new credential: an absent
 * credential carries the stored digest forward byte for byte, and so does a credential that is
 * re-typed unchanged, which the encoder confirms by verification rather than by comparing digests.
 * Re-hashing a stored digest would produce a digest of a digest and lock the identity out, so it is
 * never done. Nothing here compares digests for equality, and no method returns, echoes, renders or
 * logs one.</p>
 *
 * <p><strong>No credential rule is invented.</strong> The legacy tests that the credential field is
 * non-blank and stores whatever arrived. There is therefore no strength rule, no expiry, no history
 * and no lockout here, because adding one would reject input the legacy accepted.</p>
 *
 * <p><strong>The type field is accepted, not policed.</strong> The column is a raw single character
 * with no constraint, no enumerated mapping and no validation, so a code outside the declared
 * {@code A} and {@code U} pair must load rather than fail. Translation to the domain enumeration
 * happens for service decisions only and is deliberately total: the sign-on role split tests the
 * administrator condition and routes every other value through an unconditional alternative with no
 * third branch, and that is the shape reproduced here. Administrator access to these four
 * operations is enforced by the route-to-role table at the boundary, and is deliberately not
 * duplicated as a second check inside this service.</p>
 *
 * <p><strong>The list page holds ten rows because the screen does.</strong> See
 * {@code USER_LIST_PAGE_SIZE}. A page reached by walking backward is read descending and reversed
 * before it is returned, so the page still presents ascending exactly as the legacy screen did after
 * filling its slots from the bottom upward.</p>
 *
 * <p><strong>No failure on this path raises an exception of its own.</strong> Every legacy failure
 * arm in all four members writes a message and re-presents the screen; not one abends, not one
 * rolls back, and the estate's only explicit rollback is in the account-update program. A
 * not-found identifier is therefore reported as the legacy message rather than raised, and the two
 * mutating operations take a plain transaction with no forced rollback. The entity carries no
 * version attribute, so no optimistic-locking failure can arise here either.</p>
 *
 * <h2>Provenance</h2>
 *
 * <p>Legacy checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19, the stamp appearing in the trailer of each
 * of the four members and of {@code app/cpy/CSUSR01Y.cpy} at L25. The legacy tree under
 * {@code app/} is read-only reference material that nothing here reads at run time: only member
 * names, transaction identifiers, paragraph names, line numbers, field names, widths, offsets and
 * the exact message texts cross into this module.</p>
 */
@Service
public final class UserManagementService {

    private static final Logger LOG = LoggerFactory.getLogger(UserManagementService.class);

    /**
     * Rows the administrative user list presents: <strong>ten</strong>.
     *
     * <p>Proven rather than chosen. {@code app/cbl/COUSR00C.cbl} L56-L57 declares the screen row
     * group as {@code WS-USER-DATA} containing {@code USER-REC OCCURS 10 TIMES}, each occurrence a
     * one-character selection item, a two-character filler, an eight-character identifier, another
     * two-character filler, a twenty-five-character name, a further two-character filler and an
     * eight-character type. The two loops that fill and clear those slots bound themselves at the
     * same figure, at L293 and L300 walking upward and at L347 and L354 walking downward.</p>
     *
     * <p><strong>This is a behavioural contract, not a performance setting.</strong> It is the
     * shape of a screen that no longer exists as a screen but whose contract the REST surface
     * reproduces, so it is deliberately not exposed as configuration, not bound to a property, not
     * defaulted and not overridable by a caller. Changing it would change what the operation
     * returns, which is a parity break rather than a tuning choice.</p>
     *
     * <p><strong>Declared here and deliberately not shared.</strong> The estate has three list
     * screens with three independently proven row counts - seven for the card list, ten for the
     * transaction list and ten for this one - and two of the three coincide by accident of
     * unrelated layouts. Importing another screen's constant, or promoting one to a shared value,
     * would let a correction to one screen silently change another. The paging contract publishes
     * its own user-list figure for a request boundary to read; this one is what the service supplies
     * to the query, and the two are intentionally separate statements of the same measurement.</p>
     */
    private static final int USER_LIST_PAGE_SIZE = 10;

    /** Persistent attribute the browse orders by: the primary key, which is the browse key. */
    private static final String BROWSE_SORT_PROPERTY = "secUsrId";

    /** Legacy transaction identifier of the list screen, {@code COUSR00C.cbl} L37. */
    private static final String LIST_TRANSACTION_ID = "CU00";

    /** Legacy program name of the list screen, {@code COUSR00C.cbl} L36. */
    private static final String LIST_PROGRAM_NAME = "COUSR00C";

    /** Legacy transaction identifier of the add screen, {@code COUSR01C.cbl} L37. */
    private static final String ADD_TRANSACTION_ID = "CU01";

    /** Legacy program name of the add screen, {@code COUSR01C.cbl} L36. */
    private static final String ADD_PROGRAM_NAME = "COUSR01C";

    /** Legacy transaction identifier of the update screen, {@code COUSR02C.cbl} L37. */
    private static final String UPDATE_TRANSACTION_ID = "CU02";

    /** Legacy program name of the update screen, {@code COUSR02C.cbl} L36. */
    private static final String UPDATE_PROGRAM_NAME = "COUSR02C";

    /** Legacy transaction identifier of the delete screen, {@code COUSR03C.cbl} L37. */
    private static final String DELETE_TRANSACTION_ID = "CU03";

    /** Legacy program name of the delete screen, {@code COUSR03C.cbl} L36. */
    private static final String DELETE_PROGRAM_NAME = "COUSR03C";

    /**
     * Program the administrative menu is reached by, nominated on the exit key of all four members -
     * {@code COUSR00C.cbl} L126, {@code COUSR01C.cbl} L94, {@code COUSR02C.cbl} L114 and L125, and
     * {@code COUSR03C.cbl} L113 and L124.
     */
    private static final String ADMIN_MENU_PROGRAM_NAME = "COADM01C";

    /**
     * Program a turn carrying no prior navigation state is sent to, nominated at
     * {@code COUSR00C.cbl} L111 and at the corresponding line of each of the other three members.
     */
    private static final String SIGN_ON_PROGRAM_NAME = "COSGN00C";

    /** Screen field the list map positions the cursor on, {@code app/cpy-bms/COUSR00.CPY} L66. */
    private static final String FIELD_LIST_USER_ID = "USRIDIN";

    /** Screen field of the add map's given name, {@code app/cpy-bms/COUSR01.CPY} L60. */
    private static final String FIELD_FIRST_NAME = "FNAME";

    /** Screen field of the add map's family name, {@code app/cpy-bms/COUSR01.CPY} L66. */
    private static final String FIELD_LAST_NAME = "LNAME";

    /** Screen field of the add map's identifier, {@code app/cpy-bms/COUSR01.CPY} L72. */
    private static final String FIELD_ADD_USER_ID = "USERID";

    /** Screen field of the credential item, {@code app/cpy-bms/COUSR01.CPY} L78. */
    private static final String FIELD_PASSWORD = "PASSWD";

    /** Screen field of the type item, {@code app/cpy-bms/COUSR01.CPY} L84. */
    private static final String FIELD_USER_TYPE = "USRTYPE";

    /** Request-contract name of the identifier the other three screens maintain. */
    private static final String PROPERTY_USER_ID = "userId";

    /** Request-contract name of the given name. */
    private static final String PROPERTY_FIRST_NAME = "firstName";

    /** Request-contract name of the family name. */
    private static final String PROPERTY_LAST_NAME = "lastName";

    /**
     * Request-contract name of the credential item. Only ever used as the <em>name</em> of a field
     * in an error entry; no value of that field is ever placed beside it.
     */
    private static final String PROPERTY_PASSWORD = "password";

    /** Request-contract name of the type item. */
    private static final String PROPERTY_USER_TYPE = "userType";

    /** Selection marker that dispatches to the update screen, {@code COUSR00C.cbl} L190-L191. */
    private static final String SELECTION_UPDATE_UPPER = "U";

    /** Lower-case selection marker for update, admitted at {@code COUSR00C.cbl} L191. */
    private static final String SELECTION_UPDATE_LOWER = "u";

    /** Selection marker that dispatches to the delete screen, {@code COUSR00C.cbl} L200-L201. */
    private static final String SELECTION_DELETE_UPPER = "D";

    /** Lower-case selection marker for delete, admitted at {@code COUSR00C.cbl} L201. */
    private static final String SELECTION_DELETE_LOWER = "d";

    /**
     * Header date rendering, {@code MM/DD/YY}, assembled at {@code COUSR00C.cbl} L571-L575 from the
     * month, the day and the trailing two characters of the year. {@code Locale.ROOT} is mandatory:
     * a locale-sensitive formatter would render a non-Gregorian or non-ASCII value under a different
     * default locale, and the field is a fixed eight-character screen item.
     */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/uu", Locale.ROOT);

    /**
     * Header time rendering, {@code HH:MM:SS}, assembled at {@code COUSR00C.cbl} L577-L581, on the
     * same locale terms as the date.
     */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /** Ascending order on the browse key, which is the only order the legacy browse establishes. */
    private static final Sort ASCENDING_BY_USER_ID = Sort.by(Sort.Direction.ASC, BROWSE_SORT_PROPERTY);

    private final UserSecurityRepository userSecurityRepository;

    private final MessageCatalogService messageCatalogService;

    private final NavigationService navigationService;

    private final PasswordEncoder passwordEncoder;

    private final Clock clock;

    /**
     * Creates the service. Constructor injection only: every collaborator is final and mandatory,
     * so no instance of this class can exist in a partially wired state.
     *
     * @param userSecurityRepository the only access path to the user-security table; supplies the
     *                               full-key read, the paged projection, the single write and the
     *                               single-row delete, and nothing else
     * @param messageCatalogService  the source of the common message texts, in particular the
     *                               unmapped-key text at its full untrimmed contractual width
     * @param navigationService      the single authority for the routes this service returns; no
     *                               route table is declared here
     * @param passwordEncoder        the BCrypt encoder published as a bean by the security
     *                               configuration. Used to produce a digest on write and to confirm
     *                               that a re-typed credential is unchanged; never to compare two
     *                               digests
     * @param clock                  the clock the header date and time are read from, injected so a
     *                               test can fix it
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public UserManagementService(final UserSecurityRepository userSecurityRepository,
                                 final MessageCatalogService messageCatalogService,
                                 final NavigationService navigationService,
                                 final PasswordEncoder passwordEncoder,
                                 final Clock clock) {
        this.userSecurityRepository = Objects.requireNonNull(userSecurityRepository,
                "userSecurityRepository must not be null");
        this.messageCatalogService = Objects.requireNonNull(messageCatalogService,
                "messageCatalogService must not be null");
        this.navigationService = Objects.requireNonNull(navigationService,
                "navigationService must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder,
                "passwordEncoder must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // ==============================================================================================
    // Legacy level-88 condition names, as enumerations with predicates
    // ==============================================================================================

    /**
     * {@code WS-ERR-FLG} with its two condition names {@code ERR-FLG-ON} and {@code ERR-FLG-OFF},
     * declared identically in all four members - {@code COUSR00C.cbl} L40-L42 and the corresponding
     * lines of the other three. A two-state enumeration rather than a character flag, so that
     * {@code SET ERR-FLG-OFF TO TRUE} becomes an assignment the compiler checks.
     */
    private enum ErrorFlag {

        /** {@code ERR-FLG-ON}, the {@code 'Y'} state. */
        ON,

        /** {@code ERR-FLG-OFF}, the {@code 'N'} state. */
        OFF;

        /**
         * @return {@code true} for the {@code ERR-FLG-ON} state
         */
        boolean isOn() {
            return this == ON;
        }
    }

    /**
     * {@code WS-USER-SEC-EOF} with condition names {@code USER-SEC-EOF} and
     * {@code USER-SEC-NOT-EOF}, {@code COUSR00C.cbl} L43-L45. Set by the browse verbs when a read
     * runs off either end of the key sequence.
     */
    private enum EofFlag {

        /** {@code USER-SEC-EOF}, the {@code 'Y'} state. */
        EOF,

        /** {@code USER-SEC-NOT-EOF}, the {@code 'N'} state. */
        NOT_EOF;

        /**
         * @return {@code true} for the {@code USER-SEC-EOF} state
         */
        boolean isEof() {
            return this == EOF;
        }
    }

    /**
     * {@code WS-SEND-ERASE-FLG} with condition names {@code SEND-ERASE-YES} and
     * {@code SEND-ERASE-NO}, {@code COUSR00C.cbl} L46-L48. It selects between the two send verbs at
     * L528-L544, one of which erases the screen first. Only the list screen carries it.
     *
     * <p>The flag governs a terminal erase, which a payload has no counterpart for, so it is
     * retained as behaviour rather than dropped: the two attention keys that set it to {@code NO} -
     * the paging keys refused at L253 and L275 - are exactly the two arms that re-present the
     * screen without rebuilding its rows, and that is what the flag is read for here.
     */
    private enum SendEraseFlag {

        /** {@code SEND-ERASE-YES}, the {@code 'Y'} state: the screen is erased before the send. */
        YES,

        /** {@code SEND-ERASE-NO}, the {@code 'N'} state: the screen is overwritten in place. */
        NO;

        /**
         * @return {@code true} for the {@code SEND-ERASE-YES} state
         */
        boolean isYes() {
            return this == YES;
        }
    }

    /**
     * {@code CDEMO-CU00-NEXT-PAGE-FLG} with condition names {@code NEXT-PAGE-YES} and
     * {@code NEXT-PAGE-NO}, {@code COUSR00C.cbl} L71-L73. Discovered by attempting one read beyond
     * the page at L311-L316, which is why it can never be asserted by a caller.
     */
    private enum NextPageFlag {

        /** {@code NEXT-PAGE-YES}, the {@code 'Y'} state: a further page follows. */
        YES,

        /** {@code NEXT-PAGE-NO}, the {@code 'N'} state: the page just built is the last. */
        NO;

        /**
         * @return {@code true} for the {@code NEXT-PAGE-YES} state
         */
        boolean isYes() {
            return this == YES;
        }
    }

    /**
     * {@code WS-USR-MODIFIED} with condition names {@code USR-MODIFIED-YES} and
     * {@code USR-MODIFIED-NO}, {@code COUSR02C.cbl} L45-L47 and {@code COUSR03C.cbl} L45-L47. The
     * update member sets it from four independent field comparisons at L219-L234 and then branches
     * on it at L236; the delete member declares it and never sets it, which is reproduced here by
     * leaving the delete flow's flag at its initial state.
     */
    private enum ModifiedFlag {

        /** {@code USR-MODIFIED-YES}, the {@code 'Y'} state: at least one field differs. */
        YES,

        /** {@code USR-MODIFIED-NO}, the {@code 'N'} state: nothing differs. */
        NO;

        /**
         * @return {@code true} for the {@code USR-MODIFIED-YES} state
         */
        boolean isYes() {
            return this == YES;
        }
    }

    /**
     * Where a browse is positioned from, reproducing the three values the legacy moves into the
     * record-identification field before {@code STARTBR}.
     *
     * <p>Modelled as three named anchors rather than as sentinel strings because two of the three
     * are COBOL figurative constants rather than keys: a low-value key positions before every
     * record and a high-value key positions after every record, and neither is a value any row can
     * hold. The browse is greater-or-equal positioned, which is the command's default, so a
     * high-value anchor finds nothing and takes the not-found arm - the behaviour the paging key at
     * {@code COUSR00C.cbl} L262-L266 relies on when no last identifier has been retained yet.
     */
    private enum BrowseAnchor {

        /** {@code LOW-VALUES}: position before the first row, {@code COUSR00C.cbl} L240 and L219. */
        LOW_VALUES,

        /** {@code HIGH-VALUES}: position after the last row, {@code COUSR00C.cbl} L263. */
        HIGH_VALUES,

        /** An actual eight-character key, {@code COUSR00C.cbl} L221, L242 and L265. */
        KEY
    }

    /**
     * The outcome of a browse verb, standing for the response code the legacy evaluates at
     * {@code COUSR00C.cbl} L597, L631 and L665. Three arms, in the source's own clause order:
     * normal, the end-of-sequence condition, and everything else.
     */
    private enum BrowseResponse {

        /** {@code DFHRESP(NORMAL)}: the verb positioned or read a row. */
        NORMAL,

        /** {@code DFHRESP(NOTFND)} on positioning, {@code DFHRESP(ENDFILE)} on a read. */
        END_OF_SEQUENCE,

        /** The catch-all arm: any other response. */
        OTHER
    }

    /**
     * The outcome of a single-record verb, standing for the response code evaluated at
     * {@code COUSR01C.cbl} L250, {@code COUSR02C.cbl} L333 and L368, and {@code COUSR03C.cbl} L280
     * and L313. The duplicate arm exists only on the write path, where the source folds
     * {@code DFHRESP(DUPKEY)} and {@code DFHRESP(DUPREC)} into one arm at L260-L261.
     */
    private enum RecordResponse {

        /** {@code DFHRESP(NORMAL)}: the verb completed. */
        NORMAL,

        /** {@code DFHRESP(DUPKEY)} or {@code DFHRESP(DUPREC)}: the key already exists. */
        DUPLICATE,

        /** {@code DFHRESP(NOTFND)}: no row carries the key. */
        NOT_FOUND,

        /** The catch-all arm: any other response. */
        OTHER
    }

    // ==============================================================================================
    // Per-invocation state: the working storage and map area of one online turn
    // ==============================================================================================

    /**
     * The working storage and screen area of one turn, held for the duration of one call.
     *
     * <p>The legacy programs keep this state in {@code WORKING-STORAGE} and in the symbolic map,
     * both of which a paragraph reads and writes freely. Reproducing that faithfully needs mutable
     * state, and putting it on the service would make the singleton stateful and unusable
     * concurrently. One instance is therefore created per call and discarded when the call returns,
     * which is why <strong>this service itself holds no mutable field, caches no user, no page and
     * no cursor</strong>, and why two concurrent turns cannot observe each other.
     *
     * <p>The row slots are a fixed array of exactly the screen's row count rather than a growing
     * list, because the legacy fills numbered slots and blanks the rest: the clearing paragraph at
     * {@code COUSR00C.cbl} L446 and the filling paragraph at L384 both address a slot by number, and
     * a page walked backward fills them from the last slot downward. Holding the slots means the
     * ascending order the response carries is produced by reading the slots in slot order, which is
     * exactly the reversal the backward fill implies rather than a separate reversing step.
     */
    private static final class TurnState {

        /** {@code WS-ERR-FLG}. Starts {@code OFF}, as set at {@code COUSR00C.cbl} L100. */
        private ErrorFlag errorFlag = ErrorFlag.OFF;

        /** {@code WS-USER-SEC-EOF}. Starts {@code NOT_EOF}, as set at L101. */
        private EofFlag eofFlag = EofFlag.NOT_EOF;

        /** {@code WS-SEND-ERASE-FLG}. Starts {@code YES}, as set at L103. */
        private SendEraseFlag sendEraseFlag = SendEraseFlag.YES;

        /** {@code CDEMO-CU00-NEXT-PAGE-FLG}. Starts {@code NO}, as set at L102. */
        private NextPageFlag nextPageFlag = NextPageFlag.NO;

        /** {@code WS-USR-MODIFIED}. Starts {@code NO}, as set at {@code COUSR02C.cbl} L85. */
        private ModifiedFlag modifiedFlag = ModifiedFlag.NO;

        /**
         * The arm the most recent single-record read reported through, which the delete flow needs and
         * the update flow does not.
         *
         * <p>{@code app/cbl/COUSR03C.cbl} L188-L192 performs the read and the removal one after the
         * other with <strong>no test between them</strong>, so the removal is attempted whatever the
         * read reported and then reports through an arm of its own. Which arm depends on what the read
         * left behind: a read that found nothing leaves the removal nothing to position on and it
         * reports not-found, whereas a read that failed outright leaves no position at all and the
         * removal reports through its catch-all arm - whose text, in the source, names the update
         * operation. Carrying the read's arm forward is what keeps those two outcomes distinct;
         * collapsing them to a presence test would report a hard failure as a missing row.
         */
        private RecordResponse readResponse = RecordResponse.NORMAL;

        /** {@code WS-MESSAGE}. Starts blank, as set at {@code COUSR00C.cbl} L105. */
        private String message;

        /**
         * The screen field the cursor is placed on, which every {@code MOVE -1 TO ...L} statement
         * sets. Carried through to the response so a client can focus the same field.
         */
        private String focusFieldId;

        /** Independent per-field error entries, in the order the fields are validated. */
        private final List<ErrorResponse.FieldError> fieldErrors = new ArrayList<>();

        /** Whether the turn completed its operation, which the three success arms report. */
        private boolean actionSucceeded;

        /** {@code CDEMO-CU00-PAGE-NUM}, the program's own page counter. */
        private int pageNumber;

        /** {@code CDEMO-CU00-USRID-FIRST}, assigned at {@code COUSR00C.cbl} L389. */
        private String firstUserIdOnPage;

        /** {@code CDEMO-CU00-USRID-LAST}, assigned at {@code COUSR00C.cbl} L435. */
        private String lastUserIdOnPage;

        /** The numbered screen row slots, blank until a row is moved into one. */
        private final UserResponse.UserRow[] rowSlots = new UserResponse.UserRow[USER_LIST_PAGE_SIZE];

        /** Echoed identifier field, {@code USRIDINI} on three screens and {@code USERIDI} on add. */
        private String userId;

        /** Echoed {@code FNAMEI}. */
        private String firstName;

        /** Echoed {@code LNAMEI}. */
        private String lastName;

        /** Echoed {@code USRTYPEI}, raw and unjudged. */
        private String userType;

        /**
         * The credential item as submitted, held for the length of one turn and <strong>never
         * returned</strong>.
         *
         * <p>Inbound only, and deliberately not one of the echoed fields above even though the legacy
         * treated it as one: the update screen moved the stored value into its credential item at
         * {@code COUSR02C.cbl} L169 so the operator saw it, and reproducing that is impossible and
         * undesirable now that the column holds a one-way digest. No response component reads this
         * field, no log statement interpolates it, and the field-blanking paragraphs clear it along
         * with the rest of the screen.
         *
         * <p>The distinction between {@code null} and blank carries meaning on the update path and is
         * therefore preserved exactly as submitted: absent means the operator left the credential
         * alone, whereas blank means they emptied the item, and the two produce different outcomes.
         */
        private String submittedCredential;

        /** The communication area of the turn, replaced rather than mutated. */
        private NavigationContext context;

        /** The destination the turn resolves to. */
        private NavigationService.Route route;

        /** {@code TRNNAMEO}, written by the header paragraph. */
        private String transactionName;

        /** {@code PGMNAMEO}, written by the header paragraph. */
        private String programName;

        /** {@code TITLE01O}, written by the header paragraph. */
        private String title01;

        /** {@code TITLE02O}, written by the header paragraph. */
        private String title02;

        /** {@code CURDATEO}, written by the header paragraph. */
        private String currentDate;

        /** {@code CURTIMEO}, written by the header paragraph. */
        private String currentTime;

        /**
         * Which way the page just built was walked, or {@code null} when this turn built no page.
         *
         * <p>Absent rather than defaulted, because the paging contract admits no default direction
         * and a turn that transfers control or reports a field error never walked the key sequence at
         * all. Only a turn that actually walked one may carry paging metadata.
         */
        private PageMetadata.PagingDirection pageDirection;

        /**
         * Returns the populated row slots in slot order, which is ascending key order for a page
         * walked in either direction.
         *
         * <p>A page walked forward fills slots 1 upward and a page walked backward fills slot ten
         * downward, so reading the slots in slot order yields ascending content both ways. Blank
         * slots are skipped: on the screen a blank slot is a cleared row, and in a payload an absent
         * row is the same statement.
         *
         * @return the rows the page carries, never {@code null}, never longer than the screen's row
         *         count and ordered ascending by identifier
         */
        private List<UserResponse.UserRow> populatedRows() {
            final List<UserResponse.UserRow> rows = new ArrayList<>(rowSlots.length);
            for (final UserResponse.UserRow slot : rowSlots) {
                if (slot != null) {
                    rows.add(slot);
                }
            }
            return List.copyOf(rows);
        }
    }

    /**
     * An ordered browse over the user-security table, positioned by key and read one row at a time
     * in either direction.
     *
     * <p><strong>Why this exists at all.</strong> The legacy list screen browses a keyed cluster:
     * it positions on a key, reads forward or backward one record per verb, and stops when a read
     * runs off the end. The repository publishes exactly two read paths - a full-key read and a
     * paged scan over a credential-free projection - and deliberately declares no derived finder,
     * because the schema creates no secondary index for one to use. The paged scan is therefore the
     * sanctioned mechanism, and this class is the browse expressed in terms of it: each page is one
     * indexed range read on the primary key, and a row-at-a-time cursor is layered over the pages.
     *
     * <p>One instance serves one turn and is discarded with it, so the loaded page is a
     * within-call read buffer and never state that outlives the call. The buffer is what keeps ten
     * consecutive reads to one query rather than ten.
     *
     * <p><strong>Positioning is greater-or-equal</strong>, which is the browse command's default and
     * therefore what the legacy gets even though the explicit qualifier at {@code COUSR00C.cbl} L592
     * is commented out. A key that matches nothing positions at the next higher key, and a key above
     * every row positions nowhere and yields the not-found arm.
     *
     * <p><strong>One assumption is stated rather than left implicit.</strong> Rows arrive ordered by
     * the store, while the search key is compared here, so the two orderings must agree. They do for
     * this table: the identifier is a fixed-width eight-character field drawn from upper-case letters
     * and digits, on which lexicographic ordering and any collation the store applies coincide. The
     * comparison is deliberately not case-folded and not trimmed, matching the legacy comparison of
     * one fixed-width field against another.
     */
    private static final class SequentialBrowse {

        private final UserSecurityRepository repository;

        /** The page currently buffered, or {@code null} when the browse holds no position. */
        private Page<UserSecurityRepository.AdminEntry> bufferedPage;

        /** Index of the buffered page, or {@code -1} when no page is buffered. */
        private int bufferedPageIndex = -1;

        /**
         * Creates a browse over one repository.
         *
         * @param repository the repository whose paged projection the browse reads
         */
        private SequentialBrowse(final UserSecurityRepository repository) {
            this.repository = repository;
        }

        /**
         * Loads a page, reusing the buffer when it already holds the page wanted.
         *
         * @param pageIndex zero-based page index
         * @return the requested page, which is empty when it lies beyond the last row
         */
        private Page<UserSecurityRepository.AdminEntry> pageAt(final int pageIndex) {
            if (bufferedPageIndex != pageIndex || bufferedPage == null) {
                bufferedPage = repository.findAllProjectedBy(
                        PageRequest.of(pageIndex, USER_LIST_PAGE_SIZE, ASCENDING_BY_USER_ID));
                bufferedPageIndex = pageIndex;
            }
            return bufferedPage;
        }

        /**
         * Reads the row at an absolute offset from the start of the key sequence, which is what one
         * forward or backward read amounts to once the browse is positioned.
         *
         * @param offset zero-based offset into the key sequence; a negative offset is off the front
         *               of the sequence and reads nothing, exactly as a backward read before the
         *               first row does
         * @return the row, or an empty result when the offset lies outside the sequence
         */
        private Optional<UserSecurityRepository.AdminEntry> rowAt(final long offset) {
            if (offset < 0L || offset / USER_LIST_PAGE_SIZE > Integer.MAX_VALUE) {
                return Optional.empty();
            }
            final int pageIndex = (int) (offset / USER_LIST_PAGE_SIZE);
            final int withinPage = (int) (offset % USER_LIST_PAGE_SIZE);
            final List<UserSecurityRepository.AdminEntry> content = pageAt(pageIndex).getContent();
            if (withinPage >= content.size()) {
                return Optional.empty();
            }
            return Optional.of(content.get(withinPage));
        }

        /**
         * Positions the browse at the first row whose key is greater than or equal to a key, walking
         * page by page from the start of the sequence.
         *
         * <p>Page-at-a-time rather than row-at-a-time so that skipping a stretch of the sequence
         * costs one query per page rather than one per row, while the ordered comparison within a
         * page is the row-at-a-time comparison the legacy browse performs.
         *
         * @param key the key to position at or after; compared as supplied, uncased and untrimmed
         * @return the absolute offset of the positioned row, or an empty result when every row has a
         *         lower key and the sequence is exhausted
         */
        private OptionalLong positionAtOrAfter(final String key) {
            long pageStartOffset = 0L;
            while (true) {
                final Page<UserSecurityRepository.AdminEntry> page =
                        pageAt((int) (pageStartOffset / USER_LIST_PAGE_SIZE));
                final List<UserSecurityRepository.AdminEntry> content = page.getContent();
                for (int index = 0; index < content.size(); index++) {
                    if (content.get(index).getSecUsrId().compareTo(key) >= 0) {
                        return OptionalLong.of(pageStartOffset + index);
                    }
                }
                if (!page.hasNext()) {
                    return OptionalLong.empty();
                }
                pageStartOffset += content.size();
            }
        }

        /**
         * Positions the browse at the first row of the sequence, which is what a low-value key does.
         *
         * @return the offset zero when any row exists, or an empty result on an empty table
         */
        private OptionalLong positionAtFirst() {
            if (pageAt(0).getContent().isEmpty()) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(0L);
        }

        /**
         * Releases the browse, discarding the read buffer so no read position survives the verb.
         *
         * <p>The counterpart of the browse-end command at {@code COUSR00C.cbl} L689-L691. It is
         * genuine work rather than a formality: the buffer is the only thing the browse holds, and
         * releasing it is what guarantees a later read in the same turn re-reads rather than
         * answering from a page that a concurrent write may since have changed.
         */
        private void release() {
            bufferedPage = null;
            bufferedPageIndex = -1;
        }
    }

    // ==============================================================================================
    // CU00 - list all users. app/cbl/COUSR00C.cbl, 695 lines, 16 paragraphs
    // ==============================================================================================

    /**
     * One turn of transaction {@code CU00}, listing users a page at a time.
     * <strong>{@code MAIN-PARA}, {@code app/cbl/COUSR00C.cbl} L98.</strong>
     *
     * <p>Read-only: the paragraph and everything it reaches performs positioning and reads and never
     * a write, so the transaction is declared read-only rather than merely happening not to write.
     *
     * <p>The paragraph's structure is reproduced in order. Four flags are set at L100-L103, the
     * message is cleared at L105 and the cursor is placed on the identifier field at L108. A turn
     * with no prior state transfers to sign-on at L110-L112. A first entry marks the state as
     * re-entered, processes the enter key and sends at L115-L119. Any later entry receives the screen
     * and evaluates the attention key at L122-L137 in the source's own clause order, whose default
     * arm raises the error flag and emits the common unmapped-key text.
     *
     * @param request the submitted screen, whose attention key, row selections, search identifier and
     *                retained page anchors are read and whose displayed page number deliberately is
     *                not - the page counter is recomputed here, exactly as the legacy program
     *                computes its own
     * @return the rows, paging metadata, message and route for the client to render
     * @throws NullPointerException if {@code request} is {@code null}
     */
    @Transactional(readOnly = true)
    public UserResponse listUsers(final UserRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        final TurnState state = new TurnState();
        // L100-L103: SET ERR-FLG-OFF, USER-SEC-NOT-EOF, NEXT-PAGE-NO and SEND-ERASE-YES TO TRUE.
        state.errorFlag = ErrorFlag.OFF;
        state.eofFlag = EofFlag.NOT_EOF;
        state.nextPageFlag = NextPageFlag.NO;
        state.sendEraseFlag = SendEraseFlag.YES;
        // L105-L106: MOVE SPACES TO WS-MESSAGE and to the error line of the output map.
        state.message = null;
        // L108: MOVE -1 TO USRIDINL, placing the cursor on the identifier field.
        state.focusFieldId = FIELD_LIST_USER_ID;

        final NavigationContext inbound = request.navigationContext();
        // L110-L112: a zero-length communication area nominates sign-on and transfers.
        if (isNavigationStateAbsent(inbound)) {
            state.context = NavigationContext.empty();
            LOG.debug("User list entered with no prior navigation state: transaction={}",
                    LIST_TRANSACTION_ID);
            returnToPrevScreen(state, SIGN_ON_PROGRAM_NAME, LIST_TRANSACTION_ID, LIST_PROGRAM_NAME,
                    NavigationService.Route.SIGN_ON);
            return sendUsrlstScreen(state);
        }
        state.context = inbound;
        // L115-L119: the first entry marks re-entry, blanks the output map and builds the first page.
        if (inbound.firstEntry()) {
            state.context = inbound.withReEntry();
            processListEnterKey(request, state);
            return sendUsrlstScreen(state);
        }
        // L121: RECEIVE the submitted map before the key is evaluated.
        receiveUsrlstScreen(request, state);
        // L122-L137: EVALUATE EIBAID, clause order preserved, WHEN OTHER as the default arm.
        final KeyAction keyAction = request.keyAction();
        if (keyAction == KeyAction.ENTER) {
            processListEnterKey(request, state);
            return sendUsrlstScreen(state);
        }
        if (keyAction == KeyAction.PFK03) {
            returnToPrevScreen(state, ADMIN_MENU_PROGRAM_NAME, LIST_TRANSACTION_ID, LIST_PROGRAM_NAME,
                    NavigationService.Route.ADMIN_MENU);
            return sendUsrlstScreen(state);
        }
        if (keyAction == KeyAction.PFK07) {
            processPf7Key(request, state);
            return sendUsrlstScreen(state);
        }
        if (keyAction == KeyAction.PFK08) {
            processPf8Key(request, state);
            return sendUsrlstScreen(state);
        }
        // L132-L136: the default arm. Error flag on, cursor to the identifier field, common text.
        state.errorFlag = ErrorFlag.ON;
        state.focusFieldId = FIELD_LIST_USER_ID;
        state.message = messageCatalogService.invalidKeyMessage();
        LOG.debug("User list received an unmapped attention key: transaction={} keyAction={}",
                LIST_TRANSACTION_ID, keyAction);
        return sendUsrlstScreen(state);
    }

    /**
     * Handles the enter key on the list screen: acts on a row selection if one was made, then builds
     * the first page from the search identifier.
     * <strong>{@code PROCESS-ENTER-KEY}, {@code app/cbl/COUSR00C.cbl} L149.</strong>
     *
     * <p>Three stages, in the source's order. The selection scan at L151-L185 is an
     * {@code EVALUATE TRUE} over the ten row items in row order, which stops at the first match, so
     * the <strong>first non-blank selection wins</strong> and later ones are not examined. The
     * dispatch at L187-L215 then transfers to the update or delete screen for the two accepted
     * markers, in either case, and emits the invalid-selection text for anything else. Finally
     * L218-L228 resets the page counter and walks forward, so an accepted selection is the only path
     * that does not rebuild the page.
     *
     * @param request the submitted screen
     * @param state   the turn being assembled
     */
    private void processListEnterKey(final UserRequest request, final TurnState state) {
        final SequentialBrowse browse = new SequentialBrowse(userSecurityRepository);
        final int selectedPosition = firstSelectedRowPosition(request.rowSelections());
        if (selectedPosition > 0) {
            final String marker = request.rowSelections().get(selectedPosition - 1);
            final Optional<String> selectedUserId =
                    selectedUserIdAtPosition(browse, state, request.firstUserIdOnPage(),
                            selectedPosition);
            // L187-L188: both the marker and the selected identifier must be present to dispatch.
            if (selectedUserId.isPresent()) {
                if (dispatchSelection(state, marker, selectedUserId.get())) {
                    browse.release();
                    return;
                }
                // L210-L214: the default arm. Text only; the error flag is deliberately left clear,
                // matching the source, which sets no flag here and falls through to rebuild the page.
                state.message = UserResponse.MSG_LIST_INVALID_SELECTION;
                state.focusFieldId = FIELD_LIST_USER_ID;
            }
        }
        // L218-L222: a blank identifier field positions at a low-value key, otherwise at the field.
        final String searchUserId = request.searchUserId();
        final BrowseAnchor anchor = isBlank(searchUserId) ? BrowseAnchor.LOW_VALUES : BrowseAnchor.KEY;
        // L224: MOVE -1 TO USRIDINL.
        state.focusFieldId = FIELD_LIST_USER_ID;
        // L227: MOVE 0 TO CDEMO-CU00-PAGE-NUM, then L228 walks forward.
        state.pageNumber = 0;
        processPageForward(browse, state, anchor, searchUserId, KeyAction.ENTER);
        // L230-L232: the identifier field is blanked only when the walk raised no error.
        if (!state.errorFlag.isOn()) {
            state.userId = null;
        }
    }

    /**
     * Handles the backward paging key.
     * <strong>{@code PROCESS-PF7-KEY}, {@code app/cbl/COUSR00C.cbl} L237.</strong>
     *
     * <p>L239-L243 positions on the retained first identifier of the current page, or at a low-value
     * key when none was retained. L245 asserts that a further page follows, which is true by
     * construction because the page being left is that page. L248-L255 then walks backward only when
     * the counter shows a page before this one; otherwise it emits the already-at-top text, clears
     * the erase flag so the screen is overwritten rather than cleared, and re-presents.
     *
     * @param request the submitted screen
     * @param state   the turn being assembled
     */
    private void processPf7Key(final UserRequest request, final TurnState state) {
        final String firstOnPage = request.firstUserIdOnPage();
        final BrowseAnchor anchor = isBlank(firstOnPage) ? BrowseAnchor.LOW_VALUES : BrowseAnchor.KEY;
        // L245: SET NEXT-PAGE-YES TO TRUE.
        state.nextPageFlag = NextPageFlag.YES;
        // L246: MOVE -1 TO USRIDINL.
        state.focusFieldId = FIELD_LIST_USER_ID;
        final SequentialBrowse browse = new SequentialBrowse(userSecurityRepository);
        state.pageNumber = pageNumberOf(probeAnchor(browse, state, anchor, firstOnPage));
        // A store that refused the probe has already taken the catch-all arm, which owns the message;
        // the already-at-top text below must not overwrite it.
        if (state.errorFlag.isOn()) {
            browse.release();
            return;
        }
        // L248: IF CDEMO-CU00-PAGE-NUM > 1.
        if (state.pageNumber > 1) {
            processPageBackward(browse, state, anchor, firstOnPage, KeyAction.PFK07);
            return;
        }
        // L250-L254: already at the top. The text is emitted, the erase flag is cleared so the screen
        // is overwritten in place, and the error flag is deliberately left clear as the source leaves
        // it.
        browse.release();
        state.message = UserResponse.MSG_LIST_ALREADY_AT_TOP;
        state.sendEraseFlag = SendEraseFlag.NO;
    }

    /**
     * Handles the forward paging key.
     * <strong>{@code PROCESS-PF8-KEY}, {@code app/cbl/COUSR00C.cbl} L260.</strong>
     *
     * <p>L262-L266 positions on the retained last identifier of the current page, or at a
     * <em>high</em>-value key when none was retained - which, because positioning is
     * greater-or-equal, finds nothing at all rather than starting over at the beginning. L270-L277
     * then walks forward only when a further page follows; otherwise it emits the already-at-bottom
     * text and overwrites the screen in place.
     *
     * <p><strong>The successor test is recomputed rather than trusted.</strong> The legacy carries
     * its next-page flag across the conversation in the communication area; the navigation contract
     * does not carry it, and it could not safely be taken from a client in any case because it is an
     * outcome the browse discovers. It is therefore re-derived from the data by reading one row past
     * the anchor, which is precisely what the flag recorded when it was set at L311-L316. A page with
     * no retained last identifier is a page that never filled its final slot, so it can have no
     * successor and the two readings agree.
     *
     * @param request the submitted screen
     * @param state   the turn being assembled
     */
    private void processPf8Key(final UserRequest request, final TurnState state) {
        final String lastOnPage = request.lastUserIdOnPage();
        final BrowseAnchor anchor = isBlank(lastOnPage) ? BrowseAnchor.HIGH_VALUES : BrowseAnchor.KEY;
        // L268: MOVE -1 TO USRIDINL.
        state.focusFieldId = FIELD_LIST_USER_ID;
        final SequentialBrowse browse = new SequentialBrowse(userSecurityRepository);
        final OptionalLong anchorOffset = probeAnchor(browse, state, anchor, lastOnPage);
        state.pageNumber = pageNumberOf(anchorOffset);
        state.nextPageFlag = anchorOffset.isPresent()
                && probeRowAt(browse, state, anchorOffset.getAsLong() + 1L).isPresent()
                ? NextPageFlag.YES
                : NextPageFlag.NO;
        // A store that refused either probe has already taken the catch-all arm, which owns the
        // message; the already-at-bottom text below must not overwrite it.
        if (state.errorFlag.isOn()) {
            browse.release();
            return;
        }
        // L270: IF NEXT-PAGE-YES.
        if (state.nextPageFlag.isYes()) {
            processPageForward(browse, state, anchor, lastOnPage, KeyAction.PFK08);
            return;
        }
        // L272-L276: already at the bottom. Text only; the error flag stays clear, as in the source.
        browse.release();
        state.message = UserResponse.MSG_LIST_ALREADY_AT_BOTTOM;
        state.sendEraseFlag = SendEraseFlag.NO;
    }

    /**
     * Fills the page by walking the key sequence forward.
     * <strong>{@code PROCESS-PAGE-FORWARD}, {@code app/cbl/COUSR00C.cbl} L282.</strong>
     *
     * <p>Every step of the paragraph is reproduced. L284 positions the browse and L286 abandons the
     * walk when that raised the error flag. L288-L290 consumes one row before the loop for any key
     * other than enter, the backward pager or the exit key - in practice the forward pager, whose
     * anchor is the last row already shown. L292-L296 clears all ten slots. L298-L306 then fills
     * slots upward from one while rows remain and neither flag has tripped. L308-L323 reads one row
     * beyond the page to discover whether a further page follows, incrementing the counter on the
     * populated path and, when the sequence ended, still incrementing it if any row was placed.
     * L325 releases the browse and L327-L329 publishes the counter and sends.
     *
     * @param browse    the browse to walk
     * @param state     the turn being assembled
     * @param anchor    where to position
     * @param key       the key to position on when the anchor is a key
     * @param keyAction the attention key that led here, which selects the pre-loop read
     */
    private void processPageForward(final SequentialBrowse browse,
                                    final TurnState state,
                                    final BrowseAnchor anchor,
                                    final String key,
                                    final KeyAction keyAction) {
        state.pageDirection = PageMetadata.PagingDirection.FORWARD;
        // L284: PERFORM STARTBR-USER-SEC-FILE.
        final OptionalLong positioned = startbrUserSecFile(browse, state, anchor, key);
        // L286: IF NOT ERR-FLG-ON.
        if (state.errorFlag.isOn() || positioned.isEmpty()) {
            browse.release();
            return;
        }
        long offset = positioned.getAsLong();
        // L288-L290: consume the positioning row for any key that is not enter, PF7 or PF3.
        if (keyAction != KeyAction.ENTER && keyAction != KeyAction.PFK07
                && keyAction != KeyAction.PFK03) {
            offset = readnextUserSecFile(browse, state, offset).isPresent() ? offset + 1 : offset;
        }
        // L292-L296: clear every slot before the page is filled.
        if (!state.eofFlag.isEof() && !state.errorFlag.isOn()) {
            for (int slot = 1; slot <= USER_LIST_PAGE_SIZE; slot++) {
                initializeUserData(state, slot);
            }
        }
        // L298-L306: MOVE 1 TO WS-IDX, then fill upward until the slot count is exceeded.
        int slot = 1;
        while (slot <= USER_LIST_PAGE_SIZE && !state.eofFlag.isEof() && !state.errorFlag.isOn()) {
            final Optional<UserSecurityRepository.AdminEntry> row =
                    readnextUserSecFile(browse, state, offset);
            if (row.isPresent() && !state.errorFlag.isOn()) {
                populateUserData(state, slot, row.get());
                offset++;
                slot++;
            }
        }
        // L308-L323: one read past the page decides whether a further page follows.
        if (!state.eofFlag.isEof() && !state.errorFlag.isOn()) {
            state.pageNumber++;
            final Optional<UserSecurityRepository.AdminEntry> beyond =
                    readnextUserSecFile(browse, state, offset);
            state.nextPageFlag = beyond.isPresent() && !state.errorFlag.isOn()
                    ? NextPageFlag.YES
                    : NextPageFlag.NO;
        } else {
            state.nextPageFlag = NextPageFlag.NO;
            if (slot > 1) {
                state.pageNumber++;
            }
        }
        // L325: PERFORM ENDBR-USER-SEC-FILE.
        endbrUserSecFile(browse);
    }

    /**
     * Fills the page by walking the key sequence backward.
     * <strong>{@code PROCESS-PAGE-BACKWARD}, {@code app/cbl/COUSR00C.cbl} L336.</strong>
     *
     * <p>The mirror of the forward walk, with the one difference that carries the whole behavioural
     * contract: L352 seeds the slot number at ten and L354-L360 <strong>decrements</strong> it, so
     * rows arriving in descending key order are placed into descending slots and the assembled page
     * is ascending. That is why the response needs no separate reversing step - reading the slots in
     * slot order <em>is</em> the reversal of the read order.
     *
     * <p>L342-L344 consumes one row before the loop for any key other than enter or the forward
     * pager, positioning before the first row of the page being left. L362-L372 reads one row
     * further back and decrements the counter, flooring it at one.
     *
     * @param browse    the browse to walk
     * @param state     the turn being assembled
     * @param anchor    where to position
     * @param key       the key to position on when the anchor is a key
     * @param keyAction the attention key that led here, which selects the pre-loop read
     */
    private void processPageBackward(final SequentialBrowse browse,
                                     final TurnState state,
                                     final BrowseAnchor anchor,
                                     final String key,
                                     final KeyAction keyAction) {
        state.pageDirection = PageMetadata.PagingDirection.BACKWARD;
        // L338: PERFORM STARTBR-USER-SEC-FILE.
        final OptionalLong positioned = startbrUserSecFile(browse, state, anchor, key);
        // L340: IF NOT ERR-FLG-ON.
        if (state.errorFlag.isOn() || positioned.isEmpty()) {
            browse.release();
            return;
        }
        long offset = positioned.getAsLong();
        // L342-L344: consume the positioning row for any key that is not enter or PF8.
        if (keyAction != KeyAction.ENTER && keyAction != KeyAction.PFK08) {
            offset = readprevUserSecFile(browse, state, offset).isPresent() ? offset - 1 : offset;
        }
        // L346-L350: clear every slot before the page is filled.
        if (!state.eofFlag.isEof() && !state.errorFlag.isOn()) {
            for (int slot = 1; slot <= USER_LIST_PAGE_SIZE; slot++) {
                initializeUserData(state, slot);
            }
        }
        // L352-L360: MOVE 10 TO WS-IDX, then fill downward until the slot number falls below one.
        int slot = USER_LIST_PAGE_SIZE;
        while (slot >= 1 && !state.eofFlag.isEof() && !state.errorFlag.isOn()) {
            final Optional<UserSecurityRepository.AdminEntry> row =
                    readprevUserSecFile(browse, state, offset);
            if (row.isPresent() && !state.errorFlag.isOn()) {
                populateUserData(state, slot, row.get());
                offset--;
                slot--;
            }
        }
        // L362-L372: one read further back decides the counter, which is floored at one.
        if (!state.eofFlag.isEof() && !state.errorFlag.isOn()) {
            final Optional<UserSecurityRepository.AdminEntry> beyond =
                    readprevUserSecFile(browse, state, offset);
            if (state.nextPageFlag.isYes()) {
                final boolean furtherPageExists =
                        beyond.isPresent() && !state.errorFlag.isOn() && state.pageNumber > 1;
                state.pageNumber = furtherPageExists ? state.pageNumber - 1 : 1;
            }
        }
        // L374: PERFORM ENDBR-USER-SEC-FILE.
        endbrUserSecFile(browse);
    }

    /**
     * Moves one row into one numbered screen slot.
     * <strong>{@code POPULATE-USER-DATA}, {@code app/cbl/COUSR00C.cbl} L384.</strong>
     *
     * <p>The paragraph is an {@code EVALUATE} over the slot number with ten arms and a
     * {@code WHEN OTHER} that continues. Eight of the ten arms are positionally identical - each
     * moves the same four record fields into the items of its own row - so they are reproduced as one
     * positional assignment rather than as eight copies of one statement. <strong>Two arms are
     * not.</strong> The first arm additionally retains the identifier as the page's first key at
     * L389, and the tenth additionally retains it as the page's last key at L435; both are
     * reproduced explicitly, because those two assignments are what the paging keys later position
     * on. The out-of-range arm continues without effect, as at L439-L440.
     *
     * @param state the turn being assembled
     * @param slot  the one-based slot number
     * @param row   the projected row, which carries no credential
     */
    private void populateUserData(final TurnState state,
                                  final int slot,
                                  final UserSecurityRepository.AdminEntry row) {
        if (slot < 1 || slot > USER_LIST_PAGE_SIZE) {
            // L439-L440: WHEN OTHER CONTINUE.
            return;
        }
        state.rowSlots[slot - 1] = new UserResponse.UserRow(
                null, row.getSecUsrId(), row.getSecUsrFname(), row.getSecUsrLname(),
                row.getSecUsrType());
        if (slot == 1) {
            // L389: the first slot also retains the page's first key.
            state.firstUserIdOnPage = row.getSecUsrId();
        }
        if (slot == USER_LIST_PAGE_SIZE) {
            // L435: the last slot also retains the page's last key.
            state.lastUserIdOnPage = row.getSecUsrId();
        }
    }

    /**
     * Blanks one numbered screen slot.
     * <strong>{@code INITIALIZE-USER-DATA}, {@code app/cbl/COUSR00C.cbl} L446.</strong>
     *
     * <p>The counterpart of the filling paragraph and, like it, an {@code EVALUATE} over the slot
     * number whose ten arms are positionally identical - each moves spaces into the four items of its
     * own row - with a {@code WHEN OTHER} that continues at L499-L500. Reproduced as one positional
     * clearing rather than as ten copies. The clearing is real work and not a formality: a page that
     * does not fill every slot must leave the remainder blank, which is how a short backward page
     * ends up presenting only the rows that exist.
     *
     * @param state the turn being assembled
     * @param slot  the one-based slot number
     */
    private void initializeUserData(final TurnState state, final int slot) {
        if (slot < 1 || slot > USER_LIST_PAGE_SIZE) {
            // L499-L500: WHEN OTHER CONTINUE.
            return;
        }
        state.rowSlots[slot - 1] = null;
    }

    /**
     * Assembles the list screen.
     * <strong>{@code SEND-USRLST-SCREEN}, {@code app/cbl/COUSR00C.cbl} L522.</strong>
     *
     * <p>L524 populates the header, L526 moves the message onto the error line, and L528-L544
     * chooses between the erasing and non-erasing send verbs. Paging metadata is attached only when
     * this turn actually walked the key sequence, because the paging contract admits no default
     * direction and a transfer or a refused paging key walked nothing.
     *
     * @param state the assembled turn
     * @return the response for the client to render
     */
    private UserResponse sendUsrlstScreen(final TurnState state) {
        populateHeaderInfo(state, LIST_TRANSACTION_ID, LIST_PROGRAM_NAME);
        final List<UserResponse.UserRow> rows = state.populatedRows();
        PageMetadata pageMetadata = null;
        if (state.pageDirection != null) {
            final String displayedPageNumber = CobolStringUtils.rightJustifyZeroFill(
                    Integer.toString(Math.max(state.pageNumber, 0)),
                    PageMetadata.DISPLAYED_PAGE_NUMBER_MAX_LENGTH);
            pageMetadata = state.pageDirection == PageMetadata.PagingDirection.BACKWARD
                    ? PageMetadata.backward(rows.size(), state.firstUserIdOnPage,
                            state.lastUserIdOnPage, state.nextPageFlag.isYes(),
                            state.pageNumber > 1, displayedPageNumber)
                    : PageMetadata.forward(rows.size(), state.firstUserIdOnPage,
                            state.lastUserIdOnPage, state.nextPageFlag.isYes(),
                            state.pageNumber > 1, displayedPageNumber);
        }
        // L528-L544: IF SEND-ERASE-YES ... ELSE ... END-IF, the one erase branch in the four
        // members. Both arms send the same map data from the same output area and differ only in the
        // 3270 ERASE terminal-control option, which the else arm leaves commented out at L541. That
        // option paints the physical screen rather than shaping the data, so it has no representation
        // in a REST payload and both arms carry identical content; the branch is preserved because it
        // is the source's own control flow and it selects the send form the legacy would have used.
        if (state.sendEraseFlag.isYes()) {
            LOG.debug("User list assembled for an erasing send: transaction={} rows={} pageNumber={}"
                    + " error={}", LIST_TRANSACTION_ID, rows.size(), state.pageNumber,
                    state.errorFlag);
        } else {
            LOG.debug("User list assembled for a non-erasing send: transaction={} rows={}"
                    + " pageNumber={} error={}", LIST_TRANSACTION_ID, rows.size(), state.pageNumber,
                    state.errorFlag);
        }
        return new UserResponse(
                rows,
                pageMetadata,
                state.userId,
                state.firstName,
                state.lastName,
                state.userType,
                state.transactionName,
                state.title01,
                state.currentDate,
                state.programName,
                state.title02,
                state.currentTime,
                state.message,
                List.copyOf(state.fieldErrors),
                state.errorFlag.isOn(),
                state.actionSucceeded,
                state.focusFieldId,
                state.route == null ? null : state.route.getRouteValue(),
                state.context);
    }

    /**
     * Takes the submitted list screen.
     * <strong>{@code RECEIVE-USRLST-SCREEN}, {@code app/cbl/COUSR00C.cbl} L549.</strong>
     *
     * <p>The receive command copies the terminal's input into the symbolic map; its counterpart here
     * copies the submitted identifier field into the turn's state, so that the later paragraphs read
     * the operator's own value rather than the request object. The row selections and the retained
     * page anchors are read from the request at the point each is needed, exactly as the source reads
     * its map items where it needs them.
     *
     * @param request the submitted screen
     * @param state   the turn being assembled
     */
    private void receiveUsrlstScreen(final UserRequest request, final TurnState state) {
        state.userId = request.searchUserId();
    }

    /**
     * Positions the browse.
     * <strong>{@code STARTBR-USER-SEC-FILE}, {@code app/cbl/COUSR00C.cbl} L586.</strong>
     *
     * <p>L588-L595 issues the positioning command and L597-L614 evaluates its response in three arms,
     * in the source's clause order. The normal arm continues. The not-found arm marks end of
     * sequence, emits the at-the-top text and re-presents - and notably <strong>does not</strong>
     * raise the error flag, so a caller must test both. The default arm raises the flag and emits the
     * lookup-failure text; the diagnostic the source writes to the operator console becomes a
     * structured log record at warning level carrying no field value.
     *
     * @param browse the browse to position
     * @param state  the turn being assembled
     * @param anchor where to position
     * @param key    the key to position on when the anchor is a key
     * @return the offset positioned at, or an empty result when nothing was positioned
     */
    private OptionalLong startbrUserSecFile(final SequentialBrowse browse,
                                            final TurnState state,
                                            final BrowseAnchor anchor,
                                            final String key) {
        final OptionalLong positioned;
        final BrowseResponse response;
        try {
            positioned = switch (anchor) {
                case LOW_VALUES -> browse.positionAtFirst();
                // No key can be greater than or equal to a high value, so this positions nowhere.
                case HIGH_VALUES -> OptionalLong.empty();
                case KEY -> browse.positionAtOrAfter(key == null ? "" : key);
            };
            response = positioned.isPresent() ? BrowseResponse.NORMAL : BrowseResponse.END_OF_SEQUENCE;
        } catch (final RuntimeException failure) {
            LOG.warn("User list positioning failed: transaction={} anchor={}", LIST_TRANSACTION_ID,
                    anchor, failure);
            applyBrowseResponse(state, BrowseResponse.OTHER, UserResponse.MSG_LIST_AT_TOP);
            return OptionalLong.empty();
        }
        applyBrowseResponse(state, response, UserResponse.MSG_LIST_AT_TOP);
        return positioned;
    }

    /**
     * Reads the next row.
     * <strong>{@code READNEXT-USER-SEC-FILE}, {@code app/cbl/COUSR00C.cbl} L619.</strong>
     *
     * <p>L621-L629 issues the read and L631-L648 evaluates its response in three arms. The
     * end-of-file arm marks end of sequence and emits the reached-the-bottom text without raising the
     * error flag; the default arm raises the flag and emits the lookup-failure text.
     *
     * @param browse the positioned browse
     * @param state  the turn being assembled
     * @param offset the offset to read
     * @return the row read, or an empty result at end of sequence or on failure
     */
    private Optional<UserSecurityRepository.AdminEntry> readnextUserSecFile(
            final SequentialBrowse browse, final TurnState state, final long offset) {
        return readOneRow(browse, state, offset, UserResponse.MSG_LIST_REACHED_BOTTOM);
    }

    /**
     * Reads the previous row.
     * <strong>{@code READPREV-USER-SEC-FILE}, {@code app/cbl/COUSR00C.cbl} L653.</strong>
     *
     * <p>L655-L663 issues the read and L665-L682 evaluates its response in the same three arms as the
     * forward read, differing only in the end-of-sequence text, which is the reached-the-top message.
     * A backward read before the first row is off the front of the sequence and yields the same
     * end-of-sequence outcome that the command's own end-of-file response produces.
     *
     * @param browse the positioned browse
     * @param state  the turn being assembled
     * @param offset the offset to read
     * @return the row read, or an empty result at end of sequence or on failure
     */
    private Optional<UserSecurityRepository.AdminEntry> readprevUserSecFile(
            final SequentialBrowse browse, final TurnState state, final long offset) {
        return readOneRow(browse, state, offset, UserResponse.MSG_LIST_REACHED_TOP);
    }

    /**
     * Releases the browse.
     * <strong>{@code ENDBR-USER-SEC-FILE}, {@code app/cbl/COUSR00C.cbl} L687.</strong>
     *
     * <p>The command at L689-L691 takes no response and cannot fail, and neither can this: it
     * discards the browse's read buffer so that no read position survives the walk.
     *
     * @param browse the browse to release
     */
    private void endbrUserSecFile(final SequentialBrowse browse) {
        browse.release();
    }

    // ----------------------------------------------------------------------------------------------
    // List-screen support: selection, anchoring and browse-response handling
    // ----------------------------------------------------------------------------------------------

    /**
     * Finds the first marked row position, reproducing the ten-arm selection scan at
     * {@code app/cbl/COUSR00C.cbl} L151-L185.
     *
     * <p>The source is an {@code EVALUATE TRUE} whose ten conditions test the ten row items in row
     * order, and {@code EVALUATE} stops at its first true condition, so a submission marking several
     * rows is treated as marking the earliest of them and the rest are never examined. That ordering
     * is the behaviour, not an implementation detail, so the scan here is an ordered walk that
     * returns on its first hit rather than a filter over the whole collection.
     *
     * <p>The collection is <strong>positional</strong>: element <em>n</em> is the marker typed against
     * screen row <em>n</em>, so an empty position is meaning and must survive rather than be
     * compacted away. Positions beyond the screen's row count are ignored, because the list map
     * declares no eleventh selection item. The final arm at L182-L184, which clears both the marker
     * and the selected identifier, is represented by returning no position.
     *
     * @param rowSelections the positional markers as submitted; never {@code null}
     * @return the one-based position of the first marked row, or zero when no row is marked
     */
    private int firstSelectedRowPosition(final List<String> rowSelections) {
        final int examined = Math.min(rowSelections.size(), USER_LIST_PAGE_SIZE);
        for (int position = 1; position <= examined; position++) {
            if (!isBlank(rowSelections.get(position - 1))) {
                return position;
            }
        }
        return 0;
    }

    /**
     * Recovers the identifier displayed at one row of the page the operator was looking at.
     *
     * <p>The legacy reads this straight out of the echoed map, which holds the identifier the screen
     * displayed in that row. The request contract carries no per-row identifier echo - deliberately,
     * because an identifier a client supplies is an identifier a client can substitute - so the row
     * is recovered by re-reading the page the operator was on and taking it by position. The page is
     * the one anchored on its retained first identifier, which is the same anchor the backward pager
     * uses, so the row recovered is the row displayed.
     *
     * @param browse      the browse to read through
     * @param firstOnPage the retained first identifier of the page, or blank on a first entry
     * @param position    the one-based row position that was marked
     * @return the identifier at that position, or an empty result when the page no longer has such a
     *         row - which is the source's condition at L188 of a blank selected identifier
     */
    private Optional<String> selectedUserIdAtPosition(final SequentialBrowse browse,
                                                      final TurnState state,
                                                      final String firstOnPage,
                                                      final int position) {
        final BrowseAnchor anchor = isBlank(firstOnPage) ? BrowseAnchor.LOW_VALUES : BrowseAnchor.KEY;
        final OptionalLong pageStart = probeAnchor(browse, state, anchor, firstOnPage);
        if (pageStart.isEmpty()) {
            return Optional.empty();
        }
        return probeRowAt(browse, state, pageStart.getAsLong() + position - 1L)
                .map(UserSecurityRepository.AdminEntry::getSecUsrId);
    }

    /**
     * Positions a browse for one of the probes the paging and selection paragraphs make, reporting a
     * refusal from the store through the same arm the browse verbs themselves use.
     *
     * <p>Three paragraphs position a browse to learn something before they commit to walking a page:
     * L248 derives the page counter from the anchor of the page being left, L270 asks whether a
     * further row follows it, and the selection scan at L151-L185 resolves a marked slot to the
     * identifier standing in it. None of those is a legacy path that can raise. Every browse arm in
     * the source writes a message and re-presents the screen, so a store that refuses one of these
     * probes must take the catch-all arm here as well rather than propagating and abandoning the turn
     * with no screen to show. The end-of-sequence text passed along is the already-at-top text, which
     * the catch-all arm does not use; it uses the lookup-failure text, exactly as the browse verbs do.
     *
     * @param browse the browse to position
     * @param state  the turn being assembled
     * @param anchor where to position
     * @param key    the key to position on when the anchor is a key
     * @return the offset positioned at, or an empty result when nothing was positioned or the store
     *         refused the probe
     */
    private OptionalLong probeAnchor(final SequentialBrowse browse,
                                     final TurnState state,
                                     final BrowseAnchor anchor,
                                     final String key) {
        try {
            return locateAnchor(browse, anchor, key);
        } catch (final RuntimeException failure) {
            LOG.warn("User list anchor probe failed: transaction={} anchor={}", LIST_TRANSACTION_ID,
                    anchor, failure);
            applyBrowseResponse(state, BrowseResponse.OTHER, UserResponse.MSG_LIST_AT_TOP);
            return OptionalLong.empty();
        }
    }

    /**
     * Reads one row at a known offset for the same three probes, under the same arm.
     *
     * @param browse the positioned browse
     * @param state  the turn being assembled
     * @param offset the offset to read
     * @return the row at that offset, or an empty result at the end of the sequence or when the store
     *         refused the probe
     */
    private Optional<UserSecurityRepository.AdminEntry> probeRowAt(final SequentialBrowse browse,
                                                                  final TurnState state,
                                                                  final long offset) {
        try {
            return browse.rowAt(offset);
        } catch (final RuntimeException failure) {
            LOG.warn("User list row probe failed: transaction={} offset={}", LIST_TRANSACTION_ID,
                    offset, failure);
            applyBrowseResponse(state, BrowseResponse.OTHER, UserResponse.MSG_LIST_AT_TOP);
            return Optional.empty();
        }
    }

    /**
     * Acts on an accepted row marker, reproducing the dispatch at {@code app/cbl/COUSR00C.cbl}
     * L189-L215.
     *
     * <p>An {@code EVALUATE} over the marker with three arms, in the source's clause order: the
     * update markers at L190-L191, the delete markers at L200-L201 and the default arm at L210. Each
     * accepted arm records the originating transaction and program, zeroes the program context,
     * carries the selected identifier forward and transfers - which is what a resolved route in the
     * response amounts to. Both cases of each marker are accepted because the source lists both, and
     * no case fold is applied for the same reason: folding would also accept values the source does
     * not.
     *
     * @param state          the turn being assembled
     * @param marker         the submitted marker
     * @param selectedUserId the identifier of the marked row
     * @return {@code true} when the marker was accepted and control transferred, {@code false} for
     *         the default arm, which leaves the caller to emit the invalid-selection text and rebuild
     *         the page
     */
    private boolean dispatchSelection(final TurnState state,
                                      final String marker,
                                      final String selectedUserId) {
        final NavigationService.Route destination;
        if (SELECTION_UPDATE_UPPER.equals(marker) || SELECTION_UPDATE_LOWER.equals(marker)) {
            destination = NavigationService.Route.USER_UPDATE;
        } else if (SELECTION_DELETE_UPPER.equals(marker) || SELECTION_DELETE_LOWER.equals(marker)) {
            destination = NavigationService.Route.USER_DELETE;
        } else {
            return false;
        }
        state.context = withRouting(state.context, LIST_TRANSACTION_ID, LIST_PROGRAM_NAME,
                destination.getLegacyTransactionId(), destination.getLegacyProgramName(),
                NavigationContext.ProgramContext.ENTER);
        state.route = destination;
        state.userId = selectedUserId;
        LOG.debug("User list dispatching a row selection: transaction={} destination={}",
                LIST_TRANSACTION_ID, destination.getRouteValue());
        return true;
    }

    /**
     * Positions the browse for an anchor without touching the turn's flags.
     *
     * <p>Separate from the positioning paragraph because two callers need the position in order to
     * work out <em>which</em> page they are on before deciding whether to walk at all, and neither
     * may emit a message or set a flag while doing so. The positioning paragraph is what reports;
     * this only locates.
     *
     * @param browse the browse to position
     * @param anchor where to position
     * @param key    the key to position on when the anchor is a key
     * @return the offset positioned at, or an empty result when nothing was positioned
     */
    private OptionalLong locateAnchor(final SequentialBrowse browse,
                                      final BrowseAnchor anchor,
                                      final String key) {
        return switch (anchor) {
            case LOW_VALUES -> browse.positionAtFirst();
            case HIGH_VALUES -> OptionalLong.empty();
            case KEY -> browse.positionAtOrAfter(key == null ? "" : key);
        };
    }

    /**
     * Derives the page counter from a position in the key sequence.
     *
     * <p>The legacy carries {@code CDEMO-CU00-PAGE-NUM} across the conversation and adjusts it at
     * L309, L320 and L367. The navigation contract does not carry it, and the displayed page number
     * the request echoes is deliberately never read - it is a value the program only ever writes, so
     * a submitted one could not have influenced a page. The counter is therefore <strong>computed
     * from the position of the page's own anchor</strong>, which is a stronger arrangement than the
     * one it replaces: it cannot drift, and it cannot be steered by a client.
     *
     * @param offset the offset of the page's anchor row
     * @return the one-based page number, or zero when the sequence held no such row
     */
    private int pageNumberOf(final OptionalLong offset) {
        if (offset.isEmpty()) {
            return 0;
        }
        return (int) (offset.getAsLong() / USER_LIST_PAGE_SIZE) + 1;
    }

    /**
     * Reads one row and reports the outcome exactly as the two read paragraphs do.
     *
     * <p>Both read paragraphs have the same three-arm shape and differ only in their
     * end-of-sequence text, so the shared body takes that text as a parameter. A failure from the
     * store is the catch-all arm: it is logged with no field value and reported as the lookup-failure
     * text, which is the paragraph's own default behaviour.
     *
     * @param browse               the positioned browse
     * @param state                the turn being assembled
     * @param offset               the offset to read
     * @param endOfSequenceMessage the text the end-of-sequence arm emits
     * @return the row read, or an empty result at end of sequence or on failure
     */
    private Optional<UserSecurityRepository.AdminEntry> readOneRow(
            final SequentialBrowse browse,
            final TurnState state,
            final long offset,
            final String endOfSequenceMessage) {
        final Optional<UserSecurityRepository.AdminEntry> row;
        try {
            row = browse.rowAt(offset);
        } catch (final RuntimeException failure) {
            LOG.warn("User list read failed: transaction={}", LIST_TRANSACTION_ID, failure);
            applyBrowseResponse(state, BrowseResponse.OTHER, endOfSequenceMessage);
            return Optional.empty();
        }
        applyBrowseResponse(state,
                row.isPresent() ? BrowseResponse.NORMAL : BrowseResponse.END_OF_SEQUENCE,
                endOfSequenceMessage);
        return row;
    }

    /**
     * Applies the three-arm response evaluation the browse paragraphs share.
     *
     * <p>The arms are in the source's clause order and the catch-all is the default arm, matching
     * {@code app/cbl/COUSR00C.cbl} L597-L614, L631-L648 and L665-L682. The distinction the arms draw
     * is load-bearing: the end-of-sequence arm emits text and marks end of sequence but
     * <strong>leaves the error flag clear</strong>, while only the default arm raises it, so a caller
     * that tested one flag alone would misread one of the two outcomes.
     *
     * @param state                the turn being assembled
     * @param response             the arm to apply
     * @param endOfSequenceMessage the text the end-of-sequence arm emits
     */
    private void applyBrowseResponse(final TurnState state,
                                     final BrowseResponse response,
                                     final String endOfSequenceMessage) {
        switch (response) {
            case NORMAL -> LOG.trace("User list read returned a row: transaction={}",
                    LIST_TRANSACTION_ID);
            case END_OF_SEQUENCE -> {
                state.eofFlag = EofFlag.EOF;
                state.message = endOfSequenceMessage;
                state.focusFieldId = FIELD_LIST_USER_ID;
            }
            case OTHER -> {
                state.errorFlag = ErrorFlag.ON;
                state.message = UserResponse.MSG_LIST_UNABLE_TO_LOOKUP_USER;
                state.focusFieldId = FIELD_LIST_USER_ID;
            }
        }
    }

    // ==============================================================================================
    // CU01 - add a user. app/cbl/COUSR01C.cbl, 299 lines, 9 paragraphs
    // ==============================================================================================

    /**
     * One turn of transaction {@code CU01}, adding a user.
     * <strong>{@code MAIN-PARA}, {@code app/cbl/COUSR01C.cbl} L71.</strong>
     *
     * <p>L73 clears the error flag and L75 the message. A turn with no prior state transfers to
     * sign-on at L78-L80. A first entry marks re-entry, blanks the output map, places the cursor on
     * the given-name field and sends at L83-L87 <strong>without validating anything</strong>, which is
     * why a field error can only ever appear on a later submission. Any later entry receives the
     * screen and evaluates the attention key at L90-L103 in the source's clause order: the enter key
     * validates and writes, the exit key nominates the administrative menu, the fourth key clears the
     * screen, and the default arm emits the common unmapped-key text.
     *
     * @param request the submitted screen
     * @return the message, field errors and route for the client to render
     * @throws NullPointerException if {@code request} is {@code null}
     */
    @Transactional
    public UserResponse addUser(final UserRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        final TurnState state = new TurnState();
        // L73: SET ERR-FLG-OFF TO TRUE.
        state.errorFlag = ErrorFlag.OFF;
        // L75-L76: MOVE SPACES TO WS-MESSAGE and to the error line of the output map.
        state.message = null;

        final NavigationContext inbound = request.navigationContext();
        // L78-L80: a zero-length communication area nominates sign-on and transfers.
        if (isNavigationStateAbsent(inbound)) {
            state.context = NavigationContext.empty();
            LOG.debug("User add entered with no prior navigation state: transaction={}",
                    ADD_TRANSACTION_ID);
            returnToPrevScreen(state, SIGN_ON_PROGRAM_NAME, ADD_TRANSACTION_ID, ADD_PROGRAM_NAME,
                    NavigationService.Route.SIGN_ON);
            return sendUsraddScreen(state);
        }
        state.context = inbound;
        // L83-L87: the first entry blanks the map and places the cursor on the given-name field.
        if (inbound.firstEntry()) {
            state.context = inbound.withReEntry();
            state.focusFieldId = FIELD_FIRST_NAME;
            return sendUsraddScreen(state);
        }
        // L89: RECEIVE the submitted map before the key is evaluated.
        receiveUsraddScreen(request, state);
        // L90-L103: EVALUATE EIBAID, clause order preserved, WHEN OTHER as the default arm.
        final KeyAction keyAction = request.keyAction();
        if (keyAction == KeyAction.ENTER) {
            processAddEnterKey(state);
            return sendUsraddScreen(state);
        }
        if (keyAction == KeyAction.PFK03) {
            returnToPrevScreen(state, ADMIN_MENU_PROGRAM_NAME, ADD_TRANSACTION_ID, ADD_PROGRAM_NAME,
                    NavigationService.Route.ADMIN_MENU);
            return sendUsraddScreen(state);
        }
        if (keyAction == KeyAction.PFK04) {
            clearAddScreen(state);
            return sendUsraddScreen(state);
        }
        // L98-L102: the default arm.
        state.errorFlag = ErrorFlag.ON;
        state.focusFieldId = FIELD_FIRST_NAME;
        state.message = messageCatalogService.invalidKeyMessage();
        LOG.debug("User add received an unmapped attention key: transaction={} keyAction={}",
                ADD_TRANSACTION_ID, keyAction);
        return sendUsraddScreen(state);
    }

    /**
     * Validates the submitted add screen and writes the record.
     * <strong>{@code PROCESS-ENTER-KEY}, {@code app/cbl/COUSR01C.cbl} L115.</strong>
     *
     * <p>L117-L151 is an {@code EVALUATE TRUE} whose five conditions test the five items for
     * emptiness <strong>in this order</strong>: given name, family name, identifier, credential, type.
     * {@code EVALUATE} stops at its first true condition, so the summary text and the cursor position
     * belong to the <strong>first</strong> empty item and later ones contribute neither. The
     * per-field error entries are independent of that and are raised for <em>every</em> empty item, so
     * a client can decorate all of them at once; the summary and the focus remain the first, exactly
     * as the screen showed. The final arm at L148-L150 places the cursor on the given-name field and
     * continues.
     *
     * <p>L153-L159 then moves the five items into the record and writes it, but only when the error
     * flag is clear.
     *
     * <p><strong>No edit beyond emptiness exists here and none is added.</strong> The source applies
     * no length rule, no character-class rule, no permitted-value list for the type and no rule at all
     * on the credential beyond its being non-blank. A given name of {@code MARY ANN} is therefore
     * accepted, as it must be: the estate's alphabetic edit - which blanks letters and then tests the
     * remainder, so that embedded spaces pass - is not applied on this screen at all, and inventing
     * any edit here would reject input the legacy stores.
     *
     * @param state the turn being assembled, already carrying the received field values
     */
    private void processAddEnterKey(final TurnState state) {
        // L118-L123: WHEN FNAMEI = SPACES OR LOW-VALUES.
        if (isBlank(state.firstName)) {
            raiseFieldError(state, PROPERTY_FIRST_NAME, FIELD_FIRST_NAME,
                    UserResponse.MSG_ADD_FIRST_NAME_EMPTY);
        }
        // L124-L129: WHEN LNAMEI = SPACES OR LOW-VALUES.
        if (isBlank(state.lastName)) {
            raiseFieldError(state, PROPERTY_LAST_NAME, FIELD_LAST_NAME,
                    UserResponse.MSG_ADD_LAST_NAME_EMPTY);
        }
        // L130-L135: WHEN USERIDI = SPACES OR LOW-VALUES.
        if (isBlank(state.userId)) {
            raiseFieldError(state, PROPERTY_USER_ID, FIELD_ADD_USER_ID,
                    UserResponse.MSG_ADD_USER_ID_EMPTY);
        }
        // L136-L141: WHEN PASSWDI = SPACES OR LOW-VALUES. The submitted credential is inspected only
        // for emptiness; its value is neither retained on the turn nor placed in any error entry.
        if (isBlank(state.submittedCredential)) {
            raiseFieldError(state, PROPERTY_PASSWORD, FIELD_PASSWORD,
                    UserResponse.MSG_ADD_CREDENTIAL_FIELD_EMPTY);
        }
        // L142-L147: WHEN USRTYPEI = SPACES OR LOW-VALUES.
        if (isBlank(state.userType)) {
            raiseFieldError(state, PROPERTY_USER_TYPE, FIELD_USER_TYPE,
                    UserResponse.MSG_ADD_USER_TYPE_EMPTY);
        }
        // L148-L150: WHEN OTHER places the cursor on the given-name field and continues.
        if (!state.errorFlag.isOn()) {
            state.focusFieldId = FIELD_FIRST_NAME;
        }
        // L153-L159: IF NOT ERR-FLG-ON, move the five items into the record and write it.
        if (!state.errorFlag.isOn()) {
            writeUserSecFile(state);
        }
    }

    /**
     * Assembles the add screen.
     * <strong>{@code SEND-USRADD-SCREEN}, {@code app/cbl/COUSR01C.cbl} L184.</strong>
     *
     * <p>L186 populates the header and L188 moves the message onto the error line. This screen has a
     * single send verb, always erasing, so it carries no erase flag. No component of the response
     * carries a credential: the submitted value is not echoed and the stored digest is never read.
     *
     * @param state the assembled turn
     * @return the response for the client to render
     */
    private UserResponse sendUsraddScreen(final TurnState state) {
        populateHeaderInfo(state, ADD_TRANSACTION_ID, ADD_PROGRAM_NAME);
        return buildRecordScreen(state);
    }

    /**
     * Takes the submitted add screen.
     * <strong>{@code RECEIVE-USRADD-SCREEN}, {@code app/cbl/COUSR01C.cbl} L201.</strong>
     *
     * <p>The counterpart of the receive command at L203-L209: it copies the submitted items into the
     * turn's state so the validating paragraph reads the operator's own values. The credential is
     * copied to a field the response never reads, which is what keeps it inbound-only.
     *
     * @param request the submitted screen
     * @param state   the turn being assembled
     */
    private void receiveUsraddScreen(final UserRequest request, final TurnState state) {
        state.userId = request.userId();
        state.firstName = request.firstName();
        state.lastName = request.lastName();
        state.userType = request.userType();
        state.submittedCredential = request.password();
    }

    /**
     * Writes the new record.
     * <strong>{@code WRITE-USER-SEC-FILE}, {@code app/cbl/COUSR01C.cbl} L238.</strong>
     *
     * <p>L240-L248 issues the write and L250-L274 evaluates its response in three arms, in the
     * source's clause order. The normal arm clears the fields, then builds the success text by
     * concatenating a fixed prefix, the identifier <em>up to its first space</em> and a fixed suffix,
     * reproducing the concatenation statement at L255-L258. The duplicate arm folds the two duplicate
     * responses the source folds at L260-L261, emits the already-exists text and places the cursor on
     * the identifier field. The default arm emits the unable-to-add text and places the cursor on the
     * given-name field.
     *
     * <p><strong>The credential is hashed here and only here on this path.</strong> The submitted
     * value is passed to the encoder and the resulting digest is what the record carries; the
     * submitted value itself is never stored, returned or logged. The entity refuses any value that is
     * not structurally a digest, so an encoder that failed to produce one would be rejected at
     * construction - and that rejection is reported through the default arm rather than escaping,
     * because the source's default arm is what covers a failed write.
     *
     * <p>The duplicate test is a read before the write, because the repository publishes no
     * existence probe and a write that returns a duplicate response has no counterpart in a merge:
     * the persistence provider would silently update the row instead of refusing it, which would turn
     * an add of an existing identifier into an unreported overwrite. The read result is
     * <strong>only</strong> tested for presence and is never dereferenced, so no digest is brought
     * into a variable.
     *
     * @param state the turn being assembled
     */
    private void writeUserSecFile(final TurnState state) {
        final RecordResponse response;
        UserSecurity stored = null;
        try {
            if (userSecurityRepository.findById(state.userId).isPresent()) {
                response = RecordResponse.DUPLICATE;
            } else {
                stored = userSecurityRepository.save(new UserSecurity(
                        state.userId,
                        state.firstName,
                        state.lastName,
                        passwordEncoder.encode(state.submittedCredential),
                        state.userType));
                response = RecordResponse.NORMAL;
            }
        } catch (final RuntimeException failure) {
            LOG.warn("User add write failed: transaction={}", ADD_TRANSACTION_ID, failure);
            state.errorFlag = ErrorFlag.ON;
            state.message = UserResponse.MSG_ADD_UNABLE_TO_ADD_USER;
            state.focusFieldId = FIELD_FIRST_NAME;
            return;
        }
        switch (response) {
            case NORMAL -> {
                // L252-L259: clear the fields, then build the success text from the stored identifier.
                final String storedUserId = stored.getSecUsrId();
                initializeAddFields(state);
                state.actionSucceeded = true;
                state.message = UserResponse.MSG_ADD_SUCCESS_PREFIX
                        + delimitedBySpace(storedUserId)
                        + UserResponse.MSG_ADD_SUCCESS_SUFFIX;
                LOG.info("User added: transaction={} roleClass={}", ADD_TRANSACTION_ID,
                        resolvedRoleClass(stored.getSecUsrType()));
            }
            case DUPLICATE -> {
                // L262-L266: the two duplicate responses share one arm.
                state.errorFlag = ErrorFlag.ON;
                state.message = UserResponse.MSG_ADD_USER_ID_ALREADY_EXIST;
                state.focusFieldId = FIELD_ADD_USER_ID;
                raiseFieldErrorState(state, PROPERTY_USER_ID, FIELD_ADD_USER_ID,
                        ErrorResponse.FieldState.INVALID, UserResponse.MSG_ADD_USER_ID_ALREADY_EXIST);
            }
            case NOT_FOUND, OTHER -> {
                // L267-L273: the default arm. The not-found response cannot arise on a write, so it
                // shares the default arm rather than inventing an outcome the source cannot produce.
                state.errorFlag = ErrorFlag.ON;
                state.message = UserResponse.MSG_ADD_UNABLE_TO_ADD_USER;
                state.focusFieldId = FIELD_FIRST_NAME;
            }
        }
    }

    /**
     * Clears the add screen.
     * <strong>{@code CLEAR-CURRENT-SCREEN}, {@code app/cbl/COUSR01C.cbl} L279.</strong>
     *
     * <p>L281-L282 clears the fields and re-presents. The re-presentation is the caller's, so this
     * performs the clearing half; keeping the two halves apart is what lets the key evaluation return
     * one assembled screen per arm.
     *
     * @param state the turn being assembled
     */
    private void clearAddScreen(final TurnState state) {
        initializeAddFields(state);
    }

    /**
     * Blanks every field of the add screen.
     * <strong>{@code INITIALIZE-ALL-FIELDS}, {@code app/cbl/COUSR01C.cbl} L287.</strong>
     *
     * <p>L289 places the cursor on the given-name field and L290-L295 moves spaces into the
     * identifier, the given name, the family name, the credential, the type and the message. The
     * submitted credential is cleared with them, so it does not survive the paragraph that clears the
     * screen it arrived on. Note the field set: this screen clears its identifier item, which is why
     * this paragraph cannot be shared with the update or delete screens - one of those clears a
     * different item set and the other has no credential item at all.
     *
     * @param state the turn being assembled
     */
    private void initializeAddFields(final TurnState state) {
        state.focusFieldId = FIELD_FIRST_NAME;
        state.userId = null;
        state.firstName = null;
        state.lastName = null;
        state.submittedCredential = null;
        state.userType = null;
        state.message = null;
    }

    // ==============================================================================================
    // CU02 - update a user. app/cbl/COUSR02C.cbl, 414 lines, 11 paragraphs
    // ==============================================================================================

    /**
     * One turn of transaction {@code CU02}, updating a user.
     * <strong>{@code MAIN-PARA}, {@code app/cbl/COUSR02C.cbl} L82.</strong>
     *
     * <p>L84-L85 clears the error and modified flags and L87 the message. A turn with no prior state
     * transfers to sign-on at L90-L92. A first entry marks re-entry and, when the list screen handed
     * over a selected identifier at L99-L104, loads that identity straight away before sending. Any
     * later entry receives the screen and evaluates the attention key at L108-L131 in the source's
     * clause order, which has five named arms and a default: the enter key loads the identity, the
     * exit key <strong>saves first and then returns</strong> - a genuine ordering that a reader would
     * not guess - the fourth key clears the screen, the fifth saves, the twelfth returns to the
     * administrative menu without saving, and the default arm emits the common unmapped-key text.
     *
     * @param request the submitted screen
     * @return the loaded or saved identity, the message, the field errors and the route
     * @throws NullPointerException if {@code request} is {@code null}
     */
    @Transactional
    public UserResponse updateUser(final UserRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        final TurnState state = new TurnState();
        // L84-L85: SET ERR-FLG-OFF and USR-MODIFIED-NO TO TRUE.
        state.errorFlag = ErrorFlag.OFF;
        state.modifiedFlag = ModifiedFlag.NO;
        // L87-L88: MOVE SPACES TO WS-MESSAGE and to the error line of the output map.
        state.message = null;

        final NavigationContext inbound = request.navigationContext();
        // L90-L92: a zero-length communication area nominates sign-on and transfers.
        if (isNavigationStateAbsent(inbound)) {
            state.context = NavigationContext.empty();
            LOG.debug("User update entered with no prior navigation state: transaction={}",
                    UPDATE_TRANSACTION_ID);
            returnToPrevScreen(state, SIGN_ON_PROGRAM_NAME, UPDATE_TRANSACTION_ID,
                    UPDATE_PROGRAM_NAME, NavigationService.Route.SIGN_ON);
            return sendUsrupdScreen(state);
        }
        state.context = inbound;
        // L95-L105: the first entry places the cursor on the identifier field and, when the list
        // screen handed over a selection, loads it before sending.
        if (inbound.firstEntry()) {
            state.context = inbound.withReEntry();
            state.focusFieldId = FIELD_LIST_USER_ID;
            final String handedOver = request.userId();
            if (!isBlank(handedOver)) {
                state.userId = handedOver;
                processUpdateEnterKey(state);
            }
            return sendUsrupdScreen(state);
        }
        // L107: RECEIVE the submitted map before the key is evaluated.
        receiveUsrupdScreen(request, state);
        // L108-L131: EVALUATE EIBAID, clause order preserved, WHEN OTHER as the default arm.
        final KeyAction keyAction = request.keyAction();
        if (keyAction == KeyAction.ENTER) {
            processUpdateEnterKey(state);
            return sendUsrupdScreen(state);
        }
        if (keyAction == KeyAction.PFK03) {
            // L112-L119: the save runs first, then the destination is the originating screen when one
            // was recorded and the administrative menu otherwise.
            updateUserInfo(state);
            final NavigationService.Route back = navigationService.resolveBackNavigation(
                    carriedState(state.context), NavigationService.Route.ADMIN_MENU);
            returnToPrevScreen(state, back.getLegacyProgramName(), UPDATE_TRANSACTION_ID,
                    UPDATE_PROGRAM_NAME, back);
            return sendUsrupdScreen(state);
        }
        if (keyAction == KeyAction.PFK04) {
            clearUpdateScreen(state);
            return sendUsrupdScreen(state);
        }
        if (keyAction == KeyAction.PFK05) {
            updateUserInfo(state);
            return sendUsrupdScreen(state);
        }
        if (keyAction == KeyAction.PFK12) {
            returnToPrevScreen(state, ADMIN_MENU_PROGRAM_NAME, UPDATE_TRANSACTION_ID,
                    UPDATE_PROGRAM_NAME, NavigationService.Route.ADMIN_MENU);
            return sendUsrupdScreen(state);
        }
        // L127-L130: the default arm. Note that this screen's default arm, unlike the list and add
        // screens', does not reposition the cursor - it raises the flag and emits the text only.
        state.errorFlag = ErrorFlag.ON;
        state.message = messageCatalogService.invalidKeyMessage();
        LOG.debug("User update received an unmapped attention key: transaction={} keyAction={}",
                UPDATE_TRANSACTION_ID, keyAction);
        return sendUsrupdScreen(state);
    }

    /**
     * Loads the identity named by the identifier field so the operator can edit it.
     * <strong>{@code PROCESS-ENTER-KEY}, {@code app/cbl/COUSR02C.cbl} L143.</strong>
     *
     * <p>L145-L155 tests the identifier for emptiness and L157-L164 blanks the four editable items
     * before the read, so a failed read leaves nothing from a previous identity on the screen.
     * L166-L172 then moves the record's fields onto the screen.
     *
     * <p><strong>One field of that move is deliberately not reproduced.</strong> L169 moves the stored
     * credential into the screen's credential item, which was possible only because the legacy stored
     * eight cleartext characters. The column holds a one-way digest now, so the item is left empty and
     * the operator supplies a credential only when they intend to change one. That is the whole reason
     * an absent credential means "unchanged" on the save path rather than "empty".
     *
     * @param state the turn being assembled, already carrying the identifier
     */
    private void processUpdateEnterKey(final TurnState state) {
        // L146-L151: WHEN USRIDINI = SPACES OR LOW-VALUES.
        if (isBlank(state.userId)) {
            raiseFieldError(state, PROPERTY_USER_ID, FIELD_LIST_USER_ID,
                    UserResponse.MSG_UPDATE_USER_ID_EMPTY);
            return;
        }
        // L152-L154: WHEN OTHER places the cursor on the identifier field and continues.
        state.focusFieldId = FIELD_LIST_USER_ID;
        // L157-L163: blank the editable items, then read.
        state.firstName = null;
        state.lastName = null;
        state.submittedCredential = null;
        state.userType = null;
        final Optional<UserSecurity> found = readUserSecFileForUpdate(state);
        // L166-L172: on a successful read, move the record onto the screen - without the credential.
        if (!state.errorFlag.isOn() && found.isPresent()) {
            final UserSecurity identity = found.get();
            state.firstName = identity.getSecUsrFname();
            state.lastName = identity.getSecUsrLname();
            state.userType = identity.getSecUsrType();
        }
    }

    /**
     * Validates the submitted update screen and saves the record.
     * <strong>{@code UPDATE-USER-INFO}, {@code app/cbl/COUSR02C.cbl} L177.</strong>
     *
     * <p>L179-L213 is an {@code EVALUATE TRUE} whose five conditions test for emptiness <strong>in
     * this order</strong>: identifier, given name, family name, credential, type. The summary text and
     * the cursor belong to the first empty item; the per-field entries are raised for every empty item
     * independently.
     *
     * <p>L215-L234 then reads the record and compares the four editable fields, <strong>in the
     * source's order</strong>, setting the modified flag on the first difference in each. L236-L243
     * saves when anything differs and otherwise emits the please-modify text - which, note, the source
     * emits <em>without</em> raising the error flag, so message and flag are independent here.
     *
     * <p><strong>The credential comparison is the one place this translation must diverge, and it
     * diverges as narrowly as possible.</strong> L227 compares the submitted item against the stored
     * value, which was cleartext. Three cases replace that single comparison, and each maps onto the
     * legacy outcome it corresponds to:</p>
     * <ul>
     *   <li><em>Absent</em> - the operator did not supply a credential. This is the legacy case of an
     *       untouched pre-filled item: the stored digest is carried forward <strong>byte for
     *       byte</strong> and the modified flag is not set. Nothing is re-hashed, because hashing a
     *       digest would produce a digest of a digest and lock the identity out permanently.</li>
     *   <li><em>Blank</em> - the operator emptied the item. This is the emptiness arm at L198, and it
     *       is reported with the legacy text rather than treated as "unchanged".</li>
     *   <li><em>Supplied</em> - the encoder is asked whether the submitted value produced the stored
     *       digest. If it did, the operator re-typed what was already there, which is the legacy's
     *       equal comparison: the digest is carried forward untouched and nothing is marked modified.
     *       If it did not, the credential is genuinely new and is hashed. Verification through the
     *       encoder is the only correct test - two digests of one credential differ, because each
     *       carries its own salt, so comparing digests for equality would report every credential as
     *       changed.</li>
     * </ul>
     *
     * @param state the turn being assembled
     */
    private void updateUserInfo(final TurnState state) {
        // L180-L185: WHEN USRIDINI = SPACES OR LOW-VALUES.
        if (isBlank(state.userId)) {
            raiseFieldError(state, PROPERTY_USER_ID, FIELD_LIST_USER_ID,
                    UserResponse.MSG_UPDATE_USER_ID_EMPTY);
        }
        // L186-L191: WHEN FNAMEI = SPACES OR LOW-VALUES.
        if (isBlank(state.firstName)) {
            raiseFieldError(state, PROPERTY_FIRST_NAME, FIELD_FIRST_NAME,
                    UserResponse.MSG_UPDATE_FIRST_NAME_EMPTY);
        }
        // L192-L197: WHEN LNAMEI = SPACES OR LOW-VALUES.
        if (isBlank(state.lastName)) {
            raiseFieldError(state, PROPERTY_LAST_NAME, FIELD_LAST_NAME,
                    UserResponse.MSG_UPDATE_LAST_NAME_EMPTY);
        }
        // L198-L203: WHEN PASSWDI = SPACES OR LOW-VALUES. An absent item is not an empty one: see the
        // three-case account in this method's documentation.
        if (state.submittedCredential != null && isBlank(state.submittedCredential)) {
            raiseFieldError(state, PROPERTY_PASSWORD, FIELD_PASSWORD,
                    UserResponse.MSG_UPDATE_CREDENTIAL_FIELD_EMPTY);
        }
        // L204-L209: WHEN USRTYPEI = SPACES OR LOW-VALUES.
        if (isBlank(state.userType)) {
            raiseFieldError(state, PROPERTY_USER_TYPE, FIELD_USER_TYPE,
                    UserResponse.MSG_UPDATE_USER_TYPE_EMPTY);
        }
        if (state.errorFlag.isOn()) {
            return;
        }
        // L210-L212: WHEN OTHER places the cursor on the given-name field and continues.
        state.focusFieldId = FIELD_FIRST_NAME;
        // L215-L217: read the record that is about to be rewritten.
        final Optional<UserSecurity> found = readUserSecFileForUpdate(state);
        if (state.errorFlag.isOn() || found.isEmpty()) {
            return;
        }
        final UserSecurity identity = found.get();
        // L219-L222: compare the given name.
        if (!Objects.equals(state.firstName, identity.getSecUsrFname())) {
            identity.setSecUsrFname(state.firstName);
            state.modifiedFlag = ModifiedFlag.YES;
        }
        // L223-L226: compare the family name.
        if (!Objects.equals(state.lastName, identity.getSecUsrLname())) {
            identity.setSecUsrLname(state.lastName);
            state.modifiedFlag = ModifiedFlag.YES;
        }
        // L227-L230: compare the credential, by verification rather than by equality.
        if (isCredentialGenuinelyNew(state.submittedCredential, identity)) {
            identity.replaceCredentialDigest(passwordEncoder.encode(state.submittedCredential));
            state.modifiedFlag = ModifiedFlag.YES;
        }
        // L231-L234: compare the type. The raw code is stored as submitted, with no permitted-value
        // check, because the column has none and the legacy applied none.
        if (!Objects.equals(state.userType, identity.getSecUsrType())) {
            identity.setSecUsrType(state.userType);
            state.modifiedFlag = ModifiedFlag.YES;
        }
        // L236-L243: save when anything differs, otherwise emit the please-modify text.
        if (state.modifiedFlag.isYes()) {
            updateUserSecFile(state, identity);
            return;
        }
        state.message = UserResponse.MSG_UPDATE_NO_CHANGE;
    }

    /**
     * Assembles the update screen.
     * <strong>{@code SEND-USRUPD-SCREEN}, {@code app/cbl/COUSR02C.cbl} L266.</strong>
     *
     * <p>L268 populates the header and L270 moves the message onto the error line. A single, always
     * erasing send verb, so no erase flag. The credential item is absent from the response for the
     * reason given on the loading paragraph.
     *
     * @param state the assembled turn
     * @return the response for the client to render
     */
    private UserResponse sendUsrupdScreen(final TurnState state) {
        populateHeaderInfo(state, UPDATE_TRANSACTION_ID, UPDATE_PROGRAM_NAME);
        return buildRecordScreen(state);
    }

    /**
     * Takes the submitted update screen.
     * <strong>{@code RECEIVE-USRUPD-SCREEN}, {@code app/cbl/COUSR02C.cbl} L283.</strong>
     *
     * <p>The counterpart of the receive command at L285-L291. The identifier this screen maintains is
     * the one the add, update and delete screens share, which is deliberately not the search
     * identifier the list screen uses.
     *
     * @param request the submitted screen
     * @param state   the turn being assembled
     */
    private void receiveUsrupdScreen(final UserRequest request, final TurnState state) {
        state.userId = request.userId();
        state.firstName = request.firstName();
        state.lastName = request.lastName();
        state.userType = request.userType();
        state.submittedCredential = request.password();
    }

    /**
     * Reads the record the update screen works on.
     * <strong>{@code READ-USER-SEC-FILE}, {@code app/cbl/COUSR02C.cbl} L320.</strong>
     *
     * <p>L322-L331 issues a read for update and L333-L353 evaluates its response in three arms, in the
     * source's clause order. The normal arm emits the press-to-save prompt and re-presents - text
     * without an error flag, which is why the two must be treated independently. The not-found arm
     * raises the flag, emits the not-found text and places the cursor on the identifier field; an
     * <strong>empty result is that arm</strong>, which is how a missing identity is reported rather
     * than raised. The default arm emits the lookup-failure text.
     *
     * <p>Not shared with the delete screen's read paragraph even though the two are structurally
     * identical: their prompts differ, and a shared body would have to be told which text to emit,
     * which is the same thing as two paragraphs with clearer names.
     *
     * @param state the turn being assembled
     * @return the identity read, or an empty result on the not-found or failure arms
     */
    private Optional<UserSecurity> readUserSecFileForUpdate(final TurnState state) {
        final Optional<UserSecurity> found;
        try {
            found = userSecurityRepository.findById(state.userId);
        } catch (final RuntimeException failure) {
            LOG.warn("User update read failed: transaction={}", UPDATE_TRANSACTION_ID, failure);
            state.errorFlag = ErrorFlag.ON;
            state.message = UserResponse.MSG_UPDATE_UNABLE_TO_LOOKUP_USER;
            state.focusFieldId = FIELD_FIRST_NAME;
            return Optional.empty();
        }
        if (found.isEmpty()) {
            // L340-L345: the not-found arm.
            state.errorFlag = ErrorFlag.ON;
            state.message = UserResponse.MSG_UPDATE_USER_ID_NOT_FOUND;
            state.focusFieldId = FIELD_LIST_USER_ID;
            raiseFieldErrorState(state, PROPERTY_USER_ID, FIELD_LIST_USER_ID,
                    ErrorResponse.FieldState.INVALID, UserResponse.MSG_UPDATE_USER_ID_NOT_FOUND);
            return Optional.empty();
        }
        // L334-L339: the normal arm emits the prompt and leaves the error flag clear.
        state.message = UserResponse.MSG_UPDATE_PRESS_PF5;
        return found;
    }

    /**
     * Saves the record.
     * <strong>{@code UPDATE-USER-SEC-FILE}, {@code app/cbl/COUSR02C.cbl} L358.</strong>
     *
     * <p>L360-L366 rewrites the record in place and L368-L390 evaluates its response in three arms.
     * The normal arm builds the success text from a fixed prefix, the identifier up to its first space
     * and a fixed suffix, reproducing L372-L375. The not-found arm emits the not-found text and the
     * default arm the unable-to-update text.
     *
     * <p>The rewrite is one save of one instance, which is the record-at-a-time behaviour the legacy
     * verb has. Note what this paragraph does <strong>not</strong> do: it acquires no lock, tests no
     * version and forces no rollback, because the entity carries no version attribute and not one of
     * the four members contains a rollback - the estate's only explicit rollback is in the
     * account-update program.
     *
     * @param state    the turn being assembled
     * @param identity the identity to save, already carrying the changed fields
     */
    private void updateUserSecFile(final TurnState state, final UserSecurity identity) {
        final UserSecurity saved;
        try {
            saved = userSecurityRepository.save(identity);
        } catch (final RuntimeException failure) {
            LOG.warn("User update save failed: transaction={}", UPDATE_TRANSACTION_ID, failure);
            state.errorFlag = ErrorFlag.ON;
            state.message = UserResponse.MSG_UPDATE_UNABLE_TO_UPDATE_USER;
            state.focusFieldId = FIELD_FIRST_NAME;
            return;
        }
        // L369-L376: the normal arm.
        state.actionSucceeded = true;
        state.message = UserResponse.MSG_UPDATE_SUCCESS_PREFIX
                + delimitedBySpace(saved.getSecUsrId())
                + UserResponse.MSG_UPDATE_SUCCESS_SUFFIX;
        LOG.info("User updated: transaction={} roleClass={}", UPDATE_TRANSACTION_ID,
                resolvedRoleClass(saved.getSecUsrType()));
    }

    /**
     * Clears the update screen.
     * <strong>{@code CLEAR-CURRENT-SCREEN}, {@code app/cbl/COUSR02C.cbl} L395.</strong>
     *
     * <p>L397-L398 clears the fields and re-presents; the re-presentation belongs to the caller.
     *
     * @param state the turn being assembled
     */
    private void clearUpdateScreen(final TurnState state) {
        initializeUpdateFields(state);
    }

    /**
     * Blanks every field of the update screen.
     * <strong>{@code INITIALIZE-ALL-FIELDS}, {@code app/cbl/COUSR02C.cbl} L403.</strong>
     *
     * <p>L405 places the cursor on the identifier field - not on the given-name field, which is where
     * the add screen puts it - and L406-L411 moves spaces into the identifier, the given name, the
     * family name, the credential, the type and the message.
     *
     * @param state the turn being assembled
     */
    private void initializeUpdateFields(final TurnState state) {
        state.focusFieldId = FIELD_LIST_USER_ID;
        state.userId = null;
        state.firstName = null;
        state.lastName = null;
        state.submittedCredential = null;
        state.userType = null;
        state.message = null;
    }

    // ==============================================================================================
    // CU03 - delete a user. app/cbl/COUSR03C.cbl, 359 lines, 11 paragraphs
    // ==============================================================================================

    /**
     * One turn of transaction {@code CU03}, deleting a user.
     * <strong>{@code MAIN-PARA}, {@code app/cbl/COUSR03C.cbl} L82.</strong>
     *
     * <p>Structurally the update screen's twin, with two differences that are behaviour rather than
     * style. The exit key at L111-L118 returns <strong>without</strong> performing the delete, whereas
     * the update screen's exit key saves first - so a reader who assumed the two members were
     * interchangeable would have written a screen that deletes on the way out. And this screen has no
     * credential item at all, which is why its field-blanking paragraph clears one field fewer.
     *
     * <p>L84-L85 clears the error and modified flags, L87 the message. A turn with no prior state
     * transfers to sign-on at L90-L92. A first entry marks re-entry and loads a handed-over selection
     * at L99-L104. Later entries evaluate the attention key at L108-L130 in the source's clause order:
     * the enter key loads, the exit key returns, the fourth key clears, the fifth deletes, the twelfth
     * returns to the administrative menu, and the default arm emits the unmapped-key text.
     *
     * @param request the submitted screen
     * @return the loaded identity, the message, the field errors and the route
     * @throws NullPointerException if {@code request} is {@code null}
     */
    @Transactional
    public UserResponse deleteUser(final UserRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        final TurnState state = new TurnState();
        // L84-L85: SET ERR-FLG-OFF and USR-MODIFIED-NO TO TRUE. The modified flag is declared by this
        // member and never set by it, which is reproduced by leaving it at its initial state.
        state.errorFlag = ErrorFlag.OFF;
        state.modifiedFlag = ModifiedFlag.NO;
        // L87-L88: MOVE SPACES TO WS-MESSAGE and to the error line of the output map.
        state.message = null;

        final NavigationContext inbound = request.navigationContext();
        // L90-L92: a zero-length communication area nominates sign-on and transfers.
        if (isNavigationStateAbsent(inbound)) {
            state.context = NavigationContext.empty();
            LOG.debug("User delete entered with no prior navigation state: transaction={}",
                    DELETE_TRANSACTION_ID);
            returnToPrevScreen(state, SIGN_ON_PROGRAM_NAME, DELETE_TRANSACTION_ID,
                    DELETE_PROGRAM_NAME, NavigationService.Route.SIGN_ON);
            return sendUsrdelScreen(state);
        }
        state.context = inbound;
        // L95-L105: the first entry places the cursor on the identifier field and loads a selection.
        if (inbound.firstEntry()) {
            state.context = inbound.withReEntry();
            state.focusFieldId = FIELD_LIST_USER_ID;
            final String handedOver = request.userId();
            if (!isBlank(handedOver)) {
                state.userId = handedOver;
                processDeleteEnterKey(state);
            }
            return sendUsrdelScreen(state);
        }
        // L107: RECEIVE the submitted map before the key is evaluated.
        receiveUsrdelScreen(request, state);
        // L108-L130: EVALUATE EIBAID, clause order preserved, WHEN OTHER as the default arm.
        final KeyAction keyAction = request.keyAction();
        if (keyAction == KeyAction.ENTER) {
            processDeleteEnterKey(state);
            return sendUsrdelScreen(state);
        }
        if (keyAction == KeyAction.PFK03) {
            // L112-L118: no delete on the way out.
            final NavigationService.Route back = navigationService.resolveBackNavigation(
                    carriedState(state.context), NavigationService.Route.ADMIN_MENU);
            returnToPrevScreen(state, back.getLegacyProgramName(), DELETE_TRANSACTION_ID,
                    DELETE_PROGRAM_NAME, back);
            return sendUsrdelScreen(state);
        }
        if (keyAction == KeyAction.PFK04) {
            clearDeleteScreen(state);
            return sendUsrdelScreen(state);
        }
        if (keyAction == KeyAction.PFK05) {
            deleteUserInfo(state);
            return sendUsrdelScreen(state);
        }
        if (keyAction == KeyAction.PFK12) {
            returnToPrevScreen(state, ADMIN_MENU_PROGRAM_NAME, DELETE_TRANSACTION_ID,
                    DELETE_PROGRAM_NAME, NavigationService.Route.ADMIN_MENU);
            return sendUsrdelScreen(state);
        }
        // L126-L129: the default arm, which like the update screen's does not reposition the cursor.
        state.errorFlag = ErrorFlag.ON;
        state.message = messageCatalogService.invalidKeyMessage();
        LOG.debug("User delete received an unmapped attention key: transaction={} keyAction={}",
                DELETE_TRANSACTION_ID, keyAction);
        return sendUsrdelScreen(state);
    }

    /**
     * Loads the identity named by the identifier field so the operator can confirm the deletion.
     * <strong>{@code PROCESS-ENTER-KEY}, {@code app/cbl/COUSR03C.cbl} L142.</strong>
     *
     * <p>L144-L154 tests the identifier for emptiness and L156-L162 blanks the three displayed items -
     * three, not four, because this screen has no credential item - before the read. L164-L169 then
     * moves the record's given name, family name and type onto the screen. The read's own normal arm
     * emits the press-to-delete prompt, which is <strong>the confirmation shape this screen has</strong>:
     * the record is shown with a prompt, and nothing is removed until the confirming key arrives on a
     * later turn.
     *
     * @param state the turn being assembled, already carrying the identifier
     */
    private void processDeleteEnterKey(final TurnState state) {
        // L145-L150: WHEN USRIDINI = SPACES OR LOW-VALUES.
        if (isBlank(state.userId)) {
            raiseFieldError(state, PROPERTY_USER_ID, FIELD_LIST_USER_ID,
                    UserResponse.MSG_DELETE_USER_ID_EMPTY);
            return;
        }
        // L151-L153: WHEN OTHER places the cursor on the identifier field and continues.
        state.focusFieldId = FIELD_LIST_USER_ID;
        // L157-L161: blank the displayed items, then read.
        state.firstName = null;
        state.lastName = null;
        state.userType = null;
        final Optional<UserSecurity> found = readUserSecFileForDelete(state);
        // L164-L169: on a successful read, move the record onto the screen.
        if (!state.errorFlag.isOn() && found.isPresent()) {
            final UserSecurity identity = found.get();
            state.firstName = identity.getSecUsrFname();
            state.lastName = identity.getSecUsrLname();
            state.userType = identity.getSecUsrType();
        }
    }

    /**
     * Validates the confirmation and removes the record.
     * <strong>{@code DELETE-USER-INFO}, {@code app/cbl/COUSR03C.cbl} L174.</strong>
     *
     * <p>L176-L186 is an {@code EVALUATE TRUE} with one emptiness condition on the identifier and a
     * default arm - this screen validates one item only, because it edits none. L188-L192 then reads
     * the record and removes it.
     *
     * <p><strong>The read and the removal are both performed unconditionally, in that order, exactly
     * as the source performs them.</strong> The source does not re-test the error flag between the two,
     * so a read that reported not-found is still followed by the removal attempt - which then reports
     * not-found itself, through its own arm, with the same text. That structure is reproduced rather
     * than tidied, and the outcome is identical either way; the observation is recorded as a source
     * oddity rather than corrected here.
     *
     * @param state the turn being assembled
     */
    private void deleteUserInfo(final TurnState state) {
        // L177-L182: WHEN USRIDINI = SPACES OR LOW-VALUES.
        if (isBlank(state.userId)) {
            raiseFieldError(state, PROPERTY_USER_ID, FIELD_LIST_USER_ID,
                    UserResponse.MSG_DELETE_USER_ID_EMPTY);
            return;
        }
        // L183-L185: WHEN OTHER places the cursor on the identifier field and continues.
        state.focusFieldId = FIELD_LIST_USER_ID;
        // L189-L191: read, then delete - both unconditional, in this order.
        final Optional<UserSecurity> found = readUserSecFileForDelete(state);
        deleteUserSecFile(state, found);
    }

    /**
     * Assembles the delete screen.
     * <strong>{@code SEND-USRDEL-SCREEN}, {@code app/cbl/COUSR03C.cbl} L213.</strong>
     *
     * <p>L215 populates the header and L217 moves the message onto the error line. A single, always
     * erasing send verb, so no erase flag.
     *
     * @param state the assembled turn
     * @return the response for the client to render
     */
    private UserResponse sendUsrdelScreen(final TurnState state) {
        populateHeaderInfo(state, DELETE_TRANSACTION_ID, DELETE_PROGRAM_NAME);
        return buildRecordScreen(state);
    }

    /**
     * Takes the submitted delete screen.
     * <strong>{@code RECEIVE-USRDEL-SCREEN}, {@code app/cbl/COUSR03C.cbl} L230.</strong>
     *
     * <p>The counterpart of the receive command at L232-L238. The credential is deliberately not read
     * from the request on this path: this screen's map declares no credential item at all, so a
     * credential arriving with a delete has no legacy counterpart and is ignored rather than acted on.
     *
     * @param request the submitted screen
     * @param state   the turn being assembled
     */
    private void receiveUsrdelScreen(final UserRequest request, final TurnState state) {
        state.userId = request.userId();
        state.firstName = request.firstName();
        state.lastName = request.lastName();
        state.userType = request.userType();
    }

    /**
     * Reads the record the delete screen works on.
     * <strong>{@code READ-USER-SEC-FILE}, {@code app/cbl/COUSR03C.cbl} L267.</strong>
     *
     * <p>L269-L278 issues a read for update and L280-L300 evaluates its response in three arms. The
     * normal arm emits the press-to-delete prompt and re-presents, without raising the error flag. The
     * not-found arm - which is what an empty result is - raises the flag, emits the not-found text and
     * places the cursor on the identifier field. The default arm emits the lookup-failure text.
     *
     * @param state the turn being assembled
     * @return the identity read, or an empty result on the not-found or failure arms
     */
    private Optional<UserSecurity> readUserSecFileForDelete(final TurnState state) {
        final Optional<UserSecurity> found;
        try {
            found = userSecurityRepository.findById(state.userId);
        } catch (final RuntimeException failure) {
            LOG.warn("User delete read failed: transaction={}", DELETE_TRANSACTION_ID, failure);
            // L293-L299: the catch-all arm.
            state.readResponse = RecordResponse.OTHER;
            state.errorFlag = ErrorFlag.ON;
            state.message = UserResponse.MSG_DELETE_UNABLE_TO_LOOKUP_USER;
            state.focusFieldId = FIELD_FIRST_NAME;
            return Optional.empty();
        }
        if (found.isEmpty()) {
            // L287-L292: the not-found arm.
            state.readResponse = RecordResponse.NOT_FOUND;
            state.errorFlag = ErrorFlag.ON;
            state.message = UserResponse.MSG_DELETE_USER_ID_NOT_FOUND;
            state.focusFieldId = FIELD_LIST_USER_ID;
            raiseFieldErrorState(state, PROPERTY_USER_ID, FIELD_LIST_USER_ID,
                    ErrorResponse.FieldState.INVALID, UserResponse.MSG_DELETE_USER_ID_NOT_FOUND);
            return Optional.empty();
        }
        // L281-L286: the normal arm emits the prompt and leaves the error flag clear.
        state.readResponse = RecordResponse.NORMAL;
        state.message = UserResponse.MSG_DELETE_PRESS_PF5;
        return found;
    }

    /**
     * Removes the record.
     * <strong>{@code DELETE-USER-SEC-FILE}, {@code app/cbl/COUSR03C.cbl} L305.</strong>
     *
     * <p>L307-L311 removes the record the browse is positioned on and L313-L336 evaluates the response
     * in three arms. The normal arm clears the fields and builds the success text from a fixed prefix,
     * the identifier up to its first space and a fixed suffix, reproducing L318-L321 - and note that
     * the identifier must be captured <em>before</em> the fields are cleared, because the source builds
     * the text from the record area rather than from the screen. The not-found arm emits the not-found
     * text.
     *
     * <p><strong>The default arm's text is a source oddity, and it is reproduced rather than
     * corrected.</strong> At L332 the failure arm of the <em>delete</em> paragraph emits the
     * unable-to-<em>update</em> text. That is what an operator saw and therefore what an interface
     * consumer matching on the text expects, so the wording is carried across unchanged and the
     * observation is recorded as a finding.
     *
     * @param state the turn being assembled, carrying the arm the preceding read reported through
     * @param found the outcome of the preceding read, whose record supplies the identifier and the
     *              role class the normal arm reports
     */
    private void deleteUserSecFile(final TurnState state, final Optional<UserSecurity> found) {
        if (state.readResponse != RecordResponse.NORMAL || found.isEmpty()) {
            // L323-L335: the two failing arms, selected by what the read left behind. A read that
            // found nothing leaves the removal nothing to position on, so it reports not-found at
            // L323-L328; a read that failed outright leaves no position at all, so it reports through
            // the catch-all arm at L329-L335 - whose text names the update operation in the source.
            switch (state.readResponse) {
                case NOT_FOUND -> {
                    state.errorFlag = ErrorFlag.ON;
                    state.message = UserResponse.MSG_DELETE_USER_ID_NOT_FOUND;
                    state.focusFieldId = FIELD_LIST_USER_ID;
                }
                case NORMAL, DUPLICATE, OTHER -> {
                    state.errorFlag = ErrorFlag.ON;
                    state.message = UserResponse.MSG_DELETE_UNABLE_TO_UPDATE_USER;
                    state.focusFieldId = FIELD_FIRST_NAME;
                }
            }
            return;
        }
        final String removedUserId = found.get().getSecUsrId();
        // The role class is captured with the identifier and for the same reason: the normal arm blanks
        // the screen before it reports, so anything the report needs must be taken beforehand. It comes
        // from the record that was read rather than from the echoed screen item.
        final String removedRoleCode = found.get().getSecUsrType();
        try {
            userSecurityRepository.deleteById(removedUserId);
        } catch (final RuntimeException failure) {
            LOG.warn("User delete removal failed: transaction={}", DELETE_TRANSACTION_ID, failure);
            // L329-L335: the default arm, whose text names the update operation in the source.
            state.errorFlag = ErrorFlag.ON;
            state.message = UserResponse.MSG_DELETE_UNABLE_TO_UPDATE_USER;
            state.focusFieldId = FIELD_FIRST_NAME;
            return;
        }
        // L314-L322: the normal arm clears the fields, then builds the text from the captured
        // identifier.
        initializeDeleteFields(state);
        state.actionSucceeded = true;
        state.message = UserResponse.MSG_DELETE_SUCCESS_PREFIX
                + delimitedBySpace(removedUserId)
                + UserResponse.MSG_DELETE_SUCCESS_SUFFIX;
        LOG.info("User deleted: transaction={} roleClass={}", DELETE_TRANSACTION_ID,
                resolvedRoleClass(removedRoleCode));
    }

    /**
     * Clears the delete screen.
     * <strong>{@code CLEAR-CURRENT-SCREEN}, {@code app/cbl/COUSR03C.cbl} L341.</strong>
     *
     * <p>L343-L344 clears the fields and re-presents; the re-presentation belongs to the caller.
     *
     * @param state the turn being assembled
     */
    private void clearDeleteScreen(final TurnState state) {
        initializeDeleteFields(state);
    }

    /**
     * Blanks every field of the delete screen.
     * <strong>{@code INITIALIZE-ALL-FIELDS}, {@code app/cbl/COUSR03C.cbl} L349.</strong>
     *
     * <p>L351 places the cursor on the identifier field and L352-L356 moves spaces into the
     * identifier, the given name, the family name, the type and the message - <strong>five items, not
     * six</strong>: there is no credential item on this screen, which is the difference that stops this
     * paragraph from being shared with the update screen's.
     *
     * @param state the turn being assembled
     */
    private void initializeDeleteFields(final TurnState state) {
        state.focusFieldId = FIELD_LIST_USER_ID;
        state.userId = null;
        state.firstName = null;
        state.lastName = null;
        state.userType = null;
        state.message = null;
    }

    // ==============================================================================================
    // Paragraphs shared by more than one member, each carrying every source paragraph it covers
    // ==============================================================================================

    /**
     * Returns to the previous screen.
     * <strong>{@code RETURN-TO-PREV-SCREEN}, carrying all four members: {@code COUSR00C.cbl} L506,
     * {@code COUSR01C.cbl} L165, {@code COUSR02C.cbl} L250 and {@code COUSR03C.cbl} L197.</strong>
     *
     * <p>The four paragraphs are identical statement for statement, which is why one method carries
     * them rather than four near-copies. The only textual difference is a pair of commented-out lines
     * in the add member at L172-L173, which contribute nothing. Each does the same four things: it
     * substitutes the sign-on program when the nominated destination is blank, records the originating
     * transaction and program, zeroes the program context, and transfers control.
     *
     * <p>The blank test is the legacy one - a fixed-width field holding spaces or low values - and the
     * caller has already nominated a destination before performing this, which is why the nomination is
     * a parameter. Resolving the nominated program name to a destination is the navigation service's
     * job; no route table is declared here.
     *
     * @param state             the turn being assembled
     * @param nominatedProgram  the destination the calling arm nominated
     * @param fromTransactionId the calling screen's own transaction identifier
     * @param fromProgram       the calling screen's own program name
     * @param callerDefault     the destination to fall back on, which is per screen rather than global
     */
    private void returnToPrevScreen(final TurnState state,
                                    final String nominatedProgram,
                                    final String fromTransactionId,
                                    final String fromProgram,
                                    final NavigationService.Route callerDefault) {
        // L508-L510: substitute the sign-on program when nothing usable was nominated.
        final String destinationProgram =
                isBlank(nominatedProgram) ? SIGN_ON_PROGRAM_NAME : nominatedProgram;
        // L511-L513: record the originating identity and zero the program context, which is the
        // first-entry state - the receiving screen must not believe it is being re-entered.
        state.context = withRouting(
                state.context == null ? NavigationContext.empty() : state.context,
                fromTransactionId,
                fromProgram,
                state.context == null ? null : state.context.toTransactionId(),
                destinationProgram,
                NavigationContext.ProgramContext.ENTER);
        // L514-L517: transfer control, which is a resolved destination in the response.
        state.route = navigationService.resolveNominatedDestination(carriedState(state.context), callerDefault);
        LOG.debug("Returning to the previous screen: from={} destination={}", fromProgram,
                state.route.getRouteValue());
    }

    /**
     * Populates the screen header.
     * <strong>{@code POPULATE-HEADER-INFO}, carrying all four members: {@code COUSR00C.cbl} L562,
     * {@code COUSR01C.cbl} L214, {@code COUSR02C.cbl} L296 and {@code COUSR03C.cbl} L243.</strong>
     *
     * <p>The four paragraphs differ only in the output map they write to, so they are one behaviour
     * parameterised by the screen. Each reads the current date and time, moves the two catalogue title
     * lines and the screen's own transaction and program names onto the screen, then assembles the date
     * as month, day and the trailing two characters of the year - {@code COUSR00C.cbl} L571-L575 - and
     * the time as hours, minutes and seconds at L577-L581.
     *
     * <p>The clock is injected rather than read from the platform, so a test can fix the header without
     * the assertion depending on when it ran. Both formatters are built with the root locale: the two
     * items are fixed-width eight-character screen fields, and a locale-sensitive formatter would emit
     * a different calendar or different digits under a different default locale.
     *
     * @param state         the turn being assembled
     * @param transactionId the screen's own transaction identifier
     * @param programName   the screen's own program name
     */
    private void populateHeaderInfo(final TurnState state,
                                    final String transactionId,
                                    final String programName) {
        // L564: MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA.
        final LocalDateTime now = LocalDateTime.now(clock);
        // L566-L569: the two title lines and the screen's own identity.
        state.title01 = messageCatalogService.screenTitle01();
        state.title02 = messageCatalogService.screenTitle02();
        state.transactionName = transactionId;
        state.programName = programName;
        // L571-L575 and L577-L581: the assembled date and time.
        state.currentDate = HEADER_DATE_FORMAT.format(now);
        state.currentTime = HEADER_TIME_FORMAT.format(now);
    }

    // ==============================================================================================
    // Shared support: response assembly, field errors, blank tests and credential comparison
    // ==============================================================================================

    /**
     * Assembles the response for the three single-record screens, which carry no rows and no paging.
     *
     * <p>Extracted from the three send paragraphs because all three build the same payload once their
     * own header has been populated; the paragraphs themselves remain distinct methods, since each is a
     * separate source paragraph with its own map.
     *
     * <p><strong>No component of the payload is a credential.</strong> The submitted value is held on
     * the turn and never read here, and the stored digest is never read from the entity at all on any
     * response path.
     *
     * @param state the assembled turn
     * @return the response for the client to render
     */
    private UserResponse buildRecordScreen(final TurnState state) {
        return new UserResponse(
                List.of(),
                null,
                state.userId,
                state.firstName,
                state.lastName,
                state.userType,
                state.transactionName,
                state.title01,
                state.currentDate,
                state.programName,
                state.title02,
                state.currentTime,
                state.message,
                List.copyOf(state.fieldErrors),
                state.errorFlag.isOn(),
                state.actionSucceeded,
                state.focusFieldId,
                state.route == null ? null : state.route.getRouteValue(),
                state.context);
    }

    /**
     * Records a missing mandatory item: the summary text and cursor go to the first such item, while
     * the per-field entry is recorded for every one of them.
     *
     * <p>This is where the legacy {@code EVALUATE TRUE} cascade and the field-level error contract are
     * reconciled. The cascade stops at its first true condition, so exactly one summary text and one
     * cursor position exist per submission - and that is preserved by assigning them only the first
     * time this method fires on a turn. The field-level contract, by contrast, is a set of independent
     * per-field flags, so an entry is added for <strong>every</strong> empty item, letting a client
     * decorate all of them at once as the screen's own decoration macro did.
     *
     * <p>The state is {@code MISSING} rather than {@code INVALID} because the item was left blank,
     * which is the distinction the two-state contract exists to draw: one tells the operator to supply
     * a value and the other to correct one.
     *
     * @param state         the turn being assembled
     * @param propertyName  the field's name in the request contract
     * @param screenFieldId the legacy screen field identifier
     * @param message       the legacy text for this item
     */
    private void raiseFieldError(final TurnState state,
                                final String propertyName,
                                final String screenFieldId,
                                final String message) {
        final boolean firstError = !state.errorFlag.isOn();
        // MOVE 'Y' TO WS-ERR-FLG, present in every arm of every cascade.
        state.errorFlag = ErrorFlag.ON;
        if (firstError) {
            // The first arm to fire owns the summary line and the cursor; later arms do not.
            state.message = message;
            state.focusFieldId = screenFieldId;
        }
        raiseFieldErrorState(state, propertyName, screenFieldId, ErrorResponse.FieldState.MISSING,
                message);
    }

    /**
     * Records one per-field entry in a given state, without touching the summary line or the cursor.
     *
     * <p>Used for the states that are not an empty item - a duplicate identifier and an identifier
     * that names no row are both {@code INVALID}, because the operator supplied a value and the value
     * is wrong, which is a different instruction to the operator than supplying one.
     *
     * <p>Entries are only recorded on a re-submission. That is the legacy decoration gate: the
     * decorating macro fires only when the program context marks the turn as a re-entry, so a screen
     * being presented for the first time carries no decoration. On these four screens the gate holds
     * structurally too - the first entry validates nothing at all - and it is asserted here as well so
     * that the rule is stated rather than merely implied by the call graph.
     *
     * @param state         the turn being assembled
     * @param propertyName  the field's name in the request contract
     * @param screenFieldId the legacy screen field identifier
     * @param fieldState    which of the two legacy states the item is in
     * @param message       the legacy text for this item
     */
    private void raiseFieldErrorState(final TurnState state,
                                      final String propertyName,
                                      final String screenFieldId,
                                      final ErrorResponse.FieldState fieldState,
                                      final String message) {
        if (!reEntryGateOpen(state)) {
            return;
        }
        state.fieldErrors.add(
                new ErrorResponse.FieldError(propertyName, screenFieldId, fieldState, message));
    }

    /**
     * Reports whether field-level decoration may be applied on this turn.
     *
     * <p>The legacy gate, stated explicitly. The decoration macro at {@code app/cpy/CSSETATY.cpy}
     * fires only when the communication area marks the program as re-entered, so a first presentation
     * of a screen is never decorated even if its fields are empty - which they are, since the screen
     * was just blanked. A turn whose context is absent has no program context at all and is likewise
     * not a re-submission.
     *
     * @param state the turn being assembled
     * @return {@code true} when this turn is a re-submission and decoration is permitted
     */
    private boolean reEntryGateOpen(final TurnState state) {
        return state.context != null && state.context.reEntry();
    }

    /**
     * Reports whether a fixed-width item is blank in the legacy sense.
     *
     * <p>The test the four members apply is {@code = SPACES OR LOW-VALUES}, so an item is blank when it
     * is absent, empty, or made entirely of spaces. It is deliberately <strong>not</strong> the
     * conventional white-space test: a tab or a line separator is not a space in a fixed-width
     * alphanumeric item, so a value containing one is present rather than blank, and treating it as
     * blank would reject input the legacy accepted.
     *
     * @param value the item to test; may be {@code null}
     * @return {@code true} when the item is absent, empty or entirely spaces
     */
    private static boolean isBlank(final String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the part of a value before its first space, reproducing the space-delimited operand of
     * the three text-assembly statements at {@code COUSR01C.cbl} L255-L258, {@code COUSR02C.cbl}
     * L372-L375 and {@code COUSR03C.cbl} L318-L321.
     *
     * <p>Each of those statements concatenates a fixed prefix, the identifier <em>delimited by
     * space</em>, and a fixed suffix. The delimiter stops the transfer at the first space, so an
     * eight-character identifier padded on the right contributes only its significant characters and
     * the message reads without a gap before the suffix. Trimming would not be the same operation: it
     * would also remove leading spaces and would keep any interior ones, whereas this stops dead at the
     * first space wherever it occurs.
     *
     * @param value the sending item; may be {@code null}, which contributes nothing
     * @return the characters before the first space, or the whole value when it contains none
     */
    private static String delimitedBySpace(final String value) {
        if (value == null) {
            return "";
        }
        final int firstSpace = value.indexOf(' ');
        return firstSpace < 0 ? value : value.substring(0, firstSpace);
    }

    /**
     * Reports whether a submitted credential is genuinely a new one, which is the only case in which
     * anything is hashed.
     *
     * <p>The replacement for the direct comparison at {@code app/cbl/COUSR02C.cbl} L227, which
     * compared the submitted item against the stored cleartext value. Three outcomes, and the two that
     * answer {@code false} are the two that matter:</p>
     * <ul>
     *   <li>An <strong>absent</strong> credential is the untouched pre-filled item: nothing was
     *       supplied, so the stored digest is left exactly as it is. This is the case that a
     *       re-hashing implementation would get wrong, and getting it wrong would replace a working
     *       digest with a digest <em>of that digest</em> and lock the identity out for good.</li>
     *   <li>A credential the encoder <strong>verifies</strong> against the stored digest is the same
     *       credential re-typed, which the legacy comparison reported as equal. The digest is left as
     *       it is, so a save that changes nothing else reports no change at all.</li>
     *   <li>Anything else is new and is hashed by the caller.</li>
     * </ul>
     *
     * <p>Verification is the only admissible test. Two digests of one credential differ because each
     * embeds its own random salt, so comparing digests - by equality or otherwise - would report every
     * credential as changed on every save. Neither the submitted value nor the stored digest appears in
     * any diagnostic produced here.
     *
     * <p>A blank credential never reaches this method: the emptiness cascade has already reported it
     * and returned.
     *
     * @param submittedCredential the credential as submitted; {@code null} when none was supplied
     * @param identity            the identity whose stored digest is being compared against
     * @return {@code true} only when a genuinely different credential was supplied
     */
    private boolean isCredentialGenuinelyNew(final String submittedCredential,
                                             final UserSecurity identity) {
        if (submittedCredential == null) {
            return false;
        }
        return !passwordEncoder.matches(submittedCredential, identity.credentialDigest());
    }

    /**
     * Resolves a raw type code to a stable role label, with an unconditional alternative and no third
     * branch.
     *
     * <p>The shape of the sign-on role split, reproduced exactly: that program tests the
     * administrative code and routes <em>everything else</em> through an unconditional alternative,
     * with no branch for an unrecognised value and no validation of the code at all. So an absent code,
     * a lower-case one and a code the estate never declared all resolve here rather than raising, which
     * is what lets the raw column round-trip a value no constraint anticipated. <strong>This method
     * cannot throw for any input.</strong>
     *
     * <p><strong>It is not an authorisation check and it changes no outcome.</strong> Administrator
     * access to these four operations is enforced by the route-to-role table at the boundary, and a
     * second check here would be a second place for that policy to live and to diverge. The label
     * exists so that maintenance of a privileged identity is distinguishable in a diagnostic record -
     * the audit context that matters most on an administrative path - and it names a role class only,
     * never an identity, a name or any credential material.
     *
     * @param rawTypeCode the code as stored or submitted; may be {@code null} or unrecognised
     * @return {@code "administrative"} for exactly the administrative code, {@code "standard"} for
     *         every other value including an absent or unrecognised one
     */
    private static String resolvedRoleClass(final String rawTypeCode) {
        return UserType.fromCode(rawTypeCode)
                .map(userType -> userType.isAdmin() ? "administrative" : "standard")
                .orElse("standard");
    }

    /**
     * Returns a copy of the navigation state with the routing fields replaced.
     *
     * <p>The counterpart of the moves into the communication area's routing fields that precede every
     * transfer. The state is a record, so it is replaced rather than mutated and every non-routing
     * field is carried across untouched - including the signed-on identity, which this method never
     * derives, defaults or reconciles.
     *
     * @param context           the state to copy
     * @param fromTransactionId the originating transaction identifier
     * @param fromProgram       the originating program name
     * @param toTransactionId   the destination transaction identifier
     * @param toProgram         the destination program name
     * @param programContext    the program context the destination should see
     * @return a new state carrying the routing fields supplied and every other field unchanged
     */
    private static NavigationContext withRouting(
            final NavigationContext context,
            final String fromTransactionId,
            final String fromProgram,
            final String toTransactionId,
            final String toProgram,
            final NavigationContext.ProgramContext programContext) {
        return new NavigationContext(
                fromTransactionId,
                fromProgram,
                toTransactionId,
                toProgram,
                context.userId(),
                context.userType(),
                programContext,
                context.customerId(),
                context.customerFirstName(),
                context.customerMiddleName(),
                context.customerLastName(),
                context.accountId(),
                context.accountStatus(),
                context.cardNumber(),
                context.lastMap(),
                context.lastMapset());
    }

    /**
     * Reports whether this turn carries no prior navigation state - the equivalent of the zero-length
     * communication-area test that opens the legacy program's main paragraph.
     *
     * <p>The test is on the <strong>whole</strong> echoed record and not on its four routing fields
     * alone, because a zero-length communication area describes a turn that carries nothing at all: no
     * signed-on user, no selection and no previous screen. Projecting the record down to the routing
     * fields before testing would call a turn stateless while it still carried an identity or a
     * selection, which is a different condition from the one the legacy branches on.
     *
     * @param context the echoed navigation record, which may be {@code null}
     * @return {@code true} when no navigation state was carried into this turn
     */
    private static boolean isNavigationStateAbsent(final NavigationContext context) {
        return context == null || NavigationContext.empty().equals(context);
    }

    /**
     * Projects the echoed navigation record onto the carried state the navigation rules read.
     *
     * <p>Back-navigation and nominated-destination resolution consult the originating and nominated
     * program names and nothing else, so the projection is loss-free for them: it carries the four
     * routing fields and the program-context flag and drops the identity and selection members, which no
     * routing rule reads. The mapping is the one the transport adapter applies, so a routing decision
     * does not depend on which side of the boundary the record was projected on.
     *
     * @param context the echoed navigation record, which may be {@code null}
     * @return the carried state the navigation rules read, never {@code null}
     */
    private static ConversationState carriedState(final NavigationContext context) {
        if (context == null) {
            return ConversationState.empty();
        }
        return new ConversationState(
                context.fromTransactionId(),
                context.fromProgram(),
                context.toTransactionId(),
                context.toProgram(),
                context.reEntry()
                        ? ConversationState.EntryMode.RE_ENTRY
                        : ConversationState.EntryMode.FIRST_ENTRY);
    }
}
