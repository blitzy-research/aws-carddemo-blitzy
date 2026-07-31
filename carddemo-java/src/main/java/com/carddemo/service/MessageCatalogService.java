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

import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * Immutable catalog of the shared, fixed-width screen text that the legacy CICS estate carried in
 * copybooks rather than in a message table.
 *
 * <h2>Provenance</h2>
 * Translated from the AWS CardDemo z/OS mainframe application at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy authorities are:
 * <ul>
 *   <li>{@code app/cpy/CSMSG01Y.cpy} &mdash; 24 lines; Apache header on lines 1-16, followed by the
 *       {@code 01 CCDA-COMMON-MESSAGES} group with two {@code PIC X(50)} elementary items. This is the
 *       primary authority for this class.</li>
 *   <li>{@code app/cpy/COTTL01Y.cpy} &mdash; the {@code 01 CCDA-SCREEN-TITLE} group with three
 *       {@code PIC X(40)} elementary items.</li>
 * </ul>
 * No COBOL source is read at runtime and no COBOL statement is reproduced here; only member names, field
 * names, field widths and the external-contract text itself are carried across.
 *
 * <h2>Why this is a Spring singleton and not seventeen copies</h2>
 * {@code CSMSG01Y} is textually included by <strong>all 17</strong> online COBOL programs, so the legacy
 * estate declared the same two messages seventeen times over. Together with {@code COCOM01Y},
 * {@code COTTL01Y} and {@code CSDAT01Y} &mdash; each likewise included by all 17 online programs &mdash;
 * that is 68 textual inclusions, which this migration collapses into four injected singletons. This class
 * is one of those four: it replaces the 17 textual inclusions of {@code CSMSG01Y}, and it also hosts the
 * {@code COTTL01Y} screen titles so that those 17 inclusions collapse to a single declaration as well.
 *
 * <p>Collaborating services obtain the text by injecting this bean and calling an accessor, which is what
 * keeps a single declaration authoritative. The {@code public static final} constants are exposed for the
 * same values so that a caller which is not itself a Spring bean &mdash; a fixed-width record assertion,
 * for example &mdash; can reference them without a container.</p>
 *
 * <h2>Fixed widths are an external contract</h2>
 * Every value published here is padded to the width its COBOL {@code PIC} clause declares, and that width
 * is part of the 3270 screen contract rather than incidental whitespace. Values must therefore never be
 * shortened or whitespace-normalised, by this class or by any caller. This class performs no whitespace
 * normalisation of any kind: the only string operation it applies is the padding described below.
 *
 * <p>A source detail worth recording: in {@code CSMSG01Y} each literal as written in the copybook is 49
 * characters long while the field that receives it is declared {@code PIC X(50)}. COBOL left-justifies a
 * short alphanumeric value and space-fills it to the declared width, so the runtime field content is 50
 * characters. This class therefore materialises the <em>field</em> content at its declared width rather
 * than the shorter source literal, and it derives the padding arithmetically so the discrepancy cannot be
 * re-introduced by hand-counting spaces.</p>
 *
 * <h2>Where the two common messages are consumed</h2>
 * In {@code app/cbl/COSGN00C.cbl} (transaction {@code CC00}) the exit-key path places the thank-you text
 * into the {@code WS-MESSAGE PIC X(80)} work field and sends plain text <em>without</em> raising the error
 * flag, whereas the unmapped-attention-key path raises the error flag <em>first</em> and only then places
 * the invalid-key text and re-sends the sign-on screen. The invalid-key text is shared far more widely: it
 * is the default arm of the attention-key decision in the canonical main paragraph common to all 17 online
 * programs, as {@code app/cbl/COBIL00C.cbl} also shows.
 *
 * <p>This class supplies text only. Error-flag state, cursor positioning, screen transmission and routing
 * belong to the individual online services and to the navigation service, and field-level error decoration
 * belongs to the presentation DTO layer. No message here takes runtime substitution parameters; messages
 * that a single program composes for itself stay with the service that owns them.</p>
 *
 * <h2>Thread safety</h2>
 * Stateless and effectively immutable. Every field is {@code private static final} or {@code public static
 * final}, every published value is an immutable {@code String} or an unmodifiable {@code Map}, and there is
 * no setter and no mutable state of any kind, so the singleton is safe for concurrent use.
 */
