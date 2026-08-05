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

/**
 * Immutable card-detail request &mdash; the transmitted half of the single-card display screen that
 * legacy transaction {@code CCDL} presented from {@code app/cbl/COCRDSLC.cbl}.
 *
 * <p><strong>Provenance.</strong> Checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec},
 * upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The field contract is
 * the input group of the generated symbolic map {@code app/cpy-bms/COCRDSL.CPY}, declared at line 17,
 * with the 24&nbsp;&times;&nbsp;80 layout authority at {@code app/bms/COCRDSL.bms}. No COBOL source is
 * copied into this module; every citation is a reference into the read-only legacy tree.
 *
 * <p><strong>Why this screen submits a body where it once took a request line.</strong> The turn changes
 * nothing and would be a natural fetch, but two of the values it carries must not travel in a request
 * target. The card filter <em>is</em> a primary account number, and the echoed navigation record carries
 * a customer identifier, three name parts, an account identifier and a second copy of the card number.
 * A request line is retained by proxies, gateway access logs, browser history and referrer headers, none
 * of which has any of the protections a response body has, and the module's own diagnostic rule keeps
 * both values out of every log it writes - a rule that a URL query string quietly defeats. The turn is
 * therefore submitted, and it stays idempotent in effect: nothing is created, and re-submitting the same
 * turn composes the same screen, which is the property the pseudo-conversational original had.
 *
 * <p><strong>Both search keys are optional, and that is contract rather than leniency.</strong> The
 * program answers an absent account, an absent card and no input at all with three different messages -
 * so making either mandatory would make two of those three outcomes unreachable and would silently drop
 * a behaviour the legacy screen has. An entirely absent request is likewise valid and reads as the turn
 * that asks for input.
 *
 * <p><strong>The widths are the screen field widths, and they reject rather than truncate.</strong> The
 * account filter is eleven characters at {@code app/cpy-bms/COCRDSL.CPY} line 24 and the card filter is
 * sixteen at line 30, corroborated by the mapset. A value wider than its field could not have been typed
 * into that field on the terminal at all, so it is not a screen the legacy could have produced and it is
 * refused at the boundary as a malformed request. That is the same rule the card-list contract states
 * for its own two filters, and stating it here is what makes the published interface description
 * truthful: the operation advertises a rejection for an over-wide value, and now one occurs.
 *
 * <p><strong>Nothing here is trimmed, padded, folded or defaulted.</strong> The browse compares a filter
 * to a retrieved record character for character, and the screen distinguishes blank from
 * supplied-but-unusable from supplied-and-usable, so a boundary that normalised a value would erase a
 * distinction the service depends on. The service's own receive paragraph additionally bounds each value
 * to its field width, which stays in place as defence in depth for any caller that reaches it by another
 * route; it cannot fire for a request that passed the constraints above.
 *
 * <p><strong>No digit-format rule appears here.</strong> A non-numeric filter is something the program
 * reports back to the operator as a field-level screen message, not something it refuses to receive, so
 * the format check belongs to the message-bearing stage of the service cascade and not to this contract.
 *
 * <p>An immutable record. It holds no mutable state, publishes no collection and is safe to share.
 *
 * @param accountIdFilter the account identifier the operator typed - map field {@code ACCTSID}, width 11
 *        at {@code app/cpy-bms/COCRDSL.CPY} line 24. {@code null}, empty or blank all mean the field was
 *        left alone, which is one of the three input outcomes the screen distinguishes.
 * @param cardNumberFilter the card number the operator typed - map field {@code CARDSID}, width 16 at
 *        {@code app/cpy-bms/COCRDSL.CPY} line 30. Same absence semantics as the account filter. This is
 *        a primary account number and is excluded from {@link #toString()}.
 * @param keyAction the attention key the operator pressed. {@code null} when the key mapped to nothing,
 *        mirroring a key translation that has 28 ordered clauses and no catch-all and therefore leaves
 *        the previously held value untouched; no substitute is invented here.
 * @param navigationContext the client-echoed navigation state for this turn - not a server session. It
 *        carries what the previous screen selected, which the service reads separately from the two
 *        filters above, so a carried card can never be confused with a typed one. Validated
 *        transitively, so the widths it declares are actually evaluated. {@code null} on a first arrival,
 *        and an absent state is not a violation.
 *
 * @since 1.0.0
 */
