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
package com.carddemo.util;

/**
 * The one statement of what a queue destination and a message group may be, shared by the publisher
 * that sends to the queue and by the tests that hold the emulator bootstrap to the same contract.
 *
 * <h2>The disagreement this closes</h2>
 *
 * <p>Two components validated the same two configured values and did not agree. The bootstrap script
 * bounded the queue name at {@value #QUEUE_NAME_MAX_LENGTH} characters, restricted it to the character
 * set below, and required the first-in-first-out suffix; it bounded the message group id at
 * {@value #MESSAGE_GROUP_ID_MAX_LENGTH} with the same character set. The publisher checked only that
 * each value was non-blank and printable US-ASCII, and that the queue value ended in the suffix.
 *
 * <p>So a queue name of five hundred punctuation characters ending in {@code .fifo} passed the
 * publisher's construction and failed the bootstrap's, and - worse - a deployment that skipped the
 * bootstrap entirely would start cleanly and then fail <em>every</em> send. That last part is what
 * makes it more than an inconsistency: the legacy contract is ignore-on-error, so the publisher is
 * deliberately built to log a failed send and continue. An invalid destination therefore does not
 * announce itself; it is absorbed into the same non-fatal partial result a genuine transient failure
 * produces, and a submission reports as complete while nothing was queued. Validating at construction
 * converts that into a start-up refusal, which is the only point at which it can still be loud.
 *
 * <h2>Three destination forms, one rule about the queue itself</h2>
 *
 * <p>The publisher accepts a bare queue name, a queue URL, or a queue ARN, because the messaging
 * template resolves all three and a deployment may legitimately configure any of them. That is why the
 * character rule cannot simply be applied to the whole configured value: a URL carries a scheme,
 * slashes and dots, and an ARN carries colons, none of which may appear in a queue name. Each form is
 * therefore recognised, its own envelope is checked for shape, and <strong>the queue name is extracted
 * and held to one rule</strong> - the same rule the bootstrap applies, because in the bare-name case it
 * is literally the same string the bootstrap would have received.
 *
 * <h2>What a rejection says</h2>
 *
 * <p>Every rejection names the property key, this class's own limits and literals, and - for a
 * character violation - the offending character's zero-based position and code point. It never repeats
 * the configured value, the extracted queue name, or any fragment of either. Decision {@code DL-041}
 * governs that: the property key is the actionable fact, because it points an operator at the exact
 * configuration entry where the value can already be read. Reporting a length reports a number rather
 * than content, which is the same distinction the publisher's deduplication-length rejection draws.
 *
 * <p>Decision {@code DL-117} records why this contract is stated here once rather than separately in
 * the publisher and the emulator bootstrap, and how the two are held to agree.
 *
 * <p>Provenance: the queue these rules govern replaces the estate's sole online-to-batch bridge, the
 * CICS transient-data queue defined at the end of {@code app/csd/CARDDEMO.CSD} and written from the one
 * {@code EXEC CICS WRITEQ TD} site in {@code app/cbl/CORPT00C.cbl}, taken from checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The rules themselves are the queue service's,
 * not the legacy system's, and have no legacy antecedent. No legacy source text appears here.
 */
public final class SqsNamingRules {

    /**
     * Longest queue name the queue service accepts.
     *
     * <p>Applied to the queue name itself in all three destination forms, never to the URL or ARN that
     * may carry it.
     */
    public static final int QUEUE_NAME_MAX_LENGTH = 80;

    /** Longest message group id the queue service accepts. */
    public static final int MESSAGE_GROUP_ID_MAX_LENGTH = 128;

    /**
     * The suffix a first-in-first-out queue name must end with.
     *
     * <p>Not decoration: the queue service refuses to create a first-in-first-out queue whose name
     * omits it, and a standard queue cannot honour the append ordering that the legacy queue's
     * disposition guarantees and that acceptance drains a real queue to verify.
     */
    public static final String FIFO_SUFFIX = ".fifo";

    /** Scheme prefix identifying the queue-URL form. */
    private static final String SECURE_URL_PREFIX = "https://";

    /** Scheme prefix identifying the queue-URL form served without transport security. */
    private static final String PLAIN_URL_PREFIX = "http://";

    /** Prefix identifying the queue-ARN form. */
    private static final String ARN_PREFIX = "arn:";

    /** Segment separator inside an ARN. */
    private static final char ARN_SEPARATOR = ':';

    /** Path separator inside a queue URL. */
    private static final char URL_SEPARATOR = '/';

    /** Number of colon-separated segments a queue ARN carries. */
    private static final int ARN_SEGMENT_COUNT = 6;

    /** Zero-based index of the service segment within an ARN. */
    private static final int ARN_SERVICE_SEGMENT = 2;

    /** Zero-based index of the resource segment within an ARN, which is the queue name. */
    private static final int ARN_RESOURCE_SEGMENT = 5;