@Service
public final class MessageCatalogService {

    /**
     * Declared width of both {@code CCDA-COMMON-MESSAGES} elementary items, from their
     * {@code PIC X(50)} clauses in {@code app/cpy/CSMSG01Y.cpy}.
     */
    public static final int COMMON_MESSAGE_WIDTH = 50;

    /**
     * Declared width of all three {@code CCDA-SCREEN-TITLE} elementary items, from their
     * {@code PIC X(40)} clauses in {@code app/cpy/COTTL01Y.cpy}.
     */
    public static final int SCREEN_TITLE_WIDTH = 40;

    /**
     * Legacy field name of the 50-character thank-you message, used as its catalog key.
     */
    public static final String KEY_MSG_THANK_YOU = "CCDA-MSG-THANK-YOU";

    /**
     * Legacy field name of the 50-character invalid-key message, used as its catalog key.
     */
    public static final String KEY_MSG_INVALID_KEY = "CCDA-MSG-INVALID-KEY";

    /**
     * Legacy field name of the first screen-title line, used as its catalog key.
     */
    public static final String KEY_TITLE01 = "CCDA-TITLE01";

    /**
     * Legacy field name of the second screen-title line, used as its catalog key.
     */
    public static final String KEY_TITLE02 = "CCDA-TITLE02";

    /**
     * Legacy field name of the 40-character screen-title thank-you line, used as its catalog key.
     */
    public static final String KEY_TITLE_THANK_YOU = "CCDA-THANK-YOU";

    /**
     * Visible portion of {@code CCDA-MSG-THANK-YOU}, before padding to the declared field width.
     * Three trailing full stops are part of the text.
     */
    private static final String MSG_THANK_YOU_TEXT = "Thank you for using CardDemo application...";

    /**
     * Visible portion of {@code CCDA-MSG-INVALID-KEY}, before padding to the declared field width.
     * The single space after the first full stop and the three trailing full stops are part of the text.
     */
    private static final String MSG_INVALID_KEY_TEXT = "Invalid key pressed. Please see below...";

    /**
     * Visible portion of {@code CCDA-TITLE01}. The legacy value is centred within its field, so the six
     * leading spaces are written as an explicit repeat count rather than as counted whitespace.
     */
    private static final String TITLE01_TEXT = " ".repeat(6) + "AWS Mainframe Modernization";

    /**
     * Visible portion of the <em>active</em> value of {@code CCDA-TITLE02}, centred within its field with
     * fourteen leading spaces written as an explicit repeat count.
     *
     * <p>{@code app/cpy/COTTL01Y.cpy} carries a commented-out alternative value for this same field on the
     * line immediately above the active one. That alternative is inactive in the legacy source and is
     * deliberately left inactive here: it is neither activated nor offered as a second constant.</p>
     */
    private static final String TITLE02_TEXT = " ".repeat(14) + "CardDemo";

    /**
     * Visible portion of {@code CCDA-THANK-YOU}. Note the product token and the width: this is the
     * screen-title variant, which is a different contract from the common message of the same purpose.
     */
    private static final String TITLE_THANK_YOU_TEXT = "Thank you for using CCDA application...";

    /**
     * {@code CCDA-MSG-THANK-YOU} from {@code app/cpy/CSMSG01Y.cpy}, at its contractual width of
     * {@link #COMMON_MESSAGE_WIDTH} characters.
     *
     * <p>The trailing spaces are part of the 3270 screen contract and must never be trimmed. This is the
     * <strong>CardDemo</strong>-token, 50-character variant; it is deliberately distinct from
     * {@link #CCDA_THANK_YOU}, which is the CCDA-token, 40-character screen title. The two are separate
     * external contracts and must not be unified, aliased or de-duplicated.</p>
     */
    public static final String CCDA_MSG_THANK_YOU = padToWidth(MSG_THANK_YOU_TEXT, COMMON_MESSAGE_WIDTH);

