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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.batch;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.springframework.core.io.WritableResource;

/**
 * Resolves the logical sequential resources used by migrated batch jobs.
 *
 * <p>The legacy jobs named datasets, not host paths. The target preserves those logical names while
 * placing every resource under a deterministic per-job object key in the configured staging store:
 * {@code batch/jobs/<job-name>/<logical-name>}. A job therefore cannot collide with another job that
 * happens to use the same data-definition name, and no deployment-specific bucket, endpoint or host
 * directory enters a job configuration.
 *
 * <p>The production implementation is S3-backed. Tests may supply a filesystem adapter explicitly, but
 * jobs depend only on this contract and never select local storage from a profile or a fallback. S3
 * bucket versioning carries retained-generation history; the logical generation name remains part of the
 * key so operators still see the legacy {@code GnnnnV00} identity.
 *
 * <p>Only one path segment is accepted for each supplied name. Slash, backslash, control characters and
 * every non-ASCII character are refused before a resource is resolved, which makes containment a property
 * of this boundary rather than a convention each caller must remember.
 */
public interface BatchStagingStore {

    /** Root shared by every batch object in the one configured staging bucket. */
    String OBJECT_KEY_ROOT = "batch/jobs";

    /** Maximum encoded size of one S3 object key. */
    int MAX_OBJECT_KEY_BYTES = 1_024;

    /**
     * Resolves one logical resource for reading or writing.
     *
     * <p>Obtaining a resource creates no object. The object is created or replaced only when a caller
     * opens its output stream, and a read of an absent resource fails through the resource contract.
     *
     * @param jobName     the stable Spring Batch job name; must be one safe key segment
     * @param logicalName the legacy dataset or generation name; must be one safe key segment
     * @return a readable and writable resource under the configured staging namespace
     * @throws NullPointerException     if either name is {@code null}
     * @throws IllegalArgumentException if either name is blank or is not a safe key segment
     */
    WritableResource resource(String jobName, String logicalName);

    /**
     * Deletes the current object named by one logical resource.
     *
     * <p>Deletion is idempotent: an absent object reports {@code false}. In a versioned S3 bucket this
     * installs a delete marker rather than erasing retained versions, which is the object-store analogue
     * of scratching the current catalog entry while retaining generation history.
     *
     * @param jobName     the stable Spring Batch job name
     * @param logicalName the legacy dataset or generation name
     * @return {@code true} when a current object existed and was deleted, otherwise {@code false}
     */
    boolean delete(String jobName, String logicalName);

    /**
     * Composes the canonical object key shared by production and test adapters.
     *
     * @param jobName     the stable Spring Batch job name
     * @param logicalName the legacy dataset or generation name
     * @return {@code batch/jobs/<job-name>/<logical-name>}
     * @throws NullPointerException     if either name is {@code null}
     * @throws IllegalArgumentException if a name is unsafe or the resulting key exceeds the S3 limit
     */
    static String objectKey(final String jobName, final String logicalName) {
        final String safeJobName = requireKeySegment(jobName, "jobName");
        final String safeLogicalName = requireKeySegment(logicalName, "logicalName");
        final String key = OBJECT_KEY_ROOT + "/" + safeJobName + "/" + safeLogicalName;
        if (key.getBytes(StandardCharsets.US_ASCII).length > MAX_OBJECT_KEY_BYTES) {
            throw new IllegalArgumentException("the composed batch staging object key exceeds the "
                    + MAX_OBJECT_KEY_BYTES + "-byte S3 limit");
        }
        return key;
    }

    /**
     * Refuses any value that could add a path segment or escape a filesystem test adapter.
     *
     * @param value the candidate segment
     * @param role  the argument role used in the diagnostic
     * @return the unchanged safe segment
     */
    private static String requireKeySegment(final String value, final String role) {
        Objects.requireNonNull(value, role + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(role + " must not be blank");
        }
        if (!isAsciiAlphaNumeric(value.charAt(0))) {
            throw new IllegalArgumentException(role
                    + " must begin with an ASCII letter or digit");
        }
        for (int index = 0; index < value.length(); index++) {
            final char current = value.charAt(index);
            if (!isAsciiAlphaNumeric(current) && current != '.' && current != '-'
                    && current != '_') {
                throw new IllegalArgumentException(role
                        + " may contain only ASCII letters, digits, dots, hyphens and underscores");
            }
        }
        return value;
    }

    /**
     * Tests the deliberately small character vocabulary accepted by an object-key segment.
     *
     * @param value the character to test
     * @return whether it is an ASCII letter or digit
     */
    private static boolean isAsciiAlphaNumeric(final char value) {
        return value >= '0' && value <= '9'
                || value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z';
    }
}