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

/**
 * Service-tier conversation state: the pseudo-conversational carry-over that the service layer is
 * entitled to read, expressed as a type the service layer owns.
 *
 * <p>The legacy antecedent is {@code CARDDEMO-COMMAREA} in {@code app/cpy/COCOM01Y.cpy}, textually
 * included by all seventeen online programs. That copybook declares sixteen fields, and the REST
 * contract models all sixteen because the communication area is an external contract that must be
 * reproduced in full. <strong>This type deliberately models only five of them.</strong>
 *
 * <p><strong>Why a second type exists at all.</strong> The wire form of this state lives in the
 * {@code api.dto} package because it is a REST wire type, and the specification's layering rule
 * forbids a service from depending upward on the API package. A service that took the wire record as a
 * parameter, or returned it, inverted that direction. The two are therefore separate: the API layer
 * owns the sixteen-field wire record and an adapter in that layer converts to and from this one, so the
 * conversion happens exactly once, at the boundary, and every service signature names only types the
 * service layer owns.
 *
 * <p><strong>Why only five fields, and why the omission is the point.</strong> The eleven fields not
 * carried here are the identity and cardholder members - the user identifier and type, the customer
 * identifier and the three name parts, the account identifier and status, the primary account number,
 * and the two last-map fields. Every one of them arrives having been echoed by the client, so none of
 * them is evidence of anything: a client may return whatever it likes. Reading them in a service would
 * make a decision out of a claim.
 *
 * <ul>
 * <li><strong>Identity comes from the authenticated principal.</strong> Where a service needs to know
 *     who is acting, the caller passes the authenticated identity explicitly, which is why
 *     {@code MenuService} takes a signed-on user type as its own parameter rather than reading one out
 *     of the carried state.</li>
 * <li><strong>Cardholder values come from the record.</strong> Where a service needs a customer, an
 *     account or a card, it loads it through a repository, so an echoed value can neither select a
 *     different record nor stand in for one.</li>
 * <li><strong>The echoed values still survive the round trip</strong>, because the adapter merges the
 *     five fields returned here back onto the inbound wire record and reconciles the identity members
 *     against the authenticated principal before the response carries them. Nothing is lost from the
 *     client's view; it simply stops being something a service can act on.</li>
 * </ul>
 *
 * <p><strong>What the five carried fields are for.</strong> The four routing fields are the legacy
 * nomination pair - where the conversation came from and where it is going - which the navigation
 * authority resolves into a route, reproducing the {@code XCTL} dispatch graph. The entry mode is the
 * program-context flag, and it is load-bearing rather than cosmetic: the legacy validation macros fired
 * only on re-entry, so it is what tells a service whether field-level error decoration applies at all.
 *
 * <p>Immutable and deeply so: every component is a {@code String} or an enumeration constant, every
 * derivation returns a new instance, and there is no setter and no lazily populated field, so an
 * instance is safe for unsynchronised concurrent use. Carrying no identifier and no cardholder value,
 * it has nothing to redact, so the generated rendering is left as the record contract writes it.
 *
 * @param fromTransactionId the four-character transaction the conversation came from, or {@code null}
 * @param fromProgram the eight-character program the conversation came from, or {@code null}
 * @param toTransactionId the four-character transaction the conversation nominates next, or
 *        {@code null}
 * @param toProgram the eight-character program the conversation nominates next, or {@code null}
 * @param entryMode whether this turn is a first entry or a re-entry, never {@code null}
 * @since 1.0.0
 */