    /**
     * {@code CCDA-MSG-INVALID-KEY} from {@code app/cpy/CSMSG01Y.cpy}, at its contractual width of
     * {@link #COMMON_MESSAGE_WIDTH} characters.
     *
     * <p>The trailing spaces are part of the 3270 screen contract and must never be trimmed.</p>
     */
    public static final String CCDA_MSG_INVALID_KEY =
            padToWidth(MSG_INVALID_KEY_TEXT, COMMON_MESSAGE_WIDTH);

    /**
     * {@code CCDA-TITLE01} from {@code app/cpy/COTTL01Y.cpy}, at its contractual width of
     * {@link #SCREEN_TITLE_WIDTH} characters. Both the leading and the trailing spaces are significant:
     * together they centre the text within the 3270 field.
     */
    public static final String CCDA_TITLE01 = padToWidth(TITLE01_TEXT, SCREEN_TITLE_WIDTH);

    /**
     * {@code CCDA-TITLE02} from {@code app/cpy/COTTL01Y.cpy}, at its contractual width of
     * {@link #SCREEN_TITLE_WIDTH} characters, carrying the value that is <em>active</em> in the legacy
     * copybook. The commented-out alternative in that copybook remains inactive.
     */
    public static final String CCDA_TITLE02 = padToWidth(TITLE02_TEXT, SCREEN_TITLE_WIDTH);

    /**
     * {@code CCDA-THANK-YOU} from {@code app/cpy/COTTL01Y.cpy}, at its contractual width of
     * {@link #SCREEN_TITLE_WIDTH} characters.
     *
     * <p>This is the screen-title thank-you line and it is <strong>deliberately distinct</strong> from
     * {@link #CCDA_MSG_THANK_YOU}: the product token differs and the field width differs. Both variants
     * are reproduced exactly as the legacy source declares them; neither is "corrected" towards the
     * other.</p>
     */
    public static final String CCDA_THANK_YOU = padToWidth(TITLE_THANK_YOU_TEXT, SCREEN_TITLE_WIDTH);

    /**
     * The {@code CCDA-COMMON-MESSAGES} group as an unmodifiable map keyed by legacy field name. Built once
     * with the immutable {@code java.util.Map} factory, so it needs no defensive copy to be genuinely
     * unmodifiable.
     */
    private static final Map<String, String> COMMON_MESSAGES = Map.of(
            KEY_MSG_THANK_YOU, CCDA_MSG_THANK_YOU,
            KEY_MSG_INVALID_KEY, CCDA_MSG_INVALID_KEY);

    /**
     * The {@code CCDA-SCREEN-TITLE} group as an unmodifiable map keyed by legacy field name. Built once
     * with the immutable {@code java.util.Map} factory, so it needs no defensive copy to be genuinely
     * unmodifiable.
     */
    private static final Map<String, String> SCREEN_TITLES = Map.of(
            KEY_TITLE01, CCDA_TITLE01,
            KEY_TITLE02, CCDA_TITLE02,
            KEY_TITLE_THANK_YOU, CCDA_THANK_YOU);

    /**
     * Creates the catalog singleton.
     *
     * <p>This service sits at the base of the dependency graph and has no collaborators, so constructor
     * injection contributes no parameters. The constructor is declared explicitly rather than left implicit
     * so that the absence of collaborators is a visible, reviewable property of the class.</p>
     */
    public MessageCatalogService() {
        // No collaborators to inject: every published value is a compile-time-declared constant.
    }

    /**
     * Returns {@code CCDA-MSG-THANK-YOU} at its full contractual width.
     *
     * <p>In the legacy estate this is the text the sign-on program places in its message work field on the
     * exit-key path, without raising the error flag.</p>
     *
     * @return the thank-you common message, exactly {@link #COMMON_MESSAGE_WIDTH} characters wide, never
     *         {@code null} and never trimmed
     */
    public String thankYouMessage() {
        return CCDA_MSG_THANK_YOU;
    }

    /**
     * Returns {@code CCDA-MSG-INVALID-KEY} at its full contractual width.
     *
     * <p>In the legacy estate this is the text used by the default arm of the attention-key decision in the
     * canonical main paragraph shared by all 17 online programs, after the error flag has been raised.</p>
     *
     * @return the invalid-key common message, exactly {@link #COMMON_MESSAGE_WIDTH} characters wide, never
     *         {@code null} and never trimmed
     */
    public String invalidKeyMessage() {
        return CCDA_MSG_INVALID_KEY;
    }

