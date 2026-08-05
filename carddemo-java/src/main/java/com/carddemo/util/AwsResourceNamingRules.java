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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one statement of what a region, an object-store bucket, a notification topic and an endpoint
 * override may be, applied when the settings that carry them are bound.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>Four of the five AWS values this module binds were required to be non-blank and nothing more, so
 * a malformed one bound cleanly and surfaced only at the first request that used it. The queue was the
 * exception: it has been held to {@link SqsNamingRules} since the publisher was written, and this class
 * is that treatment extended to the other four. The reason is the same in each case, and it is not
 * tidiness. A batch run that reaches its object-store write, or a job that reaches its completion
 * notification, has already done its work; discovering there that the bucket name was never legal means
 * the work is done and the result is unreachable. A deployment that cannot address its own resources
 * should fail while it is starting, when nothing has happened yet and the diagnostic can name the key
 * an operator has to correct.
 *
 * <h2>What each rule is, and where it comes from</h2>
 *
 * <ul>
 *   <li><strong>Region.</strong> Lower-case, hyphen-separated, ending in a digit group - the shape every
 *       published region name has. Checked as a shape rather than against a list of names, because a
 *       list would refuse a region that comes into existence after this file was written, which is a
 *       worse failure than accepting a shape that happens not to exist yet.</li>
 *   <li><strong>Bucket.</strong> The general-purpose bucket rules: {@value #BUCKET_MIN_LENGTH} to
 *       {@value #BUCKET_MAX_LENGTH} characters from the set below, beginning and ending with a letter or
 *       a digit, no two adjacent dots, not formatted as a dotted-quad address, and none of the reserved
 *       affixes. Every one of these is refused by the service itself, so a value breaking any of them
 *       cannot name a bucket in any account.</li>
 *   <li><strong>Topic.</strong> Either a bare topic name - alphanumerics, hyphens and underscores, up to
 *       {@value #TOPIC_NAME_MAX_LENGTH} characters, with <em>no dot</em>, because this module's topic is
 *       a standard topic and a dot is admitted only in the ordered variant's mandatory suffix - or a
 *       fully-qualified resource identifier naming that same kind of name.</li>
 *   <li><strong>Endpoint override.</strong> An absolute address on plain or secure transport, carrying a
 *       host and nothing that makes it a request rather than a base address. Scheme is restricted
 *       because this value is handed to three client builders: a scheme those clients cannot speak
 *       produces a start-up that succeeds and three clients that fail on first use.</li>
 * </ul>
 *
 * <h2>Why the endpoint rule refuses credentials, a query and a fragment</h2>
 *
 * <p>All three are well-formed parts of a URI and none of them belongs in an endpoint override. Embedded
 * credentials are the sharpest of the three: they are a secret written into a configuration file, in the
 * one field of this module's settings that could carry one, and they would be logged by anything that
 * logs the configured endpoint. A query or a fragment turns a base address into a specific request, and
 * a client builder given one composes every subsequent path onto it, so what looks like a redirection to
 * an emulator becomes a redirection to one endpoint of it. Each is refused with its own diagnostic so an
 * operator is told which part of the value is the problem.
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <p>It does not reach the network, resolve a host, or ask whether a named resource exists. Those are
 * questions about an environment, and this class answers only questions about a value: a name that is
 * well formed may still name nothing, which is what the queue-not-found strategy and the provisioning
 * script between them are for. It also states no default and reads no configuration - every operation is
 * a function of its arguments, so the same rule serves the settings type, a test binding a hostile value
 * and any future consumer without any of them being able to disagree.
 *
 * <p>It does not share the resource-identifier machinery in {@link SqsNamingRules}, which is queue
 * specific: that class recognises three destination forms including a queue URL, requires the queue
 * service in the identifier, and requires the ordered-queue suffix. None of the three applies to a
 * topic, so the small identifier check here is stated separately rather than generalised into something
 * that would have to be conditional on which service was asking.
 *
 * <h2>Diagnostics</h2>
 *
 * <p>Every refusal names the configuration key and what the key requires, and none of them repeats the
 * configured value, for the reason recorded in {@code docs/decision-log.md} DL-041: a diagnostic leaves
 * the process, and a configured value may be one an operator would not choose to publish.
 */
public final class AwsResourceNamingRules {

    /** Shortest legal bucket name. */
    public static final int BUCKET_MIN_LENGTH = 3;

    /** Longest legal bucket name. */
    public static final int BUCKET_MAX_LENGTH = 63;

    /** Longest legal topic name. */
    public static final int TOPIC_NAME_MAX_LENGTH = 256;

    /** Longest region name this rule will consider, well above every published one. */
    public static final int REGION_MAX_LENGTH = 64;

    /**
     * Longest endpoint override this rule will consider.
     *
     * <p>An endpoint override is a host and optionally a port and a path prefix. Anything an order of
     * magnitude longer than that is not one, and bounding the value before it is parsed keeps the cost of
     * refusing a hostile entry proportional to the entry.
     */
    public static final int ENDPOINT_OVERRIDE_MAX_LENGTH = 512;

    /** The only two transports a client of these services speaks. */
    private static final List<String> PERMITTED_ENDPOINT_SCHEMES = List.of("http", "https");

    /** Resource-identifier prefix, which distinguishes a topic identifier from a bare topic name. */
    private static final String ARN_PREFIX = "arn:";

    /** Field separator inside a resource identifier. */
    private static final char ARN_SEPARATOR = ':';

    /** How many colon-separated fields a topic resource identifier has. */
    private static final int TOPIC_ARN_SEGMENT_COUNT = 6;

    /** Index of the partition field inside a resource identifier. */
    private static final int ARN_PARTITION_SEGMENT = 1;

    /** Index of the service field inside a resource identifier. */
    private static final int ARN_SERVICE_SEGMENT = 2;

    /** Index of the region field inside a resource identifier. */
    private static final int ARN_REGION_SEGMENT = 3;

    /** Index of the account field inside a resource identifier. */
    private static final int ARN_ACCOUNT_SEGMENT = 4;

    /** Index of the resource-name field inside a resource identifier. */
    private static final int ARN_RESOURCE_SEGMENT = 5;

    /** The service a topic identifier must name. */
    private static final String TOPIC_ARN_SERVICE = "sns";

    /** The partitions a topic identifier may name. */
    private static final List<String> PERMITTED_ARN_PARTITIONS =
            List.of("aws", "aws-cn", "aws-us-gov");

    /**
     * How much longer than a topic name a topic resource identifier may be.
     *
     * <p>The identifier is the name preceded by a partition, a service, a region and an account, each of
     * which is itself bounded. The allowance is generous rather than exact, because its purpose is to
     * bound the work of refusing an oversized value, and the fields are each checked individually
     * afterwards.
     */
    private static final int ARN_ENVELOPE_ALLOWANCE = 128;

    /** How many digits an account field carries. */
    private static final int ACCOUNT_ID_LENGTH = 12;

    /** Bucket-name affixes the service reserves, so a name carrying one can never be created. */
    private static final List<String> RESERVED_BUCKET_PREFIXES =
            List.of("xn--", "sthree-", "amzn-s3-demo-");

    /** Bucket-name suffixes the service reserves, for access points rather than for buckets. */
    private static final List<String> RESERVED_BUCKET_SUFFIXES =
            List.of("-s3alias", "--ol-s3", ".mrap", "--x-s3", "--table-s3");

    /** How many dot-separated parts a dotted-quad address has, which a bucket name may not resemble. */
    private static final int DOTTED_QUAD_PART_COUNT = 4;

    /** Highest value one part of a dotted-quad address may hold. */
    private static final int DOTTED_QUAD_PART_MAX = 255;

    /** Most digits one part of a dotted-quad address carries. */
    private static final int DOTTED_QUAD_PART_MAX_DIGITS = 3;

    /** Highest port number a URI authority may carry. */
    private static final int MAX_PORT = 65_535;

    /** Fewest hyphen-separated words a region name carries, being area, direction and index. */
    private static final int REGION_MIN_WORD_COUNT = 3;

    /** Longest leading area word a region name carries, such as the four letters of a sovereign area. */
    private static final int REGION_AREA_MAX_LENGTH = 4;

    /** Shortest leading area word a region name carries. */
    private static final int REGION_AREA_MIN_LENGTH = 2;

    /** Most digits the trailing index of a region name carries. */
    private static final int REGION_INDEX_MAX_DIGITS = 2;

    /**
     * Not instantiable: every operation is a pure function of its arguments and the class holds no state
     * beyond its constants.
     */
    private AwsResourceNamingRules() {
        throw new AssertionError("AwsResourceNamingRules is a utility holder and is never instantiated");
    }

    /**
     * Validates a configured region name and returns it unchanged.
     *
     * @param value       the configured region; must not be {@code null}
     * @param propertyKey the configuration key it was bound from, named in every diagnostic
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if the value is not shaped like a region name
     */
    public static String requireRegion(final String value, final String propertyKey) {
        requireBounded(value, propertyKey, REGION_MAX_LENGTH, "region");
        if (!isRegionShaped(value)) {
            throw new IllegalArgumentException("property " + propertyKey
                    + " must be a region name: lower-case words separated by hyphens and ending in a"
                    + " number, such as an area, a direction and an index, but the configured value is"
                    + " not shaped like one");
        }
        return value;
    }

    /**
     * Validates a configured object-store bucket name and returns it unchanged.
     *
     * @param value       the configured bucket name; must not be {@code null}
     * @param propertyKey the configuration key it was bound from, named in every diagnostic
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if the value could not name a bucket in any account
     */
    public static String requireBucketName(final String value, final String propertyKey) {
        requireBounded(value, propertyKey, BUCKET_MAX_LENGTH, "bucket name");
        if (value.length() < BUCKET_MIN_LENGTH) {
            throw new IllegalArgumentException("property " + propertyKey + " must be at least "
                    + BUCKET_MIN_LENGTH + " characters, which the configured value is not");
        }
        for (int position = 0; position < value.length(); position++) {
            if (!isBucketCharacter(value.charAt(position))) {
                throw new IllegalArgumentException("property " + propertyKey
                        + " must hold only lower-case letters, digits, hyphens and dots, but the"
                        + " configured value holds a character outside that set at position "
                        + (position + 1));
            }
        }
        if (!isLowerAlphanumeric(value.charAt(0))
                || !isLowerAlphanumeric(value.charAt(value.length() - 1))) {
            throw new IllegalArgumentException("property " + propertyKey
                    + " must begin and end with a lower-case letter or a digit, which the configured"
                    + " value does not");
        }
        if (value.contains("..")) {
            throw new IllegalArgumentException("property " + propertyKey
                    + " must not hold two adjacent dots, which the configured value does");
        }
        if (isDottedQuad(value)) {
            throw new IllegalArgumentException("property " + propertyKey
                    + " must not be formatted as a dotted-quad address, which the configured value is");
        }
        for (final String reserved : RESERVED_BUCKET_PREFIXES) {
            if (value.startsWith(reserved)) {
                throw new IllegalArgumentException("property " + propertyKey
                        + " must not begin with the reserved prefix " + reserved
                        + ", which the configured value does");
            }
        }
        for (final String reserved : RESERVED_BUCKET_SUFFIXES) {
            if (value.endsWith(reserved)) {
                throw new IllegalArgumentException("property " + propertyKey
                        + " must not end with the reserved suffix " + reserved
                        + ", which the configured value does");
            }
        }
        return value;
    }

    /**
     * Validates a configured notification destination, as a bare name or a resource identifier, and
     * returns it unchanged.
     *
     * <p>The value is returned rather than the extracted name, because the publishing template is given
     * exactly what the deployment configured.
     *
     * @param value       the configured topic name or resource identifier; must not be {@code null}
     * @param propertyKey the configuration key it was bound from, named in every diagnostic
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if the value is neither a legal topic name nor a resource
     *                                  identifier naming one
     */
    public static String requireTopicDestination(final String value, final String propertyKey) {
        requireBounded(value, propertyKey, TOPIC_NAME_MAX_LENGTH + ARN_ENVELOPE_ALLOWANCE, "topic");
        if (value.startsWith(ARN_PREFIX)) {
            requireTopicName(topicArnResourceOf(value, propertyKey), propertyKey);
            return value;
        }
        requireTopicName(value, propertyKey);
        return value;
    }

    /**
     * Validates a configured endpoint override and returns it parsed, which is the form a client builder
     * takes.
     *
     * @param value       the configured endpoint override; must not be {@code null}
     * @param propertyKey the configuration key it was bound from, named in every diagnostic
     * @return the parsed address
     * @throws IllegalArgumentException if the value is not an absolute plain- or secure-transport address
     *                                  carrying a host, or carries credentials, a query or a fragment
     */
    public static URI requireEndpointOverride(final String value, final String propertyKey) {
        requireBounded(value, propertyKey, ENDPOINT_OVERRIDE_MAX_LENGTH, "endpoint override");
        final URI parsed;
        try {
            parsed = new URI(value.strip());
        } catch (final URISyntaxException malformed) {
            // The cause is deliberately NOT attached. Its own message quotes the offending input, and a
            // diagnostic that leaves this process must not carry a configured value with it - the same
            // rule the rest of this class follows, recorded as DL-041.
            throw new IllegalArgumentException("property " + propertyKey
                    + " must be an absolute address, but the configured value could not be read as one");
        }
        final String scheme = parsed.getScheme();
        if (!parsed.isAbsolute() || scheme == null
                || !PERMITTED_ENDPOINT_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("property " + propertyKey
                    + " must name a " + String.join(" or ", PERMITTED_ENDPOINT_SCHEMES)
                    + " address, because those are the only transports these clients speak, but the"
                    + " configured value names neither");
        }
        if (parsed.getHost() == null || parsed.getHost().isEmpty()) {
            throw new IllegalArgumentException("property " + propertyKey
                    + " must carry a host, such as an emulator's edge endpoint, but the configured value"
                    + " carries none");
        }
        if (parsed.getUserInfo() != null) {
            throw new IllegalArgumentException("property " + propertyKey
                    + " must not carry credentials in the address; credentials belong in the credential"
                    + " chain, and an address carrying them would be written into every diagnostic that"
                    + " names the endpoint");
        }
        if (parsed.getQuery() != null) {
            throw new IllegalArgumentException("property " + propertyKey
                    + " must be a base address rather than a request, but the configured value carries a"
                    + " query");
        }
        if (parsed.getFragment() != null) {
            throw new IllegalArgumentException("property " + propertyKey
                    + " must be a base address rather than a request, but the configured value carries a"
                    + " fragment");
        }
        if (parsed.getPort() > MAX_PORT) {
            throw new IllegalArgumentException("property " + propertyKey
                    + " must carry a port within range, which the configured value does not");
        }
        return parsed;
    }

    /**
     * Validates a bare topic name.
     *
     * @param value       the name, extracted from an identifier or configured directly
     * @param propertyKey the configuration key, named in every diagnostic
     */
    private static void requireTopicName(final String value, final String propertyKey) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("property " + propertyKey + " names no topic");
        }
        if (value.length() > TOPIC_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("property " + propertyKey
                    + " must name a topic of at most " + TOPIC_NAME_MAX_LENGTH
                    + " characters, and the configured value names a longer one");
        }
        for (int position = 0; position < value.length(); position++) {
            if (!isTopicCharacter(value.charAt(position))) {
                throw new IllegalArgumentException("property " + propertyKey
                        + " must hold only letters, digits, hyphens and underscores, but the configured"
                        + " value holds a character outside that set at position " + (position + 1));
            }
        }
    }

    /**
     * Extracts and shape-checks the topic name carried by a resource identifier.
     *
     * @param value       the configured identifier, which the caller has established begins with the
     *                    identifier prefix
     * @param propertyKey the configuration key, named in every diagnostic
     * @return the resource field of the identifier
     */
    private static String topicArnResourceOf(final String value, final String propertyKey) {
        final String[] segments = splitOn(value, ARN_SEPARATOR);
        if (segments.length != TOPIC_ARN_SEGMENT_COUNT) {
            throw new IllegalArgumentException("property " + propertyKey
                    + " names a resource identifier with the wrong number of fields; a topic identifier"
                    + " carries a partition, a service, a region, an account and a name");
        }
        if (!PERMITTED_ARN_PARTITIONS.contains(segments[ARN_PARTITION_SEGMENT])) {
            throw new IllegalArgumentException("property " + propertyKey
                    + " names a resource identifier in an unrecognised partition");
        }
        if (!TOPIC_ARN_SERVICE.equals(segments[ARN_SERVICE_SEGMENT])) {
            throw new IllegalArgumentException("property " + propertyKey
                    + " must name a notification topic, but the configured identifier names a resource"
                    + " of another service");
        }
        requireRegion(segments[ARN_REGION_SEGMENT], propertyKey);
        requireAccountId(segments[ARN_ACCOUNT_SEGMENT], propertyKey);
        return segments[ARN_RESOURCE_SEGMENT];
    }

    /**
     * Requires the account field of a resource identifier to be a fixed-width digit run.
     *
     * @param value       the account field
     * @param propertyKey the configuration key, named in every diagnostic
     */
    private static void requireAccountId(final String value, final String propertyKey) {
        if (value.length() != ACCOUNT_ID_LENGTH) {
            throw new IllegalArgumentException("property " + propertyKey
                    + " names a resource identifier whose account field is not " + ACCOUNT_ID_LENGTH
                    + " digits");
        }
        for (int position = 0; position < value.length(); position++) {
            if (!isDigit(value.charAt(position))) {
                throw new IllegalArgumentException("property " + propertyKey
                        + " names a resource identifier whose account field is not all digits");
            }
        }
    }

    /**
     * Refuses a null or over-long value before any other rule inspects it.
     *
     * @param value       the configured value
     * @param propertyKey the configuration key, named in every diagnostic
     * @param maxLength   the inclusive ceiling
     * @param subject     what the value is, named in the diagnostic
     */
    private static void requireBounded(final String value, final String propertyKey,
            final int maxLength, final String subject) {
        if (value == null) {
            throw new IllegalArgumentException("property " + propertyKey + " must state a " + subject);
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException("property " + propertyKey + " must state a " + subject
                    + " of at most " + maxLength + " characters, and the configured value is longer");
        }
    }

    /**
     * Reports whether a value has the shape every published region name has.
     *
     * @param value the configured region
     * @return {@code true} when the value is a hyphen-separated lower-case name ending in a digit group
     */
    private static boolean isRegionShaped(final String value) {
        final String[] words = splitOn(value, '-');
        if (words.length < REGION_MIN_WORD_COUNT) {
            return false;
        }
        final String area = words[0];
        if (area.length() < REGION_AREA_MIN_LENGTH || area.length() > REGION_AREA_MAX_LENGTH
                || !isAllLowerLetters(area)) {
            return false;
        }
        for (int index = 1; index < words.length - 1; index++) {
            if (words[index].isEmpty() || !isAllLowerLetters(words[index])) {
                return false;
            }
        }
        final String index = words[words.length - 1];
        if (index.isEmpty() || index.length() > REGION_INDEX_MAX_DIGITS) {
            return false;
        }
        for (int position = 0; position < index.length(); position++) {
            if (!isDigit(index.charAt(position))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether a value is formatted as a dotted-quad address, which a bucket name may not be.
     *
     * @param value the configured bucket name
     * @return {@code true} when the value is four dot-separated numbers in range
     */
    private static boolean isDottedQuad(final String value) {
        final String[] parts = splitOn(value, '.');
        if (parts.length != DOTTED_QUAD_PART_COUNT) {
            return false;
        }
        for (final String part : parts) {
            if (part.isEmpty() || part.length() > DOTTED_QUAD_PART_MAX_DIGITS) {
                return false;
            }
            int number = 0;
            for (int position = 0; position < part.length(); position++) {
                if (!isDigit(part.charAt(position))) {
                    return false;
                }
                number = number * 10 + (part.charAt(position) - '0');
            }
            if (number > DOTTED_QUAD_PART_MAX) {
                return false;
            }
        }
        return true;
    }

    /**
     * Splits on a single character without a regular expression, keeping empty fields.
     *
     * <p>Written out rather than delegated to {@code String.split}, whose argument is a pattern: a
     * separator such as a dot would have to be escaped, and a rule that silently depends on escaping is
     * a rule that breaks the first time the separator changes. Trailing empty fields are kept, because
     * a value ending in the separator is exactly the malformed input these rules exist to refuse.
     *
     * @param value     the value to split
     * @param separator the separator character
     * @return the fields, in order, including empty ones
     */
    private static String[] splitOn(final String value, final char separator) {
        final List<String> fields = new ArrayList<>();
        int start = 0;
        for (int position = 0; position < value.length(); position++) {
            if (value.charAt(position) == separator) {
                fields.add(value.substring(start, position));
                start = position + 1;
            }
        }
        fields.add(value.substring(start));
        return fields.toArray(new String[0]);
    }

    /**
     * @param  value the word to test
     * @return {@code true} when every character is a lower-case ASCII letter
     */
    private static boolean isAllLowerLetters(final String value) {
        for (int position = 0; position < value.length(); position++) {
            final char character = value.charAt(position);
            if (character < 'a' || character > 'z') {
                return false;
            }
        }
        return true;
    }

    /**
     * @param  character the character to test
     * @return {@code true} for a lower-case letter, a digit, a hyphen or a dot
     */
    private static boolean isBucketCharacter(final char character) {
        return isLowerAlphanumeric(character) || character == '-' || character == '.';
    }

    /**
     * @param  character the character to test
     * @return {@code true} for a letter of either case, a digit, a hyphen or an underscore
     */
    private static boolean isTopicCharacter(final char character) {
        return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z')
                || isDigit(character) || character == '-' || character == '_';
    }

    /**
     * @param  character the character to test
     * @return {@code true} for a lower-case ASCII letter or an ASCII digit
     */
    private static boolean isLowerAlphanumeric(final char character) {
        return (character >= 'a' && character <= 'z') || isDigit(character);
    }

    /**
     * @param  character the character to test
     * @return {@code true} for an ASCII digit, and never for a digit of another numbering system
     */
    private static boolean isDigit(final char character) {
        return character >= '0' && character <= '9';
    }
}
