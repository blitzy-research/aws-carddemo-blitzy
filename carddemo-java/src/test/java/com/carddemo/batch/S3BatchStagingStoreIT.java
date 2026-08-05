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
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.WritableResource;
import org.springframework.test.context.ActiveProfiles;

import com.carddemo.config.AwsConfig;
import com.carddemo.config.AwsProperties;
import com.carddemo.support.AbstractLocalStackIT;

import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.CredentialsProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.RegionProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Proves the production staging adapter against a real S3-compatible service rather than a mock.
 */
@SpringBootTest(classes = S3BatchStagingStoreIT.Context.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("S3 batch staging store, against LocalStack")
class S3BatchStagingStoreIT extends AbstractLocalStackIT {

    private static final String JOB = "s3BatchStagingStoreIT";
    private static final String LOGICAL_NAME = "AWS.M2.CARDDEMO.TEST.G0001V00";
    private static final String CONTENT = "fixed-width-test-record";

    @Autowired
    private BatchStagingStore stagingStore;

    @Autowired
    private S3Operations objectStore;

    @Autowired
    private AwsProperties awsProperties;

    @BeforeEach
    void ensureBucketExists() {
        final String bucket = this.awsProperties.s3().batchStagingBucket();
        if (!this.objectStore.bucketExists(bucket)) {
            this.objectStore.createBucket(bucket);
        }
        this.stagingStore.delete(JOB, LOGICAL_NAME);
    }

    @Test
    @DisplayName("a resource is written, read and deleted under its deterministic per-job key")
    void writesReadsAndDeletesTheObject() throws Exception {
        final WritableResource resource = this.stagingStore.resource(JOB, LOGICAL_NAME);
        try (OutputStream output = resource.getOutputStream()) {
            output.write(CONTENT.getBytes(StandardCharsets.US_ASCII));
        }

        assertThat(resource.exists()).isTrue();
        try (InputStream input = resource.getInputStream()) {
            assertThat(new String(input.readAllBytes(), StandardCharsets.US_ASCII))
                    .isEqualTo(CONTENT);
        }
        assertThat(this.objectStore.listObjects(
                this.awsProperties.s3().batchStagingBucket(), "batch/jobs/" + JOB + "/"))
                .extracting(candidate -> candidate.getLocation().getObject())
                .containsExactly(BatchStagingStore.objectKey(JOB, LOGICAL_NAME));
        assertThat(this.stagingStore.delete(JOB, LOGICAL_NAME)).isTrue();
        assertThat(resource.exists()).isFalse();
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({CredentialsProviderAutoConfiguration.class,
            RegionProviderAutoConfiguration.class, AwsAutoConfiguration.class,
            S3AutoConfiguration.class})
    @Import({AwsConfig.class, S3BatchStagingStore.class})
    static class Context {

        Context() {
        }

        @Bean
        SnsClient notificationsClient() {
            return mock(SnsClient.class);
        }
    }
}