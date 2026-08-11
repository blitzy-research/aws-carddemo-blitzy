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
package com.carddemo.util;

/**
 * The neutral contract by which a refusal raised at the filter boundary acquires the same body shape as
 * one raised at the dispatch boundary.
 *
 * <p><strong>Why the shape is not written where the refusal happens.</strong> Two refusals exist for
 * different reasons and in different places: the security chain refuses a request carrying no usable
 * credential or no entitlement, before any handler is reached, and the boundary's own failure handler
 * refuses one a handler rejected. A client must not be able to tell the two apart by the shape of what
 * came back, so both must render the one error contract - a transport type owned by the boundary package.
 * The security chain may not import that package, so it names this interface instead and the boundary
 * supplies the implementation, which keeps the shape in the one home that owns it.
 *
 * <p>The two texts are here for the same reason: both refusals are answered with a fixed summary naming
 * neither the rule that refused nor the entitlement that would have satisfied it, and both sites must use
 * the same words for the same condition.
 *
 * <p>An implementation renders a body and nothing else: it sets no status, writes to no stream, reads no
 * request and consults no security context. Deciding the status, and writing the bytes, belongs to
 * whichever boundary is refusing.
 *
 * @since 1.0.0
 */
public interface RefusalBodyRenderer {

    /**
     * Summary returned when no credential was presented, or the one presented did not verify.
     *
     * <p>Deliberately uninformative: it says that a credential is required and not which check failed,
     * because distinguishing "no token" from "an invalid token" tells a caller how close a forgery came.
     */
    String AUTHENTICATION_REQUIRED_MESSAGE = "Authentication required";

    /**
     * Summary returned when an established identity does not entitle the request.
     *
     * <p>The distinction from {@link #AUTHENTICATION_REQUIRED_MESSAGE} is the one the estate drew: a
     * principal exists and is not entitled, which is the sign-on program routing a non-administrator away
     * from the administrative menu rather than refusing the sign-on itself.
     */
    String ACCESS_DENIED_MESSAGE = "Access denied";

    /**
     * Renders one refusal body as the JSON text of the module's error contract.
     *
     * @param message the summary to carry, which must already be a fixed refusal text rather than a
     *                framework message, a field value or anything derived from the request
     * @return the JSON document to write as the response body, never {@code null} and never empty
     * @throws IllegalStateException if the body cannot be rendered, which is a wiring fault rather than a
     *                               condition a caller can handle
     */
    String renderRefusal(String message);
}
