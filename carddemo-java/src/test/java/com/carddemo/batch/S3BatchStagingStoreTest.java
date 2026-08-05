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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.carddemo.config.AwsProperties;

import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;

/**
 * Verifies the production staging adapter's bucket use, deterministic key composition and idempotent
 * deletion contract.
 */
@DisplayName("S3 batch staging store")
class S3BatchStagingStoreTest {

    private static final String BUCKET = "carddemo-batch-staging";
    private static final String JOB = "interestCalculationJob";
    private static final String LOGICAL_NAME = "AWS.M2.CARDDEMO.TRANSACT.G0042V00";
    private static final String OBJECT_KEY =
            "batch/jobs/interestCalculationJob/AWS.M2.CARDDEMO.TRANSACT.G0042V00";

    private S3Operations objectStore;
    private S3BatchStagingStore stagingStore;

    @BeforeEach
    void createStore() {
        this.objectStore = mock(S3Operations.class);
        this.stagingStore = new S3BatchStagingStore(this.objectStore, settings());
    }

    @Test
    @DisplayName("a logical resource resolves under the job's deterministic object key")
    void resolvesAResource() {
        final S3Resource resource = mock(S3Resource.class);
        when(this.objectStore.createResource(BUCKET, OBJECT_KEY)).thenReturn(resource);

        assertThat(this.stagingStore.resource(JOB, LOGICAL_NAME)).isSameAs(resource);
        verify(this.objectStore).createResource(BUCKET, OBJECT_KEY);
    }

    @Test
    @DisplayName("delete is idempotent and does not issue a delete for an absent object")
    void absentDeleteIsAFalseNoOperation() {
        when(this.objectStore.objectExists(BUCKET, OBJECT_KEY)).thenReturn(false);

        assertThat(this.stagingStore.delete(JOB, LOGICAL_NAME)).isFalse();
        verify(this.objectStore, never()).deleteObject(BUCKET, OBJECT_KEY);
    }

    @Test
    @DisplayName("delete removes the current object when one exists")
    void existingObjectIsDeleted() {
        when(this.objectStore.objectExists(BUCKET, OBJECT_KEY)).thenReturn(true);

        assertThat(this.stagingStore.delete(JOB, LOGICAL_NAME)).isTrue();
        verify(this.objectStore).deleteObject(BUCKET, OBJECT_KEY);
    }

    @Test
    @DisplayName("unsafe path segments are refused before S3 is addressed")
    void unsafeSegmentsAreRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> this.stagingStore.resource("../job", LOGICAL_NAME))
                .withMessageContaining("jobName");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> this.stagingStore.resource(JOB, "../secret"))
                .withMessageContaining("logicalName");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BatchStagingStore.objectKey(" job", LOGICAL_NAME))
                .withMessageContaining("begin");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BatchStagingStore.objectKey(JOB, " "))
                .withMessageContaining("blank");
    }

    @Test
    @DisplayName("constructor collaborators are mandatory")
    void constructorCollaboratorsAreMandatory() {
        assertThatNullPointerException()
                .isThrownBy(() -> new S3BatchStagingStore(null, settings()))
                .withMessageContaining("objectStore");
        assertThatNullPointerException()
                .isThrownBy(() -> new S3BatchStagingStore(this.objectStore, null))
                .withMessageContaining("awsProperties");
    }

    private static AwsProperties settings() {
        return new AwsProperties("us-east-1", null,
                new AwsProperties.S3(BUCKET),
                new AwsProperties.Sqs("carddemo-jobs.fifo", "carddemo-job-submission"),
                new AwsProperties.Sns("carddemo-job-notifications"));
    }
}