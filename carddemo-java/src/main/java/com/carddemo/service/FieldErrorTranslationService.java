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
package com.carddemo.service;

import com.carddemo.exception.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * The one seam between a completed field decoration and the validation failure a service throws.
 *
 * <p>The legacy account-update program does both halves of this in one place. Its validation
 * cascade sets a one-character flag per field, the 39 expansions of the screen-decoration macro
 * {@code app/cpy/CSSETATY.cpy} turn those flags into screen edits at {@code app/cbl/COACTUPC.cbl}
 * lines 3208 to 3432, and the program then simply re-sends the map instead of writing. A REST
 * service cannot re-send a map: it has to fail, and its failure has to carry the same per-field
 * detail. {@link FieldErrorMarks} accumulates that detail in the legacy's own terms, and this
 * class is what turns the accumulation into {@link ValidationException} so the service does not
 * have to.
 *
 * <p><strong>Why the conversion lives here and nowhere else.</strong> Two structurally identical
 * per-field models exist by design: {@code ValidationException.FieldError} for a failure travelling
 * up out of the service layer, and {@code ErrorResponse.FieldError} for the body a client receives.
 * The duplication is deliberate - the response contract may not depend on the failure carrier and
 * the failure carrier imports nothing but {@code java.*} - so each direction needs exactly one
 * converter, placed where the dependency is legal. This class is the inbound converter, reached by
 * a service that has just decorated; {@code api.GlobalExceptionHandler} is the outbound one,
 * reached when the failure becomes a response. Neither direction is duplicated at a call site,
 * which is what stops the two models drifting apart. Decision log entry DL-080 records the
 * arrangement and why the accumulation itself does not perform this conversion.
 *
 * <p><strong>Which accumulation this reads.</strong> {@link FieldErrorMarks}, the service-owned
 * accumulation, not the transport record {@code api/dto/FieldErrorDecorator} it is twinned with.
 * The two carry the same marks in the same sequence and {@code api.ScreenStateAdapter} converts
 * between them at the boundary; this class stays below that boundary, so the strict downward
 * package direction holds and a validation failure can be built without the service layer naming a
 * transport type.
 *
 * <p><strong>What is preserved.</strong> Marking sequence, exactly: the legacy expansions ran in
 * source sequence and that sequence is irregular, so no entry is re-ordered, de-duplicated or
 * dropped. The two flag states stay distinct, because a field left blank and a field filled in
 * wrongly need different remedies. No per-field message is invented - the macro emitted none, the
 * explanatory text belongs to the single summary line the caller supplies - and no submitted field
 * value is ever carried, so a failure on a credential field cannot echo what was typed (decision
 * log entry D-16).
 *
 * <p><strong>What is not decided here.</strong> Whether to fail at all. The macro fired only when
 * the program-context re-enter condition was set, and that gate belongs to the caller: a service
 * that has marked nothing must not call this, exactly as the legacy program re-sent an undecorated
 * map on a first submission. This class holds no state, no catalogue of the 39 field pairings and
 * no message text, so it is safe to share and cheap to inject.
 *
 * @since 1.0.0
 */
@Service
public class FieldErrorTranslationService {

    /**
     * Turns a completed decoration into the validation failure a service throws.
     *
     * <p>The summary message becomes the exception's message and is passed through byte for byte,
     * because the legacy summary line is a fixed-width field whose padding is significant. Per-field
     * messages stay absent, so a client reads the summary for wording and each entry for which
     * field and which remedy.
     *
     * <p>An empty decoration produces a failure with no per-field detail rather than being refused.
     * That is deliberate: a caller may fail for a reason that no single field carries - a stale
     * record, a rejected key filter - and forcing it to choose a different constructor for that
     * case would put the choice at every call site instead of here.
     *
     * @param decoration     the fields marked by the validation cascade, in marking sequence.
     *                       Mandatory; use {@link FieldErrorMarks#none()} for a failure that
     *                       names no field.
     * @param summaryMessage the operator-facing summary the service or message catalogue owns,
     *                       passed through unchanged. May be {@code null}, which the carrier
     *                       accepts, though a caller that has something to say should say it.
     * @return the failure to throw, carrying one entry per marked field in the same sequence
     * @throws NullPointerException if {@code decoration} is {@code null}
     */
    public ValidationException toValidationException(FieldErrorMarks decoration,
            String summaryMessage) {
        Objects.requireNonNull(decoration, "decoration must not be null");

        return new ValidationException(summaryMessage, toFieldErrors(decoration));
    }

    /**
     * Translates a decoration into the carrier's per-field detail, for a caller that has to combine
     * it with failures the screen-decoration surface does not describe before it throws.
     *
     * @param decoration the fields marked by the validation cascade, in marking sequence. Mandatory.
     * @return one carrier entry per marked field, in the same sequence; empty when nothing was
     *         marked. The list is unmodifiable and built fresh on each call.
     * @throws NullPointerException if {@code decoration} is {@code null}
     */
    public List<ValidationException.FieldError> toFieldErrors(FieldErrorMarks decoration) {
        Objects.requireNonNull(decoration, "decoration must not be null");

        List<FieldErrorMarks.MarkedField> marked = decoration.markedFields();
        List<ValidationException.FieldError> translated = new ArrayList<>(marked.size());
        for (FieldErrorMarks.MarkedField field : marked) {
            translated.add(new ValidationException.FieldError(field.field(), field.bmsFieldId(),
                    fieldStateOf(field.flagState()), null));
        }
        return List.copyOf(translated);
    }

    /**
     * Maps one legacy flag state onto the carrier's state.
     *
     * <p>Exhaustive over the two constants with no default arm, so adding a state to either
     * enumeration stops the build here rather than funnelling a new state into a catch-all. Under
     * warnings-as-errors compilation that is a build failure, which is the intended safeguard.
     * There is no arm for an absent state because the neutral model rejects one at construction.
     *
     * @param flagState the legacy flag state, never {@code null}
     * @return the corresponding carrier state
     */
    private static ValidationException.FieldState fieldStateOf(
            FieldErrorMarks.FlagState flagState) {
        return switch (flagState) {
            case BLANK -> ValidationException.FieldState.MISSING;
            case NOT_OK -> ValidationException.FieldState.INVALID;
        };
    }
}
