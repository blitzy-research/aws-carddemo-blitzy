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
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Immutable inbound request shared by the four administrative user transactions {@code CU00},
 * {@code CU01}, {@code CU02} and {@code CU03}, whose screens are four views of the same eighty-byte
 * user-security record. Validation groups say which components each operation may carry, so one type
 * serves all four without letting a list request smuggle a credential or an add request smuggle a
 * page cursor.
 *
 * <p>There are deliberately <strong>two</strong> eight-character identifiers: the one the list screen
 * searches from, and the one the add, update and delete screens maintain. Collapsing them would let a
 * search value be written as an identity.
 *
 * <p>Every component carries a width bound and nothing else - no pattern, no presence constraint, no
 * enumerated value. The legacy programs test only that an item is non-blank and store whatever
 * arrived, so a stricter constraint here would reject input the legacy accepted; field-level rejection
 * with the legacy message text belongs to the service. The user type is therefore a raw one-character
 * code ({@code A} or {@code U}) rather than an enum, and the attention key has no default and no case
 * folding.
 *
 * <p>Paging values are text and no paging arithmetic happens here: the cursor identifiers are the
 * first and last identifier on the page, exactly as the legacy browse carried them. Row selections
 * are positional and uninterpreted, capped at the ten rows the list screen declares, and which marker
 * means what is the service's to decide.
 *
 * <p>The credential is accepted inbound only and never emitted: it is write-only in the serialised
 * contract because the stored value is a one-way digest, and {@link #toString()} redacts the whole
 * record so no component can reach a log through a rendered request.
 */
public record UserRequest(

        @Size(max = UserRequest.USER_ID_LENGTH)
        String userId,

        @Size(max = UserRequest.USER_ID_LENGTH)
        String searchUserId,

        @Size(max = UserRequest.NAME_PART_LENGTH)
        String firstName,

        @Size(max = UserRequest.NAME_PART_LENGTH)
        String lastName,

        @Size(max = UserRequest.PASSWORD_LENGTH)
        String password,

        @Size(max = UserRequest.USER_TYPE_LENGTH)
        String userType,

        /* The per-row selection items of the list map, width 1 each, COUSR00.CPY lines 72 to 342.
         * Ordered and positional: element n belongs to screen row n, so an empty position is meaning
         * and must survive. The only bound declared here is the container-element width, which is the
         * one-character item width. No bound is placed on the sequence length: how many rows a screen
         * has is the paging contract's measurement rather than this contract's, and the list map being
         * the only map that declares these items is an applicability rule the service applies. The
         * canonical constructor normalises a null collection to an empty immutable one, so an empty
         * collection is the single representation of "carries nothing". */
                List<@Size(max = UserRequest.ROW_SELECTION_LENGTH) String> rowSelections,

        /* Displayed page number, width 8, COUSR00.CPY line 60; text, not a number, so that the
         * leading zeros of the eight-digit companion at COUSR00C.cbl line 70 survive. Server-owned
         * display state: PAGENUMI appears at exactly two sites, COUSR00C.cbl lines 327 and 376, and
         * at both it is the target of a MOVE. The number itself is computed entirely by the program
         * from its own retained counter - initialised at line 227, incremented at 309 to 310 and
         * decremented at 366 to 369 - so a submitted value could never have influenced a page. That
         * it cannot influence a page is a property of the service, which recomputes the number from
         * its own counter and never reads this component, rather than of a binding rule declared
         * here. */
        @Size(max = UserRequest.DISPLAYED_PAGE_NUMBER_LENGTH)
        String displayedPageNumber,

        @Size(max = UserRequest.USER_ID_LENGTH)
        String firstUserIdOnPage,

        @Size(max = UserRequest.USER_ID_LENGTH)
        String lastUserIdOnPage,

        KeyAction keyAction,

        @Valid NavigationContext navigationContext) {
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    public static final int USER_ID_LENGTH = 8;

    public static final int NAME_PART_LENGTH = 20;

    public static final int PASSWORD_LENGTH = 8;

    public static final int USER_TYPE_LENGTH = 1;

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
     * <p><strong>It refuses nothing on length.</strong> The collection is accepted untouched at
     * whatever length it arrived, and nothing is padded out to a row count. How many rows the list
     * screen offers is a measured property of the paging contract rather than of this request, so no
     * cardinality bound and no row-count constant is declared here: publishing one would put the same
     * measurement in two places and would let a client read a screen dimension off a request type.
     * The service, which already owns each operation's ordered emptiness cascade and the
     * first-non-blank-wins selection decision, is the single place that knows how many positions the
     * screen it is serving actually has.
     */
    public UserRequest {
        rowSelections = (rowSelections == null) ? List.of() : List.copyOf(rowSelections);
    }

    /**
     * Returns a diagnostic representation that identifies the request and discloses no personal data.
     *
     * <p><strong>Why redacting the credential alone was not enough.</strong> An earlier form of this
     * method withheld the password and printed everything else verbatim, on the reasoning that only a
     * credential is a secret. That reasoning does not survive contact with what the other components
     * actually are: two account-holder names, three user identifiers and a user type, all belonging to
     * one identifiable person, on a single line. A record whose remaining components are a person's
     * given name, family name, sign-on identifier and privilege level is a personal-data record
     * whether or not a password sits beside it, and one instance exists per administrative request, so
     * a verbatim rendering placed that record one interpolation away from every log line, assertion
     * message and diagnostic dump on the user-administration path.
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
     * structurally by the absence of any credential component on every response type in this package.
     * Neither control substitutes for the other: this rendering would stay intact even if a response
     * grew a credential member, and the response contract would stay closed even if this override were
     * removed and the credential travelled into a log line. The rendering is deliberately the only
     * control this type applies, because a binding directive here would also close the inbound
     * direction the add and update operations depend on.
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
                + ", keyAction=" + keyAction
                + ", navigationContext=" + REDACTION_PLACEHOLDER
                + "]";
    }
}