public record CardDetailRequest(
        @Size(max = CardDetailRequest.ACCOUNT_ID_FILTER_LENGTH) String accountIdFilter,
        @Size(max = CardDetailRequest.CARD_NUMBER_FILTER_LENGTH) String cardNumberFilter,
        KeyAction keyAction,
        @Valid NavigationContext navigationContext) {

    /**
     * Width in characters of the account filter: 11.
     *
     * <p>A legacy field width rather than a restriction invented here. Map field {@code ACCTSID} of
     * {@code app/cpy-bms/COCRDSL.CPY} is 11 characters wide at line 24 and the mapset declares the same
     * width. Declared locally rather than shared with the card-list screen's identically sized filter, so
     * that a change to one screen cannot silently move the other.
     */
    public static final int ACCOUNT_ID_FILTER_LENGTH = 11;

    /**
     * Width in characters of the card filter: 16.
     *
     * <p>A legacy field width, cited for the same reason: map field {@code CARDSID} of
     * {@code app/cpy-bms/COCRDSL.CPY} is 16 characters wide at line 30.
     */
    public static final int CARD_NUMBER_FILTER_LENGTH = 16;

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of each regulated component.
     *
     * <p>A constant rather than any transformation of the value, so nothing about a withheld component -
     * not its length, not a prefix or suffix, not a digest, not a partial mask - can be recovered from a
     * stringified instance. A partial mask was rejected deliberately: a truncated primary account number
     * is still cardholder data.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * The turn that carries nothing at all, which is how a client asks for the empty search screen.
     *
     * <p>Returned in place of an absent body so that the route never has to null-check the request it was
     * handed, and so that the three-way input distinction the screen makes is expressed by absent
     * components rather than by an absent object.
     *
     * @return a request with every component absent, never {@code null}
     */
    public static CardDetailRequest empty() {
        return new CardDetailRequest(null, null, null, null);
    }

    /**
     * Returns a diagnostic representation that mirrors the request layout and discloses no regulated
     * value.
     *
     * <p><strong>Why the implicit record rendering could not stand.</strong> A record's generated
     * rendering prints every component. Two of these are regulated: the card filter is a primary account
     * number, and the account filter is the key that joins straight to a cardholder. Any structured
     * logger, framework diagnostic, failed assertion, exception message or string interpolation touching
     * an instance would otherwise have emitted both.
     *
     * <p><strong>Why the remainder is retained.</strong> The attention key is screen-interaction state
     * that identifies nobody, and the navigation context is printed by delegation because it withholds
     * its own identifying values. Withholding them as well would remove the only useful content without
     * protecting anything.
     *
     * <p><strong>Withholding is confined to this method.</strong> Every accessor returns its component
     * exactly as supplied; no value is masked, truncated or transformed anywhere in this type, because
     * the service compares a filter to a retrieved record character for character.
     *
     * <p>{@code equals} and {@code hashCode} are deliberately left as the record contract generates them.
     * They compare every component by value, which is what a request contract requires, and neither emits
     * anything: an in-memory comparison is not a disclosure surface.
     *
     * @return the request layout with each regulated component replaced by a fixed placeholder
     */
    @Override
    public String toString() {
        return "CardDetailRequest["
                + "accountIdFilter=" + REDACTION_PLACEHOLDER
                + ", cardNumberFilter=" + REDACTION_PLACEHOLDER
                + ", keyAction=" + keyAction
                + ", navigationContext=" + navigationContext
                + "]";
    }
}
