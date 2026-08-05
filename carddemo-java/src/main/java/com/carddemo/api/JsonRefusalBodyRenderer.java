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
import com.carddemo.util.RefusalBodyRenderer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Renders a refusal body in the boundary's own error contract, so that a refusal decided before any
 * handler is reached is indistinguishable from one decided by a handler.
 *
 * <p>This is the boundary half of {@link RefusalBodyRenderer}. The security chain refuses a request that
 * carries no usable credential or no entitlement and needs a body for it; the shape of that body is
 * {@link ErrorResponse}, which is a transport type this package owns. Rather than let the configuration
 * package reach up into this one - or, worse, hand-assemble a second copy of the shape - the chain names
 * the neutral contract and this component supplies the rendering. One shape, one home, no upward
 * dependency.
 *
 * <p>The summary is carried through exactly as supplied and nothing is added: no field error, no focus
 * hint, no status, no timestamp, no path, no framework message and nothing derived from the request. Both
 * refusal texts are fixed literals published by the contract, so a rendered body can never echo a value a
 * caller sent.
 *
 * <p>Stateless and immutable: one final collaborator, no mutable field and no static state, so the
 * singleton is safe for unsynchronised concurrent use. The mapper is the container's own, which is what
 * makes the rendered bytes identical to the ones the dispatch boundary produces for the same record -
 * including the module's omit-absent-properties policy, so an absent field-error list and focus hint do
 * not appear.
 *
 * @since 1.0.0
 */
@Component
public final class JsonRefusalBodyRenderer implements RefusalBodyRenderer {

    /** The container's configured mapper, so a refusal serialises exactly as every other response does. */
    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper the container's configured JSON mapper; must not be {@code null}
     * @throws NullPointerException if the mapper is {@code null}
     */
    public JsonRefusalBodyRenderer(final ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * {@inheritDoc}
     *
     * <p>A serialisation failure here cannot be caused by the argument - the record holds one string and
     * two absent components - so it can only mean the mapper is misconfigured, which is a wiring fault.
     * It is reported as one rather than swallowed, because a silently empty refusal body would look like a
     * successful empty answer.
     */
    @Override
    public String renderRefusal(final String message) {
        try {
            return this.objectMapper.writeValueAsString(new ErrorResponse(message));
        } catch (final JsonProcessingException unrenderable) {
            throw new IllegalStateException("the refusal body could not be rendered", unrenderable);
        }
    }
}
