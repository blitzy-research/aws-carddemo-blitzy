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

import java.util.List;
import java.util.Locale;

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
 * <p>The rules themselves are the queue service's, not the legacy system's, and have no legacy
 * antecedent. No legacy source text appears here.
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

    /** Zero-based index of the partition segment within an ARN. */
    private static final int ARN_PARTITION_SEGMENT = 1;

    /** Zero-based index of the region segment within an ARN. */
    private static final int ARN_REGION_SEGMENT = 3;

    /** Zero-based index of the account segment within an ARN. */
    private static final int ARN_ACCOUNT_SEGMENT = 4;

    /**
     * The partitions a production destination may name.
     *
     * <p>An allow list rather than a shape test. A partition decides which set of endpoints, which
     * trust root and which account namespace a value addresses, so an unrecognised partition is not a
     * new region to be tolerated - it is a value nobody in this deployment can have meant.
     */
    private static final List<String> PERMITTED_ARN_PARTITIONS =
            List.of("aws", "aws-cn", "aws-us-gov");

    /**
     * Number of digits in an account identifier.
     *
     * <p>Checked because it is the one segment of a trusted destination that is neither a fixed
     * literal nor the deployment's own region, so shape is all there is to hold it to. A value that is
     * not twelve digits is not an account and the destination is therefore not the queue service's.
     */
    private static final int ACCOUNT_ID_LENGTH = 12;

    /**
     * Host prefix every queue-service endpoint carries.
     *
     * <p>Both the ordinary and the validated-cryptography endpoints are covered, because the second is
     * a legitimate production choice and refusing it would push a deployment towards the first.
     */
    private static final List<String> QUEUE_HOST_PREFIXES = List.of("sqs.", "sqs-fips.");

    /**
     * Host suffixes a queue-service endpoint may carry, one per partition.
     *
     * <p>An allow list for the same reason the partitions are: the suffix is what decides whose
     * infrastructure receives the job cards, and a host outside these is by definition not it.
     */
    private static final List<String> QUEUE_HOST_SUFFIXES =
            List.of(".amazonaws.com", ".amazonaws.com.cn");

    /** Number of path segments a queue URL carries after its host: the account and the queue. */
    private static final int QUEUE_URL_PATH_SEGMENT_COUNT = 2;

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
     * Validates a configured queue destination against the stricter rule a production deployment is
     * held to, and returns it unchanged.
     *
     * <h2>Why a second, stricter rule exists at all</h2>
     *
     * <p>{@link #requireQueueDestination(String, String)} answers "is this a well-formed destination",
     * which is the right question for a developer's machine and for the suite, where the destination
     * legitimately addresses an emulator over plain transport on the loopback interface. It is the
     * wrong question for a deployment, because a well-formed destination can address <em>anybody</em>:
     * the messaging client accepts any syntactically valid URI as a queue locator, so a value such as a
     * plain-transport URL on an unrelated host whose last path segment merely ends in the
     * first-in-first-out suffix passes every shape test and then receives the job cards. That is not a
     * malformed value being tolerated - it is a correctly formed value naming the wrong recipient, and
     * only an identity check can tell the two apart.
     *
     * <p>The job cards are the estate's single online-to-batch bridge. Each is an eighty-column
     * job-control image naming the job, the procedure library, the step and the reporting period. A
     * destination outside the deployment therefore discloses the batch topology and, more to the point,
     * silently prevents every requested job from ever running while each request still answers
     * successfully - the queue is defined ignore-on-error, so nothing complains.
     *
     * <h2>The three accepted forms, in order of preference</h2>
     *
     * <ul>
     *   <li><strong>A bare queue name</strong> is the preferred form and needs no identity check at
     *       all, because it carries no destination: the client resolves it against the region and
     *       credentials the deployment itself supplies, so a name cannot redirect anything. Held to
     *       {@link #requireQueueName(String, String)} and nothing more.</li>
     *   <li><strong>A queue URL</strong> must use transport security, must sit on a queue-service
     *       endpoint host for the deployment's own region, and must carry exactly an account segment
     *       and a queue segment. Plain transport is refused outright rather than warned about: job
     *       cards on an unencrypted connection are readable and rewritable in flight.</li>
     *   <li><strong>A queue ARN</strong> must name a recognised partition, the queue service, the
     *       deployment's own region and a twelve-digit account.</li>
     * </ul>
     *
     * <p>Both the URL and the ARN form are checked against {@code expectedRegion}, which is the region
     * the deployment configured for its clients. That is the "deployment identity" half of the check
     * and it is what makes the rule about <em>this</em> deployment rather than about AWS in general: a
     * genuine queue URL in somebody else's region is still a destination this deployment did not mean.
     *
     * <p>Every diagnostic names the property key and the rule it broke and never repeats the configured
     * value, per {@code docs/decision-log.md} DL-041: the key is the actionable fact, and an operator
     * reads the value there.
     *
     * @param value          the configured destination: a queue name, a queue URL, or a queue ARN;
     *                       must not be {@code null}
     * @param propertyKey    the configuration key it was bound from, named in every diagnostic
     * @param expectedRegion the region this deployment configured for its clients, which a URL or ARN
     *                       destination must agree with; must not be {@code null} or blank
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if the value is not one of the three accepted forms, uses plain
     *                                  transport, sits on a host that is not a queue-service endpoint,
     *                                  names a partition, service, region or account this deployment
     *                                  cannot have meant, or carries a queue name that breaks the
     *                                  queue-name rule
     * @throws NullPointerException     if {@code expectedRegion} is {@code null}
     */
    public static String requireProductionQueueDestination(final String value,
            final String propertyKey, final String expectedRegion) {
        requireBounded(value, propertyKey, QUEUE_DESTINATION_MAX_LENGTH, "queue destination");
        if (expectedRegion == null || expectedRegion.isBlank()) {
            throw new IllegalArgumentException("property " + propertyKey + " cannot be held to a"
                    + " production destination rule because this deployment declares no region for its"
                    + " clients; a destination can only be checked against the deployment it belongs"
                    + " to, so the region must be configured first");
        }
        if (value.startsWith(PLAIN_URL_PREFIX)) {
            throw new IllegalArgumentException("property " + propertyKey + " names a queue over plain"
                    + " transport, which a deployment may not do: an eighty-column job-control card on"
                    + " an unencrypted connection is readable and rewritable in flight. Use a bare"
                    + " queue name, or a '" + SECURE_URL_PREFIX + "' queue URL on a queue-service"
                    + " endpoint host");
        }
        if (value.startsWith(ARN_PREFIX)) {
            requireTrustedArn(value, propertyKey, expectedRegion);
            return value;
        }
        if (value.startsWith(SECURE_URL_PREFIX)) {
            requireTrustedQueueUrl(value, propertyKey, expectedRegion);
            return value;
        }
        if (value.indexOf("//") >= 0 || value.indexOf(ARN_SEPARATOR) >= 0) {
            throw new IllegalArgumentException("property " + propertyKey + " is neither a bare queue"
                    + " name, a '" + SECURE_URL_PREFIX + "' queue URL, nor an '" + ARN_PREFIX
                    + "' queue ARN, and a deployment may name nothing else. A value carrying a scheme"
                    + " separator or a colon is read as one of the latter two and must satisfy that"
                    + " form's rule");
        }
        // The preferred form: a name carries no destination, so the client resolves it against this
        // deployment's own region and credentials and nothing can be redirected.
        requireQueueName(value, propertyKey);
        return value;
    }

    /**
     * Holds a queue ARN to the partition, service, region and account a deployment can have meant.
     *
     * @param value          the configured value, known to start with the ARN prefix
     * @param propertyKey    the configuration key it was bound from
     * @param expectedRegion the region this deployment configured
     * @throws IllegalArgumentException if any segment is one this deployment cannot have meant
     */
    private static void requireTrustedArn(final String value, final String propertyKey,
            final String expectedRegion) {
        final String[] segments = splitOn(value, ARN_SEPARATOR);
        if (segments.length != ARN_SEGMENT_COUNT) {
            throw new IllegalArgumentException("property " + propertyKey + " begins with '"
                    + ARN_PREFIX + "' and is therefore read as a queue ARN, which carries exactly "
                    + ARN_SEGMENT_COUNT + " colon-separated segments; the configured value carries "
                    + segments.length);
        }
        if (!PERMITTED_ARN_PARTITIONS.contains(segments[ARN_PARTITION_SEGMENT])) {
            throw new IllegalArgumentException("property " + propertyKey + " is read as a queue ARN"
                    + " whose partition segment must be one of " + PERMITTED_ARN_PARTITIONS
                    + "; the configured value names another partition, which addresses a different set"
                    + " of endpoints and a different account namespace entirely");
        }
        if (!ARN_SERVICE.equals(segments[ARN_SERVICE_SEGMENT])) {
            throw new IllegalArgumentException("property " + propertyKey + " is read as an ARN whose"
                    + " service segment must be '" + ARN_SERVICE + "' for it to address a queue, and"
                    + " the configured value names a different service");
        }
        if (!expectedRegion.equalsIgnoreCase(segments[ARN_REGION_SEGMENT])) {
            throw new IllegalArgumentException("property " + propertyKey + " is read as a queue ARN"
                    + " whose region segment must be the region this deployment configured for its"
                    + " clients; the configured value names a different region, so it addresses a queue"
                    + " in a deployment this one is not");
        }
        requireAccountId(segments[ARN_ACCOUNT_SEGMENT], propertyKey);
        requireQueueName(segments[ARN_RESOURCE_SEGMENT], propertyKey);
    }

    /**
     * Holds a queue URL to a queue-service endpoint host in this deployment's region, with exactly an
     * account segment and a queue segment beneath it.
     *
     * @param value          the configured value, known to start with the secure URL prefix
     * @param propertyKey    the configuration key it was bound from
     * @param expectedRegion the region this deployment configured
     * @throws IllegalArgumentException if the host is not a queue-service endpoint for that region, or
     *                                  the path is not an account and a queue
     */
    private static void requireTrustedQueueUrl(final String value, final String propertyKey,
            final String expectedRegion) {
        final String authorityAndPath = value.substring(SECURE_URL_PREFIX.length());
        final int pathStart = authorityAndPath.indexOf(URL_SEPARATOR);
        if (pathStart <= 0) {
            throw new IllegalArgumentException("property " + propertyKey + " is read as a queue URL"
                    + " and carries no path, so it names an endpoint rather than a queue. A queue URL"
                    + " carries an account segment and a queue segment");
        }
        // Anything a userinfo or a port could hide is refused with the host, below: the authority is
        // compared whole against the endpoint grammar rather than picked apart, so a value such as
        // "sqs.us-east-1.amazonaws.com@attacker.example" cannot masquerade as the host it prefixes.
        final String host = authorityAndPath.substring(0, pathStart).toLowerCase(Locale.ROOT);
        requireQueueServiceHost(host, propertyKey, expectedRegion);

        final String[] pathSegments = splitOn(authorityAndPath.substring(pathStart + 1), URL_SEPARATOR);
        if (pathSegments.length != QUEUE_URL_PATH_SEGMENT_COUNT) {
            throw new IllegalArgumentException("property " + propertyKey + " is read as a queue URL,"
                    + " whose path is exactly an account segment and a queue segment; the configured"
                    + " value carries " + pathSegments.length + " path segment(s)");
        }
        requireAccountId(pathSegments[0], propertyKey);
        requireQueueName(pathSegments[1], propertyKey);
    }

    /**
     * Requires a URL authority to be a queue-service endpoint host for one region.
     *
     * <p>The comparison is a whole-authority match against the endpoint grammar - a known prefix, the
     * expected region, a known suffix, and nothing else at all - rather than a search for the region or
     * the suffix inside the authority. That direction matters: a containment test would accept
     * {@code sqs.us-east-1.amazonaws.com.attacker.example}, and a suffix test alone would accept
     * {@code attacker.amazonaws.com}. Requiring the authority to be <em>exactly</em> prefix, region and
     * suffix admits nothing that is not the endpoint, and it also refuses a userinfo prefix or a port
     * suffix, neither of which a configured endpoint needs and either of which changes who is
     * addressed.
     *
     * @param host           the lower-cased URL authority
     * @param propertyKey    the configuration key it was bound from
     * @param expectedRegion the region this deployment configured
     * @throws IllegalArgumentException if the authority is not a queue-service endpoint for that region
     */
    private static void requireQueueServiceHost(final String host, final String propertyKey,
            final String expectedRegion) {
        final String region = expectedRegion.strip().toLowerCase(Locale.ROOT);
        for (final String prefix : QUEUE_HOST_PREFIXES) {
            for (final String suffix : QUEUE_HOST_SUFFIXES) {
                if (host.equals(prefix + region + suffix)) {
                    return;
                }
            }
        }
        throw new IllegalArgumentException("property " + propertyKey + " is read as a queue URL whose"
                + " host must be a queue-service endpoint for the region this deployment configured -"
                + " one of the prefixes " + QUEUE_HOST_PREFIXES + " followed by that region and one of"
                + " the suffixes " + QUEUE_HOST_SUFFIXES + ", and nothing else. The configured host is"
                + " not, so the job-submission cards would be sent somewhere this deployment does not"
                + " own");
    }

    /**
     * Requires an account segment to be twelve digits.
     *
     * @param account     the segment to check
     * @param propertyKey the configuration key it was bound from
     * @throws IllegalArgumentException if the segment is not exactly {@value #ACCOUNT_ID_LENGTH} digits
     */
    private static void requireAccountId(final String account, final String propertyKey) {
        boolean allDigits = account.length() == ACCOUNT_ID_LENGTH;
        for (int index = 0; allDigits && index < account.length(); index++) {
            allDigits = account.charAt(index) >= '0' && account.charAt(index) <= '9';
        }
        if (!allDigits) {
            throw new IllegalArgumentException("property " + propertyKey + " must carry an account"
                    + " identifier of exactly " + ACCOUNT_ID_LENGTH + " digits in its account"
                    + " position; the configured value does not, so it does not address the queue"
                    + " service at all");
        }
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
