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

import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Immutable response contract for both CardDemo menu screens: the user main menu, legacy transaction
 * {@code CM00} driven by {@code app/cbl/COMEN01C.cbl}, and the administrative menu, legacy
 * transaction {@code CA00} driven by {@code app/cbl/COADM01C.cbl}.
 *
 * <p>One type serves both screens because the two legacy programs are near-identical - their working
 * storage, key handling and option-entry sequence line up statement for statement, and their two
 * screen maps declare the same field names at the same widths. What differs between the screens is
 * exactly one thing: the catalog of options each one lists.
 *
 * <p><strong>The two option catalogs are deliberately different shapes, and that asymmetry is
 * contractual.</strong> A user entry declares four elementary items - number, name, program name and
 * a one-character user-type code - while an administrative entry declares only the first three:
 * <strong>there is no user-type item in the administrative catalog at all.</strong>
 * {@link UserMenuOption} and {@link AdminMenuOption} therefore have four and three components. They
 * share no supertype, no interface and no common base record, and the administrative shape carries no
 * nullable stand-in for the item its copybook never declared. Unifying them would fabricate a field,
 * and a fabricated field is a feature the legacy system does not have.
 *
 * <p><strong>Ten user options and four administrative options - never twelve and nine.</strong> Those
 * two counts are declared in the copybooks and both programs compare the operator's entry against the
 * count before indexing the table. The table views themselves are dimensioned twelve and nine, so two
 * user entries and five administrative entries exist as blank capacity the counts exclude. That
 * surplus is not part of this contract: this response carries only populated options, this type
 * publishes the two counts and never the two capacities, and no blank option is ever emitted.
 * Iterating capacity instead of count is the easiest way to render blank rows onto a menu.
 *
 * <p><strong>Option 8 has exactly one label and no role gate.</strong> Its copybook label is preceded
 * by a commented-out alternative; only the active value exists here, and the inactive alternative is
 * neither declared, quoted, referenced nor implied. Option 8 carries the same standard user-type code
 * as its nine peers, and reviving that dead comment - as a label or as a role restriction - would
 * change who may add a transaction, which is feature expansion rather than migration.
 *
 * <p><strong>Every text value crosses this boundary byte for byte.</strong> Nothing is trimmed,
 * blank-filled, re-cased, re-numbered, re-ordered or re-shaped. Legacy fixed-width fields are
 * space-significant, so leading and trailing spaces are content. Option labels arrive in their final
 * display form - the configuration-layer catalog already publishes the trimmed label and records the
 * declared width separately - and option order is screen order, taken from the copybook declaration
 * sequence, never sorted or re-indexed.
 *
 * <p><strong>Three fixed-width message values exist in this estate and all three must stay
 * separate.</strong> The title copybook declares three forty-character values - the two title lines
 * and a forty-character acknowledgement - and the common-message copybook separately declares two
 * <em>fifty</em>-character messages. The acknowledgement in the title copybook and the one in the
 * common-message copybook look interchangeable and are not: they carry different product tokens at
 * different declared widths. <strong>None of the five is declared in this file</strong>; all five
 * belong to the message catalog in the service layer, and keeping every one of them out is what makes
 * confusing the forty-character acknowledgement with the fifty-character one structurally impossible
 * here. Each of the two fifty-character values is forty-nine characters as written in its copybook
 * literal yet occupies a fifty-character field, so one trailing space completes it and a caller must
 * supply the fifty-character form; measuring the literal instead of the field is how a
 * byte-equivalence comparison silently loses its last character. Only the widths are recorded below,
 * so that a caller supplying a value can be held to the right one.
 *
 * <p><strong>What the message line carries.</strong> Both programs hold the outgoing message in an
 * eighty-character working-storage field and then place it into a seventy-eight-character screen
 * field. Both widths are published below, because the bound that matters to a caller is the
 * working-storage width while the width that matters to a byte comparison against the screen is the
 * narrower one. Four texts reach that line from the menu programs: a thirty-seven-character rejection
 * emitted by both, with a lower-case verb, exactly three dots and no space before them; a
 * thirty-three-character access-denied text emitted only by the user program as part of its user-type
 * gate, ending in a significant trailing space and having no administrative counterpart; and the two
 * divergent acknowledgements described next.
 *
 * <p><strong>The coming-soon divergence - two different texts that must never be merged.</strong>
 * When the selected option names a placeholder program, both programs assemble an acknowledgement
 * from a twelve-character prefix and an eighteen-character suffix. The user program inserts the
 * selected option's name between them and takes only the name's leading word, so the rendered result
 * runs that word straight into the suffix <em>with no separating space</em>. The administrative
 * program uses the identical construction with the name operand commented out, so its rendered result
 * carries no name at all. The missing space in the first is a source defect and is nonetheless the
 * observable contract, so the two texts are two distinct behaviours and are never reconciled into
 * one. Assembling either is the menu service's work; this response carries the result unaltered.
 *
 * <p><strong>One authority per piece of legacy text, and it is never this type.</strong> Two bodies of
 * legacy text appear on a menu screen and both are owned elsewhere. The option rows are owned by
 * {@code com.carddemo.service.MenuOptionCatalog}, which holds every item of every entry - the number,
 * the label, the target program name and, on the user rows, the one-character user-type code. The
 * screen titles are owned by {@code com.carddemo.service.MessageCatalogService}, which publishes them
 * at their full declared width and keeps the forty-character acknowledgement of
 * {@code app/cpy/COTTL01Y.cpy} distinct from the two fifty-character common messages of
 * {@code app/cpy/CSMSG01Y.cpy}. <strong>This type declares neither.</strong> It receives the projected
 * rows and the title lines from its producer and carries them verbatim, so there is exactly one place
 * where a row can be added, a label corrected or a title changed. A response that also declared its
 * own copy would be a second authority that can drift from the first, and the two consequences are not
 * symmetrical: a stale copy here misreports a screen, while a stale copy there misroutes a dispatch or
 * misjudges an authorization.
 *
 * <p>The nested option shapes below are the exception that proves the rule, and they are a shape
 * rather than a body of text. The catalog declares its own pair of nested records because this package
 * sits above both the configuration layer and the service layer in the dependency direction, so no
 * import of {@code com.carddemo.config} or {@code com.carddemo.service} may appear in this file. The
 * two declarations are deliberately <em>not</em> the same shape: the catalog's rows carry the target
 * program name and the user-type code, and the projections here carry only the number and the label,
 * because that is all either screen renders and publishing the rest would tell a client which internal
 * program answers a row and which role gates it. {@code com.carddemo.service.MenuService} is the one
 * component that may read both owners, and narrowing a catalog row to a screen projection is its work.
 *
 * <p><strong>No behaviour lives here.</strong> This type does not normalise the operator's entry
 * &mdash; the legacy blank-to-zero fill, applied to the right-justified two-character work field
 * declared at {@code app/cbl/COADM01C.cbl} lines 45 to 46 and applied at
 * {@code app/cbl/COADM01C.cbl} line 123, and mirrored at exactly those lines of
 * {@code app/cbl/COMEN01C.cbl}, belongs to the menu service and its string utilities. It does not
 * range-check the entry against the option count, and it does not test whether an option names a
 * placeholder program: that test is made at {@code app/cbl/COADM01C.cbl} line 138 and at
 * {@code app/cbl/COMEN01C.cbl} line 146, and both programs make it only after the count comparison
 * has passed. It does not evaluate the user-type gate, does not assemble any message and does not
 * resolve or execute a route. It reads nothing from disk, parses no fixed-width record and never
 * reads a legacy source artefact at run time. It holds no route table, no route enumeration and no
 * dispatch method: {@link #nextRoute()} is an opaque declarative string that the navigation service
 * chooses and the client acts on, because the estate's twenty-five program-to-program transfers and
 * nineteen re-arming returns all become route values in a response body rather than server-side
 * forwarding.
 *
 * <p><strong>Screen presentation is not modelled.</strong> The legacy screens carry field lengths,
 * attribute bytes, cursor placement, terminal highlighting and a twelve-byte terminal-area prefix.
 * None of that appears here. {@link #messageSeverity()} records only the semantic intent the legacy
 * highlighting expressed, never the highlighting itself, and {@link #focusScreenFieldId()} is an
 * opaque label rather than a coordinate at most {@link #SCREEN_FIELD_ID_WIDTH} characters wide, which
 * is the widest symbolic field name either mapset declares.
 *
 * <p><strong>The whole common header is published, and its widths are the map's own.</strong>
 * Both symbolic maps declare the same six-item header before their option rows &mdash;
 * {@code TRNNAME}, {@code TITLE01}, {@code CURDATE}, {@code PGMNAME}, {@code TITLE02} and
 * {@code CURTIME} &mdash; and both programs write all six on every send, in the header paragraph at
 * {@code app/cbl/COMEN01C.cbl} lines 212 to 231 and {@code app/cbl/COADM01C.cbl} lines 202 to 221. The
 * first six components of this record are those six items in map declaration order, so a client can
 * redisplay the screen exactly. Two of them differ between the two menus and are therefore components
 * rather than constants: the transaction identifier and the program name, which the two factory methods
 * fill from {@link #USER_MENU_TRANSACTION_NAME} and {@link #USER_MENU_PROGRAM_NAME} or from
 * {@link #ADMIN_MENU_TRANSACTION_NAME} and {@link #ADMIN_MENU_PROGRAM_NAME}. Every one of the six
 * crosses as text at its measured width, never as a date, time or numeric type, so a rendered leading
 * zero survives. <strong>The rendered time is eight characters here.</strong> The sign-on map is the
 * one place in the estate where that item is nine characters wide, so
 * {@link #CURRENT_TIME_WIDTH} and the sign-on contract's own constant are deliberately separate
 * figures and must never be folded into one.
 *
 * <p><strong>Dispatch and authorization metadata stay server-side.</strong> The two option table views
 * each declare a target program name, and the user table view additionally declares a one-character
 * user-type code. Neither is published on the wire by this contract. Both mapsets render an option row
 * as its number and its label only, and the operator selects a row by typing its number, so the program
 * name is dispatch metadata and the user-type code is an authorization input &mdash; publishing either
 * would tell a client which internal program answers a row and which role gates it, and would invite a
 * client to send back a target of its own choosing. The requirement that every copybook field be mapped
 * is discharged where those fields are actually used, in the configuration-layer catalog
 * {@code com.carddemo.service.MenuOptionCatalog}, which declares both option shapes in full with all
 * fourteen program names and the shared user-type code. That duplication is deliberate: this package
 * may not depend on the configuration layer, so the wire shape and the server-side shape are declared
 * independently and only the wire shape is narrowed.
 *
 * <p><strong>Exactly one menu, at exactly its declared size.</strong> Three invariants are enforced on
 * construction rather than left to a caller. Precisely one of the two option collections is present, so
 * a response can be neither both menus at once nor neither of them; a present user collection holds
 * exactly {@link #USER_MENU_OPTION_COUNT} entries; and a present administrative collection holds
 * exactly {@link #ADMIN_MENU_OPTION_COUNT}. Those two figures are declared by the copybooks themselves
 * &mdash; the occurrence count at {@code app/cpy/COMEN02Y.cpy} line 21 is ten and the one at
 * {@code app/cpy/COADM02Y.cpy} line 20 is four &mdash; and both programs rebuild their whole table on
 * every send, including every redisplay after a rejected entry, so a shorter collection is not a
 * legitimate screen state that this contract would otherwise have to carry. Enforcing the figures here
 * is what makes a partially-rendered or dual-menu payload unrepresentable rather than merely unusual.
 *
 * <p><strong>Provenance.</strong> Every citation above resolves against commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec} of the legacy repository, which is read-only
 * reference material: no COBOL statement, picture clause or table declaration is transcribed into
 * this file, and only external contract text is reproduced. The release stamps embedded in the two
 * option copybooks differ and are recorded as they stand rather than reconciled:
 * {@code app/cpy/COMEN02Y.cpy} line 94 carries {@code CardDemo_v1.0-15-g27d6c6f-68} dated
 * 2022-07-19 23:15:58 CDT, the estate-wide stamp also carried by {@code app/cpy/COTTL01Y.cpy},
 * {@code app/cpy/CSMSG01Y.cpy}, {@code app/cpy/COCOM01Y.cpy}, {@code app/cbl/COMEN01C.cbl} and
 * {@code app/cbl/COADM01C.cbl}, while {@code app/cpy/COADM02Y.cpy} line 50 carries
 * {@code CardDemo_v1.0-26-g42273c1-79} dated 2022-07-20 16:59:12 CDT. Two further observations
 * complete the audit trail for the screen artefacts cited above: the two mapset definitions
 * {@code app/bms/COMEN01.bms} and {@code app/bms/COADM01.bms} carry a third and later stamp,
 * {@code CardDemo_v1.0-70-g193b394-123} dated 2022-08-22, at line 166 of each; and the two generated
 * symbolic maps {@code app/cpy-bms/COMEN01.CPY} and {@code app/cpy-bms/COADM01.CPY} carry no stamp
 * at all, being compiler output rather than hand-maintained source.
 *
 * <p><strong>Recorded source anomaly.</strong> Both option copybooks open with an identical line-2
 * comment naming the administrative menu, yet the group declared at line 19 of
 * {@code app/cpy/COMEN02Y.cpy} is the <em>main</em> menu group holding the ten user options. The
 * comment is a copy-and-paste defect and the data item is authoritative, so this file follows the
 * data item: the ten options below are the user menu, whatever the copybook comment says.
 *
 * @param transactionName    the transaction identifier the screen echoes, from {@code WS-TRANID} at
 *                           {@code app/cbl/COMEN01C.cbl} line 37 or {@code app/cbl/COADM01C.cbl} line
 *                           37, written into {@code TRNNAME} by line 218 or line 208 respectively. At
 *                           most {@link #TRANSACTION_NAME_WIDTH} characters, and one of
 *                           {@link #USER_MENU_TRANSACTION_NAME} or
 *                           {@link #ADMIN_MENU_TRANSACTION_NAME}. May be {@code null}.
 * @param title01            the first screen title line, from {@code CCDA-TITLE01} at
 *                           {@code app/cpy/COTTL01Y.cpy} line 19, at exactly
 *                           {@link #SCREEN_TITLE_WIDTH} characters with its leading and trailing
 *                           spaces intact. Both menu screens show the same value. Supplied by the
 *                           producer from {@code com.carddemo.service.MessageCatalogService}, which
 *                           owns the text, and carried here verbatim - never trimmed, padded or
 *                           re-cased. May be {@code null}.
 * @param currentDate        the current date exactly as the screen rendered it, assembled by
 *                           {@code app/cbl/COMEN01C.cbl} lines 221 to 226 and written into
 *                           {@code CURDATE}. {@link #CURRENT_DATE_WIDTH} characters of text and never
 *                           a date type, so the rendered form survives unchanged. May be
 *                           {@code null}.
 * @param programName        the program name the screen echoes, from {@code WS-PGMNAME} at line 36 of
 *                           either program, written into {@code PGMNAME} by line 219 or line 209. At
 *                           most {@link #PROGRAM_NAME_WIDTH} characters, and one of
 *                           {@link #USER_MENU_PROGRAM_NAME} or {@link #ADMIN_MENU_PROGRAM_NAME}. This
 *                           is the responding program's <em>own</em> identity and never a
 *                           destination. May be {@code null}.
 * @param title02            the second screen title line, from the <em>active</em> value of
 *                           {@code CCDA-TITLE02} at {@code app/cpy/COTTL01Y.cpy} line 22, at exactly
 *                           {@link #SCREEN_TITLE_WIDTH} characters. The alternative value commented
 *                           out at line 21 of that copybook stays inactive and appears nowhere in this
 *                           module. Supplied by the producer from the same owner as {@code title01}
 *                           and carried here verbatim. May be {@code null}.
 * @param currentTime        the current time exactly as the screen rendered it, assembled by
 *                           {@code app/cbl/COMEN01C.cbl} lines 227 to 231 and written into
 *                           {@code CURTIME}. {@link #CURRENT_TIME_WIDTH} characters of text &mdash;
 *                           eight here, not the nine the sign-on map uses. May be {@code null}.
 * @param userMenuOptions    the populated user-menu options in copybook order, or {@code null} when
 *                           this response is an administrative-menu response. Never blank-entry
 *                           filler and never dimensioned to the table capacity. Projected by the
 *                           producer from {@code com.carddemo.service.MenuOptionCatalog}, which owns
 *                           the rows, down to the number and label the screen renders. When present it
 *                           holds exactly {@link #USER_MENU_OPTION_COUNT} entries, which construction
 *                           enforces. Defensively copied on construction and always immutable when
 *                           present.
 * @param adminMenuOptions   the populated administrative-menu options in copybook order, or
 *                           {@code null} when this response is a user-menu response. Exactly one of
 *                           the two option components is populated on every response, which
 *                           construction enforces. Projected by the producer from the same owner as
 *                           the user rows. When present it holds exactly
 *                           {@link #ADMIN_MENU_OPTION_COUNT} entries. Defensively copied on
 *                           construction and always immutable when present.
 * @param selectedOption     the operator's option entry echoed back, from {@code OPTIONO} at
 *                           {@code app/cpy-bms/COMEN01.CPY} line 254 and
 *                           {@code app/cpy-bms/COADM01.CPY} line 254, at most
 *                           {@link #SELECTED_OPTION_WIDTH} characters. Carried exactly as the
 *                           service resolved it; this type performs no blank-to-zero fill and no
 *                           right justification of its own. May be {@code null}.
 * @param message            the single message line, bounded by the eighty-character working-storage
 *                           width {@link #MESSAGE_WIDTH} and rendered into the narrower
 *                           {@link #SCREEN_MESSAGE_FIELD_WIDTH} screen field. Carries whichever
 *                           legacy text applies, including a fifty-character common message at its
 *                           full width, and is never trimmed or altered. May be {@code null} when
 *                           the screen shows no message.
 * @param messageSeverity    the semantic intent of {@link #message()}, or {@code null} when there is
 *                           no message. A meaning and never a presentation value; see
 *                           {@link MessageSeverity}.
 * @param errorFlag          whether this response reports a failed interaction. The typed form of
 *                           the legacy error switch declared at {@code app/cbl/COMEN01C.cbl} line 40
 *                           and {@code app/cbl/COADM01C.cbl} line 40, whose two condition names
 *                           occupy lines 41 and 42 of each program. Carried as its own fact and
 *                           never inferred from the presence of a message, because both programs
 *                           also emit a message on the successful placeholder path.
 * @param focusScreenFieldId the legacy screen field identifier that input focus belongs on, or
 *                           {@code null} when the response offers no hint. An opaque label only: it
 *                           is not a coordinate, not an attribute byte and not a cursor position,
 *                           and a client may ignore it. At most {@link #SCREEN_FIELD_ID_WIDTH}
 *                           characters, the widest symbolic field name either mapset declares.
 * @param nextRoute          the declarative next route, or {@code null} when the response nominates
 *                           none. Opaque to this type, which neither resolves nor performs it.
 * @param navigationContext  the client-echoed navigation state to send back on the next call, or
 *                           {@code null}. Echoed request state and never a server session.
 * @since 1.0.0
 */
public record MenuResponse(
        @Size(max = MenuResponse.TRANSACTION_NAME_WIDTH) String transactionName,
        @Size(max = MenuResponse.SCREEN_TITLE_WIDTH) String title01,
        @Size(max = MenuResponse.CURRENT_DATE_WIDTH) String currentDate,
        @Size(max = MenuResponse.PROGRAM_NAME_WIDTH) String programName,
        @Size(max = MenuResponse.SCREEN_TITLE_WIDTH) String title02,
        @Size(max = MenuResponse.CURRENT_TIME_WIDTH) String currentTime,
        List<UserMenuOption> userMenuOptions,
        List<AdminMenuOption> adminMenuOptions,
        @Size(max = MenuResponse.SELECTED_OPTION_WIDTH) String selectedOption,
        @Size(max = MenuResponse.MESSAGE_WIDTH) String message,
        MessageSeverity messageSeverity,
        boolean errorFlag,
        @Size(max = MenuResponse.SCREEN_FIELD_ID_WIDTH) String focusScreenFieldId,
        String nextRoute,
        NavigationContext navigationContext) {

    public static final int OPTION_NUMBER_WIDTH = 2;

    public static final int OPTION_LABEL_WIDTH = 35;

    /**
     * Declared width of the transaction identifier the screen displays: 4 characters.
     *
     * <p>The width of {@code TRNNAMEI} at {@code app/cpy-bms/COMEN01.CPY} line 24 and of the
     * identically declared item at the same line of {@code app/cpy-bms/COADM01.CPY}, echoed back as
     * {@code TRNNAMEO}. Both programs move their own four-character transaction identifier into that
     * field while assembling the header, at {@code app/cbl/COMEN01C.cbl} line 218 and
     * {@code app/cbl/COADM01C.cbl} line 208.</p>
     */
    public static final int TRANSACTION_NAME_WIDTH = 4;

    /**
     * Declared width of the program name the screen displays: 8 characters.
     *
     * <p>The width of {@code PGMNAMEI} at {@code app/cpy-bms/COMEN01.CPY} line 42 and at the same
     * line of {@code app/cpy-bms/COADM01.CPY}, echoed back as {@code PGMNAMEO}. Each program moves
     * its own name into that field at {@code app/cbl/COMEN01C.cbl} line 219 and
     * {@code app/cbl/COADM01C.cbl} line 209.</p>
     *
     * <p>This is the width of the <em>displayed</em> program name in the screen header. It is also
     * the width the two menu copybooks give the program name of an individual option row, but that
     * item is a different field with a different owner and is not published by this contract at all;
     * see {@link UserMenuOption} for why dispatch metadata stays server-side.</p>
     */
    public static final int PROGRAM_NAME_WIDTH = 8;

    /**
     * Declared width of the rendered current date: 8 characters.
     *
     * <p>The width of {@code CURDATEI} at {@code app/cpy-bms/COMEN01.CPY} line 36 and at the same
     * line of {@code app/cpy-bms/COADM01.CPY}, echoed back as {@code CURDATEO}. Both programs
     * assemble the value from the system date at {@code app/cbl/COMEN01C.cbl} lines 214 and 221 to
     * 226 and {@code app/cbl/COADM01C.cbl} lines 204 and 211 to 216, and this contract carries the
     * assembled text without re-formatting it.</p>
     */
    public static final int CURRENT_DATE_WIDTH = 8;

    /**
     * Declared width of the rendered current time: 8 characters.
     *
     * <p>The width of {@code CURTIMEI} at {@code app/cpy-bms/COMEN01.CPY} line 54 and at the same
     * line of {@code app/cpy-bms/COADM01.CPY}, echoed back as {@code CURTIMEO}. Both programs
     * assemble the value at {@code app/cbl/COMEN01C.cbl} lines 227 to 231 and
     * {@code app/cbl/COADM01C.cbl} lines 217 to 221.</p>
     *
     * <p><strong>Eight, not nine.</strong> The sign-on mapset is the one place in the estate where
     * this item is nine characters wide, so the sign-on contract declares its own figure and the two
     * must never be folded into a single shared constant. Widening this one to match it would put a
     * character on the menu screens that neither menu map has room for.</p>
     */
    public static final int CURRENT_TIME_WIDTH = 8;

    /**
     * Upper bound on the opaque screen field identifier focus may be nominated for: 7 characters.
     *
     * <p>The widest symbolic field name either menu mapset declares. Every field name in
     * {@code app/cpy-bms/COMEN01.CPY} and {@code app/cpy-bms/COADM01.CPY} fits within it, and the
     * same figure bounds the equivalent identifier on every other response contract in this package,
     * so a client that carries one identifier from one screen to the next never has to widen its
     * own field.</p>
     *
     * <p>A measurement and not a vocabulary: this contract does not enumerate the admissible
     * identifiers, because the set differs by screen and the identifier is advisory in any case.</p>
     */
    public static final int SCREEN_FIELD_ID_WIDTH = 7;

    /**
     * Width of the echoed option entry: 2 characters. Declared separately from
     * {@link #OPTION_NUMBER_WIDTH} even though the two values are equal: this one is a screen field
     * the operator types into and the program echoes back as text, that one is an item of a copybook
     * table. Neither is derived from the other.
     */
    public static final int SELECTED_OPTION_WIDTH = 2;

    public static final int SCREEN_TITLE_WIDTH = 40;

    /**
     * Width of the working-storage field the menu programs hold the outgoing message in: 80
     * characters. This is the bound a caller is measured against.
     */
    public static final int MESSAGE_WIDTH = 80;

    /**
     * Width of the screen field the message is rendered into: 78 characters. Recorded alongside
     * {@link #MESSAGE_WIDTH} because the two genuinely differ and both matter - a byte comparison
     * against what the legacy screen displayed is bounded by this narrower one. Neither width is
     * applied as a transformation: this type never shortens a message to fit.
     */
    public static final int SCREEN_MESSAGE_FIELD_WIDTH = 78;

    /**
     * Declared width of the two common messages this response's message line may carry: 50
     * characters. Each literal is written as forty-nine characters and occupies a fifty-character
     * field, so one trailing space completes it and a caller must supply that fifty-character form.
     *
     * <p>The two values themselves are deliberately absent from this file - they belong to the message
     * catalog in the service layer, as does the forty-character acknowledgement of the title copybook.
     * Only the widths are recorded here, and only so that the fifty-character acknowledgement can
     * never be mistaken for the forty-character one: they are different texts at different widths, and
     * a caller supplying either must supply it at its own width.</p>
     */
    public static final int COMMON_MESSAGE_WIDTH = 50;

    /** Number of populated user-menu options: 10. A count, never the larger table capacity. */
    public static final int USER_MENU_OPTION_COUNT = 10;

    /** Number of populated administrative-menu options: 4. A count, never the table capacity. */
    public static final int ADMIN_MENU_OPTION_COUNT = 4;

    /**
     * The transaction identifier the <em>user</em> menu screen displays: {@code "CM00"}.
     *
     * <p>The value of {@code WS-TRANID} at {@code app/cbl/COMEN01C.cbl} line 37, moved into the
     * header's transaction field at line 218 of the same program. Exactly
     * {@link #TRANSACTION_NAME_WIDTH} characters.</p>
     */
    public static final String USER_MENU_TRANSACTION_NAME = "CM00";

    /**
     * The program name the <em>user</em> menu screen displays: {@code "COMEN01C"}.
     *
     * <p>The value of {@code WS-PGMNAME} at {@code app/cbl/COMEN01C.cbl} line 36, moved into the
     * header's program field at line 219 of the same program. Exactly {@link #PROGRAM_NAME_WIDTH}
     * characters.</p>
     */
    public static final String USER_MENU_PROGRAM_NAME = "COMEN01C";

    /**
     * The transaction identifier the <em>administrative</em> menu screen displays: {@code "CA00"}.
     *
     * <p>The value of {@code WS-TRANID} at {@code app/cbl/COADM01C.cbl} line 37, moved into the
     * header's transaction field at line 208 of the same program. Exactly
     * {@link #TRANSACTION_NAME_WIDTH} characters.</p>
     *
     * <p>Declared separately from {@link #USER_MENU_TRANSACTION_NAME} because the two menus are two
     * transactions: the header identifies which one produced the screen, so a shared value would
     * misreport it on one of them.</p>
     */
    public static final String ADMIN_MENU_TRANSACTION_NAME = "CA00";

    /**
     * The program name the <em>administrative</em> menu screen displays: {@code "COADM01C"}.
     *
     * <p>The value of {@code WS-PGMNAME} at {@code app/cbl/COADM01C.cbl} line 36, moved into the
     * header's program field at line 209 of the same program. Exactly {@link #PROGRAM_NAME_WIDTH}
     * characters, and distinct from {@link #USER_MENU_PROGRAM_NAME} for the same reason.</p>
     */
    public static final String ADMIN_MENU_PROGRAM_NAME = "COADM01C";

    /*
     * NO TITLE TEXT AND NO OPTION CATALOG IS DECLARED IN THIS FILE, DELIBERATELY.
     *
     * The two forty-character title lines and the forty-character acknowledgement declared in
     * app/cpy/COTTL01Y.cpy have exactly one owner in this module, com.carddemo.service.
     * MessageCatalogService, which publishes them at their full padded width and keeps the
     * forty-character acknowledgement distinct from the two fifty-character common messages of
     * app/cpy/CSMSG01Y.cpy. The ten user rows of app/cpy/COMEN02Y.cpy and the four administrative
     * rows of app/cpy/COADM02Y.cpy likewise have exactly one owner, com.carddemo.config.
     * MenuOptionCatalog, which holds every item of every entry including the target program name and
     * the user-type code that dispatch and authorization need.
     *
     * Declaring either here as well would make this contract a second authority for the same text.
     * Two authorities drift: a row added to the catalog would not reach the screen, a label corrected
     * on the screen would not reach dispatch, and an authorization decision could be taken against a
     * different row set than the one the operator saw. The response therefore receives the title
     * lines and the option projections from its producer and declares neither - see the factory
     * methods below, which take both as parameters rather than substituting a hidden default.
     *
     * This is not a layering workaround. This package sits above both the configuration layer and the
     * service layer in the dependency direction, so importing either owner here is forbidden and no
     * import of com.carddemo.config or com.carddemo.service appears in this file. The producer -
     * com.carddemo.service.MenuService - is the one component that may read both owners, and it is
     * where the narrowing of a four-item catalog row to a two-item screen projection belongs.
     */

    /**
     * Detaches both option collections from the caller and enforces the menu invariants, without
     * altering what either collection contains.
     *
     * <p>A supplied collection is copied with {@link List#copyOf(java.util.Collection)}, which both
     * severs aliasing to caller-owned state and rejects a {@code null} element outright - a blank
     * option would be exactly the surplus-capacity entry this contract excludes, so failing is
     * preferable to serializing a row a client would then render.</p>
     *
     * <p><strong>A {@code null} collection is stored as {@code null}, deliberately, and is not
     * converted to an empty list.</strong> The two option components answer a different question than
     * their contents do: on a user-menu response there is no administrative option collection
     * <em>at all</em>, which is a different statement from an administrative menu that happens to list
     * nothing. Because the module serializes only non-{@code null} properties, storing {@code null}
     * omits the irrelevant collection from the payload entirely. Callers that want the distinction
     * made for them should use {@link #forUserMenu} or {@link #forAdminMenu}.</p>
     *
     * <p><strong>Three invariants are enforced, and each of them is the legacy screen's own
     * arithmetic rather than a policy invented here.</strong>
     *
     * <ol>
     *   <li><em>Exactly one option collection is present.</em> A menu screen is one menu. Legacy
     *       transaction {@code CM00} runs {@code app/cbl/COMEN01C.cbl} and renders the user table;
     *       legacy transaction {@code CA00} runs {@code app/cbl/COADM01C.cbl} and renders the
     *       administrative one. Neither program can render both, and neither can render a screen with
     *       no rows on it, so a response carrying both collections or neither describes no screen the
     *       estate can produce.</li>
     *   <li><em>A present user collection holds exactly {@link #USER_MENU_OPTION_COUNT} entries.</em>
     *       That is the value of {@code CDEMO-MENU-OPT-COUNT} at {@code app/cpy/COMEN02Y.cpy} line
     *       21, and {@code app/cbl/COMEN01C.cbl} rebuilds every one of those rows on every send,
     *       including each redisplay after a rejected entry. There is therefore no legitimate screen
     *       state in which a user menu shows fewer rows.</li>
     *   <li><em>A present administrative collection holds exactly
     *       {@link #ADMIN_MENU_OPTION_COUNT} entries.</em> The value of
     *       {@code CDEMO-ADMIN-OPT-COUNT} at {@code app/cpy/COADM02Y.cpy} line 20, rebuilt in full by
     *       {@code app/cbl/COADM01C.cbl} on the same terms.</li>
     * </ol>
     *
     * <p>These are cardinality facts about the two copybook tables, and the copybooks are frozen
     * source. Publishing the counts as constants while accepting any other size would let a
     * mis-assembled screen reach a client as a well-formed response, which is the failure the
     * invariants exist to make impossible. A rejected construction raises
     * {@link IllegalArgumentException}, naming which invariant failed and what was supplied.
     *
     * <p>Every other component is stored exactly as supplied, including {@code null} and including any
     * leading or trailing space, because the legacy fields they derive from are fixed-width and
     * space-significant. In particular no count, width or identifier is filled in, defaulted or
     * corrected here.
     *
     * @throws IllegalArgumentException when both option collections are present, when neither is, or
     *                                  when a present collection does not hold exactly the number of
     *                                  entries its copybook declares
     */
    public MenuResponse {
        if ((userMenuOptions == null) == (adminMenuOptions == null)) {
            throw new IllegalArgumentException(
                    "a menu response carries exactly one option collection, but "
                            + ((userMenuOptions == null) ? "neither was supplied"
                                                         : "both were supplied"));
        }
        if (userMenuOptions != null && userMenuOptions.size() != USER_MENU_OPTION_COUNT) {
            throw new IllegalArgumentException("the user menu renders exactly "
                    + USER_MENU_OPTION_COUNT + " options, but " + userMenuOptions.size()
                    + " were supplied");
        }
        if (adminMenuOptions != null && adminMenuOptions.size() != ADMIN_MENU_OPTION_COUNT) {
            throw new IllegalArgumentException("the administrative menu renders exactly "
                    + ADMIN_MENU_OPTION_COUNT + " options, but " + adminMenuOptions.size()
                    + " were supplied");
        }
        userMenuOptions = (userMenuOptions == null) ? null : List.copyOf(userMenuOptions);
        adminMenuOptions = (adminMenuOptions == null) ? null : List.copyOf(adminMenuOptions);
    }

    /**
     * Builds a user-menu response, the reply to legacy transaction {@code CM00}: populates the user
     * option collection and leaves the administrative collection absent.
     *
     * <p>Two of the header items the user menu displays are this response's own identity and are
     * therefore filled here, from {@link #USER_MENU_TRANSACTION_NAME} and
     * {@link #USER_MENU_PROGRAM_NAME}: {@code app/cbl/COMEN01C.cbl} lines 216 to 219 move the
     * responding program's own transaction identifier and program name into the header on every send,
     * and no other value can correctly appear there on a user-menu response. Those two are not
     * duplicated anywhere else in the module.
     *
     * <p><strong>The two title lines and the option rows are supplied by the caller, never defaulted
     * here.</strong> Both are legacy text owned elsewhere - the titles by
     * {@code com.carddemo.service.MessageCatalogService} and the rows by
     * {@code com.carddemo.service.MenuOptionCatalog} - and a factory that quietly substituted its own
     * copy would make this contract a second authority for that text, which is the failure mode this
     * type is written to avoid. Passing them in keeps a single owner for each and keeps the producer
     * honest: {@code com.carddemo.service.MenuService} reads both owners, narrows a catalog row to the
     * number and label the screen renders, and hands the results here. The titles are the same two
     * values on both menu screens, but sameness is a property of the source text rather than a licence
     * for this type to hold it.
     *
     * <p>The rendered date and time are parameters for a different reason: they are the only header
     * items the program computes per interaction, at lines 214 and 221 to 231 of the same program.
     * Everything this method receives is carried through unchanged - no value is trimmed, padded,
     * re-cased or normalised.
     *
     * @param title01            the first screen title line as its owner publishes it, at exactly
     *                           {@link #SCREEN_TITLE_WIDTH} characters with its leading and trailing
     *                           spaces intact, or {@code null}. Carried verbatim
     * @param title02            the second screen title line as its owner publishes it, at exactly
     *                           {@link #SCREEN_TITLE_WIDTH} characters, or {@code null}. Carried
     *                           verbatim
     * @param currentDate        the rendered current date, at most {@link #CURRENT_DATE_WIDTH}
     *                           characters, or {@code null}
     * @param currentTime        the rendered current time, at most {@link #CURRENT_TIME_WIDTH}
     *                           characters, or {@code null}
     * @param userMenuOptions    the populated user options in screen order, projected by the producer
     *                           from the configuration-layer catalog. Must hold exactly
     *                           {@link #USER_MENU_OPTION_COUNT} entries; {@code null} is rejected,
     *                           because a user-menu response with no user menu describes no screen
     * @param selectedOption     the echoed option entry, or {@code null}
     * @param message            the message line, or {@code null} when the screen shows none
     * @param messageSeverity    the semantic intent of {@code message}, or {@code null}
     * @param errorFlag          whether this response reports a failed interaction
     * @param focusScreenFieldId the opaque screen field label focus belongs on, or {@code null}
     * @param nextRoute          the declarative next route, or {@code null}
     * @param navigationContext  the client-echoed navigation state, or {@code null}
     * @return a user-menu response carrying no administrative option collection
     * @throws IllegalArgumentException when {@code userMenuOptions} is absent or does not hold
     *                                  exactly {@link #USER_MENU_OPTION_COUNT} entries
     */
    public static MenuResponse forUserMenu(String title01,
                                           String title02,
                                           String currentDate,
                                           String currentTime,
                                           List<UserMenuOption> userMenuOptions,
                                           String selectedOption,
                                           String message,
                                           MessageSeverity messageSeverity,
                                           boolean errorFlag,
                                           String focusScreenFieldId,
                                           String nextRoute,
                                           NavigationContext navigationContext) {
        return new MenuResponse(USER_MENU_TRANSACTION_NAME, title01, currentDate,
                USER_MENU_PROGRAM_NAME, title02, currentTime, userMenuOptions, null,
                selectedOption, message, messageSeverity, errorFlag, focusScreenFieldId, nextRoute,
                navigationContext);
    }

    /**
     * Builds an administrative-menu response, the reply to legacy transaction {@code CA00}: populates
     * the administrative option collection and leaves the user collection absent.
     *
     * <p>Fills the two identity header items exactly as {@link #forUserMenu} does and for the same
     * reason, but from {@link #ADMIN_MENU_TRANSACTION_NAME} and {@link #ADMIN_MENU_PROGRAM_NAME},
     * because {@code app/cbl/COADM01C.cbl} lines 206 to 209 identify a different transaction and a
     * different program.
     *
     * <p>The two title lines and the option rows are supplied by the caller here too, and for the
     * reason given on {@link #forUserMenu}: their owners are
     * {@code com.carddemo.service.MessageCatalogService} and
     * {@code com.carddemo.service.MenuOptionCatalog}, and this contract is not a second one. The titles
     * happen to be the same two values the user menu shows, which is a property of the source text
     * rather than a reason for this type to hold a copy of it.
     *
     * @param title01            the first screen title line as its owner publishes it, at exactly
     *                           {@link #SCREEN_TITLE_WIDTH} characters with its leading and trailing
     *                           spaces intact, or {@code null}. Carried verbatim
     * @param title02            the second screen title line as its owner publishes it, at exactly
     *                           {@link #SCREEN_TITLE_WIDTH} characters, or {@code null}. Carried
     *                           verbatim
     * @param currentDate        the rendered current date, at most {@link #CURRENT_DATE_WIDTH}
     *                           characters, or {@code null}
     * @param currentTime        the rendered current time, at most {@link #CURRENT_TIME_WIDTH}
     *                           characters, or {@code null}
     * @param adminMenuOptions   the populated administrative options in screen order, projected by the
     *                           producer from the configuration-layer catalog. Must hold exactly
     *                           {@link #ADMIN_MENU_OPTION_COUNT} entries; {@code null} is rejected
     * @param selectedOption     the echoed option entry, or {@code null}
     * @param message            the message line, or {@code null} when the screen shows none
     * @param messageSeverity    the semantic intent of {@code message}, or {@code null}
     * @param errorFlag          whether this response reports a failed interaction
     * @param focusScreenFieldId the opaque screen field label focus belongs on, or {@code null}
     * @param nextRoute          the declarative next route, or {@code null}
     * @param navigationContext  the client-echoed navigation state, or {@code null}
     * @return an administrative-menu response carrying no user option collection
     * @throws IllegalArgumentException when {@code adminMenuOptions} is absent or does not hold
     *                                  exactly {@link #ADMIN_MENU_OPTION_COUNT} entries
     */
    public static MenuResponse forAdminMenu(String title01,
                                            String title02,
                                            String currentDate,
                                            String currentTime,
                                            List<AdminMenuOption> adminMenuOptions,
                                            String selectedOption,
                                            String message,
                                            MessageSeverity messageSeverity,
                                            boolean errorFlag,
                                            String focusScreenFieldId,
                                            String nextRoute,
                                            NavigationContext navigationContext) {
        return new MenuResponse(ADMIN_MENU_TRANSACTION_NAME, title01, currentDate,
                ADMIN_MENU_PROGRAM_NAME, title02, currentTime, null, adminMenuOptions,
                selectedOption, message, messageSeverity, errorFlag, focusScreenFieldId, nextRoute,
                navigationContext);
    }

    /**
     * Tests whether this response carries a user option collection at all.
     *
     * <p>Presence, not content. It answers which of the two option shapes a client is about to read,
     * and it selects nothing and routes nothing.
     *
     * <p>Because construction admits exactly one option collection, this test and
     * {@link #carriesAdminMenu()} always disagree: whichever answers {@code true} identifies the menu
     * this response describes, and a client needs only one of the two tests. Content never enters the
     * answer &mdash; a present collection is guaranteed to hold exactly
     * {@link #USER_MENU_OPTION_COUNT} entries, so there is no empty-collection case left for this
     * test to have an opinion about.
     *
     * @return {@code true} when this response describes the user menu
     */
    public boolean carriesUserMenu() {
        return userMenuOptions != null;
    }

    /**
     * Tests whether this response carries an administrative option collection at all.
     *
     * <p>The administrative counterpart of {@link #carriesUserMenu()}, with the same
     * presence-not-content semantics and the same guarantee: a present collection holds exactly
     * {@link #ADMIN_MENU_OPTION_COUNT} entries, and exactly one of the two tests answers
     * {@code true}.
     *
     * @return {@code true} when this response describes the administrative menu
     */
    public boolean carriesAdminMenu() {
        return adminMenuOptions != null;
    }

    /**
     * What a menu message <em>means</em>, so a client can present it appropriately without this
     * contract dictating how.
     *
     * <p>The legacy programs distinguished the two cases by switching the message line's terminal
     * highlighting before sending the screen. That highlighting is a presentation value belonging to a
     * 3270 device and is modelled nowhere in this module; only the distinction it encoded survives, as
     * these two constants.</p>
     *
     * <p>The two are genuinely independent of {@link MenuResponse#errorFlag()} and neither is derived
     * from the other: both programs set their error switch on a rejected entry, and both also emit a
     * message on the successful placeholder path, which leaves the switch clear and still needs a
     * message.</p>
     *
     * @since 1.0.0
     */
    public enum MessageSeverity {

        /**
         * The message reports an outcome rather than a problem: the interaction succeeded, the selected
         * option simply has no program behind it, and the operator is told so. The error switch is
         * clear on this path.
         */
        INFORMATIONAL,

        /**
         * The message reports a problem the operator has to resolve - a rejected entry, an option the
         * signed-on user type may not select, or an unmapped key. The error switch is set on these
         * paths.
         */
        ERROR
    }

    /**
     * One rendered row of the <em>user</em> menu: the option number the operator types and the text
     * printed beside it, from the first two elementary items of the table view at
     * {@code app/cpy/COMEN02Y.cpy} lines 87 to 92.
     *
     * <p><strong>Only what the screen renders is published.</strong> The mapset lays out one field per
     * row, {@code OPTN001} onward at {@code app/cpy-bms/COMEN01.CPY} line 60 and following, and the
     * program fills it with the number and the label. The two remaining items of the copybook entry
     * are not screen content: the program name is the dispatch target the program transfers control
     * to, and the user-type code is the input to the authorization comparison the program makes
     * <em>before</em> it dispatches, at {@code app/cbl/COMEN01C.cbl} lines 136 to 143. Neither is ever
     * displayed and neither is needed to select a row, because selection is by number. Both remain
     * where they belong, on {@code com.carddemo.service.MenuOptionCatalog}, which carries all four
     * items of every entry and is where row three of the migration's construct-mapping table is
     * discharged. Publishing them here would hand a client the means to name a dispatch target and
     * would let it read an authorization rule it has no use for.
     *
     * <p>This shape is not interchangeable with {@link AdminMenuOption}, and the guarantee rests on
     * type identity rather than on the two shapes differing in their component lists. The records
     * share no supertype, neither is a specialisation of the other, and neither is convertible to the
     * other: a caller holding one always knows which menu it came from, statically, and would still
     * know if the two ever came to carry the same components. That is the property worth having,
     * because it does not decay when a copybook changes.
     *
     * <p>Nothing is validated and nothing is transformed. The bound below measures and never alters,
     * so a value arrives and leaves byte for byte. No presence, pattern or numeric-range constraint is
     * applied: the option number's admissible range is the count comparison both legacy programs
     * perform <em>before</em> they index their table, and that comparison belongs to the menu service,
     * not to a response record that has already been handed the rows to render.
     *
     * @param number the option number the operator types to select this row, from
     *               {@code CDEMO-MENU-OPT-NUM}. An {@code int} because it is a cardinal the program
     *               compares arithmetically against its option count, not an identifier with
     *               contractual leading zeros; its external width is
     *               {@link MenuResponse#OPTION_NUMBER_WIDTH} digits.
     * @param label  the row text, from {@code CDEMO-MENU-OPT-NAME}, declared
     *               {@link MenuResponse#OPTION_LABEL_WIDTH} characters wide. Carried in display form
     *               exactly as supplied &mdash; neither widened to the declared width nor shortened.
     * @since 1.0.0
     */
    public record UserMenuOption(
            int number,
            @Size(max = MenuResponse.OPTION_LABEL_WIDTH) String label) {
    }

    /**
     * One rendered row of the <em>administrative</em> menu: the option number the operator types and
     * the text printed beside it, from the first two elementary items of the table view at
     * {@code app/cpy/COADM02Y.cpy} lines 44 to 48.
     *
     * <p>As on the user shape, only screen content is published. The mapset fills one field per row,
     * {@code OPTN001} onward at {@code app/cpy-bms/COADM01.CPY} line 60 and following, with the number
     * and the label; the program name the copybook also declares is the dispatch target
     * {@code app/cbl/COADM01C.cbl} transfers control to and is never displayed. It stays on
     * {@code com.carddemo.service.MenuOptionCatalog}.
     *
     * <p><strong>There is no user-type component here and none may be added.</strong> The
     * administrative table view declares no such item, giving a forty-five-byte entry against the user
     * catalog's forty-six. That asymmetry is a fact about the two copybooks and is recorded where the
     * full entries live; adding a component here &mdash; even a permanently absent one &mdash; would
     * fabricate a field the copybook does not have, and a field that exists in a contract is a field a
     * client will eventually populate.
     *
     * <p>This shape is likewise not interchangeable with {@link UserMenuOption}: two distinct record
     * types with no shared supertype and no conversion between them, which is what makes the
     * distinction survive independently of what either one happens to carry.
     *
     * <p>Nothing is validated and nothing is transformed; the bound below measures only.
     *
     * @param number the option number the operator types to select this row, from
     *               {@code CDEMO-ADMIN-OPT-NUM}, external width
     *               {@link MenuResponse#OPTION_NUMBER_WIDTH} digits.
     * @param label  the row text, from {@code CDEMO-ADMIN-OPT-NAME}, declared
     *               {@link MenuResponse#OPTION_LABEL_WIDTH} characters wide and carried in display
     *               form exactly as supplied.
     * @since 1.0.0
     */
    public record AdminMenuOption(
            int number,
            @Size(max = MenuResponse.OPTION_LABEL_WIDTH) String label) {
    }
}
