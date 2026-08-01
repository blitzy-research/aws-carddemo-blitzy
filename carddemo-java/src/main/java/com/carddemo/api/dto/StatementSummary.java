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

import java.math.BigDecimal;

import jakarta.validation.constraints.Size;

/**
 * Immutable per-card statement transaction projection.
 *
 * <p>This is the Java carrier for the reporting-altered transaction record declared by
 * {@code app/cpy/COSTM01.CPY}, whose own line-2 header describes the layout as the CardDemo
 * transaction layout altered for use in reporting. The declaration spans lines 20 to 36 of that
 * copybook and is 350 bytes wide: a 32-byte key group at lines 21 to 23 followed by a 318-byte
 * remainder group at lines 24 to 36. One value is built per transaction while a statement is
 * assembled for a single card, which is why this shape is a per-card projection rather than a
 * whole-statement document. The thirteen components are deliberately flat: the copybook's two group
 * levels exist so the batch tier can lift a key out of the record image, and they are cited as
 * provenance rather than reproduced as nested types. Each component's copybook name, line and width
 * are on its {@code @param} tag below.</p>
 *
 * <p><strong>The record tail is not represented.</strong> The 318 bytes of the remainder group
 * include a 20-byte unnamed slack area at line 36 that carries no data name and exists only to round
 * the image out to 350 bytes. No component, accessor or serialised property corresponds to it, so a
 * caller can neither observe nor supply it.</p>
 *
 * <p><strong>Numeric pictures are carried as bounded text, never as a numeric Java type.</strong>
 * {@code TRNX-CAT-CD} at line 26 and {@code TRNX-MERCHANT-ID} at line 30 use the numeric picture
 * character, and {@code TRNX-ID} at line 23 is alphanumeric, yet all three are fixed-width character
 * values whose leading zeroes are part of the value: the bill-payment path emits a category of
 * {@code "0002"} and a merchant identifier of {@code "999999999"}, and transaction identifiers read
 * like {@code "0000000000000001"}. A numeric Java type would render the first as {@code 2} and the
 * last as {@code 1}, breaking the fixed-width contract, so no component here is a numeric Java type
 * and none is ever numerically parsed.</p>
 *
 * <p><strong>Three widths are wider than the online view map, deliberately.</strong> Because this is
 * the reporting-altered layout, the description is 100 characters at line 28, the merchant name 50
 * at line 31 and the merchant city 50 at line 32, whereas the online view map
 * {@code app/cpy-bms/COTRN01.CPY} bounds the same three concepts at 60 (its line 96), 30 (line 126)
 * and 25 (line 132) to fit a 24 by 80 terminal. These are two separate external contracts that
 * merely describe overlapping concepts, so they are not unified: this type shares no constant, base
 * type, interface or mixin with the transaction view or transaction add shapes, and narrowing any of
 * the three widths here would silently truncate statement output and break byte parity.</p>
 *
 * <p><strong>The source channel is carried raw.</strong> {@code TRNX-SOURCE} at line 27 is 10
 * characters wide and the estate writes blank-filled literals in it. It stays a 10-character text
 * value: never trimmed and never narrowed to an enumerated type, so a value outside the literals the
 * estate happens to write today passes through unchanged.</p>
 *
 * <p><strong>Money is exact, never approximated.</strong> {@code TRNX-AMT} at line 29 is a signed
 * zoned decimal with nine digits ahead of the decimal separator and two behind it, held under
 * {@code USAGE DISPLAY}, and is carried as a {@link BigDecimal} so the decimal representation stays
 * identical to the legacy one; substituting a binary approximation is prohibited by the migration
 * mapping requirement. A scale of two is the contract and this type does not enforce it: the single
 * place that applies the scale, truncating towards zero, is
 * {@code com.carddemo.util.ZonedDecimalCodec}, reached through the service tier. Keeping the policy
 * in one class is what stops two truncation behaviours coexisting, and truncation rather than
 * nearest-value rounding is faithful because no arithmetic statement anywhere in the legacy estate
 * asks for rounding (decision log entry D-02). This type performs no arithmetic, scaling, negation
 * or sign handling; it carries the value it was handed.</p>
 *
 * <p><strong>Two 26-character stamp formats, both carried verbatim.</strong>
 * {@code TRNX-ORIG-TS} at line 34 and {@code TRNX-PROC-TS} at line 35 are both 26 characters wide
 * and the estate writes them in two different shapes. The online tier writes
 * {@code YYYY-MM-DD HH:MM:SS.mmmmmm}: hyphens inside the date, a blank as the eleventh character,
 * colons inside the time and a full stop ahead of a six-digit fraction that is all zeroes throughout
 * the seeded data. The batch tier writes {@code YYYY-MM-DD-HH.MM.SS.mm0000}: a hyphen between every
 * date part and ahead of the hour, full stops inside the time, then two hundredths digits and four
 * literal zero characters. Both components carry text exactly as received - no date or time object
 * type takes part, no formatter takes part, and no reformatting, re-separating, trimming or
 * blank-filling happens here. A value of 26 blanks is how the estate represents a stamp that was
 * never set, and it round-trips byte for byte: neither collapsed to an empty value nor replaced by
 * {@code null}.</p>
 *
 * <p><strong>No formatting lives here.</strong> {@code app/cbl/CBSTM03A.CBL} is the statement
 * generator - 924 lines and 25 procedure paragraphs driven by a hand-rolled state machine - and it
 * includes this copybook at its line 51, holds up to 51 cards with 10 transactions each (lines 226
 * and 228) and writes two output records, one 80 bytes wide (line 45) and one 100 bytes wide
 * (line 47). Those two fixed widths are assembled by
 * {@code com.carddemo.util.StatementTextTemplates} and
 * {@code com.carddemo.util.StatementHtmlTemplates}. This type carries data only: no banner text, no
 * markup, no template, no line assembly, no blank-filling to a fixed width, no page break and no
 * total.</p>
 *
 * <p><strong>Validation policy.</strong> The only validation applied is a size bound at each
 * measured copybook width, which in declaration order is 16, 16, 2, 4, 10, 100, 9, 50, 50, 10, 26
 * and 26. A size bound measures length and never alters a value, so blank-filled text and a 26-blank
 * stamp both survive validation exactly as supplied. Nothing is marked mandatory, pattern-matched or
 * digit-checked, and the amount carries no numeric bounds, because the legacy record tolerates blank
 * and blank-filled values throughout and declares no such limits.</p>
 *
 * <p>Every component is either text or a {@link BigDecimal}, both immutable, so an instance is
 * immutable and safe to share between threads, with canonical record equality, hashing and text
 * rendering. The canonical constructor is left exactly as generated and normalises nothing, so what
 * a producer supplies is precisely what a consumer observes, and {@code null} is a legitimate
 * component value meaning the producer supplied nothing for that field.</p>
 *
 * @param cardNumber           card number, 16 characters, from {@code TRNX-CARD-NUM} at
 *                             {@code COSTM01.CPY} line 22, picture {@code X(16)}; the leading part
 *                             of the copybook key group
 * @param transactionId        transaction identifier, 16 characters, from {@code TRNX-ID} at line
 *                             23, picture {@code X(16)}; alphanumeric text, so
 *                             {@code "0000000000000001"} is not the same value as {@code "1"} and is
 *                             never numerically parsed
 * @param typeCode             transaction type code, 2 characters, from {@code TRNX-TYPE-CD} at line
 *                             25, picture {@code X(02)}
 * @param categoryCode         transaction category code, 4 characters, from {@code TRNX-CAT-CD} at
 *                             line 26, picture {@code 9(04)}; text so that leading zeroes such as
 *                             those in {@code "0002"} survive
 * @param source               originating channel, 10 characters, from {@code TRNX-SOURCE} at line
 *                             27, picture {@code X(10)}; raw and blank-filled, never trimmed and
 *                             never narrowed to an enumerated type
 * @param description          transaction description, 100 characters, from {@code TRNX-DESC} at
 *                             line 28, picture {@code X(100)}; wider than the online view map's 60
 *                             and deliberately not narrowed to it
 * @param amount               transaction amount, from {@code TRNX-AMT} at line 29, picture
 *                             {@code S9(09)V99}; an exact decimal whose contractual scale is two,
 *                             carried as supplied and neither scaled nor rounded by this type
 * @param merchantId           merchant identifier, 9 characters, from {@code TRNX-MERCHANT-ID} at
 *                             line 30, picture {@code 9(09)}; text so a value such as
 *                             {@code "999999999"} keeps its full width
 * @param merchantName         merchant name, 50 characters, from {@code TRNX-MERCHANT-NAME} at line
 *                             31, picture {@code X(50)}; wider than the online view map's 30 and
 *                             deliberately not narrowed to it
 * @param merchantCity         merchant city, 50 characters, from {@code TRNX-MERCHANT-CITY} at line
 *                             32, picture {@code X(50)}; wider than the online view map's 25 and
 *                             deliberately not narrowed to it
 * @param merchantZip          merchant postal code, 10 characters, from
 *                             {@code TRNX-MERCHANT-ZIP} at line 33, picture {@code X(10)}
 * @param originationTimestamp origination stamp, 26 characters, from {@code TRNX-ORIG-TS} at line
 *                             34, picture {@code X(26)}; carried verbatim in whichever of the two
 *                             26-character shapes it arrives, and 26 blanks round-trip unchanged
 * @param processingTimestamp  processing stamp, 26 characters, from {@code TRNX-PROC-TS} at line 35,
 *                             picture {@code X(26)}; carried verbatim in whichever of the two
 *                             26-character shapes it arrives, and 26 blanks round-trip unchanged
 */
