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
package com.carddemo.batch.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.WritableResource;

/**
 * Verifies the test-only filesystem adapter against the production object-key namespace.
 */
@DisplayName("local filesystem batch staging test adapter")
class LocalFileSystemBatchStagingStoreTest {

    @TempDir
    private Path root;

    @Test
    @DisplayName("resource writes are contained under the production key shape and delete is idempotent")
    void writesAndDeletesAContainedResource() throws Exception {
        final LocalFileSystemBatchStagingStore store =
                new LocalFileSystemBatchStagingStore(this.root);
        final WritableResource resource =
                store.resource("createStatementJob", "AWS.M2.CARDDEMO.STMTFILE.PS");

        try (OutputStream output = resource.getOutputStream()) {
            output.write("record".getBytes(StandardCharsets.US_ASCII));
        }

        final Path expected = this.root.resolve(
                "batch/jobs/createStatementJob/AWS.M2.CARDDEMO.STMTFILE.PS");
        assertThat(store.path("createStatementJob", "AWS.M2.CARDDEMO.STMTFILE.PS"))
                .isEqualTo(expected.toAbsolutePath().normalize());
        assertThat(Files.readString(expected, StandardCharsets.US_ASCII)).isEqualTo("record");
        assertThat(store.delete("createStatementJob", "AWS.M2.CARDDEMO.STMTFILE.PS")).isTrue();
        assertThat(store.delete("createStatementJob", "AWS.M2.CARDDEMO.STMTFILE.PS")).isFalse();
    }
}