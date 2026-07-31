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
package com.carddemo.domain.enums;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Origination channel of a CardDemo transaction: the migrated vocabulary of the
 * fixed-width {@code TRAN-SOURCE} field.
 *
 * <p><strong>Record position.</strong> {@code TRAN-SOURCE} is declared {@code PIC X(10)}
 * on line 8 of copybook {@code CVTRA05Y} and occupies bytes 23 through 32 of the fixed
 * 350-byte transaction record, immediately after {@code TRAN-ID} ({@code X(16)}, bytes 1
 * through 16), {@code TRAN-TYPE-CD} ({@code X(02)}, bytes 17 through 18) and
 * {@code TRAN-CAT-CD} ({@code 9(04)}, bytes 19 through 22). The daily-transaction
 * copybook {@code CVTRA06Y} declares the same field as {@code DALYTRAN-SOURCE}, also
 * {@code PIC X(10)}, at the same offsets of a byte-for-byte identical 350-byte layout.
 * One enum therefore serves both record types; there is no separate daily-transaction
 * vocabulary, and none should be introduced.</p>
 *
 * <p><strong>Why the padding is contractual, not cosmetic.</strong> The field is a fixed
 * ten-byte slot, so moving a shorter literal into it leaves the remainder space-filled.
 * Each constant below consequently carries the complete ten-character stored image,
 * trailing spaces included. Output derived from these records is compared byte for byte
 * against golden fixtures, and the enclosing record is fixed at 350 bytes: a value
 * shortened by even one space would shift every following byte and corrupt the layout.
 * For that reason nothing in this type trims, strips, upper-folds or lower-folds a value,
 * and the lookup keys on the exact padded form, which is precisely how these values
 * arrive when a fixed-width record image is sliced. A caller that needs a human-readable
 * form is responsible for producing it; the COBOL string primitives the estate itself
 * used belong to the utility layer, not here.</p>
 *
 * <p><strong>Observed vocabulary.</strong> Three images exist in the entire estate, and
 * every one of them was measured rather than assumed:</p>
 * <ul>
 *   <li>{@code POS TERM} plus two trailing spaces, in 250 of the 300 seeded
 *       daily-transaction records and written by the online bill-payment program;</li>
 *   <li>{@code OPERATOR} plus two trailing spaces, in the remaining 50 of those 300
 *       records;</li>
 *   <li>{@code System} plus four trailing spaces, written by the interest-calculation
 *       batch program when it synthesises an accrued-interest transaction.</li>
 * </ul>
 *
 * <p><strong>Not a JPA attribute type.</strong> The transaction and daily-transaction
 * entities keep their source column as a raw {@code VARCHAR(10)} string, because only a
 * raw string preserves the padding, and neither entity refers to this enum. Translation
 * between the stored image and this vocabulary belongs to the service layer. This type
 * carries no persistence mapping at all, for three independently sufficient reasons: a
 * string-valued enum mapping would persist the Java constant name rather than the padded
 * image, silently destroying the very padding this type exists to protect; an ordinal
 * mapping would require an integer column and fail schema validation; and an attribute
 * converter would place translation logic inside the domain layer.</p>
 *
 * <p><strong>Provenance.</strong> Behaviour migrated from the COBOL CardDemo estate,
 * which is cited by member, field, length and offset and never transcribed:</p>
 * <ul>
 *   <li>source checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}</li>
 *   <li>upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68}, dated 2022-07-19</li>
 * </ul>
 */
public enum TransactionSourceType {

    /**
     * Point-of-sale terminal. The eight characters {@code POS TERM}, which contain a
     * single interior space, followed by two trailing spaces.
     *
     * <p>Evidence: 250 of the 300 records in the seeded daily-transaction fixture carry
     * this image in bytes 23 through 32, and the online bill-payment program
     * {@code COBIL00C} moves the same literal into {@code TRAN-SOURCE} at line 222 when
     * it posts a payment.</p>
     */
    POS_TERM("POS TERM  "),

    /**
     * Operator-originated transaction. The eight characters {@code OPERATOR} followed by
     * two trailing spaces.
     *
     * <p>Evidence: the remaining 50 of the 300 records in the seeded daily-transaction
     * fixture carry this image in bytes 23 through 32. With the 250 point-of-sale records
     * it accounts for that fixture exactly.</p>
     */
    OPERATOR("OPERATOR  "),

    /**
     * System-generated transaction. The six characters {@code System}, a capital
     * {@code S} followed by a lower-case remainder, then four trailing spaces.
     *
     * <p>Evidence: the interest-calculation batch program {@code CBACT04C} moves the
     * literal into {@code TRAN-SOURCE} at line 484 while synthesising an accrued-interest
     * transaction, and the {@code PIC X(10)} receiving field pads it to ten characters.
     * Note that the padding here is four spaces rather than two, because the literal is
     * shorter than the other two.</p>
     *
     * <p>The mixed case is deliberate and is preserved exactly. This constant's Java name
     * is upper case purely because that is the language convention for enum constants, and
     * because a constant literally named {@code System} would shadow
     * {@code java.lang.System} inside this enum body. The stored image is neither
     * {@code SYSTEM} nor {@code system}, and nothing in this type folds its case.</p>
     */
    SYSTEM("System    ");

