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
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Null;
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
 * scope.</strong> Every width declared below was measured from {@code app/cpy-bms/COUSR00.CPY},
 * {@code app/cpy-bms/COUSR01.CPY}, {@code app/cpy-bms/COUSR02.CPY} and
 * {@code app/cpy-bms/COUSR03.CPY} rather than inferred, and each component states its own item name,
 * width and map line at its declaration. Two asymmetries in those maps matter here and are carried:
 * the add map declares the two name parts before the user id while the update and delete maps declare
 * the user id first, and <strong>the delete map declares no password item at all</strong>.
 *
 * <p>Everything else those maps declare is out of scope: the six leading screen-furniture items and the
 * seventy-eight-character message item are outbound and belong to the response contract, and the
 * generated terminal artefacts - per-item length, flag and attribute groups, the leading terminal
 * input/output area filler, the map coordinates and field lengths in {@code app/bms/COUSR0*.bms}, the
 * colour and highlight attributes, the cursor-positioning value and the marker byte - are 3270 plumbing.
 * Modelling any of them would tie a REST payload to a terminal that no longer exists.
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
 * <p>The ten-occurrence working-storage table at lines 56 to 64 of {@code COUSR00C.cbl} is shaped for a
 * fixed-pitch display rather than for the map: it carries a single combined twenty-five-character name,
 * an eight-character user type and three alignment fillers. None of those widths is the contract. The
 * map keeps the name in two separate twenty-character parts and declares the user type one character
 * wide, exactly as the credential record {@code app/cpy/CSUSR01Y.cpy} does at its lines 19, 20 and 22,
 * so this record models eight, twenty, twenty and one - and models no combined name, no wider type and
 * no filler. Copying the staging row would produce a type that cannot round-trip a name whose two parts
 * together exceed twenty-five characters.
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
 * <p><strong>The two authoritative codes are {@code A} and {@code U}, and they are the whole
 * vocabulary.</strong> {@code CDEMO-USRTYP-ADMIN} carries {@code A} at line 27 of
 * {@code app/cpy/COCOM01Y.cpy} and {@code CDEMO-USRTYP-USER} carries {@code U} at line 28, matching
 * {@code SEC-USR-TYPE} at line 22 of {@code app/cpy/CSUSR01Y.cpy}. Those two characters are the only
 * values the estate declares, and they are what a well-formed request carries. Width tolerance is not
 * a second vocabulary: it is the deliberate absence of enforcement described above, retained so that
 * a character the mainframe already stores can still be read back.
 *
 * <p><strong>That vocabulary is shared, not local to this type.</strong> The same raw one-character
 * code is what the sign-on response returns, what the navigation context carries between turns and
 * across a signed token claim, and what the user-administration response echoes at its top level and on
 * every list row. No type in this package emits the enumeration's Java constant names and none accepts a
 * spelled-out role word, so a client learns the two codes once. Resolving a code to a typed role lives
 * behind the non-throwing lookup on {@code com.carddemo.domain.enums.UserType}, which yields an empty
 * result rather than raising for an undeclared character, and never in a transport type.
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
 * no group sequence, no element-level cascade into the selection collection and no operation
 * discriminator component. Every component tolerates {@code null}, the empty string and an all-blank
 * string without complaint, which is what allows the service to run each operation's own cascade and
 * emit the single correct message. The controller endpoint identifies the operation; the request does
 * not carry a mode.
 *
 * <p><strong>The operation markers below are the one use of validation groups, and every constraint
 * scoped to one asserts an absence.</strong> That is the opposite of a presence rule and does not
 * reintroduce the ordering problem described above: an absence assertion adds no mandatory field,
 * contributes no message to any operation's cascade, and cannot fire at all unless a caller names the
 * group explicitly, so a submission validated the ordinary unqualified way behaves exactly as it would
 * if the groups did not exist. What they buy is that one record body serving four screens cannot carry
 * a component the screen it is serving never declared - a list request cannot smuggle a credential and
 * an add request cannot smuggle a page cursor.
 *
 * <p>The primary constraint that <em>is</em> present is a maximum length per value, set to the
 * declared screen width. It measures only: it never trims, folds, pads, strips or canonicalises
 * anything, so leading and trailing blanks - which are real data in a fixed-width estate - survive
 * validation untouched.
 *
 * <p><strong>Two structural bounds sit beside it, and neither is a field edit.</strong> The selection
 * sequence carries a cardinality bound at the screen's ten rows, and the navigation component carries a
 * cascade so that the widths it declares are actually evaluated. Both differ in kind from the cascades
 * above: a sequence longer than the screen and an over-wide value inside a nested record are states no
 * legacy submission could produce, so the estate has no ordered check and no message for either and
 * there is nothing to pre-empt - what there is instead is unbounded work, an arbitrarily long sequence
 * the canonical constructor would faithfully copy and nested widths that nothing evaluates. Neither
 * bound alters, reorders or truncates a value, and neither expresses an opinion about <em>which</em>
 * characters a selection may carry.
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
 * accepted the value and never showed it. This type honours that asymmetry on <strong>both</strong>
 * of the two routes by which a record can disclose a component, and the two are independent.
 *
 * <p>The first is the diagnostic route. {@link #toString()} is overridden and substitutes a fixed
 * placeholder, because a record's implicitly generated string form prints every component, which here
 * would put a plaintext credential into any log line, exception message, debugger view, diagnostic
 * dump or test-failure report that stringifies the object. The placeholder is a constant - never the
 * value, never a prefix or suffix of it, never a digest of it and never a length-preserving mask,
 * because a mask that matches the value's length still discloses the length.
 *
 * <p>The second is the serialization route, and redacting the first does nothing about it. An
 * accessor a serializer can reach is a value a serializer will write, and this type is reachable by
 * one wherever a request object is used as a response body, cached, queued, attached to an audit
 * event, snapshotted for a problem report or recorded as a trace attribute - none of which passes
 * through {@code toString()} at all. The component is therefore annotated
 * {@link JsonProperty.Access#WRITE_ONLY}, which closes the outbound direction while leaving the
 * inbound direction open: a document carrying the credential still binds it, because an operation that
 * refuses to read a credential cannot store one. The published interface description says the same
 * thing declaratively, so a generated client is told the property is write-only rather than
 * discovering it by omission.
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
 * <p>All four transactions are administrator-gated in {@code app/csd/CARDDEMO.CSD}. What follows from
 * that here, and only here, is a negative: no authority, no privilege set and no administrator
 * indicator is carried on this request, because a client-supplied privilege claim would be a
 * privilege-escalation surface and authorisation decided by request content is not authorisation.
 *
 * <p><strong>The positive half of that gating is discharged elsewhere, and it is asserted rather than
 * described.</strong> {@code api.AdminUserController} receives this type and maps all four operations
 * beneath {@code /api/admin/users}, which is inside the path prefix the module's security configuration
 * requires the administrator authority for, so an unprivileged caller is refused before any component of
 * this record is read. Two tests hold that in place from different directions:
 * {@code config.SecurityConfigTest} drives a standard principal at the gated region through a real chain
 * and requires a forbidden answer, and {@code config.DeliveredRouteSecurityStateTest} requires each of
 * these four operations to stay inside that prefix - so moving one out fails the build instead of silently
 * falling through to the catch-all rule that admits any authenticated caller.
 *
 * <p>The negative in the paragraph above is therefore load-bearing rather than merely cautious: because
 * the authority is established by the filter chain from the bearer grant, nothing needs to read one from
 * this request, and the identity echoed in the navigation component is never the thing authorised. That is
 * the arrangement working - the authority arrives on the transport, and the request body carries only what
 * the operation acts on.
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
 *     absent. Matches line 21 of {@code app/cpy/CSUSR01Y.cpy}. This value is a secret: it is replaced
 *     by a fixed placeholder in {@link #toString()}, excluded from every serialized document by
 *     {@link JsonProperty.Access#WRITE_ONLY}, published as write-only in the interface description,
 *     and must never be written to any diagnostic sink. Being write-only means a body deserialized
 *     from a client and re-serialized cannot carry it back out. No response type in this package
 *     exposes a credential either: the stored value is a one-way digest, and the legacy update
 *     screen's habit of pre-filling the field from the record (line 169 of
 *     {@code app/cbl/COUSR02C.cbl}) is deliberately not reproduced.
 * @param userType the raw one-character user-type code - line 84 of both
 *     {@code app/cpy-bms/COUSR01.CPY} and {@code app/cpy-bms/COUSR02.CPY}, line 78 of
 *     {@code app/cpy-bms/COUSR03.CPY}, and the per-row item of the list map. Matches line 22 of
 *     {@code app/cpy/CSUSR01Y.cpy}. The two authoritative codes are {@code A} for an administrator
 *     and {@code U} for a standard user, the same single vocabulary every other type in this package
 *     uses on the wire. Bounded by width only and <em>not</em> restricted to those two values, because
 *     neither program tests the value; resolution to a typed role happens through one non-throwing
 *     adapter in the service layer. See the dedicated section above.
 * @param rowSelections the per-row selection characters of the list screen, one element per screen
 *     row in row order - the ten one-character items running from line 72 to line 342 of
 *     {@code app/cpy-bms/COUSR00.CPY}. Never {@code null}: a {@code null} argument becomes the empty
 *     immutable list. Order is significant and is preserved; the collection is neither filtered,
 *     compacted, re-ordered nor padded, and nothing here interprets an element.
 * @param displayedPageNumber the eight-character displayed page number of the list screen - line 60
 *     of {@code app/cpy-bms/COUSR00.CPY}, whose communication-area companion at line 70 of
 *     {@code app/cbl/COUSR00C.cbl} is an eight-digit display value. Text rather than a number so
 *     that leading zeros and the declared width survive. <strong>Server-owned and non-bindable:</strong>
 *     the map item appears at exactly two sites, lines 327 and 376 of {@code app/cbl/COUSR00C.cbl},
 *     and is the target of a MOVE at both. The program computes the number from its own retained
 *     counter, so a submitted value never influenced a page and is discarded on the way in. Its width
 *     is specific to this screen: the card-list contract declares a three-character page indicator,
 *     and the two are never reconciled.
 * @param firstUserIdOnPage the eight-character key of the first row of the page just displayed -
 *     the retained first-key field at line 68 of {@code app/cbl/COUSR00C.cbl}. Echoed by the client
 *     so the service can reposition a backward browse without holding server-side cursor state.
 *     <strong>Deliberately bindable</strong>, unlike the page number beside it: the backward-paging
 *     paragraph tests this value at line 239 and moves it into the browse key at line 242, so it is a
 *     genuine input and making it non-bindable would leave the server unable to position a backward
 *     page at all.
 * @param lastUserIdOnPage the eight-character key of the last row of the page just displayed - the
 *     retained last-key field at line 69 of {@code app/cbl/COUSR00C.cbl}. Echoed for the same reason
 *     as {@link #firstUserIdOnPage()}.
 * @param rowSnapshotToken authenticated server-minted snapshot of the ordered identifiers displayed
 *     in the ten list rows. It is accepted only by the list operation and is required when a row
 *     marker is submitted; the service resolves the selected position from this snapshot rather than
 *     from a mutable re-query.
 * @param keyAction the resolved attention key, or {@code null} when the submitted terminal
 *     identifier resolved to nothing. Carries the confirmation keystroke the update and delete
 *     screens gate on; no default is substituted and no key folding is performed here.
 * @param navigationContext the echoed screen-flow state from the shared communication area declared
 *     by {@code app/cpy/COCOM01Y.cpy}, or {@code null} on a first entry that has none. Echoed
 *     request state, never a server-side session, and never a routing instruction.
 */
public record UserRequest(

        /* Add map user-id item, width 8, COUSR01.CPY line 72; the target reading of the update and
         * delete maps' item at COUSR02.CPY line 60 and COUSR03.CPY line 60. Absent on the list
         * operation, whose only scalar identifier input is searchUserId: the list map's ten
         * per-row identifier items are echoes it writes, never scalar inputs it reads.
         *
         * THE BOUND IS THE SCREEN FIELD'S WIDTH, NOT THE STORED WIDTH. A 3270 field transmits whatever
         * the operator typed, so a blank or part-typed identifier is a value this contract must be able
         * to carry: the legacy program answers it with a field-level screen message rather than refusing
         * the transmission, and an exact-width bound here would turn that message into a rejected
         * request. Exact width is enforced where a short value could do damage - com.carddemo.domain
         * .UserSecurity refuses anything but eight characters before an insert or an update, and
         * V1__create_schema.sql carries the same rule as a check constraint - so a short identifier can
         * be typed, is reported as a field error, and can never be stored. No digit class applies at
         * either layer: the record declares this field alphanumeric and every seeded identity carries
         * letters. */
        @Size(max = UserRequest.USER_ID_LENGTH)
        @Null(groups = UserRequest.ListOperation.class)
        String userId,

        /* List map browse start key, width 8, COUSR00.CPY line 66 - deliberately separate from
         * userId because a blank start key is meaningful; COUSR00C.cbl lines 218 to 222. Declared by
         * the list map alone, so it is inapplicable to the other three operations. */
        @Size(max = UserRequest.USER_ID_LENGTH)
        @Null(groups = {UserRequest.AddOperation.class, UserRequest.UpdateOperation.class,
                UserRequest.DeleteOperation.class})
        String searchUserId,

        /* First-name item, width 20, COUSR01.CPY line 60 / COUSR02.CPY line 66 / COUSR03.CPY line
         * 66; credential record CSUSR01Y.cpy line 19. Consumed by add (COUSR01C) and update
         * (COUSR02C.cbl lines 186 and 219 to 220) only. The delete map declares the item but
         * COUSR03C never reads it - lines 157, 165 and 353 are the only sites and all three write
         * to it - so on delete it is a display echo the server produces, not an input. */
        @Size(max = UserRequest.NAME_PART_LENGTH)
        @Null(groups = {UserRequest.ListOperation.class, UserRequest.DeleteOperation.class})
        String firstName,

        /* Last-name item, width 20, COUSR01.CPY line 66 / COUSR02.CPY line 72 / COUSR03.CPY line
         * 72; credential record CSUSR01Y.cpy line 20. Read by update at COUSR02C.cbl lines 192 and
         * 223 to 224; only ever written by delete at COUSR03C.cbl lines 158, 166 and 354. */
        @Size(max = UserRequest.NAME_PART_LENGTH)
        @Null(groups = {UserRequest.ListOperation.class, UserRequest.DeleteOperation.class})
        String lastName,

        /* Password item, width 8, COUSR01.CPY line 78 and COUSR02.CPY line 78 only - the delete map
         * declares none, and neither does the list map. Non-display in the mapsets; redacted by
         * toString; write-only on the wire. Read by update at COUSR02C.cbl lines 198 and 227 to
         * 228. Because two of the four maps declare no credential item at all, accepting one on
         * those two operations would transport a credential the legacy screen had no field for. */
        @Size(max = UserRequest.PASSWORD_LENGTH)
        @Null(groups = {UserRequest.ListOperation.class, UserRequest.DeleteOperation.class})
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Schema(
                accessMode = Schema.AccessMode.WRITE_ONLY,
                format = "password",
                description =
                        "Credential supplied by an administrator on the add and update operations"
                                + " only, from PASSWDI at app/cpy-bms/COUSR01.CPY line 78 and"
                                + " app/cpy-bms/COUSR02.CPY line 78. Accepted inbound and never"
                                + " emitted outbound: the stored value is a one-way digest, so no"
                                + " response in this package can echo it."
                                + " REQUIRED on add, because a new record has no stored digest to carry"
                                + " forward. CONDITIONALLY required on update, where three states are"
                                + " distinguished: omitted leaves the credential unchanged and carries"
                                + " the stored digest forward; present but blank is reported as a"
                                + " missing field, which is the legacy map's own empty-item condition;"
                                + " present and populated is hashed and replaces the stored digest."
                                + " Rejected outright on list and delete, whose maps declare no"
                                + " credential item at all.")
        String password,

        /* User-type item, width 1, COUSR01.CPY line 84 / COUSR02.CPY line 84 / COUSR03.CPY line 78.
         * Raw character on purpose: no program tests the value. Read by update at COUSR02C.cbl lines
         * 204 and 231 to 232; only ever written by delete at COUSR03C.cbl lines 159, 167 and 355.
         * The published description states the A/U vocabulary that every type in this package shares;
         * it is documentation, not a constraint, because a value restriction here would reject a
         * character the legacy record already holds. */
        @Size(max = UserRequest.USER_TYPE_LENGTH)
        @Null(groups = {UserRequest.ListOperation.class, UserRequest.DeleteOperation.class})
        @Schema(
                description =
                        "Raw one-character user-type code. The two authoritative codes are A for an"
                                + " administrator, from CDEMO-USRTYP-ADMIN at app/cpy/COCOM01Y.cpy"
                                + " line 27, and U for a standard user, from CDEMO-USRTYP-USER at"
                                + " line 28; the same two codes travel on every other user-type"
                                + " field in this API. Bounded by width rather than by value,"
                                + " because the legacy programs test only that the item is"
                                + " non-blank and store whatever arrived.")
        String userType,

        /* The ten per-row selection items of the list map, width 1 each, COUSR00.CPY lines 72 to
         * 342. Ordered and positional; the element bound is a container-element constraint and the
         * bound on the sequence itself is its cardinality - the screen has exactly ten rows, so an
         * eleventh selection corresponds to no row. Declared by the list map alone, so emptiness
         * rather than absence is asserted for the other three operations: the canonical constructor
         * normalises a null collection to an empty one, and an empty collection is therefore the only
         * representation of "carries nothing". */
        @Size(max = UserRequest.ROW_SELECTION_COUNT)
        @Size(max = 0, groups = {UserRequest.AddOperation.class,
                UserRequest.UpdateOperation.class, UserRequest.DeleteOperation.class})
                List<@Size(max = UserRequest.ROW_SELECTION_LENGTH) String> rowSelections,

        /* Displayed page number, width 8, COUSR00.CPY line 60; text, not a number, so that the
         * leading zeros of the eight-digit companion at COUSR00C.cbl line 70 survive. Server-owned
         * display state: PAGENUMI appears at exactly two sites, COUSR00C.cbl lines 327 and 376, and
         * at both it is the target of a MOVE. The number itself is computed entirely by the program
         * from its own retained counter - initialised at line 227, incremented at 309 to 310 and
         * decremented at 366 to 369 - so a submitted value could never have influenced a page. */
        @Size(max = UserRequest.DISPLAYED_PAGE_NUMBER_LENGTH)
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        String displayedPageNumber,

        /* Retained first key of the displayed page, width 8, COUSR00C.cbl line 68. Genuinely an
         * input and therefore deliberately bindable: the backward-paging paragraph tests it at line
         * 239 and moves it into the browse key at line 242. Marking it non-bindable would leave the
         * server unable to position a backward page at all. */
        @Size(max = UserRequest.USER_ID_LENGTH)
        @Null(groups = {UserRequest.AddOperation.class, UserRequest.UpdateOperation.class,
                UserRequest.DeleteOperation.class})
        String firstUserIdOnPage,

        /* Retained last key of the displayed page, width 8, COUSR00C.cbl line 69. Bindable for the
         * same reason: the forward-paging paragraph tests it at line 262 and moves it into the
         * browse key at line 265. */
        @Size(max = UserRequest.USER_ID_LENGTH)
        @Null(groups = {UserRequest.AddOperation.class, UserRequest.UpdateOperation.class,
                UserRequest.DeleteOperation.class})
        String lastUserIdOnPage,

        /* Server-minted authenticated snapshot of the ordered identifiers displayed in the list
         * rows. The legacy terminal returned each row's protected identifier with its marker; a REST
         * request instead returns this opaque token so an intervening insert or delete cannot move a
         * different identity into the selected position. It is list-only and never rendered by
         * toString. */
        @Null(groups = {UserRequest.AddOperation.class, UserRequest.UpdateOperation.class,
                UserRequest.DeleteOperation.class})
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String rowSnapshotToken,

        /* Resolved attention key. Nullable on purpose: the CSSTRPFY key mapping has no catch-all
         * branch, so an unresolved identifier is a real state. */
        KeyAction keyAction,

        /* Echoed screen-flow state from COCOM01Y.cpy. Request state, not a server session. Marked
         * @Valid so the bounds the nested type declares are actually evaluated: Bean Validation does
         * not descend into a nested object unless told to, so without this every bound inside it is
         * decorative and an over-long identifier reaches the service unreported. Cascading a bound
         * is not the same as adding one - no new constraint is introduced here. */
        @Valid NavigationContext navigationContext) {

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
     * Number of selection items the list screen declares, and therefore the largest sequence
     * {@link #rowSelections()} can legitimately carry: 10.
     *
     * <p>The list map declares exactly ten selection items, running from line 72 to line 342 of
     * {@code app/cpy-bms/COUSR00.CPY}, and the program fills exactly ten screen rows from the
     * ten-occurrence table at line 57 of {@code app/cbl/COUSR00C.cbl}. This is the shape of the
     * screen, not a tunable value, and it agrees with the row figure the response-side paging
     * contract names for this screen.
     *
     * <p><strong>Why a cardinality bound is present when no other structural constraint is.</strong>
     * Every other bound in this file measures one value against the width of the item it mirrors, and
     * the reason none of them constrains presence or vocabulary is that the legacy programs run
     * ordered, message-bearing cascades a declarative constraint cannot reproduce. A sequence longer
     * than the screen is a different kind of thing: element <em>n</em> is the selection typed against
     * screen row <em>n</em>, so an eleventh element corresponds to no row at all and no legacy
     * submission could produce one. There is no cascade to defer to and no message to preserve,
     * because the estate has no branch for a state it cannot reach. Without the bound the sequence is
     * unbounded, and the defensive copy in the canonical constructor would faithfully retain every
     * element of an arbitrarily long one.
     *
     * <p>Declared separately from the response-side row figure rather than referenced from it,
     * because this is the count of <em>inbound items a map declares</em> while that is the count of
     * <em>rows a page presents</em>; the two coincide for this screen and are not the same quantity.
     * Like every other bound here it only reports: an over-long sequence is reported and never
     * truncated, so nothing silently discards a selection.
     *
     * <p>Public because it is part of the request contract rather than an implementation choice: a
     * client needs to know how many positions it may submit. Deliberately not shared with the
     * card-list contract, which offers seven rows, nor reconciled with it: each screen is its own
     * contract and a change to one must not propagate to the other.
     *
     * <p><strong>This is the single declaration of the figure, and both enforcement points read
     * it.</strong> The declarative bound on {@link #rowSelections()} and the refusal in the canonical
     * constructor name this same constant, so the count cannot drift between the two and no second copy
     * of it exists to be widened alone.
     */
    public static final int ROW_SELECTION_COUNT = 10;

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
    public static final int DISPLAYED_PAGE_NUMBER_LENGTH = 8;

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
     *
     * <p><strong>The one thing it refuses.</strong> A collection carrying more selections than the
     * screen has rows is rejected rather than truncated. The list map declares exactly
     * {@value #ROW_SELECTION_COUNT} selection items and the program's own table is declared
     * {@code OCCURS 10 TIMES} at {@code app/cbl/COUSR00C.cbl} line 57, so an eleventh selection
     * corresponds to no row on any screen the estate ever rendered and cannot be a position the
     * operator typed against. Refusing is deliberate and is not a normalisation: a shorter
     * collection is accepted untouched at whatever length it arrived, because the emptiness cascade
     * must still see precisely what was sent, and nothing is padded out to the row count.
     *
     * @throws IllegalArgumentException if {@code rowSelections} carries more than
     *     {@value #ROW_SELECTION_COUNT} entries
     */
    public UserRequest {
        rowSelections = (rowSelections == null) ? List.of() : List.copyOf(rowSelections);
        if (rowSelections.size() > ROW_SELECTION_COUNT) {
            throw new IllegalArgumentException("rowSelections must carry at most "
                    + ROW_SELECTION_COUNT
                    + " entries, because the list screen declares that many rows, but it carries "
                    + rowSelections.size());
        }
    }

    /**
     * Compatibility constructor for the pre-token request shape.
     *
     * <p>It deliberately supplies no page token. Such a request remains valid for first presentation,
     * paging and every non-list operation; a list request that marks a row is refused by the service
     * until it echoes the token returned with the displayed page.
     */
    public UserRequest(final String userId,
                       final String searchUserId,
                       final String firstName,
                       final String lastName,
                       final String password,
                       final String userType,
                       final List<String> rowSelections,
                       final String displayedPageNumber,
                       final String firstUserIdOnPage,
                       final String lastUserIdOnPage,
                       final KeyAction keyAction,
                       final NavigationContext navigationContext) {
        this(userId, searchUserId, firstName, lastName, password, userType, rowSelections,
                displayedPageNumber, firstUserIdOnPage, lastUserIdOnPage, null, keyAction,
                navigationContext);
    }

    /**
     * Marker for the list operation of transaction {@code CU00}.
     *
     * <p><strong>Why these markers exist.</strong> One record body serves all four user-administration
     * operations, and the four maps declare different input items. Without a discriminator a client
     * could submit a credential to the delete operation, whose map declares no credential item at
     * all, or a page cursor to the add operation, which has no list. The interfaces below let each
     * controller method name the operation it serves so that the components its map does not declare
     * are rejected as inapplicable rather than silently carried.
     *
     * <p><strong>Why this does not disturb the ordered validation cascade.</strong> Each constraint
     * these groups carry asserts <em>absence</em>, never presence. Not one of them makes a component
     * mandatory, so none of them can pre-empt the first-error-wins emptiness cascade that the legacy
     * programs perform in their own order - the add screen tests the user identifier third while the
     * update screen tests it first, and that ordering stays entirely in the service layer where it
     * belongs. The constraints are also inert under the default validation group, so a caller that
     * validates without naming an operation sees exactly the behaviour this type had before.
     *
     * <p>Nested rather than free-standing, so the operation vocabulary stays attached to the body it
     * discriminates and no separate type is introduced.
     */
    public interface ListOperation {
    }

    /**
     * Marker for the add operation of transaction {@code CU01}.
     *
     * <p>Its map declares a first name, a last name, a user identifier, a credential and a user type
     * at {@code app/cpy-bms/COUSR01.CPY} lines 60, 66, 72, 78 and 84. It declares no browse key, no
     * selection item and no page cursor, so those components are inapplicable to it.
     */
    public interface AddOperation {
    }

    /**
     * Marker for the update operation of transaction {@code CU02}.
     *
     * <p>Its map declares the same five items as the add operation, at {@code COUSR02.CPY} lines 60,
     * 66, 72, 78 and 84, and {@code app/cbl/COUSR02C.cbl} genuinely reads all of them - the name at
     * lines 186 and 219, the surname at 192 and 223, the credential at 198 and 227 and the type at
     * 204 and 231. It declares no list state.
     */
    public interface UpdateOperation {
    }

    /**
     * Marker for the delete operation of transaction {@code CU03}.
     *
     * <p><strong>The user identifier is its only input.</strong> Its map declares a first name, a last
     * name and a user type at {@code COUSR03.CPY} lines 66, 72 and 78, but {@code app/cbl/COUSR03C.cbl}
     * never reads any of them: lines 157 to 159 clear them, 165 to 167 populate them from the record
     * it fetched and 353 to 355 clear them again, and there is no fourth kind of site. They are
     * therefore values the server produces for display, not values a client supplies. The map declares
     * no credential item at all, which is why a credential is inapplicable here as well.
     */
    public interface DeleteOperation {
    }

    /**
     * Returns a diagnostic representation that identifies the request and discloses no personal data.
     *
     * <p><strong>Why the credential is not the only component withheld.</strong> The remaining
     * components are two account-holder names, three user identifiers and a user type, all belonging to
     * one identifiable person and all on a single line. A record carrying a person's given name, family
     * name, sign-on identifier and privilege level is a personal-data record whether or not a password
     * sits beside it, and one instance exists per administrative request - so a verbatim rendering would
     * place that record one interpolation away from every log line, assertion message and diagnostic
     * dump on the user-administration path.
     *
     * <p><strong>What is withheld.</strong> Both user identifiers, the browse key, the two retained
     * page anchors, the two name parts, the user type and the credential, each replaced by
     * {@link #REDACTION_PLACEHOLDER} - a fixed constant and never a length-preserving mask, for the
     * reason given on that constant. The navigation state is withheld too: it redacts its own
     * identifying fields, and omitting it here means this rendering does not depend on that. No branch
     * of this method can render any of these values.
     *
     * <p><strong>What is retained, and why it is sufficient.</strong> The number of selections rather
     * than the selections themselves, the displayed page number, and the attention key. That set names
     * <em>which</em> submission this was and <em>what</em> the operator did - how many rows were
     * marked, which page was on screen and which key was pressed - which is what a diagnostic needs in
     * order to locate the same submission again, and none of it identifies a person. The selection
     * count is safe where the selections are not: a count cannot be joined back to a row, whereas the
     * ordered characters reveal which specific users an administrator singled out. The page number is
     * safe because the program computes it itself and it names no user. Fail-closed is the correct
     * default here: the retained set was chosen because it is sufficient, not because the remainder
     * happened to look harmless.
     *
     * <p><strong>This override protects one channel only.</strong> It governs what a diagnostic sink
     * receives and says nothing about what a serializer emits, which is a separate route closed
     * separately by {@link JsonProperty.Access#WRITE_ONLY} on the credential component itself. Neither
     * control substitutes for the other: removing the annotation would leave this rendering intact
     * while the credential travelled outbound in JSON, and removing this override would leave the
     * annotation intact while the credential travelled into a log line.
     *
     * <p>{@code equals} and {@code hashCode} are intentionally not overridden, so they keep comparing
     * every component as the record semantics require; equality is an in-memory operation that emits
     * nothing. This override exists solely to prevent disclosure through diagnostics, and it
     * deliberately performs no validation, normalisation or comparison of its own.
     *
     * @return the request identification, with every personal and credential component replaced by a
     *     fixed placeholder
     */
    @Override
    public String toString() {
        return "UserRequest["
                + "userId=" + REDACTION_PLACEHOLDER
                + ", searchUserId=" + REDACTION_PLACEHOLDER
                + ", firstName=" + REDACTION_PLACEHOLDER
                + ", lastName=" + REDACTION_PLACEHOLDER
                + ", password=" + REDACTION_PLACEHOLDER
                + ", userType=" + REDACTION_PLACEHOLDER
                + ", rowSelectionCount=" + rowSelections.size()
                + ", displayedPageNumber=" + displayedPageNumber
                + ", firstUserIdOnPage=" + REDACTION_PLACEHOLDER
                + ", lastUserIdOnPage=" + REDACTION_PLACEHOLDER
                + ", rowSnapshotToken=" + REDACTION_PLACEHOLDER
                + ", keyAction=" + keyAction
                + ", navigationContext=" + REDACTION_PLACEHOLDER
                + "]";
    }
}
