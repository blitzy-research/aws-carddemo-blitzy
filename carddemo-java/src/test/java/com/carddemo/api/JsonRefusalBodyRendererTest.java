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
package com.carddemo.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.carddemo.util.RefusalBodyRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the boundary's rendering of a refusal body, which is what lets the security chain answer a
 * refusal in the module's own error contract without importing a transport type.
 */
@DisplayName("the refusal body the security chain renders through the boundary")
class JsonRefusalBodyRendererTest {

    /** The renderer under test, built over a plain mapper exactly as the container builds it. */
    private final RefusalBodyRenderer renderer = new JsonRefusalBodyRenderer(new ObjectMapper());

    @Nested
    @DisplayName("the rendered body is the error contract and nothing more")
    class TheRenderedBody {

        @Test
        @DisplayName("an authentication refusal carries the fixed summary and no other member")
        void anAuthenticationRefusalCarriesTheFixedSummary() {
            final String body =
                    renderer.renderRefusal(RefusalBodyRenderer.AUTHENTICATION_REQUIRED_MESSAGE);

            assertThat(body).contains("\"message\":\"Authentication required\"");
            assertThat(body)
                    .as("a refusal names neither a rule nor an entitlement, and echoes nothing sent")
                    .doesNotContain("stack")
                    .doesNotContain("exception")
                    .doesNotContain("Exception");
        }

        @Test
        @DisplayName("an authorization refusal carries the other fixed summary, so the two conditions "
                + "stay distinguishable to an operator and identical in shape to a client")
        void anAuthorizationRefusalCarriesTheOtherFixedSummary() {
            final String body = renderer.renderRefusal(RefusalBodyRenderer.ACCESS_DENIED_MESSAGE);

            assertThat(body).contains("\"message\":\"Access denied\"");
        }

        @Test
        @DisplayName("the field-error list is always present and empty, so a client never tests it for "
                + "absence")
        void theFieldErrorListIsAlwaysPresentAndEmpty() {
            assertThat(renderer.renderRefusal(RefusalBodyRenderer.ACCESS_DENIED_MESSAGE))
                    .contains("\"fieldErrors\":[]");
        }
    }

    @Nested
    @DisplayName("the collaborator contract")
    class TheCollaboratorContract {

        @Test
        @DisplayName("a renderer cannot be built without a mapper, because a silently absent one would "
                + "produce an empty refusal body that reads like a successful empty answer")
        void aRendererCannotBeBuiltWithoutAMapper() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new JsonRefusalBodyRenderer(null))
                    .withMessageContaining("objectMapper");
        }

        @Test
        @DisplayName("both fixed texts are declared by the neutral contract, so the chain and the "
                + "boundary cannot disagree about the same condition")
        void bothFixedTextsAreDeclaredByTheNeutralContract() {
            assertThat(GlobalExceptionHandler.AUTHENTICATION_REQUIRED_MESSAGE)
                    .isEqualTo(RefusalBodyRenderer.AUTHENTICATION_REQUIRED_MESSAGE);
            assertThat(GlobalExceptionHandler.ACCESS_DENIED_MESSAGE)
                    .isEqualTo(RefusalBodyRenderer.ACCESS_DENIED_MESSAGE);
        }
    }
}