public record ConversationState(
        String fromTransactionId,
        String fromProgram,
        String toTransactionId,
        String toProgram,
        EntryMode entryMode) {

    /** The empty state, which is what an absent carry-over resolves to. */
    private static final ConversationState EMPTY =
            new ConversationState(null, null, null, null, EntryMode.FIRST_ENTRY);

    /**
     * Normalizes the entry mode so that no instance can carry an absent one.
     *
     * <p>A {@code null} entry mode becomes {@link EntryMode#FIRST_ENTRY}, which is the legacy default:
     * the program-context flag is unset on a first entry and set on every subsequent turn, so an absent
     * value and a first entry are the same state. Every other component is stored exactly as supplied,
     * including {@code null} and any blank padding, because the routing fields are fixed-width and
     * blank-significant in the estate they come from.
     */
    public ConversationState {
        entryMode = (entryMode == null) ? EntryMode.FIRST_ENTRY : entryMode;
    }

    /**
     * Returns the empty state, carrying no nomination and standing at first entry.
     *
     * @return the shared empty instance
     */
    public static ConversationState empty() {
        return EMPTY;
    }

    /**
     * Reports whether this state is the empty one, which the legacy treats as no carry-over at all.
     *
     * @return {@code true} when no field carries a value and the turn is a first entry
     */
    public boolean absent() {
        return EMPTY.equals(this);
    }

    /**
     * Reports whether this turn is the first entry into the screen.
     *
     * @return {@code true} when the program-context flag is unset
     */
    public boolean firstEntry() {
        return entryMode == EntryMode.FIRST_ENTRY;
    }

    /**
     * Reports whether this turn is a re-entry, which is the condition field decoration depends on.
     *
     * @return {@code true} when the program-context flag is set
     */
    public boolean reEntry() {
        return entryMode == EntryMode.RE_ENTRY;
    }

    /**
     * Returns a copy standing at re-entry, leaving every routing field unchanged.
     *
     * @return a copy whose entry mode is {@link EntryMode#RE_ENTRY}
     */
    public ConversationState withReEntry() {
        return new ConversationState(fromTransactionId, fromProgram, toTransactionId, toProgram,
                EntryMode.RE_ENTRY);
    }

    /**
     * Returns a copy standing at first entry, leaving every routing field unchanged.
     *
     * @return a copy whose entry mode is {@link EntryMode#FIRST_ENTRY}
     */
    public ConversationState withFirstEntry() {
        return new ConversationState(fromTransactionId, fromProgram, toTransactionId, toProgram,
                EntryMode.FIRST_ENTRY);
    }

    /**
     * Returns a copy naming a different originating program, which is how a screen records where a
     * conversation is to be sent back to.
     *
     * @param programName the originating program name, which may be {@code null}
     * @return a copy carrying that originating program
     */
    public ConversationState withOriginatingProgram(final String programName) {
        return new ConversationState(fromTransactionId, programName, toTransactionId, toProgram,
                entryMode);
    }

    /**
     * Returns a copy naming a different nominated program, which is how a screen records where the
     * conversation is going next.
     *
     * @param programName the nominated program name, which may be {@code null}
     * @return a copy carrying that nominated program
     */
    public ConversationState withNominatedProgram(final String programName) {
        return new ConversationState(fromTransactionId, fromProgram, toTransactionId, programName,
                entryMode);
    }

    /**
     * Returns a copy naming this screen as the origin of the next turn, standing at first entry.
     *
     * <p>This is the hand-off a screen builds when it dispatches: the originating pair becomes this
     * screen's own transaction and program so the destination knows where to return to, and the entry
     * mode resets to first entry because the destination has not yet presented itself.
     *
     * @param transactionId the originating transaction identifier
     * @param programName the originating program name
     * @return a copy carrying that origin and standing at first entry
     */
    public ConversationState withOrigin(final String transactionId, final String programName) {
        return new ConversationState(transactionId, programName, toTransactionId, toProgram,
                EntryMode.FIRST_ENTRY);
    }

    /**
     * Whether a turn is the first entry into a screen or a re-entry into one already presented.
     *
     * <p>The legacy antecedent is the program-context flag of {@code CARDDEMO-COMMAREA}, modelled as
     * two named states rather than a boolean so that neither reading has to be inferred from the
     * absence of the other.
     */
    public enum EntryMode {

        /** The screen has not yet been presented on this conversation. */
        FIRST_ENTRY,

        /** The screen has been presented and is being submitted back. */
        RE_ENTRY;

        /**
         * Returns the mode as its legacy two-state name, for diagnostics only.
         *
         * @return the constant name, never {@code null}
         */
        public String describe() {
            return name();
        }
    }
}
