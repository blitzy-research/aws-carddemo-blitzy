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
package com.carddemo.batch;

import java.util.Objects;

import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.carddemo.config.AwsProperties;

import io.awspring.cloud.s3.S3Operations;

/**
 * The object-store boundary batch datasets are <strong>read</strong> through.
 *
 * <p>Spring Batch's file readers require a real local resource, while the migration contract requires
 * batch inputs to cross the S3 staging boundary. A reader therefore prefers
 * {@link #stagedInput(String)} when {@link #holds(String)} confirms that the named object exists, and
 * otherwise retains its existing local or classpath fallback.
 *
 * <p><strong>This component does not publish.</strong> Every outbound generation goes through
 * {@code batch/step/StagedGenerationStore}, which is what makes one generation reach the bucket under
 * exactly one canonical {@code base/G…V00} key, inside the generation retention pass, and only once its
 * whole submission has completed. Three publication forms and a publishing writer decorator were once
 * exposed here as well; four job steps used them, and each of those steps put the same generation in the
 * bucket a second time under a second key shape that the retention scan could not see. The forms are
 * removed rather than documented as discouraged, so the invariant is structural: there is no method here
 * to publish through. See {@code docs/decision-log.md} entry DL-212.
 *
 * <p>The bucket is provisioned outside the application. This component never creates, configures or
 * deletes a bucket and implements no retention policy. It only checks for and downloads objects in the
 * bucket bound by {@code carddemo.aws.s3.batch-staging-bucket}.
 */
@Component
public final class BatchStagingArea {

    /** Object-store operations supplied by Spring Cloud AWS. */
    private final S3Operations objectStore;

    /** Pre-provisioned bucket that holds every staged batch object. */
    private final String bucket;

    /**
     * Creates the staging boundary.
     *
     * @param objectStore the object-store operations; must not be {@code null}
     * @param properties the bound AWS resource names; must not be {@code null}
     */
    public BatchStagingArea(final S3Operations objectStore, final AwsProperties properties) {
        this.objectStore = Objects.requireNonNull(objectStore, "objectStore must not be null");
        final AwsProperties.S3 s3 = Objects.requireNonNull(
                Objects.requireNonNull(properties, "properties must not be null").s3(),
                "properties.s3 must not be null");
        this.bucket = requireObjectKey(s3.batchStagingBucket(), "batch staging bucket");
    }

    /**
     * Returns the pre-provisioned staging bucket.
     *
     * @return the configured bucket name
     */
    public String bucket() {
        return this.bucket;
    }

    /**
     * Reports whether the staging bucket currently holds an object.
     *
     * @param objectKey the object key to test
     * @return {@code true} when the object exists
     */
    public boolean holds(final String objectKey) {
        return this.objectStore.objectExists(this.bucket, requireObjectKey(objectKey, "object key"));
    }

    /**
     * Resolves one staged object as a Spring resource suitable for a fixed-width item reader.
     *
     * <p>The caller decides whether absence is an error or whether a local fallback applies by calling
     * {@link #holds(String)} first. No bucket or object is created as a side effect of resolution.
     *
     * @param objectKey the object key to download
     * @return the staged object resource
     */
    public Resource stagedInput(final String objectKey) {
        return this.objectStore.download(this.bucket, requireObjectKey(objectKey, "object key"));
    }

    /**
     * Validates a configured bucket or object name.
     *
     * @param value the candidate value
     * @param role the value's role in an error message
     * @return the unchanged, non-blank value
     */
    private static String requireObjectKey(final String value, final String role) {
        Objects.requireNonNull(value, role + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(role + " must not be blank");
        }
        return value;
    }
}
