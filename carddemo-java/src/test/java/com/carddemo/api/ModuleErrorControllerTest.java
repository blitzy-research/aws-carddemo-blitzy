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

import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.exception.AbendException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit specification for {@link ModuleErrorController}, the module's answer on the container's error
 * dispatch.
 *
 * <p><strong>What is being asserted, and why each part is load-bearing.</strong> Four request
 * conditions - an unsupported method, an unreadable media type, an unsatisfiable {@code Accept} header
 * and an unmatched path - are refused by the framework before a handler exists, so no controller advice
 * can answer them. They arrive here instead, and before this class existed they arrived at the
 * framework's own error controller, which published a differently shaped document; and before the
 * error-dispatch permit in {@code SecurityConfig} existed they never arrived at all, because the
 * authorization rules re-ran over a request that no longer carried an authentication and answered
 * {@code 401}. The tests below pin the three properties that together undo that: the recorded status is
 * the status answered, the body is the module's own envelope, and no detail of the condition is echoed.
 *
 * <p>The summary text itself is deliberately <em>not</em> restated here. It is read from
 * {@link GlobalExceptionHandler#neutralSummaryFor}, which is the same method the controller reads, so
 * these tests assert that the two agree rather than asserting a literal that could drift from both.
 *
 * @since 1.0.0
 */
@DisplayName("ModuleErrorController - the error dispatch answered in the module's own envelope")
class ModuleErrorControllerTest {

    /** The controller under test. It holds no collaborator. */
    private final ModuleErrorController controller = new ModuleErrorController();

    @Nested
    @DisplayName("the status a condition was refused with is the status answered")
    class TheRecordedStatusIsAnswered {

        @ParameterizedTest(name = "a recorded {0} is answered as {0}")
        @CsvSource({"405", "415", "406", "404", "400", "409", "413", "500", "503"})
        @DisplayName("every recorded status crosses unchanged, which is the whole of the defect: each "
                + "was previously overwritten with 401 by the authorization rules")
        void everyRecordedStatusCrossesUnchanged(final int recorded) {
            final ResponseEntity<ErrorResponse> answer = controller.handleErrorDispatch(
                    dispatchRecording(recorded));

            assertThat(answer.getStatusCode().value()).isEqualTo(recorded);
            assertThat(answer.getBody()).isNotNull();
            assertThat(answer.getBody().message())
                    .as("the summary is the one the boundary handler publishes for the same status, "
                            + "read from it rather than restated here")
                    .isEqualTo(GlobalExceptionHandler.neutralSummaryFor(
                            HttpStatus.valueOf(recorded)));
        }

        @Test
        @DisplayName("a status the framework has no constant for is still answered as itself, so a "
                + "container-recorded value is never silently reshaped")
        void anUnnamedStatusIsStillAnsweredAsItself() {
            final ResponseEntity<ErrorResponse> answer = controller.handleErrorDispatch(
                    dispatchRecording(499));

            assertThat(answer.getStatusCode().value()).isEqualTo(499);
            assertThat(answer.getBody()).isNotNull();
        }

        @Test
        @DisplayName("a request carrying no recorded status - which a client addressing this path "
                + "directly produces - is answered as the terminal server failure")
        void aRequestWithNoRecordedStatusIsAnsweredAsTheTerminalFailure() {
            final ResponseEntity<ErrorResponse> answer =
                    controller.handleErrorDispatch(new MockHttpServletRequest());

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(answer.getBody()).isNotNull();
            assertThat(answer.getBody().message())
                    .as("a response cannot honestly carry a status the request never had")
                    .isEqualTo(AbendException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("a recorded value outside the range of an HTTP status is refused rather than "
                + "answered, because a response cannot carry one")
        void aRecordedValueOutsideTheStatusRangeIsRefused() {
            for (final Object unusable : new Object[] {999, 0, -1, 42, "405", Boolean.TRUE}) {
                final MockHttpServletRequest request = new MockHttpServletRequest();
                request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, unusable);

                assertThat(controller.handleErrorDispatch(request).getStatusCode())
                        .describedAs("a recorded %s is not a status", unusable)
                        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }
    }

    @Nested
    @DisplayName("the body is the module's envelope and discloses nothing about the condition")
    class TheBodyDisclosesNothing {

        @Test
        @DisplayName("carries the module's two-component envelope rather than the framework's "
                + "timestamp-status-error document")
        void carriesTheModulesOwnEnvelope() {
            final ErrorResponse body =
                    controller.handleErrorDispatch(dispatchRecording(404)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.fieldErrors())
                    .as("always present and empty rather than absent, so a client never tests for null")
                    .isEmpty();
            assertThat(body.focusScreenFieldId())
                    .as("no screen field is named, because a refused request reached no screen")
                    .isNull();
        }

        @Test
        @DisplayName("echoes neither the requested path, the exception, nor the framework's message, "
                + "even when the container recorded all three")
        void echoesNothingTheContainerRecorded() {
            final MockHttpServletRequest request = dispatchRecording(404);
            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI,
                    "/api/secret/path?token=canary-token-value");
            request.setAttribute(RequestDispatcher.ERROR_MESSAGE,
                    "jdbc:postgresql://host/db?password=canary-secret");
            request.setAttribute(RequestDispatcher.ERROR_EXCEPTION,
                    new IllegalStateException("canary-secret in a message"));

            final ErrorResponse body = controller.handleErrorDispatch(request).getBody();

            assertThat(body).isNotNull();
            assertThat(body.message())
                    .as("the summary is derived from the status alone, which is precisely why it cannot "
                            + "echo a path, a message, a class name or a value")
                    .doesNotContain("canary-token-value")
                    .doesNotContain("canary-secret")
                    .doesNotContain("/api/secret/path")
                    .doesNotContain("IllegalStateException")
                    .doesNotContain("jdbc:");
        }
    }

    @Nested
    @DisplayName("the registration, which is what displaces the framework's own controller")
    class TheRegistration {

        @Test
        @DisplayName("is an error controller, which is the type the framework resolves the error path "
                + "by and the type whose presence withdraws the framework's own")
        void isTheTypeThatDisplacesTheFrameworkController() {
            assertThat(controller).isInstanceOf(ErrorController.class);
            assertThat(ModuleErrorController.class.getAnnotation(RestController.class))
                    .as("a response body rather than a view name")
                    .isNotNull();
            assertThat(Modifier.isFinal(ModuleErrorController.class.getModifiers()))
                    .as("nothing subclasses it, so the answer cannot be changed by extension")
                    .isTrue();
        }

        @Test
        @DisplayName("maps the framework's own error-path expression rather than a restated literal, so "
                + "a deployment that moves the path is still answered by this class")
        void mapsTheFrameworkErrorPathExpression() throws Exception {
            final RequestMapping mapping = ModuleErrorController.class
                    .getDeclaredMethod("handleErrorDispatch",
                            jakarta.servlet.http.HttpServletRequest.class)
                    .getAnnotation(RequestMapping.class);

            assertThat(mapping).isNotNull();
            assertThat(mapping.path()).containsExactly(ModuleErrorController.ERROR_PATH_EXPRESSION);
            assertThat(ModuleErrorController.ERROR_PATH_EXPRESSION)
                    .as("both property names the framework consults, in its order, with the "
                            + "conventional path as the final fallback")
                    .isEqualTo("${server.error.path:${error.path:/error}}");
            assertThat(mapping.method())
                    .as("every method is mapped deliberately: the dispatch reproduces the original "
                            + "request's method, so a wrong-method refusal arrives as that same method")
                    .isEmpty();
            assertThat(mapping.produces()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
        }

        @Test
        @DisplayName("is hidden from the published interface description, which describes the estate's "
                + "transactions and not a servlet mechanism")
        void isHiddenFromThePublishedDescription() {
            assertThat(ModuleErrorController.class.getAnnotation(Hidden.class)).isNotNull();
        }
    }

    /**
     * Builds an error dispatch recording one status, as the container populates it.
     *
     * @param recorded the status the container refused the original request with
     * @return the request the dispatch presents
     */
    private static MockHttpServletRequest dispatchRecording(final int recorded) {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, recorded);
        return request;
    }
}
