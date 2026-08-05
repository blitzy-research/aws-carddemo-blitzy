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
package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * Holds the framework-level SQS tracing switch on in every shipped profile document.
 */
@DisplayName("SQS observation configuration")
class SqsObservationConfigurationTest {

    private static final String PROPERTY = "spring.cloud.aws.sqs.observation-enabled";

    private static final List<Path> DOCUMENTS = List.of(
            Path.of("src/main/resources/application.yml"),
            Path.of("src/main/resources/application-local.yml"),
            Path.of("src/main/resources/application-test.yml"),
            Path.of("src/main/resources/application-prod.yml"),
            Path.of("src/test/resources/application-test.yml"));

    @Test
    @DisplayName("enables framework message propagation in the baseline and every overlay")
    void enablesObservationInEveryDocument() throws IOException {
        final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (final Path document : DOCUMENTS) {
            final FileSystemResource resource = new FileSystemResource(document);
            final List<PropertySource<?>> sources =
                    loader.load(resource.getDescription(), resource);
            assertThat(sources)
                    .as("%s must contain a YAML document", document)
                    .isNotEmpty();
            assertThat(sources.stream()
                    .map(source -> source.getProperty(PROPERTY))
                    .filter(value -> value != null)
                    .toList())
                    .as("%s must explicitly enable %s", document, PROPERTY)
                    .containsExactly(Boolean.TRUE);
        }
    }
}
