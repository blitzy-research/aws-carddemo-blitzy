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
package com.carddemo.api.dto;

import com.carddemo.domain.enums.KeyAction;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Immutable inbound request contract shared by the four administrative user transactions
 * {@code CU00}, {@code CU01}, {@code CU02} and {@code CU03}.
 *
 * <p>One record serves all four legacy screens because they operate on one entity through one
 * eight-character key and differ only in which of the same handful of items each screen presents:
 * the list driven by {@code app/cbl/COUSR00C.cbl}, the add screen driven by
 * {@code app/cbl/COUSR01C.cbl}, the update screen driven by {@code app/cbl/COUSR02C.cbl} and the
 * delete screen driven by {@code app/cbl/COUSR03C.cbl}. The union of their inputs is small enough
 * that four near-duplicate types would obscure rather than clarify the contract, and a shared type
 * makes the one genuine asymmetry - which items each operation requires - explicit rather than
 * implied by four separate shapes.
 *
 * <p><strong>The symbolic maps are the contract, and only their inbound value items are in
 * scope.</strong> The widths below were measured from {@code app/cpy-bms/COUSR00.CPY},
 * {@code app/cpy-bms/COUSR01.CPY}, {@code app/cpy-bms/COUSR02.CPY} and
 * {@code app/cpy-bms/COUSR03.CPY} rather than inferred:
 *
 * <ul>
 *   <li>the list map declares an eight-character page number at line 60 and an eight-character
 *       user-id entry field at line 66, followed by ten identical row families running from line 72
 *       to line 366, each family carrying a one-character selection item, an eight-character user
 *       id, a twenty-character first name, a twenty-character last name and a one-character user
 *       type;</li>
 *   <li>the add map declares a twenty-character first name at line 60, a twenty-character last name
 *       at line 66, an eight-character user id at line 72, an eight-character password at line 78
 *       and a one-character user type at line 84;</li>
 *   <li>the update map declares the eight-character user id first, at line 60, then the two
 *       twenty-character name parts at lines 66 and 72, the eight-character password at line 78 and
 *       the one-character user type at line 84;</li>
 *   <li>the delete map declares the eight-character user id at line 60, the two name parts at lines
 *       66 and 72 and the one-character user type at line 78 - <strong>and no password item at
 *       all.</strong></li>
 * </ul>
 *
 * <p>Everything else those maps declare is out of scope. The six leading items of every map - the
 * transaction name, the two title lines, the current date, the program name and the current time -
 * are screen furniture the programs write outbound, and the seventy-eight-character message item
 * each map declares last is an outbound diagnostic; all seven belong to the response contract and
 * are deliberately absent here. So are the generated terminal artefacts: the per-item length, flag
 * and attribute groups, the leading twelve-byte terminal input/output area filler, the map
 * coordinates and field lengths carried in {@code app/bms/COUSR00.bms},
 * {@code app/bms/COUSR01.bms}, {@code app/bms/COUSR02.bms} and {@code app/bms/COUSR03.bms}, the
 * colour and highlight attributes, the cursor-positioning value and the marker byte. Those are
 * 3270 plumbing, not contract, and modelling any of them would tie a REST payload to a terminal
 * that no longer exists.
 *
 * <h2>Two eight-character user identifiers, not one</h2>
 *
 * <p>The same map item name carries two different meanings across the four screens, so this record
 * carries two components rather than merging them.
 *
 * <p>On the list screen the entry field is a <em>browse start key</em>: lines 218 to 222 of
 * {@code COUSR00C.cbl} substitute the lowest possible key when the field arrives empty, so a blank
 * value is not a missing value - it means "begin at the very first record". On the update and
 * delete screens the identically named item is the <em>target</em> of the operation, and a blank
 * value there is a hard error the programs report before doing anything else. Merging the two into
 * a single component would make "list from the beginning" indistinguishable from "update nobody",
 * and the service could no longer tell a legitimate blank from an omission. {@link #searchUserId()}
 * is therefore the list start key and {@link #userId()} is the operation target; a client populates
 * whichever one its endpoint uses and leaves the other absent.
 *
 * <h2>Paging values are text, and paging policy is not modelled here</h2>
 *
 * <p>The page number and the two browse boundary keys are carried as {@link String}. The list map
 * declares the page number as an eight-character alphanumeric item, and the program's own
 * communication-area extension at line 70 of {@code COUSR00C.cbl} declares its companion as an
 * eight-digit external decimal - a display representation whose leading zeros occupy real
 * positions. Parsing either into a whole-number type would discard those positions and make the
 * value un-echoable at its declared width, so both stay text and are echoed exactly as received.
 * The boundary keys correspond to the two eight-character retained-key fields declared immediately
 * above it, at lines 68 and 69.
 *
 * <p>The width is eight <em>here</em>. Another list screen in this estate declares a narrower page
 * number, and the two widths are unrelated: they belong to two independently authored screens and
 * neither is derived from the other, so the bound below is never shared with, or reconciled
 * against, any other screen's page number.
 *
 * <p><strong>No page size, cap or window length appears in this type.</strong> The list presents
 * ten rows, established by the ten-occurrence row table at lines 56 to 64 of
 * {@code COUSR00C.cbl} and corroborated by the ten row families of the list map. That count is the
 * shape of the screen rather than a value a client may choose, so it is declared once by the
 * response-side paging contract and consumed by the service. A request that could carry its own row
 * count would let a client widen the page, which the legacy screen cannot do.
 *
 * <h2>The internal staging row is not the screen contract</h2>
 *
 * <p>Lines 56 to 64 of {@code COUSR00C.cbl} declare a ten-occurrence working-storage table the
 * program fills before transmitting. Its element is shaped for a fixed-pitch display, not for the
 * map: it carries a one-character selection, an eight-character user id, a <strong>single combined
 * twenty-five-character name</strong>, an <strong>eight-character user type</strong> and three
 * two-character alignment fillers between them. None of those three widths is the contract.
 *
 * <p>The map - which is the contract - keeps the name in two separate twenty-character parts and
 * declares the user type one character wide, exactly as the credential record
 * {@code app/cpy/CSUSR01Y.cpy} does at its lines 19, 20 and 22. This record therefore models eight,
 * twenty, twenty and one, and models neither the combined twenty-five-character name, nor an
 * eight-character type, nor any alignment filler. Copying the staging row would have produced a
 * type that cannot round-trip a name whose two parts together exceed twenty-five characters, and a
 * user-type field seven characters wider than anything the screen or the record can hold. The
 * divergence is recorded here because it is a deliberate rejection of the wider internal shape, not
 * an oversight.
 *
 * <h2>The row selections are positional, and nothing here interprets them</h2>
 *
 * <p>The ten one-character selection items are carried as an ordered {@link List} of one-character
 * strings, one element per screen row, because <strong>position is meaning</strong>: element
 * <em>n</em> is the selection typed against row <em>n</em>, and a selection is worthless without the
 * row it refers to. The list is copied defensively on construction, so the component is immutable
 * and cannot be aliased to caller-owned state.
 *
 * <p>Empty positions therefore survive intact. The collection is never de-duplicated, never keyed,
 * never filtered to the non-blank entries, never compacted, never re-ordered and never padded out to
 * the full row count. Any of those would break the correspondence between an element's index and its
 * screen row, and the two operations the list dispatches to need that index to identify the record
 * the operator picked. A client that submits fewer elements than the screen has rows is describing a
 * shorter prefix of the page, not a sparse selection.
 *
 * <p><strong>Which selection wins, and what a selection means, are the service's decisions.</strong>
 * Lines 190 to 209 of {@code COUSR00C.cbl} accept two distinct letters, each in either case, and
 * route the first satisfied one to the update screen and to the delete screen respectively; line 210
 * onward handles every other non-blank entry. This record performs none of that: it does not scan
 * the collection, does not locate the first non-blank element, does not compare an element against
 * an accepted letter and does not reject an unaccepted one. It transports the ten positions and
 * stops.
 *
 * <p><strong>An unaccepted selection does not suppress the page.</strong> Lines 210 to 214 move a
 * message and reposition the cursor but - uniquely among the error paths of these four programs -
 * never raise the error flag the program tests before listing. Control consequently falls through
 * and the page is still built and sent. A response that carries an unaccepted-selection message
 * <em>together with</em> a fully populated row list is therefore correct legacy behaviour rather
 * than an inconsistency, and the service must not be written to treat the message as terminal.
 *
 * <p>The wording of that message is plural, naming both accepted letters, whereas the equivalent
 * message on the transaction-list screen is singular and names one letter. The two texts are
 * therefore never shared, unified or derived from one another. Neither text appears in this type at
 * all: message wording is an outbound concern carried by {@code UserResponse}.
 *
 * <h2>The user type is a raw character, deliberately not an enumerated value</h2>
 *
 * <p>This is a faithful-beats-idiomatic decision, and it is the one place in this contract where the
 * obviously nicer Java shape is the wrong shape.
 *
 * <p>The legacy programs never check <em>which</em> character the user type is. The add screen tests
 * only that the item is neither spaces nor low values, at line 142 of {@code COUSR01C.cbl}, and then
 * transfers whatever arrived straight into the credential record at line 158. The update screen does
 * the same: the emptiness test sits at line 204 of {@code COUSR02C.cbl} and the transfer at line 232,
 * guarded only by a change comparison. No value test exists anywhere in either program, the list
 * screen echoes the stored character without interpreting it, and the persisted column is a
 * single-character variable-length text column with no check constraint. The legacy system therefore
 * accepts, stores and redisplays <em>any</em> non-blank character.
 *
 * <p>The domain layer's user-type enumeration, by contrast, declares exactly two constants, folds no
 * case and offers no synthetic catch-all - correctly, because those two are the only values the
 * estate's condition names declare. Binding this component to that enumeration would make the
 * transport layer reject input the mainframe stores, turning a data-quality observation into a
 * deserialization failure and silently losing rows that a migrated database legitimately contains.
 *
 * <p>The component is consequently a one-character {@link String}, bounded by width and by nothing
 * else. Interpreting it - resolving the character to a known type where it is one, and carrying it
 * through unresolved where it is not - happens in the service layer through the enumeration's
 * non-throwing lookup. That is why this file does not reference the enumeration and does not import
 * it.
 *
 * <h2>The attention key, with no default and no folding</h2>
 *
 * <p>The keystroke that accompanies a submission is carried as {@link KeyAction}, the enumerated
 * form of the five-character attention-key work field. A {@code null} key action is a legitimate
 * value meaning "no key resolved", not a defect: the procedural copybook
 * {@code app/cpy/CSSTRPFY.cpy}, whose two paragraphs begin at lines 17 and 80, has no catch-all
 * branch, so an unrecognised terminal identifier leaves the work field untouched. Substituting the
 * enter key, or any other constant, for an unresolved keystroke would manufacture a state the legacy
 * system cannot reach.
 *
 * <p>Resolving a raw terminal identifier into one of those constants - including collapsing the
 * upper twelve program-function keys onto the lower twelve, which the same copybook does and which is
 * why no constant exists for the upper twelve - belongs to the utility-layer translator that owns
 * that mapping. This record neither performs nor imports that translation.
 *
 * <p><strong>The save and delete confirmations are keystrokes, so no confirmation flag exists
 * here.</strong> The update screen prompts for a specific program-function keystroke before it
 * commits, at line 336 of {@code COUSR02C.cbl}, and the delete screen does the same at line 283 of
 * {@code COUSR03C.cbl}. Both gates are satisfied by the key action alone. A separate confirmation
 * indicator would duplicate that state and allow the two to disagree, so no such component is
 * declared, and no key mnemonic is named in this file.
 *
 * <h2>Why a length bound is the only constraint on any component</h2>
 *
 * <p>Each of the four programs runs an ordered, first-satisfied-wins emptiness cascade and reports
 * exactly one message per submission. The orders are <strong>not</strong> the same, and the
 * difference is contractual rather than incidental:
 *
 * <ul>
 *   <li>the add screen tests the first name, then the last name, then <strong>the user id
 *       third</strong>, then the password, then the user type - the five clauses reporting at lines
 *       120, 126, 132, 138 and 144 of {@code COUSR01C.cbl};</li>
 *   <li>the update screen tests <strong>the user id first</strong>, then the first name, the last
 *       name, the password and the user type - reporting at lines 182, 188, 194, 200 and 206 of
 *       {@code COUSR02C.cbl} - and tests the user id once more on its retrieval path, at line
 *       148;</li>
 *   <li>the delete screen tests the user id and nothing else, at lines 147 and 179 of
 *       {@code COUSR03C.cbl};</li>
 *   <li>the list screen tests none of them, because every one of its items is optional.</li>
 * </ul>
 *
 * <p>Bean Validation cannot express that. It evaluates constraints in an unspecified order and
 * reports every violation of a submission at once, so a request with three empty fields would yield
 * three messages where the legacy yields one - and it would yield them in an order this contract
 * cannot pin down, which is precisely the ordering the add and update screens disagree about. Worse,
 * a mandatory-field annotation is a property of the <em>operation</em>, not of the type: the user id
 * is mandatory for three operations and optional for the fourth, the password is mandatory for two
 * and absent from a third, and the two name parts and the user type are mandatory for two and
 * optional for two.
 *
 * <p>This record therefore declares <strong>no</strong> presence, format, character-class, range or
 * cross-field constraint of any kind - no {@code NotNull}, {@code NotBlank}, {@code NotEmpty},
 * {@code Pattern}, {@code Digits}, {@code Min}, {@code Max}, {@code Positive} or {@code AssertTrue},
 * no validation group, no group sequence, no cascade into the selection collection and no operation
 * discriminator. Every component tolerates {@code null}, the empty string and an all-blank string
 * without complaint, which is what allows the service to run each operation's own cascade and emit
 * the single correct message. The controller endpoint identifies the operation; the request does not
 * carry a mode.
 *
 * <p>The one constraint that <em>is</em> present is a maximum length per value, set to the declared
 * screen width. It measures only: it never trims, folds, pads, strips or canonicalises anything, so
 * leading and trailing blanks - which are real data in a fixed-width estate - survive validation
 * untouched.
 *
 * <h2>The password is carried inbound only, and never rendered</h2>
 *
 * <p><strong>The delete screen has no password item.</strong> Its map declares none and its program
 * references none, so a delete request simply leaves the component absent. The component is declared
 * on the shared record because two of the four operations need it, and it is never required by any of
 * them at this layer.
 *
 * <p>The two screens that do declare it define the item as non-display in the mapset, at line 126 of
 * {@code app/bms/COUSR01.bms} and line 130 of {@code app/bms/COUSR02.bms}, so the legacy terminal
 * accepted the value and never showed it. This type honours that asymmetry in the only place a record
 * can leak it: {@link #toString()} is overridden and substitutes a fixed placeholder. A record's
 * implicitly generated string form prints every component, which here would put a plaintext
 * credential into any log line, exception message, debugger view, diagnostic dump or test-failure
 * report that stringifies the object. The placeholder is a constant - never the value, never a
 * prefix or suffix of it, never a digest of it and never a length-preserving mask, because a mask
 * that matches the value's length still discloses the length.
 *
 * <p>Nothing else happens to the value here. This type does not compare it, encode it, digest it,
 * salt it, verify it, echo it or write it anywhere. No credential literal, no seeded identity and no
 * work factor appears in this file. Credential protection belongs to the security configuration and
 * to the service that persists the record.
 *
 * <p><strong>One legacy behaviour is deliberately not reproduced.</strong> The update screen reads
 * the stored record and writes the stored plaintext password back into the input item before
 * redisplaying the screen, at line 169 of {@code COUSR02C.cbl}, then compares the resubmitted value
 * against the stored one to decide whether it changed, at lines 227 to 230. The migrated contract
 * never round-trips a credential: the stored value is a one-way digest, the response type exposes
 * neither cleartext nor digest, and no current-password or confirm-password component is added here
 * to recreate the comparison. That substitution is the documented parity exception recorded in
 * {@code docs/decision-log.md}; the persisted column is widened to hold a digest, which is the one
 * intentional width change in the eleven-table schema and the reason the eight-character plaintext
 * item must never travel outbound.
 *
 * <p>The credential record {@code app/cpy/CSUSR01Y.cpy} places the password third from last in an
 * eighty-byte fixed-width image. No byte offset, filler or record width is modelled here and no
 * positional extraction is performed: this is a transport contract, and fixed-width decoding belongs
 * to the utility layer.
 *
 * <h2>Navigation state, and what navigation is not</h2>
 *
 * <p>The navigation context component is the echoed screen-flow state the legacy programs carried in
 * the shared communication area declared by {@code app/cpy/COCOM01Y.cpy}. It is immutable request
 * state supplied by the client and returned to it, not a server-side session: the client drives the
 * next call, exactly as the legacy terminal did.
 *
 * <p>No route, route table, route enumeration or route holder appears here, and this type dispatches
 * nothing. Route vocabulary belongs to the navigation service. Nor does the screen work-area
 * transfer object appear: these four programs are not part of the family that includes it. Nor does
 * the response-side paging contract, which is a cursor-and-direction shape for outbound use; this
 * request carries the raw echoed paging values instead.
 *
 * <p>All four transactions are administrator-gated in {@code app/csd/CARDDEMO.CSD}. That gating is
 * enforced by the module's security configuration, so no authority, no privilege set and no
 * administrator indicator is carried on this request - a client-supplied privilege claim would be a
 * privilege-escalation surface, and authorisation decided by request content is not authorisation.
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the CardDemo COBOL estate at checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The estate under {@code app/} is read-only
 * reference: it is cited above and below by member name, item name, declared width and line number
 * only, and no source text from it is reproduced.
 *
 * @param userId the eight-character identifier of the user the operation acts on - the add map's
 *     user-id item at line 72 of {@code app/cpy-bms/COUSR01.CPY}, and the target reading of the
 *     identically named item at line 60 of both {@code app/cpy-bms/COUSR02.CPY} and
 *     {@code app/cpy-bms/COUSR03.CPY}. Matches the user-id field at line 18 of
 *     {@code app/cpy/CSUSR01Y.cpy}. May be {@code null}, empty or blank so that each operation's
 *     own ordered cascade decides; carried exactly as supplied.
 * @param searchUserId the eight-character browse start key of the list screen - the entry item at
 *     line 66 of {@code app/cpy-bms/COUSR00.CPY}. Distinct from {@link #userId()} because a blank
 *     value here means "start at the first record" rather than "no target", per lines 218 to 222 of
 *     {@code app/cbl/COUSR00C.cbl}. Carried exactly as supplied.
 * @param firstName the twenty-character first name - line 60 of {@code app/cpy-bms/COUSR01.CPY} and
 *     line 66 of both {@code app/cpy-bms/COUSR02.CPY} and {@code app/cpy-bms/COUSR03.CPY}, matching
 *     line 19 of {@code app/cpy/CSUSR01Y.cpy}. Carried exactly as supplied, blanks included.
 * @param lastName the twenty-character last name - line 66 of {@code app/cpy-bms/COUSR01.CPY} and
 *     line 72 of both {@code app/cpy-bms/COUSR02.CPY} and {@code app/cpy-bms/COUSR03.CPY}, matching
 *     line 20 of {@code app/cpy/CSUSR01Y.cpy}. Carried exactly as supplied, blanks included.
 * @param password the eight-character credential, declared only by the add map at line 78 of
 *     {@code app/cpy-bms/COUSR01.CPY} and the update map at line 78 of
 *     {@code app/cpy-bms/COUSR02.CPY}; the delete map declares none, so a delete request leaves this
 *     absent. Matches line 21 of {@code app/cpy/CSUSR01Y.cpy}. This value is a secret: it is
 *     replaced by a fixed placeholder in {@link #toString()} and must never be written to any
 *     diagnostic sink.
 * @param userType the raw one-character user-type code - line 84 of both
 *     {@code app/cpy-bms/COUSR01.CPY} and {@code app/cpy-bms/COUSR02.CPY}, line 78 of
 *     {@code app/cpy-bms/COUSR03.CPY}, and the per-row item of the list map. Matches line 22 of
 *     {@code app/cpy/CSUSR01Y.cpy}. Bounded by width only and <em>not</em> restricted to the two
 *     values the estate's condition names declare, because neither program tests the value; see the
 *     dedicated section above.
 * @param rowSelections the per-row selection characters of the list screen, one element per screen
 *     row in row order - the ten one-character items running from line 72 to line 342 of
 *     {@code app/cpy-bms/COUSR00.CPY}. Never {@code null}: a {@code null} argument becomes the empty
 *     immutable list. Order is significant and is preserved; the collection is neither filtered,
 *     compacted, re-ordered nor padded, and nothing here interprets an element.
 * @param pageNumber the eight-character displayed page number of the list screen - line 60 of
 *     {@code app/cpy-bms/COUSR00.CPY}, whose communication-area companion at line 70 of
 *     {@code app/cbl/COUSR00C.cbl} is an eight-digit display value. Text rather than a number so
 *     that leading zeros and the declared width survive; echoed, never computed from.
 * @param firstUserIdOnPage the eight-character key of the first row of the page just displayed -
 *     the retained first-key field at line 68 of {@code app/cbl/COUSR00C.cbl}. Echoed by the client
 *     so the service can reposition a backward browse without holding server-side cursor state.
 * @param lastUserIdOnPage the eight-character key of the last row of the page just displayed - the
 *     retained last-key field at line 69 of {@code app/cbl/COUSR00C.cbl}. Echoed for the same reason
 *     as {@link #firstUserIdOnPage()}.
 * @param keyAction the resolved attention key, or {@code null} when the submitted terminal
 *     identifier resolved to nothing. Carries the confirmation keystroke the update and delete
 *     screens gate on; no default is substituted and no key folding is performed here.
 * @param navigationContext the echoed screen-flow state from the shared communication area declared
 *     by {@code app/cpy/COCOM01Y.cpy}, or {@code null} on a first entry that has none. Echoed
 *     request state, never a server-side session, and never a routing instruction.
 */
public record UserRequest(

        /* Add map user-id item, width 8, COUSR01.CPY line 72; the target reading of the update and
         * delete maps' item at COUSR02.CPY line 60 and COUSR03.CPY line 60. */
        @Size(max = UserRequest.USER_ID_LENGTH) String userId,

        /* List map browse start key, width 8, COUSR00.CPY line 66 - deliberately separate from
         * userId because a blank start key is meaningful; COUSR00C.cbl lines 218 to 222. */
        @Size(max = UserRequest.USER_ID_LENGTH) String searchUserId,

        /* First-name item, width 20, COUSR01.CPY line 60 / COUSR02.CPY line 66 / COUSR03.CPY line
         * 66; credential record CSUSR01Y.cpy line 19. */
        @Size(max = UserRequest.NAME_PART_LENGTH) String firstName,

        /* Last-name item, width 20, COUSR01.CPY line 66 / COUSR02.CPY line 72 / COUSR03.CPY line
         * 72; credential record CSUSR01Y.cpy line 20. */
        @Size(max = UserRequest.NAME_PART_LENGTH) String lastName,

        /* Password item, width 8, COUSR01.CPY line 78 and COUSR02.CPY line 78 only - the delete map
         * declares none. Non-display in the mapsets; redacted by toString. */
        @Size(max = UserRequest.PASSWORD_LENGTH) String password,

        /* User-type item, width 1, COUSR01.CPY line 84 / COUSR02.CPY line 84 / COUSR03.CPY line 78.
         * Raw character on purpose: no program tests the value. */
        @Size(max = UserRequest.USER_TYPE_LENGTH) String userType,

        /* The ten per-row selection items of the list map, width 1 each, COUSR00.CPY lines 72 to
         * 342. Ordered and positional; the element bound is a container-element constraint. */
        List<@Size(max = UserRequest.ROW_SELECTION_LENGTH) String> rowSelections,

        /* Displayed page number, width 8, COUSR00.CPY line 60; text, not a number, so that the
         * leading zeros of the eight-digit companion at COUSR00C.cbl line 70 survive. */
        @Size(max = UserRequest.PAGE_NUMBER_LENGTH) String pageNumber,

        /* Retained first key of the displayed page, width 8, COUSR00C.cbl line 68. */
        @Size(max = UserRequest.USER_ID_LENGTH) String firstUserIdOnPage,

        /* Retained last key of the displayed page, width 8, COUSR00C.cbl line 69. */
        @Size(max = UserRequest.USER_ID_LENGTH) String lastUserIdOnPage,

        /* Resolved attention key. Nullable on purpose: the CSSTRPFY key mapping has no catch-all
         * branch, so an unresolved identifier is a real state. */
        KeyAction keyAction,

        /* Echoed screen-flow state from COCOM01Y.cpy. Request state, not a server session. */
        NavigationContext navigationContext) {

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of the credential.
     *
     * <p>A constant rather than any transformation of the value, so nothing about the credential -
     * not its characters, not a prefix or suffix, not a digest and <em>not its length</em> - can be
     * recovered from a stringified instance. A length-preserving mask was rejected deliberately: for
     * an item this narrow, disclosing the length materially narrows a guess.
     *
     * <p>The constant is named without the word it stands in for, so that a credential scan of this
     * module cannot mistake it for a transcribed secret. Private, because it is a rendering detail
     * and not part of the request contract.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Width in characters of a user identifier: 8.
     *
     * <p>The declared width of the add map's user-id item at line 72 of
     * {@code app/cpy-bms/COUSR01.CPY}, of the identically shaped item at line 60 of both
     * {@code app/cpy-bms/COUSR02.CPY} and {@code app/cpy-bms/COUSR03.CPY}, of the list map's browse
     * entry item at line 66 of {@code app/cpy-bms/COUSR00.CPY} and of each row's user id, and of the
     * two retained browse-boundary keys at lines 68 and 69 of {@code app/cbl/COUSR00C.cbl}. It is
     * also the width of the credential record's key at line 18 of {@code app/cpy/CSUSR01Y.cpy}.
     *
     * <p>One constant governs all four user-identifier components because they are the same kind of
     * value at the same declared width - not because their widths happen to coincide. The bound only
     * reports an over-length value; it never trims, pads or otherwise alters one.
     */
    public static final int USER_ID_LENGTH = 8;

    /**
     * Width in characters of each name part: 20.
     *
     * <p>The declared width of the first-name and last-name items on all three single-record maps -
     * lines 60 and 66 of {@code app/cpy-bms/COUSR01.CPY}, lines 66 and 72 of
     * {@code app/cpy-bms/COUSR02.CPY}, lines 66 and 72 of {@code app/cpy-bms/COUSR03.CPY} - of the
     * two per-row name items of {@code app/cpy-bms/COUSR00.CPY}, and of the two name fields at lines
     * 19 and 20 of {@code app/cpy/CSUSR01Y.cpy}.
     *
     * <p>One constant governs both name components because they are the two parts of one name on one
     * record, declared at one width. This is emphatically <em>not</em> the twenty-five-character
     * combined name of the internal staging row at lines 56 to 64 of {@code app/cbl/COUSR00C.cbl},
     * which this type does not model. The bound only reports an over-length value.
     */
    public static final int NAME_PART_LENGTH = 20;

    /**
     * Width in characters of the credential item: 8.
     *
     * <p>The declared width of the password item at line 78 of {@code app/cpy-bms/COUSR01.CPY} and at
     * line 78 of {@code app/cpy-bms/COUSR02.CPY}, corroborated by the eight-character field length in
     * both mapsets and by the credential record's field at line 21 of {@code app/cpy/CSUSR01Y.cpy}.
     * The delete map declares no such item at all.
     *
     * <p>Declared separately from {@link #USER_ID_LENGTH} even though the values are equal, because a
     * credential and an identifier are unrelated fields whose widths coincide; neither is derived
     * from the other, and a future change to one must not propagate to the other. This is the width
     * of the value a client may submit and carries no implication for how it is stored: the persisted
     * column is wider so that it can hold a one-way digest instead of cleartext.
     */
    public static final int PASSWORD_LENGTH = 8;

    /**
     * Width in characters of the raw user-type code: 1.
     *
     * <p>The declared width of the user-type item at line 84 of {@code app/cpy-bms/COUSR01.CPY}, line
     * 84 of {@code app/cpy-bms/COUSR02.CPY} and line 78 of {@code app/cpy-bms/COUSR03.CPY}, of each
     * row's type item in {@code app/cpy-bms/COUSR00.CPY}, and of the field at line 22 of
     * {@code app/cpy/CSUSR01Y.cpy}.
     *
     * <p>The bound deliberately does <em>not</em> restrict the value to the two codes the estate's
     * condition names declare, because the programs test only that the item is non-blank - line 142
     * of {@code app/cbl/COUSR01C.cbl} and line 204 of {@code app/cbl/COUSR02C.cbl} - and then store
     * whatever arrived, at lines 158 and 232 respectively. This is width, not vocabulary. It is also
     * emphatically not the eight-character type field of the internal staging row at lines 56 to 64
     * of {@code app/cbl/COUSR00C.cbl}.
     */
    public static final int USER_TYPE_LENGTH = 1;

    /**
     * Width in characters of one row's selection item: 1.
     *
     * <p>The declared width of each of the ten selection items of {@code app/cpy-bms/COUSR00.CPY},
     * running from line 72 to line 342. Applied as a container-element bound to every element of
     * {@link #rowSelections()}, which is how a per-element width is expressed without cascading
     * validation into the collection.
     *
     * <p>Declared separately from {@link #USER_TYPE_LENGTH} even though both are one, because a
     * selection keystroke and a stored user-type code are unrelated values on unrelated items.
     * The bound reports an over-length element and interprets none: which characters a selection may
     * legally carry, and which element wins, are the service's decisions.
     */
    public static final int ROW_SELECTION_LENGTH = 1;

    /**
     * Width in characters of the displayed page number: 8.
     *
     * <p>The declared width of the page-number item at line 60 of {@code app/cpy-bms/COUSR00.CPY},
     * corroborated by its eight-digit communication-area companion at line 70 of
     * {@code app/cbl/COUSR00C.cbl}.
     *
     * <p>This is the width of a <em>displayed value</em>, not a row count and not a cap: it bounds
     * how many characters the echoed page label may occupy. The number of rows the screen presents is
     * a different quantity entirely, is declared once by the response-side paging contract, and is
     * deliberately absent from this request. The width is also specific to this screen - another list
     * screen in the estate declares a narrower page number, and the two are never reconciled.
     */
    public static final int PAGE_NUMBER_LENGTH = 8;

    /**
     * Normalizes the selection collection so that the component is never {@code null}, never aliased
     * to caller-owned state and never mutable.
     *
     * <p>A {@code null} collection becomes the empty immutable list rather than being stored, so
     * every accessor sees a usable collection and no caller has to null-check before iterating. A
     * non-{@code null} collection is defensively copied with
     * {@link List#copyOf(java.util.Collection)}, which detaches it from the caller and rejects a
     * {@code null} element - a selection with no character would be neither blank nor a choice, and
     * silently dropping it would shift every later element onto the wrong screen row.
     *
     * <p><strong>The copy preserves order and length exactly.</strong> Nothing is filtered,
     * de-duplicated, compacted, re-ordered or padded, because element <em>n</em> is the selection
     * typed against screen row <em>n</em> and that correspondence is the whole meaning of the
     * collection.
     *
     * <p>Every other component is stored exactly as supplied - including {@code null}, the empty
     * string and any leading or trailing blank - because the items they derive from are fixed-width
     * and blank-significant, and because each operation's own ordered emptiness cascade, which lives
     * in the service layer, must see precisely what the client sent.
     */
    public UserRequest {
        rowSelections = (rowSelections == null) ? List.of() : List.copyOf(rowSelections);
    }

    /**
     * Returns a diagnostic representation that mirrors the record layout but redacts the credential.
     *
     * <p><strong>What is shown.</strong> Every non-credential component, verbatim. The two user
     * identifiers, the two name parts, the raw user-type character, the ordered selections, the
     * echoed paging values, the attention key and the navigation context are exactly what anyone
     * diagnosing an administrative user request needs, and withholding them would remove this type's
     * diagnostic value without protecting anything. The navigation context redacts its own
     * identifying fields, so nesting it here discloses nothing further.
     *
     * <p><strong>What is withheld.</strong> The password, replaced by
     * {@link #REDACTION_PLACEHOLDER} - a fixed constant rather than a length-preserving mask, for the
     * reason given on that constant. No branch of this method can render the credential value.
     *
     * <p>{@code equals} is intentionally not overridden, so it keeps comparing every component as the
     * record semantics require; equality is an in-memory operation that emits nothing. This override
     * exists solely to prevent credential leakage through diagnostics, and it deliberately performs
     * no validation, normalisation or comparison of its own.
     *
     * @return the request state with the credential replaced by a fixed placeholder
     */
    @Override
    public String toString() {
        return "UserRequest["
                + "userId=" + userId
                + ", searchUserId=" + searchUserId
                + ", firstName=" + firstName
                + ", lastName=" + lastName
                + ", password=" + REDACTION_PLACEHOLDER
                + ", userType=" + userType
                + ", rowSelections=" + rowSelections
                + ", pageNumber=" + pageNumber
                + ", firstUserIdOnPage=" + firstUserIdOnPage
                + ", lastUserIdOnPage=" + lastUserIdOnPage
                + ", keyAction=" + keyAction
                + ", navigationContext=" + navigationContext
                + "]";
    }
}
