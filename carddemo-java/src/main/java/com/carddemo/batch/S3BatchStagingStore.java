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

import java.util.Objects;

import org.springframework.core.io.WritableResource;
import org.springframework.stereotype.Component;

import com.carddemo.config.AwsProperties;

import io.awspring.cloud.s3.S3Operations;

/**
 * Production batch staging over the configured versioned S3 bucket.
 *
 * <p>The AWS auto-configuration owns the client and {@link AwsProperties} owns the bucket name. This
 * adapter composes only object keys and asks {@link S3Operations} for resources; it creates no bucket,
 * client or marker object and therefore preserves the bootstrap contract that resources exist before the
 * application starts.
 */
@Component
public final class S3BatchStagingStore implements BatchStagingStore {

    /** Object-store operations supplied by Spring Cloud AWS. */
    private final S3Operations objectStore;

    /** The one staging bucket shared by every batch job. */
    private final String bucket;

    /**
     * Creates the adapter.
     *
     * @param objectStore   the configured S3 operations; must not be {@code null}
     * @param awsProperties the validated AWS settings; must not be {@code null}
     */
    public S3BatchStagingStore(final S3Operations objectStore,
            final AwsProperties awsProperties) {
        this.objectStore = Objects.requireNonNull(objectStore, "objectStore must not be null");
        final AwsProperties properties =
                Objects.requireNonNull(awsProperties, "awsProperties must not be null");
        this.bucket = Objects.requireNonNull(properties.s3(),
                "awsProperties.s3 must not be null").batchStagingBucket();
    }

    @Override
    public WritableResource resource(final String jobName, final String logicalName) {
        return this.objectStore.createResource(this.bucket,
                BatchStagingStore.objectKey(jobName, logicalName));
    }

    @Override
    public boolean delete(final String jobName, final String logicalName) {
        final String objectKey = BatchStagingStore.objectKey(jobName, logicalName);
        if (!this.objectStore.objectExists(this.bucket, objectKey)) {
            return false;
        }
        this.objectStore.deleteObject(this.bucket, objectKey);
        return true;
    }
}