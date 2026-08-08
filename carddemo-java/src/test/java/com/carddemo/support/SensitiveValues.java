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
package com.carddemo.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Projections that let an assertion pin a sensitive value without printing it when it fails.
 *
 * <h2>The problem this exists for</h2>
 *
 * <p>An assertion library reports what it compared. {@code assertThat(token).isNotBlank()} prints the
 * token when it fails; {@code assertThat(digests).doesNotHaveDuplicates()} prints every digest;
 * {@code assertThat(body).doesNotContain(token)} prints both the body and the token. A build log is
 * retained, is usually readable by more people than the running system's data is, and is frequently
 * shipped to a third-party service. So an assertion over a session token, a stored credential digest, a
 * fixture password, a card number or a verification code turns a test failure into a disclosure - and it
 * does so precisely when something is already going wrong.
 *
 * <p>The values in this module's tests are synthetic. That is a reason the exposure is low-severity, not a
 * reason it is acceptable: the same assertion shapes are what a team copies into a suite that runs against
 * something real, and a card number and a verification code are regulated by their shape rather than by
 * their provenance.
 *
 * <h2>What this provides, and why not an assertion wrapper</h2>
 *
 * <p>Projections rather than assertions. Each method converts a sensitive value into something safe to
 * print - a digest fingerprint, a length, a shape predicate - and the call site then asserts on that with
 * the ordinary assertion library. The alternative, a helper that raises the failure itself, would take the
 * description, the soft-assertion grouping and the chaining away from the call site and would make every
 * such assertion read differently from every other assertion in the suite.
 *
 * <p>A fingerprint comparison is exactly as strong as an equality comparison for this purpose. Two values
 * with one SHA-256 digest are the same value, so an assertion that compares fingerprints still fails
 * whenever the value is wrong; what changes is only what the failure prints. That property is asserted in
 * {@code SensitiveValuesTest} rather than assumed, and each converted call site was checked by mutating the
 * value under it and confirming the assertion still fails.
 *
 * <h2>What this deliberately does not do</h2>
 *
 * <p>It does not remove the synthetic constants the fixtures are built from. A test that posts a card
 * update has to send a card number, so the value must exist in the test tier; what must not happen is an
 * assertion printing it. Nor does it mask a value that is not sensitive: an account identifier, a name, a
 * date and a status are printed as they always were, because obscuring them would cost the diagnostic
 * everything and protect nothing.
 */
public final class SensitiveValues {

    /** Characters of the hex digest a fingerprint carries - enough that a collision is not a concern. */
    private static final int FINGERPRINT_LENGTH = 12;

    /** Prefix every fingerprint carries, so a reader can see what a printed value is and is not. */
    private static final String FINGERPRINT_PREFIX = "sha256:";

    /** Rendered in place of a fingerprint of nothing, so a null and an empty value stay distinguishable. */
    private static final String ABSENT = "<absent>";

    /** Rendered in place of a fingerprint of an empty value. */
    private static final String EMPTY = "<empty>";

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private SensitiveValues() {
        throw new AssertionError("SensitiveValues is a utility holder and must not be instantiated");
    }

