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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.config.AwsProperties;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the S3-backed staging boundary batch datasets are <strong>read</strong> through.
 *
 * <p>The adapter deliberately keeps Spring Batch readers on local resources because their execution
 * state requires {@link java.io.File} access, so a staged read resolves through {@link S3Operations}
 * and a job's own fallback covers absence. Both are pinned here, together with the bucket the
 * component is configured with, because substituting a hardcoded destination is the failure this
 * boundary exists to make impossible.
 *
 * <p><strong>There is no publication surface to test.</strong> Three publication forms and a
 * publishing-writer decorator were removed from this component: four job steps used them, and each of
 * those steps put its generation in the bucket a second time under a second key shape the generation
 * retention scan could not see. Publication now belongs entirely to
 * {@code batch/step/StagedGenerationStore}, whose own suite covers it, and the census below asserts
 * that nothing here uploads at all - which is what makes the single-publisher invariant structural
 * rather than conventional. See {@code docs/decision-log.md} entry DL-212.
 */
@DisplayName("BatchStagingArea - the S3 boundary staged batch datasets are read through")
final class BatchStagingAreaTest {

    /** Configured bucket used to prove that no operation substitutes a hardcoded destination. */
    private static final String BUCKET = "unit-test-batch-staging";

    /** Explicit key used by read and write assertions. */
    private static final String OBJECT_KEY = "jobs/42/output.dat";

    /**
     * Builds valid settings with a nominated bucket.
     *
     * @param bucket configured staging bucket
     * @return settings accepted by the staging adapter
     */
    private static AwsProperties properties(final String bucket) {
        return new AwsProperties("us-west-2", null,
                new AwsProperties.S3(bucket),
                new AwsProperties.Sqs("carddemo-jobs.fifo", "carddemo-jobs"),
                new AwsProperties.Sns("carddemo-job-notifications"));
    }

    /**
     * Builds an adapter over a mocked object store.
     *
     * @param objectStore object-store collaborator
     * @return adapter under test
     */
    private static BatchStagingArea stagingArea(final S3Operations objectStore) {
        return new BatchStagingArea(objectStore, properties(BUCKET));
    }

    @Nested
    @DisplayName("Configuration and staged reads")
    final class ConfigurationAndReads {

        @Test
        @DisplayName("the configured bucket is exposed unchanged")
        void configuredBucketIsExposedUnchanged() {
            final BatchStagingArea staging = stagingArea(mock(S3Operations.class));

            assertThat(staging.bucket()).isEqualTo(BUCKET);
        }

        @Test
        @DisplayName("existence checks and downloads always address the configured bucket")
        void existenceChecksAndDownloadsUseTheConfiguredBucket() {
            final S3Operations objectStore = mock(S3Operations.class);
            final S3Resource resource = mock(S3Resource.class);
            when(objectStore.objectExists(BUCKET, OBJECT_KEY)).thenReturn(true);
            when(objectStore.download(BUCKET, OBJECT_KEY)).thenReturn(resource);
            final BatchStagingArea staging = stagingArea(objectStore);

            assertThat(staging.holds(OBJECT_KEY)).isTrue();
            assertThat(staging.stagedInput(OBJECT_KEY)).isSameAs(resource);

            verify(objectStore).objectExists(BUCKET, OBJECT_KEY);
            verify(objectStore).download(BUCKET, OBJECT_KEY);
        }
    }

    @Nested
    @DisplayName("Boundary validation")
    final class BoundaryValidation {

        @Test
        @DisplayName("the constructor refuses missing collaborators and bucket settings")
        void constructorRefusesMissingCollaboratorsAndBucketSettings() {
            final S3Operations objectStore = mock(S3Operations.class);

            assertThatNullPointerException()
                    .isThrownBy(() -> new BatchStagingArea(null, properties(BUCKET)))
                    .withMessage("objectStore must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> new BatchStagingArea(objectStore, null))
                    .withMessage("properties must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> new BatchStagingArea(objectStore,
                            new AwsProperties("us-west-2", null, null,
                                    new AwsProperties.Sqs("carddemo-jobs.fifo", "carddemo-jobs"),
                                    new AwsProperties.Sns("carddemo-job-notifications"))))
                    .withMessage("properties.s3 must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> new BatchStagingArea(objectStore, properties(null)))
                    .withMessage("batch staging bucket must not be null");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new BatchStagingArea(objectStore, properties(" \t")))
                    .withMessage("batch staging bucket must not be blank");
        }

        @Test
        @DisplayName("read and write operations refuse missing or blank object keys")
        void operationsRefuseMissingOrBlankObjectKeys() {
            final BatchStagingArea staging = stagingArea(mock(S3Operations.class));

            assertThatNullPointerException().isThrownBy(() -> staging.holds(null))
                    .withMessage("object key must not be null");
            assertThatIllegalArgumentException().isThrownBy(() -> staging.holds("  "))
                    .withMessage("object key must not be blank");
            assertThatNullPointerException().isThrownBy(() -> staging.stagedInput(null))
                    .withMessage("object key must not be null");
            assertThatIllegalArgumentException().isThrownBy(() -> staging.stagedInput("\n"))
                    .withMessage("object key must not be blank");
        }

        @Test
        @DisplayName("reading is the whole of the published surface: nothing here can upload, so a "
                + "second key shape cannot be reintroduced by a caller")
        void nothingOnThisBoundaryCanUpload() {
            final S3Operations objectStore = mock(S3Operations.class);
            when(objectStore.objectExists(BUCKET, OBJECT_KEY)).thenReturn(true);
            when(objectStore.download(BUCKET, OBJECT_KEY)).thenReturn(mock(S3Resource.class));
            final BatchStagingArea staging = stagingArea(objectStore);

            // Every published method of the component, exercised. Stated as a census over the declared
            // methods rather than as an absence of named methods, so a publication form added under any
            // name at all fails this rather than slipping past a list of spellings.
            staging.bucket();
            staging.holds(OBJECT_KEY);
            staging.stagedInput(OBJECT_KEY);

            // Members the author did not write are excluded before the census is taken. Coverage
            // instrumentation adds a probe accessor to every class it rewrites, and this class is
            // rewritten whenever the build measures coverage, so a census over the raw reflection result
            // reports a method that no source line declares. Both exclusions are needed: the compiler
            // flags what it generates as synthetic, while the probe accessor is a bootstrap method that
            // is not flagged and is recognisable only by the dollar prefix reserved for generated names.
            // Neither exclusion can hide a real publication method, because an author-written method is
            // never synthetic and never carries that prefix.
            final List<String> declaredByTheAuthor = Stream.of(BatchStagingArea.class.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic())
                    .map(Method::getName)
                    .filter(name -> !name.startsWith("$"))
                    .toList();

            assertThat(declaredByTheAuthor)
                    .as("the census must see the real surface, not an empty list, or it proves nothing")
                    .isNotEmpty();
            assertThat(declaredByTheAuthor)
                    .as("the declared surface is the three read-side operations and the key guard, and"
                            + " nothing more: publication belongs to StagedGenerationStore (DL-212)")
                    .allSatisfy(name -> assertThat(name)
                            .isIn("bucket", "holds", "stagedInput", "requireObjectKey"));

            verify(objectStore, never()).upload(any(), any(), any(InputStream.class));
            verify(objectStore, never()).upload(any(), any(), any(InputStream.class), any());
        }
    }
}