    /** The service an ARN must name for it to address a queue. */
    private static final String ARN_SERVICE = "sqs";

    /**
     * Longest configured destination value accepted in any form.
     *
     * <p>A URL and an ARN are both longer than the name they carry, so the name limit cannot bound
     * them. This ceiling exists so that an absurd value is refused before it is parsed rather than
     * after, and it is generous: the longest legitimate form is an ARN or URL wrapping an
     * {@value #QUEUE_NAME_MAX_LENGTH}-character name.
     */
    public static final int QUEUE_DESTINATION_MAX_LENGTH = 512;

    /**
     * Not instantiable: every operation is a pure function of its arguments and the class holds no
     * state beyond its constants.
     */
    private SqsNamingRules() {
        throw new AssertionError("SqsNamingRules is a utility holder and is never instantiated");
    }

    /**
     * Validates a configured queue destination in any of the three accepted forms and returns it
     * unchanged.
     *
     * <p>The value is recognised as an ARN, a URL, or a bare name, its envelope is checked for shape,
     * and the queue name it carries is then held to {@link #requireQueueName(String, String)}. The
     * value is returned rather than the extracted name, because the messaging template is given
     * exactly what the deployment configured.
     *
     * @param value       the configured destination: a queue name, a queue URL, or a queue ARN; must
     *                    not be {@code null}
     * @param propertyKey the configuration key it was bound from, named in every diagnostic
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if the value is too long, is not a recognisable form of one of
     *                                  the three, or carries a queue name that breaks the queue-name
     *                                  rule
     */
    public static String requireQueueDestination(final String value, final String propertyKey) {
        requireBounded(value, propertyKey, QUEUE_DESTINATION_MAX_LENGTH, "queue destination");
        if (value.startsWith(ARN_PREFIX)) {
            requireQueueName(arnResourceOf(value, propertyKey), propertyKey);
            return value;
        }
        if (value.startsWith(SECURE_URL_PREFIX) || value.startsWith(PLAIN_URL_PREFIX)) {
            requireQueueName(urlQueueNameOf(value, propertyKey), propertyKey);
            return value;
        }
        requireQueueName(value, propertyKey);
        return value;
    }

    /**
     * Validates a bare queue name and returns it unchanged.
     *
     * <p>This is the rule the emulator bootstrap applies, stated once here so the two cannot drift:
     * at most {@value #QUEUE_NAME_MAX_LENGTH} characters, letters, digits, dots, hyphens and
     * underscores only, and ending in {@value #FIFO_SUFFIX}.
     *
     * @param queueName   the bare queue name, already extracted from whichever form carried it
     * @param propertyKey the configuration key it was bound from, named in every diagnostic
     * @return {@code queueName}, unchanged
     * @throws IllegalArgumentException if the name is empty, too long, carries a character outside the
     *                                  permitted set, or does not end in {@value #FIFO_SUFFIX}
     */
    public static String requireQueueName(final String queueName, final String propertyKey) {
        requireBounded(queueName, propertyKey, QUEUE_NAME_MAX_LENGTH, "queue name");
        requirePermittedCharacters(queueName, propertyKey, "queue name");
        if (!queueName.endsWith(FIFO_SUFFIX)) {
            throw new IllegalArgumentException("property " + propertyKey + " must name a"
                    + " first-in-first-out queue, whose name ends with '" + FIFO_SUFFIX + "', because"
                    + " job-submission cards must keep their order and a standard queue would reorder"
                    + " them; the configured value does not carry that suffix");
        }
        if (queueName.length() == FIFO_SUFFIX.length()) {
            throw new IllegalArgumentException("property " + propertyKey + " carries the '"
                    + FIFO_SUFFIX + "' suffix and nothing before it, so it names no queue");
        }
        return queueName;
    }

    /**
     * Validates a configured message group id and returns it unchanged.
     *
     * <p>The group id provisions nothing - the publisher puts it on every message - but an invalid one
     * fails every send just as surely as an invalid queue name, and through the same non-fatal path,
     * so it is held to the queue service's own limit of {@value #MESSAGE_GROUP_ID_MAX_LENGTH}
     * characters over the same character set.
     *
     * @param value       the configured group id; must not be {@code null}
     * @param propertyKey the configuration key it was bound from, named in every diagnostic
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if the value is empty, too long, or carries a character outside
     *                                  the permitted set
     */
    public static String requireMessageGroupId(final String value, final String propertyKey) {
        requireBounded(value, propertyKey, MESSAGE_GROUP_ID_MAX_LENGTH, "message group id");
        requirePermittedCharacters(value, propertyKey, "message group id");
        return value;
    }