    /**
     * A short, stable, one-way fingerprint of a sensitive value, safe to print.
     *
     * <p>Comparing two fingerprints is as decisive as comparing the values: equal values fingerprint
     * equally, and unequal values do not, so an assertion loses no strength by comparing these instead.
     *
     * @param  value the sensitive value, which may be {@code null}
     * @return {@code "sha256:"} and twelve hex characters, or a marker for a {@code null} or empty value
     */
    public static String fingerprint(final String value) {
        if (value == null) {
            return ABSENT;
        }
        if (value.isEmpty()) {
            return EMPTY;
        }
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", unavailable);
        }
        final byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        final StringBuilder hex = new StringBuilder(FINGERPRINT_LENGTH);
        for (int index = 0; hex.length() < FINGERPRINT_LENGTH; index++) {
            hex.append(String.format(Locale.ROOT, "%02x", Byte.valueOf(hashed[index])));
        }
        return FINGERPRINT_PREFIX + hex.substring(0, FINGERPRINT_LENGTH);
    }

    /**
     * A value's length and fingerprint together, for a description that must identify it.
     *
     * @param  value the sensitive value, which may be {@code null}
     * @return a rendering such as {@code <length=16 sha256:0a1b2c3d4e5f>}, printing no character of it
     */
    public static String describe(final String value) {
        if (value == null) {
            return ABSENT;
        }
        return String.format(Locale.ROOT, "<length=%d %s>",
                Integer.valueOf(value.length()), fingerprint(value));
    }

    /**
     * Fingerprints of a whole collection, so a collection assertion prints no member.
     *
     * <p>Order is preserved, so an ordering assertion can be made on the values' own order by fingerprinting
     * a list that is already in that order. Note that fingerprints do not sort in the same order as the
     * values they stand for, so a sortedness assertion must be made through {@link #ascending(List)}
     * instead.
     *
     * @param  values the sensitive values; must not be {@code null} and must contain no {@code null}
     * @return one fingerprint per member, in the same order
     * @throws NullPointerException if {@code values} is {@code null}
     */
    public static List<String> fingerprints(final List<String> values) {
        Objects.requireNonNull(values, "values must not be null");
        return values.stream().map(SensitiveValues::fingerprint).collect(Collectors.toList());
    }

    /**
     * Whether a list of sensitive values is in ascending order, as a predicate rather than a rendering.
     *
     * <p>Sortedness cannot be asserted on fingerprints, because a digest does not preserve order. It is
     * therefore reported as a boolean, and the values themselves are never printed.
     *
     * @param  values the sensitive values; must not be {@code null}
     * @return {@code true} if each member is greater than or equal to the one before it
     * @throws NullPointerException if {@code values} is {@code null}
     */
    public static boolean ascending(final List<String> values) {
        Objects.requireNonNull(values, "values must not be null");
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index - 1).compareTo(values.get(index)) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * How many members of a collection of sensitive values are distinct.
     *
     * <p>Asserted against the expected count in place of a duplicate-freeness assertion, which would print
     * every member of the collection on failure.
     *
     * @param  values the sensitive values; must not be {@code null}
     * @return the number of distinct members
     * @throws NullPointerException if {@code values} is {@code null}
     */
    public static int distinctCount(final List<String> values) {
        Objects.requireNonNull(values, "values must not be null");
        return Set.copyOf(values).size();
    }

    /**
     * Whether a sensitive value is absent from a larger text, as a predicate.
     *
     * <p>Replaces {@code assertThat(text).doesNotContain(secret)}, which prints the secret <em>and</em> the
     * text it was looked for in - so a failure of that assertion discloses twice over, once directly and
     * once through whatever the text itself carries.
     *
     * @param  text   the text to search, which may be {@code null}
     * @param  value  the sensitive value; must not be {@code null} or empty
     * @return {@code true} if {@code text} is {@code null} or does not contain {@code value}
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is empty, which every text trivially contains
     */
    public static boolean absentFrom(final String text, final String value) {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    "an empty value is contained in every text, so asking whether it is absent cannot"
                            + " express an expectation");
        }
        return text == null || !text.contains(value);
    }

    /**
     * Whether a value has the three dot-separated parts of a signed web token.
     *
     * <p>Reported as a predicate rather than asserted through a pattern match, because a failed pattern
     * match prints the subject - and the subject here is a live session token.
     *
     * @param  token the token, which may be {@code null}
     * @return {@code true} if the token is non-blank and carries exactly three non-empty dot-separated
     *         parts
     */
    public static boolean hasSignedTokenShape(final String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        final String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return false;
        }
        for (final String part : parts) {
            if (part.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
