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
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The module's own answer on the container's error dispatch: the same {@link ErrorResponse} envelope
 * every other refusal uses, at the status the condition actually was.
 *
 * <h2>The defect this exists for</h2>
 *
 * <p>Not every request failure reaches an exception handler. A request whose method the route does not
 * support, whose media type no converter reads, whose {@code Accept} header no representation
 * satisfies, or whose path matches no route at all is refused by the framework <em>before</em> a
 * handler is resolved. There is no handler method for a controller advice to be selected against, so
 * the advice is never consulted; the framework sets the status through the servlet error mechanism and
 * the container re-dispatches the request to this path.
 *
 * <p>Two things then went wrong and this class, together with the error-dispatch permit in
 * {@code SecurityConfig}, fixes both. Without a permit for that dispatch the authorization rules run
 * again over a request that no longer carries an authentication - the bearer filter is a
 * once-per-request filter and does not run on an error dispatch - so the closing catch-all answered
 * {@code 401 Authentication required} and overwrote the real status. Every one of those four
 * conditions was reported to the caller as an authentication failure, including on the anonymous
 * sign-on route, which sent a client with a wrong content type looking for a credential problem.
 * And once the dispatch is permitted, what answers by default is the framework's own error controller,
 * whose body is {@code {"timestamp":…,"status":…,"error":…}} - a second, differently shaped error
 * document alongside the module's, published by no contract.
 *
 * <h2>What it publishes</h2>
 *
 * <p>The status the container recorded for the condition, and one neutral summary chosen from that
 * status by {@link GlobalExceptionHandler#neutralSummaryFor(HttpStatusCode)} - the same method the
 * advice itself uses, read rather than restated, so the two paths cannot come to call the same status
 * by different names. The body is {@link ErrorResponse}, so a client parses one document shape for
 * every refusal.
 *
 * <p><strong>Nothing about the failure is disclosed.</strong> The request attributes the container
 * populates include the exception and the requested path; neither is read. A summary derived from the
 * status alone cannot echo a path, a message, a stack frame or a value, and that is the whole reason
 * the derivation is by status rather than by exception.
 *
 * <h2>What it does not change</h2>
 *
 * <p>A request that a client sends to this path directly is an ordinary request dispatch, not an error
 * dispatch, so it is still authorized by the closing catch-all rule and still needs a credential. It
 * carries no error attributes either, so it is answered as the terminal server failure - which is the
 * honest answer to a caller asking a diagnostic endpoint for an error that did not happen.
 *
 * <p>It is hidden from the published interface description. The description exists to describe the
 * operations the estate's transactions became, and this path is a servlet mechanism rather than one of
 * them; publishing it would add a nineteenth path that answers no transaction.
 *
 * <p>Provenance: no legacy antecedent. The estate's transaction manager answered an unmapped
 * transaction identifier with its own terminal message, which this module reproduces at the routes it
 * publishes; the error dispatch is a servlet-container concept the estate had no equivalent of. Legacy
 * estate read as read-only reference at checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec},
 * upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19; no legacy source text
 * is reproduced.
 *
 * <p>Stateless, holding one static logger and nothing else, so the single instance the framework
 * creates serves every request concurrently.
 *
 * @since 1.0.0
 */
@Hidden
@RestController
public final class ModuleErrorController implements ErrorController {

    /**
     * The path the container re-dispatches a failed request to when no deployment moves it.
     *
     * <p>Published so that a caller reasoning about the boundary - the authorization rules, or a test
     * asserting that a client addressing this path directly is still authenticated - reads the value from
     * here rather than restating it.
     */
    public static final String ERROR_PATH_DEFAULT = "/error";

    /**
     * The path the container re-dispatches a failed request to.
     *
     * <p>Bound from the framework's own two property names, in the framework's own order, with
     * {@link #ERROR_PATH_DEFAULT} as the final fallback. Restating the literal alone would answer the
     * wrong path for a deployment that moved it, and would leave the framework's controller answering the
     * real one.
     */
    public static final String ERROR_PATH_EXPRESSION =
            "${server.error.path:${error.path:" + ERROR_PATH_DEFAULT + "}}";

    /** The diagnostic channel, recording the status and never the condition's own detail. */
    private static final Logger LOG = LoggerFactory.getLogger(ModuleErrorController.class);

    /** Creates the controller. It holds no collaborator, because it reads only the recorded status. */
    public ModuleErrorController() {
    }

    /**
     * Answers the error dispatch in the module's envelope, at the status the condition actually was.
     *
     * <p>Every method is mapped, deliberately: the dispatch reproduces the original request's method, so
     * a wrong-method refusal arrives here as that same wrong method and a mapping narrowed to a verb
     * would fail to answer exactly the condition this exists for. The response is JSON for the same
     * reason the rest of the boundary is - a client that could not negotiate a representation for the
     * operation still has to be able to read why.
     *
     * @param request the error dispatch, carrying the status the container recorded
     * @return the recorded status with the module's neutral summary for it, or {@code 500} with the
     *         terminal summary when no status was recorded
     */
    @RequestMapping(path = ERROR_PATH_EXPRESSION, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ErrorResponse> handleErrorDispatch(final HttpServletRequest request) {
        final HttpStatusCode status = recordedStatusOf(request);
        LOG.debug("Error dispatch answered in the module envelope: status={}", status.value());
        return ResponseEntity.status(status)
                .body(new ErrorResponse(GlobalExceptionHandler.neutralSummaryFor(status)));
    }

    /**
     * Reads the status the container recorded for the condition being dispatched.
     *
     * <p>Three answers are separated because they mean different things. A recorded value the framework
     * resolves is used as it is, including a status it has no constant for. A recorded value that is not
     * a usable HTTP status - which a directly-addressed request produces, since it carries no error
     * attributes at all - is answered as the terminal server failure rather than passed on, because a
     * response cannot honestly carry a status the request never had. An absent attribute is the same
     * case and is answered the same way.
     *
     * @param request the error dispatch
     * @return the status to answer with, never {@code null}
     */
    private static HttpStatusCode recordedStatusOf(final HttpServletRequest request) {
        final Object recorded = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (recorded instanceof Integer statusCode && statusCode >= MINIMUM_HTTP_STATUS
                && statusCode <= MAXIMUM_HTTP_STATUS) {
            return HttpStatusCode.valueOf(statusCode);
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /** Lowest value an HTTP status code may take, which the recorded attribute must reach to be used. */
    private static final int MINIMUM_HTTP_STATUS = 100;

    /** Highest value an HTTP status code may take. */
    private static final int MAXIMUM_HTTP_STATUS = 599;
}