    /**
     * Reports whether a character may appear in a queue name or a message group id.
     *
     * <p>Letters, digits, dot, hyphen and underscore. The dot is permitted because the mandatory
     * first-in-first-out suffix needs it; it is not otherwise meaningful.
     *
     * @param character the character to test
     * @return {@code true} when the character is permitted
     */
    public static boolean isPermittedNameCharacter(final char character) {
        return (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '.' || character == '-' || character == '_';
    }

    /**
     * Extracts the resource segment of a queue ARN, having first checked the ARN's shape.
     *
     * @param value       the configured value, known to start with the ARN prefix
     * @param propertyKey the configuration key it was bound from
     * @return the resource segment, which for a queue ARN is the queue name
     * @throws IllegalArgumentException if the value does not have the shape of a queue ARN
     */
    private static String arnResourceOf(final String value, final String propertyKey) {
        final String[] segments = splitOn(value, ARN_SEPARATOR);
        if (segments.length != ARN_SEGMENT_COUNT) {
            throw new IllegalArgumentException("property " + propertyKey + " begins with '"
                    + ARN_PREFIX + "' and is therefore read as a queue ARN, which carries exactly "
                    + ARN_SEGMENT_COUNT + " colon-separated segments; the configured value carries "
                    + segments.length);
        }
        if (!ARN_SERVICE.equals(segments[ARN_SERVICE_SEGMENT])) {
            throw new IllegalArgumentException("property " + propertyKey + " is read as an ARN whose"
                    + " service segment must be '" + ARN_SERVICE + "' for it to address a queue, and"
                    + " the configured value names a different service");
        }
        return segments[ARN_RESOURCE_SEGMENT];
    }

    /**
     * Extracts the final path segment of a queue URL, having first checked that the URL has a path.
     *
     * @param value       the configured value, known to start with a URL scheme prefix
     * @param propertyKey the configuration key it was bound from
     * @return the final path segment, which for a queue URL is the queue name
     * @throws IllegalArgumentException if the value has no path, or an empty final segment
     */
    private static String urlQueueNameOf(final String value, final String propertyKey) {
        final int schemeEnd = value.indexOf("//") + 2;
        final int pathStart = value.indexOf(URL_SEPARATOR, schemeEnd);
        if (pathStart < 0 || pathStart == value.length() - 1) {
            throw new IllegalArgumentException("property " + propertyKey + " begins with a URL scheme"
                    + " and is therefore read as a queue URL, whose last path segment names the"
                    + " queue; the configured value carries no such segment");
        }
        final String queueName = value.substring(value.lastIndexOf(URL_SEPARATOR) + 1);
        if (queueName.isEmpty()) {
            throw new IllegalArgumentException("property " + propertyKey + " is read as a queue URL"
                    + " whose last path segment names the queue, and that segment is empty");
        }
        return queueName;
    }

    /**
     * Splits a value on a separator without a regular expression, so no pattern is compiled and no
     * character of the value carries meta-meaning.
     *
     * @param value     the value to split
     * @param separator the separator to split on
     * @return the segments, in order, including empty ones
     */
    private static String[] splitOn(final String value, final char separator) {
        int count = 1;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == separator) {
                count++;
            }
        }
        final String[] segments = new String[count];
        int start = 0;
        int written = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == separator) {
                segments[written++] = value.substring(start, index);
                start = index + 1;
            }
        }
        segments[written] = value.substring(start);
        return segments;
    }

    /**
     * Requires a value to be non-empty and no longer than a ceiling, naming lengths and never content.
     *
     * @param value       the value to bound
     * @param propertyKey the configuration key it was bound from
     * @param maxLength   the inclusive ceiling
     * @param subject     what is being bounded, one of this class's own literals
     * @throws IllegalArgumentException if the value is empty or longer than {@code maxLength}
     */
    private static void requireBounded(final String value, final String propertyKey,
            final int maxLength, final String subject) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("property " + propertyKey + " resolves to an empty "
                    + subject);
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException("property " + propertyKey + " resolves to a " + subject
                    + " of " + value.length() + " characters, and the queue service accepts at most "
                    + maxLength);
        }
    }

    /**
     * Requires every character of a value to be permitted in a queue name or group id.
     *
     * <p>The diagnostic reports the offending character's zero-based position and code point, which
     * locates the problem without repeating any part of the value.
     *
     * @param value       the value to check
     * @param propertyKey the configuration key it was bound from
     * @param subject     what is being checked, one of this class's own literals
     * @throws IllegalArgumentException on the first character outside the permitted set
     */
    private static void requirePermittedCharacters(final String value, final String propertyKey,
            final String subject) {
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (!isPermittedNameCharacter(character)) {
                throw new IllegalArgumentException("property " + propertyKey + " resolves to a "
                        + subject + " holding a character the queue service does not accept there;"
                        + " letters, digits, '.', '-' and '_' are permitted, and the character at"
                        + " zero-based position " + index + " is code point " + (int) character);
            }
        }
    }
}
