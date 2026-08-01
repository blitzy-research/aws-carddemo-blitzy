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
 * Immutable response contract for both CardDemo menu screens &mdash; the user main menu, legacy
 * transaction {@code CM00} driven by {@code app/cbl/COMEN01C.cbl}, and the administrative menu,
 * legacy transaction {@code CA00} driven by {@code app/cbl/COADM01C.cbl}.
 *
 * <p>A single type serves both screens because the two legacy programs are near-identical: their
 * working storage, their key handling, their option-entry sequence and their two screen maps line up
 * statement for statement, and both present the same two title lines from
 * {@code app/cpy/COTTL01Y.cpy} above a numbered list of options and a single message line. Their
 * screen maps are structurally identical too &mdash; {@code app/cpy-bms/COMEN01.CPY} and
 * {@code app/cpy-bms/COADM01.CPY} are both 260 lines with the same field names at the same widths,
 * and {@code app/bms/COMEN01.bms} and {@code app/bms/COADM01.bms} are both 167 lines with the same
 * twelve forty-character option rows, the same two-character option input and the same
 * seventy-eight-character message row. What differs between the two screens is exactly one thing:
 * the catalog of options each one lists.
 *
 * <p><strong>The two option catalogs are deliberately different shapes, and that asymmetry is
 * contractual.</strong> The user catalog is the group {@code CARDDEMO-MAIN-MENU-OPTIONS} declared at
 * {@code app/cpy/COMEN02Y.cpy} line 19, whose table view at lines 87 to 92 gives each entry four
 * elementary items &mdash; a two-digit number, a thirty-five-character name, an eight-character
 * program name and a one-character user-type code &mdash; for a forty-six-byte entry. The
 * administrative catalog is the group {@code CARDDEMO-ADMIN-MENU-OPTIONS} declared at
 * {@code app/cpy/COADM02Y.cpy} line 19, whose table view at lines 44 to 48 gives each entry only
 * three elementary items, for a forty-five-byte entry: <strong>there is no user-type item in the
 * administrative catalog at all.</strong> {@link UserMenuOption} and {@link AdminMenuOption}
 * therefore have four and three components respectively. They share no supertype, no interface and
 * no common base record, and the administrative shape carries no nullable stand-in for the item its
 * copybook never declared. Unifying them would fabricate a field, and a fabricated field is a
 * feature the legacy system does not have.
 *
 * <p><strong>Ten user options and four administrative options &mdash; never twelve and nine.</strong>
 * The user count is fixed at ten by {@code CDEMO-MENU-OPT-COUNT} at {@code app/cpy/COMEN02Y.cpy}
 * line 21 and the administrative count at four by {@code CDEMO-ADMIN-OPT-COUNT} at
 * {@code app/cpy/COADM02Y.cpy} line 20, and both programs compare the operator's entry against that
 * count before they index the table. The table views, however, are dimensioned twelve and nine, so
 * two user entries and five administrative entries exist as blank capacity that the counts exclude.
 * That surplus capacity is not part of this contract: this response carries only populated options,
 * this type publishes the two counts and never the two capacities, and no blank option is ever
 * emitted. Iterating capacity instead of count is the single easiest way to render blank rows onto a
 * menu, which is why the capacity numbers appear nowhere below.
 *
 * <p><strong>Option 8 has exactly one label and no role gate.</strong> At
 * {@code app/cpy/COMEN02Y.cpy} the label of user option 8 spans lines 68 to 70, where line 69 is a
 * commented-out alternative and line 70 is the value the compiler actually sees. Only the active
 * value at line 70 exists here. The inactive alternative is neither declared, nor quoted, nor
 * referenced, nor implied, and option 8 carries the same standard user-type code as its nine peers.
 * Reviving that dead comment &mdash; either as a label or as a role restriction on option 8 &mdash;
 * would change who may add a transaction, which is feature expansion rather than migration.
 *
 * <p><strong>Every text value crosses this boundary byte for byte.</strong> Nothing here is
 * trimmed, blank-filled, re-cased, re-numbered, re-ordered, canonicalised or re-shaped. Legacy
 * fixed-width fields are space-significant, so leading and trailing spaces are content and are
 * carried exactly as supplied. The option labels arrive in their final display form: the
 * configuration-layer catalog described below already publishes the trimmed label and records the
 * thirty-five-character declared width separately, so this response neither restores the trailing
 * spaces nor removes any. Option order is screen order, taken from the copybook declaration
 * sequence, and is never sorted or re-indexed.
 *
 * <p><strong>Three fixed-width message values exist in this estate and all three must stay
 * separate.</strong> {@code app/cpy/COTTL01Y.cpy} declares three forty-character values, all of
 * which are published below: the first title line at line 19, the <em>active</em> second title line
 * at line 22, and a forty-character acknowledgement at line 24 exposed here as
 * {@link #SCREEN_TITLE_THANK_YOU}. {@code app/cpy/CSMSG01Y.cpy} separately declares two
 * <em>fifty</em>-character common messages at its lines 19 and 21. The acknowledgement in the title
 * copybook and the acknowledgement in the common-message copybook look interchangeable and are not:
 * they carry different product tokens and different declared widths. The two fifty-character values
 * are deliberately <em>not</em> declared in this file &mdash; they belong to the message catalog in
 * the service layer, and keeping them out of this type makes confusing them with
 * {@link #SCREEN_TITLE_THANK_YOU} structurally impossible. This response's {@link #message()}
 * component simply carries whichever of them applies, at its full {@link #COMMON_MESSAGE_WIDTH}
 * characters and untouched.
 *
 * <p><strong>The fifty-versus-forty-nine reconciliation.</strong> Each of the two common messages is
 * forty-nine characters as written in its copybook literal, yet each occupies a fifty-character
 * field, so one trailing space completes it and the stored value is exactly fifty characters. A
 * caller must supply the fifty-character form; measuring the literal instead of the field is how a
 * byte-equivalence comparison silently loses its last character.
 *
 * <p><strong>What the message line carries.</strong> Both programs hold the outgoing message in an
 * eighty-character working-storage field declared at {@code app/cbl/COMEN01C.cbl} line 38 and
 * {@code app/cbl/COADM01C.cbl} line 38, then place it into a seventy-eight-character screen field
 * &mdash; {@code ERRMSGO} at {@code app/cpy-bms/COMEN01.CPY} line 260 and
 * {@code app/cpy-bms/COADM01.CPY} line 260, defined at that width in
 * {@code app/bms/COMEN01.bms} line 154 and {@code app/bms/COADM01.bms} line 154. Both widths are
 * published below, because the bound that matters to a caller is the working-storage width while the
 * width that matters to a byte comparison against the screen is the narrower one. Four texts reach
 * that line from the menu programs themselves. The thirty-seven-character rejection
 * {@code "Please enter a valid option number..."} is emitted by both programs at the same line
 * number &mdash; {@code app/cbl/COMEN01C.cbl} line 131 and {@code app/cbl/COADM01C.cbl} line 131
 * &mdash; with a lower-case verb, exactly three dots and no space before them. A
 * thirty-three-character access-denied text is emitted only by the user program, at
 * {@code app/cbl/COMEN01C.cbl} line 140, as part of the user-type gate that spans
 * {@code app/cbl/COMEN01C.cbl} lines 136 to 143 and raises its flag at
 * {@code app/cbl/COMEN01C.cbl} line 138; that text ends in a significant trailing space, and the
 * administrative program has no counterpart to it. The remaining two are the divergent
 * acknowledgements described next.
 *
 * <p><strong>The coming-soon divergence &mdash; two different texts that must never be merged.</strong>
 * When the option a caller selected names a placeholder program, both programs assemble an
 * acknowledgement from a twelve-character prefix and an eighteen-character suffix,
 * {@code "is coming soon ..."}. The user program, at {@code app/cbl/COMEN01C.cbl} lines 159 to 162,
 * inserts the selected option's name between them and takes only the name's leading word, so the
 * rendered result runs the word straight into the suffix with no separating space &mdash; option 1
 * renders as {@code "This option Accountis coming soon ..."}. The administrative program, at
 * {@code app/cbl/COADM01C.cbl} lines 149 to 152, uses the identical construction with the name
 * operand commented out, so its rendered result is {@code "This option is coming soon ..."}. The
 * missing space in the first is a source defect and is nonetheless the observable contract, so the
 * two texts are two distinct behaviours and are never reconciled into one. Assembling either text is
 * the menu service's work; this response only has to carry the result unaltered, which it does
 * because it applies no transformation to {@link #message()} whatsoever.
 *
 * <p><strong>Deliberate duplication with the configuration-layer option catalog.</strong> The
 * configuration layer holds a catalog bean that declares its own pair of nested option records with
 * these same two shapes. That duplication is intentional and must not be removed. This package sits
 * above the configuration layer in the dependency direction, so importing the catalog here would
 * invert the layering and drag a framework-managed singleton into a data-transfer type. The menu
 * service is the component that bridges the catalog to this response. The canonical lists published
 * below therefore exist independently, so that this response contract can be read, asserted against
 * and serialized without reaching into another layer for the text of its own screens.
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
 * dispatch method: {@link #route()} is an opaque declarative string that the navigation service
 * chooses and the client acts on, because the estate's twenty-five program-to-program transfers and
 * nineteen re-arming returns all become route values in a response body rather than server-side
 * forwarding.
 *
 * <p><strong>Screen presentation is not modelled.</strong> The legacy screens carry field lengths,
 * attribute bytes, cursor placement, terminal highlighting and a twelve-byte terminal-area prefix.
 * None of that appears here. {@link #messageSeverity()} records only the semantic intent the legacy
 * highlighting expressed, never the highlighting itself, and {@link #focusScreenFieldId()} is an
 * opaque label rather than a coordinate.
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
 * @param screenTitleLine1   the first screen title line, from {@code CCDA-TITLE01} at
 *                           {@code app/cpy/COTTL01Y.cpy} line 19, at exactly
 *                           {@link #SCREEN_TITLE_WIDTH} characters with its leading and trailing
 *                           spaces intact. Both menu screens show the same value; see
 *                           {@link #SCREEN_TITLE_LINE_1}. May be {@code null}.
 * @param screenTitleLine2   the second screen title line, from the <em>active</em> value of
 *                           {@code CCDA-TITLE02} at {@code app/cpy/COTTL01Y.cpy} line 22, at exactly
 *                           {@link #SCREEN_TITLE_WIDTH} characters. The alternative value commented
 *                           out at line 21 of that copybook stays inactive and is not published
 *                           anywhere in this type; see {@link #SCREEN_TITLE_LINE_2}. May be
 *                           {@code null}.
 * @param userMenuOptions    the populated user-menu options in copybook order, or {@code null} when
 *                           this response is not a user-menu response. Never blank-entry filler and
 *                           never dimensioned to the table capacity; see
 *                           {@link #CANONICAL_USER_MENU_OPTIONS} and
 *                           {@link #USER_MENU_OPTION_COUNT}. Defensively copied on construction and
 *                           always immutable when present.
 * @param adminMenuOptions   the populated administrative-menu options in copybook order, or
 *                           {@code null} when this response is not an administrative-menu response.
 *                           Exactly one of the two option components is populated on any real
 *                           response; see {@link #CANONICAL_ADMIN_MENU_OPTIONS} and
 *                           {@link #ADMIN_MENU_OPTION_COUNT}. Defensively copied on construction and
 *                           always immutable when present.
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
 *                           and a client may ignore it.
 * @param route              the declarative next route, or {@code null} when the response nominates
 *                           none. Opaque to this type, which neither resolves nor performs it.
 * @param navigationContext  the client-echoed navigation state to send back on the next call, or
 *                           {@code null}. Echoed request state and never a server session; see
 *                           {@link NavigationContext}, the typed form of the communication area
 *                           declared at {@code app/cpy/COCOM01Y.cpy} line 19 and included by both
 *                           menu programs.
 * @since 1.0.0
 */
public record MenuResponse(
        @Size(max = MenuResponse.SCREEN_TITLE_WIDTH) String screenTitleLine1,
        @Size(max = MenuResponse.SCREEN_TITLE_WIDTH) String screenTitleLine2,
        List<UserMenuOption> userMenuOptions,
        List<AdminMenuOption> adminMenuOptions,
        @Size(max = MenuResponse.SELECTED_OPTION_WIDTH) String selectedOption,
        @Size(max = MenuResponse.MESSAGE_WIDTH) String message,
        MessageSeverity messageSeverity,
        boolean errorFlag,
        String focusScreenFieldId,
        String route,
        NavigationContext navigationContext) {

    /**
     * Number of digits in a menu option number: 2.
     *
     * <p>The declared width of {@code CDEMO-MENU-OPT-NUM} in the table view at
     * {@code app/cpy/COMEN02Y.cpy} lines 87 to 92 and of {@code CDEMO-ADMIN-OPT-NUM} in the table
     * view at {@code app/cpy/COADM02Y.cpy} lines 44 to 48. Published as provenance for the option
     * number's external width; the number itself is carried as an {@code int} on both option records
     * because it is a genuine cardinal that the operator types and the program compares
     * arithmetically, not an identifier with contractual leading zeros.</p>
     */
    public static final int OPTION_NUMBER_WIDTH = 2;

    /**
     * Declared width of a menu option label: 35 characters.
     *
     * <p>The width of {@code CDEMO-MENU-OPT-NAME} at {@code app/cpy/COMEN02Y.cpy} lines 87 to 92 and
     * of {@code CDEMO-ADMIN-OPT-NAME} at {@code app/cpy/COADM02Y.cpy} lines 44 to 48, and the width
     * every one of the fourteen copybook label literals occupies. It is published as provenance and
     * as the upper bound on the label component of both option records. It is <em>not</em> a target
     * width: labels reach this response already in display form and this type widens nothing and
     * shortens nothing.</p>
     */
    public static final int OPTION_LABEL_WIDTH = 35;

    /**
     * Declared width of the program name an option targets: 8 characters.
     *
     * <p>The width of {@code CDEMO-MENU-OPT-PGMNAME} at {@code app/cpy/COMEN02Y.cpy} lines 87 to 92
     * and of {@code CDEMO-ADMIN-OPT-PGMNAME} at {@code app/cpy/COADM02Y.cpy} lines 44 to 48. All
     * fourteen program names in the two canonical lists below occupy exactly this width.</p>
     */
    public static final int OPTION_PROGRAM_NAME_WIDTH = 8;

    /**
     * Declared width of the user-type code on a <em>user</em> menu option: 1 character.
     *
     * <p>The width of {@code CDEMO-MENU-OPT-USRTYPE}, the fourth elementary item of the user table
     * view at {@code app/cpy/COMEN02Y.cpy} lines 87 to 92. There is deliberately no administrative
     * counterpart to this constant, because the administrative table view at
     * {@code app/cpy/COADM02Y.cpy} lines 44 to 48 declares no such item.</p>
     */
    public static final int USER_OPTION_USER_TYPE_WIDTH = 1;

    /**
     * Width of the echoed option entry: 2 characters.
     *
     * <p>The width of the {@code OPTION} screen field, declared as {@code OPTIONI} at
     * {@code app/cpy-bms/COMEN01.CPY} line 132 and {@code app/cpy-bms/COADM01.CPY} line 132, echoed
     * back as {@code OPTIONO} at line 254 of each, and defined at that length in
     * {@code app/bms/COMEN01.bms} line 145 and {@code app/bms/COADM01.bms} line 145.</p>
     *
     * <p>Declared separately from {@link #OPTION_NUMBER_WIDTH} even though the two values are equal.
     * They are different fields with different owners: this one is a screen field the operator types
     * into and the program echoes back as text, that one is an item of a copybook table. Neither is
     * derived from the other and a change to one must not silently change the other.</p>
     */
    public static final int SELECTED_OPTION_WIDTH = 2;

    /**
     * Declared width of each screen title line: 40 characters.
     *
     * <p>The width of all three elementary items of {@code CCDA-SCREEN-TITLE} in
     * {@code app/cpy/COTTL01Y.cpy}, and of the {@code TITLE01} and {@code TITLE02} screen fields at
     * {@code app/cpy-bms/COMEN01.CPY} lines 30 and 48, at the same lines of
     * {@code app/cpy-bms/COADM01.CPY}, and in {@code app/bms/COMEN01.bms} lines 38 and 61 with
     * {@code app/bms/COADM01.bms} matching. Every value published below at this width is exactly this
     * many characters, leading and trailing spaces included.</p>
     */
    public static final int SCREEN_TITLE_WIDTH = 40;

    /**
     * Declared width of the message the menu programs hold before sending it: 80 characters.
     *
     * <p>The width of the outgoing message field in the working storage of both programs, at
     * {@code app/cbl/COMEN01C.cbl} line 38 and {@code app/cbl/COADM01C.cbl} line 38. This is the
     * bound applied to {@link #message()}, because it is the widest value either program can hold and
     * therefore the widest a caller can legitimately supply.</p>
     */
    public static final int MESSAGE_WIDTH = 80;

    /**
     * Width of the screen field the message is rendered into: 78 characters.
     *
     * <p>The width of {@code ERRMSGI} at {@code app/cpy-bms/COMEN01.CPY} line 138 and of
     * {@code ERRMSGO} at line 260, matched line for line by {@code app/cpy-bms/COADM01.CPY}, and
     * defined at that length in {@code app/bms/COMEN01.bms} line 154 and
     * {@code app/bms/COADM01.bms} line 154.</p>
     *
     * <p>Recorded alongside {@link #MESSAGE_WIDTH} because the two genuinely differ and both matter:
     * a caller is bounded by the working-storage width, while a byte comparison against what the
     * legacy screen displayed is bounded by this narrower one. This type applies neither width as a
     * transformation &mdash; it never shortens a message to fit.</p>
     */
    public static final int SCREEN_MESSAGE_FIELD_WIDTH = 78;

    /**
     * Declared width of the two common messages this response's message line may carry: 50
     * characters.
     *
     * <p>The width of both elementary items of {@code CCDA-COMMON-MESSAGES} in
     * {@code app/cpy/CSMSG01Y.cpy}, whose literals appear at its lines 19 and 21. Each literal is
     * written as forty-nine characters and occupies a fifty-character field, so one trailing space
     * completes it and the stored value is exactly fifty characters. A caller must supply that
     * fifty-character form.</p>
     *
     * <p>The two values themselves are deliberately absent from this file: they belong to the message
     * catalog in the service layer. Only their width is recorded here, and only so that the
     * fifty-character acknowledgement can never be mistaken for the forty-character
     * {@link #SCREEN_TITLE_THANK_YOU} below, which is a different text at a different width.</p>
     */
    public static final int COMMON_MESSAGE_WIDTH = 50;

    /**
     * Number of populated user-menu options: 10.
     *
     * <p>The value of {@code CDEMO-MENU-OPT-COUNT} at {@code app/cpy/COMEN02Y.cpy} line 21, which is
     * the count both the legacy program and this contract honour. The table view at lines 87 to 92 of
     * the same copybook is dimensioned larger, and that surplus is blank capacity rather than
     * content: it is neither published here nor ever emitted.</p>
     */
    public static final int USER_MENU_OPTION_COUNT = 10;

    /**
     * Number of populated administrative-menu options: 4.
     *
     * <p>The value of {@code CDEMO-ADMIN-OPT-COUNT} at {@code app/cpy/COADM02Y.cpy} line 20. As with
     * the user menu, the table view at lines 44 to 48 is dimensioned larger and that surplus is blank
     * capacity that this contract excludes.</p>
     */
    public static final int ADMIN_MENU_OPTION_COUNT = 4;

    /**
     * The one-character standard user-type code carried by every user-menu option: {@code "U"}.
     *
     * <p>The literal value of the fourth item of all ten entries of {@code CDEMO-MENU-OPTIONS-DATA},
     * declared across {@code app/cpy/COMEN02Y.cpy} lines 25 to 84. All ten carry this same code
     * &mdash; <em>including option 8</em>, whose inactive commented-out label at line 69 of that
     * copybook might suggest otherwise and does not change the code the compiler sees at line 72.</p>
     *
     * <p>Published as the raw code rather than as a domain enumeration constant, so this
     * data-transfer type stays free of any dependency on the domain layer and an undeclared code
     * arriving from a caller survives the round trip untranslated. The meaning of the code is decided
     * where the gate is evaluated, which is the menu service, not here.</p>
     */
    public static final String STANDARD_USER_TYPE_CODE = "U";

    /**
     * The first screen title line at its full {@link #SCREEN_TITLE_WIDTH} characters.
     *
     * <p>The value of {@code CCDA-TITLE01} at {@code app/cpy/COTTL01Y.cpy} line 19. Both the six
     * leading spaces and the seven trailing spaces are content: the legacy value is centred within
     * its field and the surrounding spaces are what centre it, so a comparison that discards them
     * compares a different value.</p>
     *
     * <p>Written out in full rather than assembled from a shorter literal and a space-producing
     * helper, so that what this constant contains is visible at the point of declaration and nothing
     * computes it.</p>
     */
    public static final String SCREEN_TITLE_LINE_1 = "      AWS Mainframe Modernization       ";

    /**
     * The second screen title line at its full {@link #SCREEN_TITLE_WIDTH} characters.
     *
     * <p>The <em>active</em> value of {@code CCDA-TITLE02}, at {@code app/cpy/COTTL01Y.cpy} line 22.
     * Line 21 of that copybook holds an alternative value that is commented out; it is inactive in
     * the legacy source, it is therefore not the screen contract, and it is deliberately declared
     * nowhere in this file. Reviving it would change what every menu screen displays.</p>
     *
     * <p>The fourteen leading and eighteen trailing spaces are content, for the reason given on
     * {@link #SCREEN_TITLE_LINE_1}.</p>
     */
    public static final String SCREEN_TITLE_LINE_2 = "              CardDemo                  ";

    /**
     * The forty-character acknowledgement declared in the <em>title</em> copybook, at its full
     * {@link #SCREEN_TITLE_WIDTH} characters.
     *
     * <p>The value of {@code CCDA-THANK-YOU} at {@code app/cpy/COTTL01Y.cpy} line 24, ending in one
     * significant trailing space.</p>
     *
     * <p><strong>This is not the acknowledgement from the common-message copybook.</strong>
     * {@code app/cpy/CSMSG01Y.cpy} line 19 declares a similar-looking acknowledgement that differs in
     * two ways at once: it names the product differently and it occupies a
     * {@link #COMMON_MESSAGE_WIDTH}-character field rather than a
     * {@link #SCREEN_TITLE_WIDTH}-character one. The two are separate values with separate owners and
     * merging them, or substituting one for the other, is a byte-equivalence failure. The constant
     * name here says {@code SCREEN_TITLE} precisely so that the distinction survives a careless
     * edit.</p>
     */
    public static final String SCREEN_TITLE_THANK_YOU = "Thank you for using CCDA application... ";

    /**
     * The ten populated user-menu options, in the order {@code CDEMO-MENU-OPTIONS-DATA} declares them
     * across {@code app/cpy/COMEN02Y.cpy} lines 25 to 84.
     *
     * <p>The order is contractual, not incidental: the legacy screen renders the rows in table order
     * and the operator selects a row by the number printed beside it, so this list is never sorted,
     * re-indexed or re-numbered. Exactly {@link #USER_MENU_OPTION_COUNT} entries are present and
     * every one is populated; the surplus capacity of the copybook's table view contributes nothing
     * here and no blank entry exists to be rendered.</p>
     *
     * <p>Labels appear in display form, matching the trimmed label the configuration-layer catalog
     * publishes, with {@link #OPTION_LABEL_WIDTH} recorded above as the declared width they occupy in
     * the copybook. Option 8 carries the active label from line 70 of the copybook and the same
     * {@link #STANDARD_USER_TYPE_CODE} as every other entry.</p>
     *
     * <p>Immutable and safe to share: the list is unmodifiable and every element is a record whose
     * components are immutable values.</p>
     */
    public static final List<UserMenuOption> CANONICAL_USER_MENU_OPTIONS = List.of(
            new UserMenuOption(1, "Account View", "COACTVWC", STANDARD_USER_TYPE_CODE),
            new UserMenuOption(2, "Account Update", "COACTUPC", STANDARD_USER_TYPE_CODE),
            new UserMenuOption(3, "Credit Card List", "COCRDLIC", STANDARD_USER_TYPE_CODE),
            new UserMenuOption(4, "Credit Card View", "COCRDSLC", STANDARD_USER_TYPE_CODE),
            new UserMenuOption(5, "Credit Card Update", "COCRDUPC", STANDARD_USER_TYPE_CODE),
            new UserMenuOption(6, "Transaction List", "COTRN00C", STANDARD_USER_TYPE_CODE),
            new UserMenuOption(7, "Transaction View", "COTRN01C", STANDARD_USER_TYPE_CODE),
            new UserMenuOption(8, "Transaction Add", "COTRN02C", STANDARD_USER_TYPE_CODE),
            new UserMenuOption(9, "Transaction Reports", "CORPT00C", STANDARD_USER_TYPE_CODE),
            new UserMenuOption(10, "Bill Payment", "COBIL00C", STANDARD_USER_TYPE_CODE));

    /**
     * The four populated administrative-menu options, in the order
     * {@code CDEMO-ADMIN-OPTIONS-DATA} declares them across {@code app/cpy/COADM02Y.cpy} lines 24 to
     * 42.
     *
     * <p>Exactly {@link #ADMIN_MENU_OPTION_COUNT} entries are present, in screen order, with no blank
     * entry and no surplus capacity, for the reasons given on
     * {@link #CANONICAL_USER_MENU_OPTIONS}. No entry carries a user-type code, because the
     * administrative table view declares no such item &mdash; the shape of {@link AdminMenuOption} is
     * the shape of the copybook.</p>
     *
     * <p>Immutable and safe to share.</p>
     */
    public static final List<AdminMenuOption> CANONICAL_ADMIN_MENU_OPTIONS = List.of(
            new AdminMenuOption(1, "User List (Security)", "COUSR00C"),
            new AdminMenuOption(2, "User Add (Security)", "COUSR01C"),
            new AdminMenuOption(3, "User Update (Security)", "COUSR02C"),
            new AdminMenuOption(4, "User Delete (Security)", "COUSR03C"));

    /**
     * Detaches both option collections from the caller without altering what either of them contains.
     *
     * <p>A supplied collection is copied with {@link List#copyOf(java.util.Collection)}, which both
     * severs any aliasing to caller-owned state and rejects a {@code null} element outright. A blank
     * option would be exactly the surplus-capacity entry this contract excludes, so failing on
     * {@code null} is preferable to serializing a row a client would then render.
     *
     * <p><strong>A {@code null} collection is stored as {@code null}, deliberately, and is not
     * converted to an empty list.</strong> The two option components answer different questions than
     * their contents do: on a user-menu response there is no administrative option collection
     * <em>at all</em>, which is a different statement from an administrative menu that happens to
     * list nothing. Because the module serializes only non-{@code null} properties, storing
     * {@code null} omits the irrelevant collection from the payload entirely, and a client reading a
     * user-menu response never has to decide what an empty administrative collection was supposed to
     * mean. Callers that want the distinction made for them should use {@link #forUserMenu} or
     * {@link #forAdminMenu}, which populate exactly one collection and leave the other absent.
     *
     * <p>No count is enforced. The copybook counts are published as
     * {@link #USER_MENU_OPTION_COUNT} and {@link #ADMIN_MENU_OPTION_COUNT} and the canonical lists
     * honour them, but rejecting any other size here would make this response unable to carry a
     * legitimately shorter collection &mdash; and the legacy programs themselves compare the
     * operator's entry against the count rather than against the size of what was rendered. Enforcing
     * the count in a response type would move a service decision into a data-transfer type and would
     * turn a screen with one fewer row into a server failure.
     *
     * <p>Every other component is stored exactly as supplied, including {@code null} and including any
     * leading or trailing space, because the legacy fields they derive from are fixed-width and
     * space-significant.
     */
    public MenuResponse {
        userMenuOptions = (userMenuOptions == null) ? null : List.copyOf(userMenuOptions);
        adminMenuOptions = (adminMenuOptions == null) ? null : List.copyOf(adminMenuOptions);
    }

    /**
     * Builds a user-menu response &mdash; the reply to legacy transaction {@code CM00}.
     *
     * <p>Populates the user option collection, leaves the administrative collection absent, and fills
     * both title lines from {@link #SCREEN_TITLE_LINE_1} and {@link #SCREEN_TITLE_LINE_2}. Filling the
     * titles here is faithful rather than convenient: both menu screens display the same two values
     * from the same copybook, so there is no case in which a menu response carries different ones.
     *
     * @param userMenuOptions    the populated user options in screen order, ordinarily
     *                           {@link #CANONICAL_USER_MENU_OPTIONS}; {@code null} leaves the
     *                           collection absent
     * @param selectedOption     the echoed option entry, or {@code null}
     * @param message            the message line, or {@code null} when the screen shows none
     * @param messageSeverity    the semantic intent of {@code message}, or {@code null}
     * @param errorFlag          whether this response reports a failed interaction
     * @param focusScreenFieldId the opaque screen field label focus belongs on, or {@code null}
     * @param route              the declarative next route, or {@code null}
     * @param navigationContext  the client-echoed navigation state, or {@code null}
     * @return a user-menu response carrying no administrative option collection
     */
    public static MenuResponse forUserMenu(List<UserMenuOption> userMenuOptions,
                                           String selectedOption,
                                           String message,
                                           MessageSeverity messageSeverity,
                                           boolean errorFlag,
                                           String focusScreenFieldId,
                                           String route,
                                           NavigationContext navigationContext) {
        return new MenuResponse(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2, userMenuOptions, null,
                selectedOption, message, messageSeverity, errorFlag, focusScreenFieldId, route,
                navigationContext);
    }

    /**
     * Builds an administrative-menu response &mdash; the reply to legacy transaction {@code CA00}.
     *
     * <p>Populates the administrative option collection, leaves the user collection absent, and fills
     * both title lines exactly as {@link #forUserMenu} does, for the same reason.
     *
     * @param adminMenuOptions   the populated administrative options in screen order, ordinarily
     *                           {@link #CANONICAL_ADMIN_MENU_OPTIONS}; {@code null} leaves the
     *                           collection absent
     * @param selectedOption     the echoed option entry, or {@code null}
     * @param message            the message line, or {@code null} when the screen shows none
     * @param messageSeverity    the semantic intent of {@code message}, or {@code null}
     * @param errorFlag          whether this response reports a failed interaction
     * @param focusScreenFieldId the opaque screen field label focus belongs on, or {@code null}
     * @param route              the declarative next route, or {@code null}
     * @param navigationContext  the client-echoed navigation state, or {@code null}
     * @return an administrative-menu response carrying no user option collection
     */
    public static MenuResponse forAdminMenu(List<AdminMenuOption> adminMenuOptions,
                                            String selectedOption,
                                            String message,
                                            MessageSeverity messageSeverity,
                                            boolean errorFlag,
                                            String focusScreenFieldId,
                                            String route,
                                            NavigationContext navigationContext) {
        return new MenuResponse(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2, null, adminMenuOptions,
                selectedOption, message, messageSeverity, errorFlag, focusScreenFieldId, route,
                navigationContext);
    }

    /**
     * Tests whether this response carries a user option collection at all.
     *
     * <p>Presence, not content: a response that carries an empty user collection answers {@code true}
     * here, because an empty collection is still a user menu whereas an absent one is not a user menu.
     * This is the test a client uses to decide which of the two option shapes it is about to read, and
     * it selects nothing and routes nothing.
     *
     * @return {@code true} when the user option collection is present, even if empty
     */
    public boolean carriesUserMenu() {
        return userMenuOptions != null;
    }

    /**
     * Tests whether this response carries an administrative option collection at all.
     *
     * <p>The administrative counterpart of {@link #carriesUserMenu()}, with the same
     * presence-not-content semantics.
     *
     * @return {@code true} when the administrative option collection is present, even if empty
     */
    public boolean carriesAdminMenu() {
        return adminMenuOptions != null;
    }

    /**
     * What a menu message <em>means</em>, so that a client can present it appropriately without this
     * contract dictating how.
     *
     * <p>The legacy programs distinguished the two cases by switching the message line's terminal
     * highlighting before sending the screen. That highlighting is a presentation value belonging to a
     * 3270 device and it is modelled nowhere in this module: no highlight value, no attribute byte and
     * no marker character appears in this package. Only the distinction the highlighting encoded
     * survives, as these two constants.
     *
     * <p>The two are genuinely independent of {@link MenuResponse#errorFlag()} and neither is derived
     * from the other. Both programs set their error switch on a rejected entry, and both also emit a
     * message on the successful path where the selected option names a placeholder program &mdash;
     * that path leaves the error switch clear and still needs a message, which is exactly why the
     * intent is carried separately.
     *
     * @since 1.0.0
     */
    public enum MessageSeverity {

        /**
         * The message reports an outcome rather than a problem.
         *
         * <p>The case the legacy programs took at {@code app/cbl/COMEN01C.cbl} lines 159 to 162 and
         * {@code app/cbl/COADM01C.cbl} lines 149 to 152: the interaction succeeded, the selected
         * option simply has no program behind it yet, and the operator is told so. The error switch is
         * clear on this path.
         */
        INFORMATIONAL,

        /**
         * The message reports a problem the operator has to resolve.
         *
         * <p>The case the legacy programs took when they rejected the entry at their common line 131,
         * when the user program refused an option the signed-on user type may not select at
         * {@code app/cbl/COMEN01C.cbl} lines 136 to 143, and when either program received an
         * unmapped key. The error switch is set on these paths.
         */
        ERROR
    }

    /**
     * One populated entry of the <em>user</em> menu catalog: four components, matching the four
     * elementary items of the table view at {@code app/cpy/COMEN02Y.cpy} lines 87 to 92.
     *
     * <p>This shape is not interchangeable with {@link AdminMenuOption}. It carries a user-type code
     * that the administrative shape does not have, because the administrative copybook does not
     * declare one. The two records share no supertype and neither is a specialisation of the other; a
     * caller that holds one always knows which menu it belongs to, and no run-time test is needed to
     * find out.
     *
     * <p>Nothing is validated and nothing is transformed. The bounds below measure and never alter, so
     * a value arrives and leaves byte for byte. No presence, pattern or numeric-range constraint is
     * applied: the option number's admissible range is the count comparison both legacy programs
     * perform <em>before</em> they index their table, and that comparison belongs to the menu service,
     * not to a response record that has already been handed the rows to render.
     *
     * @param number      the option number the operator types to select this row, from
     *                    {@code CDEMO-MENU-OPT-NUM}. An {@code int} because it is a cardinal the
     *                    program compares arithmetically against its option count, not an identifier
     *                    with contractual leading zeros; its external width is
     *                    {@link MenuResponse#OPTION_NUMBER_WIDTH} digits.
     * @param label       the row text, from {@code CDEMO-MENU-OPT-NAME}, declared
     *                    {@link MenuResponse#OPTION_LABEL_WIDTH} characters wide. Carried in display
     *                    form exactly as supplied &mdash; neither widened to the declared width nor
     *                    shortened.
     * @param programName the legacy program this row targets, from {@code CDEMO-MENU-OPT-PGMNAME},
     *                    {@link MenuResponse#OPTION_PROGRAM_NAME_WIDTH} characters. Carried as an
     *                    opaque legacy name; whether it denotes a placeholder is the menu service's
     *                    question, not this record's.
     * @param userType    the raw one-character user-type code, from {@code CDEMO-MENU-OPT-USRTYPE},
     *                    {@link MenuResponse#USER_OPTION_USER_TYPE_WIDTH} character. Held as raw text
     *                    rather than as a domain enumeration so an undeclared code survives the round
     *                    trip untranslated and this data-transfer type stays independent of the domain
     *                    layer. All ten canonical entries carry
     *                    {@link MenuResponse#STANDARD_USER_TYPE_CODE}.
     * @since 1.0.0
     */
    public record UserMenuOption(
            int number,
            @Size(max = MenuResponse.OPTION_LABEL_WIDTH) String label,
            @Size(max = MenuResponse.OPTION_PROGRAM_NAME_WIDTH) String programName,
            @Size(max = MenuResponse.USER_OPTION_USER_TYPE_WIDTH) String userType) {
    }

    /**
     * One populated entry of the <em>administrative</em> menu catalog: three components, matching the
     * three elementary items of the table view at {@code app/cpy/COADM02Y.cpy} lines 44 to 48.
     *
     * <p><strong>There is no user-type component here and none may be added.</strong> The
     * administrative table view declares a two-digit number, a label and a program name and nothing
     * else, giving a forty-five-byte entry against the user catalog's forty-six. Adding a component
     * &mdash; even a permanently absent one &mdash; would fabricate a field the copybook does not
     * have, and a field that exists in the contract is a field a client will eventually populate. The
     * asymmetry against {@link UserMenuOption} is the whole point and is preserved on purpose.
     *
     * <p>As with the user shape, nothing is validated and nothing is transformed; the bounds below
     * measure only.
     *
     * @param number      the option number the operator types to select this row, from
     *                    {@code CDEMO-ADMIN-OPT-NUM}, external width
     *                    {@link MenuResponse#OPTION_NUMBER_WIDTH} digits.
     * @param label       the row text, from {@code CDEMO-ADMIN-OPT-NAME}, declared
     *                    {@link MenuResponse#OPTION_LABEL_WIDTH} characters wide and carried in
     *                    display form exactly as supplied.
     * @param programName the legacy program this row targets, from {@code CDEMO-ADMIN-OPT-PGMNAME},
     *                    {@link MenuResponse#OPTION_PROGRAM_NAME_WIDTH} characters, carried as an
     *                    opaque legacy name.
     * @since 1.0.0
     */
    public record AdminMenuOption(
            int number,
            @Size(max = MenuResponse.OPTION_LABEL_WIDTH) String label,
            @Size(max = MenuResponse.OPTION_PROGRAM_NAME_WIDTH) String programName) {
    }
}