    /**
     * Returns {@code CCDA-TITLE01}, the first screen-title line, at its full contractual width.
     *
     * @return the first screen title, exactly {@link #SCREEN_TITLE_WIDTH} characters wide, never
     *         {@code null} and never trimmed
     */
    public String screenTitle01() {
        return CCDA_TITLE01;
    }

    /**
     * Returns {@code CCDA-TITLE02}, the second screen-title line, at its full contractual width, carrying
     * the value that is active in the legacy copybook.
     *
     * @return the second screen title, exactly {@link #SCREEN_TITLE_WIDTH} characters wide, never
     *         {@code null} and never trimmed
     */
    public String screenTitle02() {
        return CCDA_TITLE02;
    }

    /**
     * Returns {@code CCDA-THANK-YOU}, the screen-title thank-you line, at its full contractual width.
     *
     * <p>This is the 40-character, CCDA-token variant. Callers that need the 50-character, CardDemo-token
     * common message must call {@link #thankYouMessage()} instead; the two values are separate external
     * contracts.</p>
     *
     * @return the screen-title thank-you line, exactly {@link #SCREEN_TITLE_WIDTH} characters wide, never
     *         {@code null} and never trimmed
     */
    public String screenTitleThankYou() {
        return CCDA_THANK_YOU;
    }

    /**
     * Returns the whole {@code CCDA-COMMON-MESSAGES} group keyed by legacy field name.
     *
     * <p>Provided so that a contract test can enumerate the group and verify every entry's width without
     * naming each entry, and so that the mapping from legacy field name to migrated value is auditable in
     * one place.</p>
     *
     * @return an unmodifiable map of legacy field name to message text; every value is exactly
     *         {@link #COMMON_MESSAGE_WIDTH} characters wide
     */
    public Map<String, String> commonMessages() {
        return COMMON_MESSAGES;
    }

    /**
     * Returns the whole {@code CCDA-SCREEN-TITLE} group keyed by legacy field name.
     *
     * <p>The commented-out alternative title present in the legacy copybook is deliberately absent from
     * this map, because it is inactive in the legacy source.</p>
     *
     * @return an unmodifiable map of legacy field name to screen-title text; every value is exactly
     *         {@link #SCREEN_TITLE_WIDTH} characters wide
     */
    public Map<String, String> screenTitles() {
        return SCREEN_TITLES;
    }

    /**
     * Right-pads {@code text} with spaces to exactly {@code width} characters, reproducing how COBOL
     * left-justifies a short alphanumeric value and space-fills it to the width its {@code PIC} clause
     * declares.
     *
     * <p>Padding is derived arithmetically from the declared width so that no space in this source file is
     * ever counted by eye, and the returned value is exactly {@code width} characters by construction. That
     * is why no runtime assertion is used to confirm the width: assertions are disabled by default in a
     * normal JVM launch and would therefore guarantee nothing.</p>
     *
     * <p>Two failure modes are rejected eagerly, during class initialisation, so that either surfaces as
     * the bean failing to load rather than as wrong bytes on a screen or in a fixed-width record. Text
     * longer than its declared width is a contract violation rather than something to truncate, because
     * silently shortening screen text would corrupt the field layout. Null text means a catalog entry was
     * initialised from a field declared later in this class, which Java would otherwise resolve to null
     * silently; naming that explicitly is what keeps a static-initialisation-order mistake from shipping.</p>
     *
     * @param text  the visible text to pad; must not be {@code null}
     * @param width the declared field width to pad up to
     * @return {@code text} followed by enough spaces to reach exactly {@code width} characters
     * @throws IllegalStateException if {@code text} is {@code null} or is already longer than {@code width}
     */
    private static String padToWidth(final String text, final int width) {
        if (text == null) {
            throw new IllegalStateException("Catalog text must not be null; a value of width " + width
                    + " was expected. Check that it is not initialised from a field declared later.");
        }
        if (text.length() > width) {
            throw new IllegalStateException("Catalog text exceeds its declared field width of " + width
                    + " characters (actual length " + text.length() + "): " + text);
        }
        return text + " ".repeat(width - text.length());
    }
}
