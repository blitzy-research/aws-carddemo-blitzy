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

package com.carddemo.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The shape rules every natural key and every stored amount of this module must satisfy before reaching
 * the database.
 *
 * <h2>Why a maximum length is not enough</h2>
 *
 * <p>Every persisted key in this estate is a field of a fixed-width record image: an eleven-character
 * account identifier, a sixteen-character card number, a sixteen-character transaction identifier, a
 * nine-character customer identifier, an eight-character sign-on identifier. A value sliced out of a
 * valid record image is therefore <em>always</em> exactly that width - never shorter, never longer.
 * Declaring only an upper bound, as a bounded variable-length column and a maximum-length constraint
 * both do, admits a shorter value that no record image could have produced, and a shorter value is not
 * a harmless near-miss:
 *
 * <ul>
 *   <li><strong>It splits one identity in two.</strong> The relational key {@code "1"} and the record
 *       key {@code "00000000001"} are different strings and therefore different rows, while the
 *       fixed-width image they are written back into is the same eleven bytes. Two rows then claim one
 *       record.</li>
 *   <li><strong>It breaks orderings that are only valid over equal widths.</strong> The
 *       transaction-identifier maximum that mints the next identifier is a character maximum, and it
 *       coincides with the numeric maximum only while every stored value is exactly sixteen digits,
 *       zero-padded: {@code "9"} sorts above {@code "0000000000000042"}, so a single short value would
 *       silently freeze identifier allocation. The same holds for the card-number ordering the legacy
 *       report sort depends on, which is a zoned-decimal ordering reproduced as a character
 *       ordering.</li>
 *   <li><strong>It survives compilation and every test that does not look for it.</strong> Which is
 *       why the rule is enforced here rather than described in prose.</li>
 * </ul>
 *
 * <h2>What this class refuses, and what it deliberately does not do</h2>
 *
 * <p>For a <strong>key</strong> it refuses and never repairs. Nothing here trims, pads, folds case or
 * substitutes a default, because every one of those would turn a caller's mistake into a silently
 * different stored value - padding {@code "1"} to eleven characters would invent an identity, and
 * trimming a legitimately space-bearing value would destroy one. A caller holding a value of the wrong
 * width has a defect upstream, at the point the value was parsed or built, and that is where it is
 * fixed.
 *
 * <p>For an <strong>amount</strong> it normalises, and the asymmetry is the legacy's rather than a
 * preference: a COBOL store of a longer intermediate into a two-decimal field truncates it silently, so
 * discarding the surplus digit reproduces the original and refusing it would fail a computation the
 * original completed. What it will not do is round, and {@link #MONETARY_ROUNDING} records why.
 *
 * <p>The digit check is applied only where the legacy picture clause is numeric. Several keys are
 * declared alphanumeric even though every seeded value happens to be digits - the card number, the
 * transaction identifier and the sign-on identifier among them - and one, the disclosure group
 * identifier, is legitimately ten spaces in all fifty seeded account rows. Applying a digit class to
 * an alphanumeric field would reject data the legacy system stored, so the two checks are separate
 * methods and each field is bound to the one its own picture clause justifies.
 *
 * <h2>Enforced before the write, not in the constructor</h2>
 *
 * <p>These methods are called from the entities' own {@code @PrePersist} and {@code @PreUpdate}
 * callbacks. That placement is deliberate and was chosen over guarding the constructors and setters:
 * the persistence provider hydrates a row by instantiating the entity and assigning its fields
 * directly, so a constructor guard is bypassed on every read, while a callback sits on the one path
 * every insert and every update must take. It also means an entity built for an assertion, a fixture
 * or an intermediate calculation is never refused - only one about to become a row is.
 *
 * <p>The database carries the same rules a second time, as check constraints in
 * {@code V1__create_schema.sql}. That is not redundancy: a bulk load, a migration script or a future
 * writer that never constructs an entity is refused by the constraint, and the two together mean the
 * invariant holds however a row arrives.
 *
 * <h2>Layer position</h2>
 *
 * <p>Declared in the domain package because the rules belong to the record layouts themselves rather
 * than to any one service, and because the domain package may not depend on the utility package - the
 * fixed-width mappers there already depend on these entities, so the edge only runs one way. The
 * failure mode is {@link IllegalArgumentException}, which is what this package already raises for a
 * value no legitimate record image could carry.
 *
 * @since 1.0.0
 */
public final class StoredValueRules {

    /**
     * The scale of every monetary and rate field in the estate: two.
     *
     * <p>Every such field is declared with two digits after the implied decimal point - the five account
     * money fields, both transaction amounts, the category balance and the interest rate alike - so one
     * scale governs all of them.
     *
     * <p><strong>Restated here rather than imported from the codec, on purpose.</strong> The fixed-width
     * codec in the utility package declares the same figure, and importing it would be the obvious move,
     * but the utility package already depends on this one - its record mappers construct these entities -
     * so an import in this direction would close a package cycle. The two declarations are therefore
     * deliberate duplicates of one contractual number, and this comment is the link between them: a
     * change to either is a change to both.
     */
    public static final int MONETARY_SCALE = 2;

    /**
     * The rounding mode every store into a two-decimal field uses: truncation toward zero.
     *
     * <p><strong>This is the single most consequential constant in the module, and the conventional Java
     * choice is wrong here.</strong> A search of every program and copybook in the estate finds no
     * rounding clause on any arithmetic statement at all, and a COBOL store without one truncates rather
     * than rounding. Half-even rounding - what general Java guidance would suggest, and what
     * {@code BigDecimal} arithmetic tends to be paired with - differs by one cent on roughly half of all
     * interest computations, which is a byte-parity failure invisible to any test written under the same
     * assumption.
     *
     * <p>Restated here rather than imported, for the package-cycle reason given on {@link #MONETARY_SCALE}.
     */
    public static final RoundingMode MONETARY_ROUNDING = RoundingMode.DOWN;

    /**
     * No instance is ever required: the class holds no state and every member is static.
     */
    private StoredValueRules() {
        // No instance state exists, so no instance is ever required.
    }

    /**
     * Normalises a monetary or rate value to the scale its column declares, truncating toward zero, and
     * refuses one whose magnitude the column cannot hold.
     *
     * <h2>Why a stored amount has to pass through here</h2>
     *
     * <p>The fixed-width codec produces amounts at scale two already, so a value that arrived by decoding
     * a record image needs no adjustment. The hazard is every other route: a value computed in a service,
     * built in a fixture, parsed from a request or divided during an interest calculation carries whatever
     * scale the arithmetic left it with, and an entity that accepted it verbatim would let a repository
     * write bypass the truncation policy entirely. The persistence provider would then either store a
     * differently-rounded amount or have the database round it under a policy nobody chose. Normalising on
     * the way to the row makes the policy unconditional.
     *
     * <p><strong>It truncates rather than rejecting, because that is what the legacy did.</strong> A COBOL
     * store of a three-decimal intermediate into a two-decimal field discards the third digit silently,
     * and the interest calculation is exactly that shape - a balance multiplied by a rate and divided by
     * twelve hundred, stored into a two-decimal field. Refusing such a value would fail a computation the
     * legacy completed; rounding it would change the cent.
     *
     * <p><strong>It does reject a magnitude the column cannot hold</strong>, because that is not a
     * rounding question. A value beyond the declared precision has no faithful stored form: the database
     * refuses it, and refusing it here names the attribute instead of surfacing a numeric-overflow failure
     * from the driver. No legacy path can produce one - the values come from fields of the same declared
     * width - so the check refuses only what no writer should have built.
     *
     * @param value     the amount about to be stored
     * @param precision the column's total digit count, of which {@link #MONETARY_SCALE} are after the
     *                  decimal point
     * @param attribute the attribute's name, used only to make the rejection message name its subject
     * @return the same value at scale {@link #MONETARY_SCALE}, truncated toward zero; the argument itself
     *         when it already carries that scale
     * @throws IllegalArgumentException if {@code value} is {@code null} or its integer part needs more
     *                                  digits than the column declares
     */
    public static BigDecimal normalizedAmount(final BigDecimal value, final int precision,
                                              final String attribute) {
        if (value == null) {
            throw new IllegalArgumentException(attribute
                    + " must be present before this record is stored: the column is declared not-null,"
                    + " and the legacy field it maps carries zeros rather than nothing.");
        }
        final BigDecimal scaled = value.scale() == MONETARY_SCALE
                ? value
                : value.setScale(MONETARY_SCALE, MONETARY_ROUNDING);
        if (scaled.precision() - scaled.scale() > precision - MONETARY_SCALE) {
            throw new IllegalArgumentException(attribute
                    + " must fit " + (precision - MONETARY_SCALE) + " digits before the decimal point,"
                    + " which is the width of the legacy field it maps; the value supplied needs "
                    + (scaled.precision() - scaled.scale()) + ".");
        }
        return scaled;
    }

    /**
     * Requires a value to be present and to be exactly the width its record layout declares, returning
     * it unchanged.
     *
     * <p>Used for keys whose legacy picture clause is alphanumeric, where the width is contractual but
     * the character class is not - the card number, the transaction identifier and the sign-on
     * identifier among them.
     *
     * @param value     the value about to be stored
     * @param width     the exact number of characters the record layout declares for this field
     * @param attribute the attribute's name, used only to make the rejection message name its subject
     * @return {@code value} unchanged, so the check can be applied inline at the point of assignment
     * @throws IllegalArgumentException if {@code value} is {@code null} or is not exactly {@code width}
     *                                  characters long
     */
    public static String requireFixedWidth(final String value, final int width,
                                           final String attribute) {
        if (value == null) {
            throw new IllegalArgumentException(attribute
                    + " must be present before this record is stored: it is a natural key of width "
                    + width + " and a row cannot be identified without it.");
        }
        if (value.length() != width) {
            throw new IllegalArgumentException(attribute
                    + " must be exactly " + width + " characters, because it is a field of a"
                    + " fixed-width record image and every value sliced from a valid image is that"
                    + " width; " + value.length() + " were supplied. The value is neither padded nor"
                    + " trimmed here, because either would invent or destroy an identity.");
        }
        return value;
    }

    /**
     * Requires a value to be present, to be exactly the width its record layout declares and to consist
     * only of ASCII digits, returning it unchanged.
     *
     * <p>Used for keys whose legacy picture clause is numeric - the account identifier, the customer
     * identifier and the transaction category code among them - and for the transaction identifier,
     * whose picture clause is alphanumeric but on which an ordering that is only valid over
     * zero-padded digits depends.
     *
     * <p>The digit test is written against the ASCII digit range rather than against a
     * locale-sensitive or Unicode-aware predicate, for the same reason the module folds case with an
     * explicit table: a Unicode-aware digit test admits characters the legacy field could not hold, and
     * would let a value that is not a legacy digit string pass a check whose whole purpose is to prove
     * that it is one.
     *
     * @param value     the value about to be stored
     * @param width     the exact number of characters the record layout declares for this field
     * @param attribute the attribute's name, used only to make the rejection message name its subject
     * @return {@code value} unchanged, so the check can be applied inline at the point of assignment
     * @throws IllegalArgumentException if {@code value} is {@code null}, is not exactly {@code width}
     *                                  characters long, or carries any character outside {@code 0}-{@code 9}
     */
    public static String requireFixedWidthDigits(final String value, final int width,
                                                 final String attribute) {
        requireFixedWidth(value, width, attribute);
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(attribute
                        + " must be " + width + " ASCII digits, because its legacy picture clause is"
                        + " numeric and an ordering over it is only valid across zero-padded digits;"
                        + " position " + index + " is not a digit.");
            }
        }
        return value;
    }
}