public record StatementSummary(
        @Size(max = 16) String cardNumber,
        @Size(max = 16) String transactionId,
        @Size(max = 2) String typeCode,
        @Size(max = 4) String categoryCode,
        @Size(max = 10) String source,
        @Size(max = 100) String description,
        BigDecimal amount,
        @Size(max = 9) String merchantId,
        @Size(max = 50) String merchantName,
        @Size(max = 50) String merchantCity,
        @Size(max = 10) String merchantZip,
        @Size(max = 26) String originationTimestamp,
        @Size(max = 26) String processingTimestamp) {

    // The canonical constructor generated for this record is intentionally left in place: no compact
    // constructor, no defaulting and no normalisation. Every value crosses this boundary byte for
    // byte, which is what lets a 26-blank stamp, a blank-filled channel value and a leading-zero
    // code reach the statement writers unchanged.

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of each cardholder-bearing component.
     *
     * <p>A constant rather than any transformation of the value. A partial mask of the card number was
     * rejected deliberately: a truncated primary account number is still cardholder data, and the
     * customary practice of showing the last four digits is a presentation decision for a statement,
     * not a licence to put them in a log.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Returns a diagnostic representation that identifies the statement line and discloses nothing about
     * the cardholder, the amount or the merchant.
     *
     * <p><strong>Why the implicit record rendering could not stand.</strong> A record's generated
     * {@code toString()} prints every component, and the first component of this one is a primary
     * account number. This type is constructed once per statement line, so a default rendering placed a
     * primary account number one interpolation away from every log line, assertion message and
     * diagnostic dump on the statement path - the highest-volume rendering surface in the batch tier.
     *
     * <p><strong>What is retained.</strong> The transaction identifier, the type and category codes, the
     * transaction source and the two 26-character timestamps. That set is exactly what a batch
     * diagnostic needs: it names <em>which</em> line was processed, how it was classified, where it
     * entered the system and when it was originated and processed, and it is enough to locate the same
     * line again in the source data. None of it is cardholder data or personal data.
     *
     * <p><strong>What is withheld, and why the list is wider than the card number alone.</strong> The
     * card number is withheld because it is a primary account number. The amount, the description and
     * the four merchant components are withheld too, because together with the retained transaction
     * identifier they reconstruct what a specific cardholder spent and where - which is the substance of
     * a statement, and is not something a diagnostic channel needs in order to be useful. Fail-closed is
     * the correct default here: the retained set was chosen because it is sufficient, not because the
     * rest happened to look harmless.
     *
     * <p>{@code equals} and {@code hashCode} remain as the record contract generates them: they compare
     * every component by value and emit nothing, so byte-for-byte fixture comparison is unaffected.
     *
     * @return the statement-line identification, with every cardholder-bearing component replaced by a
     *     fixed placeholder
     */
    @Override
    public String toString() {
        return "StatementSummary["
                + "cardNumber=" + REDACTION_PLACEHOLDER
                + ", transactionId=" + transactionId
                + ", typeCode=" + typeCode
                + ", categoryCode=" + categoryCode
                + ", source=" + source
                + ", description=" + REDACTION_PLACEHOLDER
                + ", amount=" + REDACTION_PLACEHOLDER
                + ", merchantId=" + REDACTION_PLACEHOLDER
                + ", merchantName=" + REDACTION_PLACEHOLDER
                + ", merchantCity=" + REDACTION_PLACEHOLDER
                + ", merchantZip=" + REDACTION_PLACEHOLDER
                + ", originationTimestamp=" + originationTimestamp
                + ", processingTimestamp=" + processingTimestamp
                + "]";
    }
}