    /**
     * Width of {@code TRAN-SOURCE} and {@code DALYTRAN-SOURCE} in characters, taken from
     * their {@code PIC X(10)} declarations.
     *
     * <p>Every constant's {@link #getValue()} is exactly this long, trailing spaces
     * included, so code that assembles or slices bytes 23 through 32 of a record image can
     * rely on it, and a test can assert the invariant directly.</p>
     */
    public static final int VALUE_LENGTH = 10;

    /**
     * Index from the exact ten-character stored image to the constant that carries it.
     *
     * <p>Built once from {@code values()} and made unmodifiable, so the vocabulary cannot
     * drift at run time. Deriving it from {@code values()} rather than from a hand-written
     * literal list means a constant can never be added without also being indexed.</p>
     */
    private static final Map<String, TransactionSourceType> BY_VALUE = buildIndex();

    /** The exact ten-character field image, trailing spaces included. */
    private final String value;

    TransactionSourceType(final String value) {
        this.value = value;
    }

    /**
     * Builds the unmodifiable image-to-constant index.
     *
     * <p>Runs once, as this enum initialises, after every constant has been constructed.
     * A {@link LinkedHashMap} is populated in declaration order and then copied into an
     * unmodifiable map, so the result exposes no mutator and is not a lazily populated
     * cache.</p>
     *
     * @return an unmodifiable map from each constant's ten-character image to the constant
     */
    private static Map<String, TransactionSourceType> buildIndex() {
        final Map<String, TransactionSourceType> index = new LinkedHashMap<>();
        for (final TransactionSourceType sourceType : values()) {
            index.put(sourceType.value, sourceType);
        }
        return Map.copyOf(index);
    }

    /**
     * Returns the exact ten-character field image for this channel, trailing spaces
     * included, ready to be written straight into bytes 23 through 32 of a transaction or
     * daily-transaction record.
     *
     * <p>The returned string is never trimmed and never case-folded.</p>
     *
     * @return the ten-character stored image, never {@code null}
     */
    public String getValue() {
        return value;
    }

    /**
     * Resolves the channel that a raw field image denotes, without ever throwing.
     *
     * <p>Matching is exact. The argument must be the complete ten-character padded image,
     * which is what slicing bytes 23 through 32 out of a fixed-width record produces. No
     * trimming, stripping or case folding is applied, so a trimmed or re-cased argument is
     * simply not found rather than being coerced into a match.</p>
     *
     * <p>An unrecognised image is a legitimate outcome rather than an error, and is
     * reported as {@link Optional#empty()}. The legacy system never validated this field
     * against a list: the batch programs copy it straight out of the record image, the
     * online add-transaction program {@code COTRN02C} moves the operator-typed screen
     * field {@code TRNSRCI}, itself {@code PIC X(10)}, into {@code TRAN-SOURCE}
     * at line 454 having checked only that it is not blank, and the migrated column is
     * a plain {@code VARCHAR(10)} with no check constraint. Images outside the three
     * the estate writes therefore genuinely occur and must pass through unharmed, which
     * is also why no synthetic fallback constant exists for them to be coerced into.</p>
     *
     * <p>A {@code null} argument is tolerated for the same reason and for a concrete one
     * as well: the unmodifiable index rejects a null key with a
     * {@link NullPointerException}, so the guard below is what makes this method total.</p>
     *
     * @param value the raw field image to resolve; may be {@code null}, may be any length,
     *              and may be an image the estate never writes
     * @return the matching channel, or {@link Optional#empty()} when the image is
     *         {@code null} or is not one of the three the estate writes
     */
    public static Optional<TransactionSourceType> fromValue(final String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_VALUE.get(value));
    }

    /**
     * Reports whether the estate itself generated the transaction rather than an external
     * actor.
     *
     * <p>The distinction is the legacy one: only the interest-calculation batch program
     * synthesises transactions of its own accord, whereas point-of-sale and
     * operator-originated transactions enter from outside the application. Implemented as
     * a switch that is exhaustive over every constant, so it needs no {@code default} arm
     * and its arrow form makes fall-through structurally impossible.</p>
     *
     * @return {@code true} for the system-generated channel, {@code false} for the two
     *         externally originated channels
     */
    public boolean isSystemGenerated() {
        return switch (this) {
            case POS_TERM, OPERATOR -> false;
            case SYSTEM -> true;
        };
    }
}
