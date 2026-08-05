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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.carddemo.api.GlobalExceptionHandler;
import com.carddemo.api.ScreenStateAdapter;
import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.exception.ValidationException;
import com.carddemo.service.FieldErrorMarks.FlagState;
import com.carddemo.service.FieldErrorMarks.MarkedField;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Unit test for {@link FieldErrorTranslationService}, the one seam between a completed field
 * decoration and the validation failure a service throws.
 *
 * <h2>What is being pinned</h2>
 *
 * <p>Three per-field representations exist in the module and they must stay in step: the neutral
 * {@link MarkedField} a decoration accumulates, the {@code ValidationException.FieldError} a failure
 * carries up out of the service layer, and the {@code ErrorResponse.FieldError} a client receives.
 * The runtime failure path has exactly two converters - this class inbound and
 * {@link GlobalExceptionHandler} outbound - while {@link ScreenStateAdapter} owns the API boundary's
 * direct marks-to-wire projection for responses that carry marks without throwing. The risk is not
 * that a converter is missing but that those paths drift apart and a client is told to supply a
 * value it already supplied.
 *
 * <p>The nested class at the end therefore drives the whole path in one test rather than asserting
 * each hop separately: a decoration is marked, translated, thrown at the handler, and the resulting
 * response body is compared entry for entry against the decoration's own projection. If either
 * converter is changed alone, that comparison fails. Decision log entry DL-080 records why the
 * conversions sit at the two boundaries and why the decorator performs neither.
 *
 * <h2>Why the legacy sequence is asserted rather than a sorted one</h2>
 *
 * <p>The 39 expansions of {@code app/cpy/CSSETATY.cpy} in {@code app/cbl/COACTUPC.cbl} lines 3208 to
 * 3432 run in source sequence, and that sequence is irregular: the account status field is decorated
 * between the two address lines and the postal code ahead of city and country. The fixtures below
 * reproduce a fragment of that irregularity deliberately, so a translation that sorted or grouped
 * its output would fail rather than look tidy.
 */
@DisplayName("FieldErrorTranslationService - the one inbound converter of a field decoration")
final class FieldErrorTranslationServiceTest {

    /** The operator-facing summary line, standing in for what the message catalogue supplies. */
    private static final String SUMMARY = "Please correct the highlighted fields";

    /** A request-contract field name and its screen label, from the account-update mapset. */
    private static final String PROP_ACCT_STATUS = "accountStatus";

    /** The screen label the macro decorated for the account status. */
    private static final String SCREEN_ACCT_STATUS = "ACSTTUS";

    /** A second request-contract field name, used where sequence is under test. */
    private static final String PROP_CREDIT_LIMIT = "creditLimit";

    /** The screen label the macro decorated for the credit limit. */
    private static final String SCREEN_CREDIT_LIMIT = "ACRDLIM";

    /** A third pairing, so an ordering assertion has something to be wrong about. */
    private static final String PROP_ADDRESS_LINE_1 = "addressLine1";

    /** The screen label the macro decorated for the first address line. */
    private static final String SCREEN_ADDRESS_LINE_1 = "ACSADL1";

    /** The number of times the macro was textually expanded in the account-update program. */
    private static final int MACRO_EXPANSION_SITES = 39;

    /** The class under test holds no state, so one instance serves every case. */
    private final FieldErrorTranslationService translator = new FieldErrorTranslationService();

    @Nested
    @DisplayName("A decoration becomes the failure a service throws")
    class ADecorationBecomesAFailure {

        @Test
        @DisplayName("the summary the caller supplies becomes the failure's message, unchanged")
        void theSummaryBecomesTheFailureMessage() {
            final ValidationException failure =
                    translator.toValidationException(FieldErrorMarks.none(), SUMMARY);

            assertThat(failure.getMessage()).isEqualTo(SUMMARY);
        }

        @Test
        @DisplayName("a summary carrying legacy padding crosses byte for byte, because the legacy "
                + "message line is fixed-width and its trailing spaces are part of the field")
        void aPaddedSummaryCrossesByteForByte() {
            final String padded = "Account status must be supplied.                ";

            final ValidationException failure =
                    translator.toValidationException(FieldErrorMarks.none(), padded);

            assertThat(failure.getMessage()).isEqualTo(padded);
        }

        @Test
        @DisplayName("an empty decoration produces a failure with no per-field detail, which is the "
                + "first-submission shape the legacy macro's re-enter gate produced")
        void anEmptyDecorationProducesNoPerFieldDetail() {
            final ValidationException failure =
                    translator.toValidationException(FieldErrorMarks.none(), SUMMARY);

            assertThat(failure.fieldErrors()).isEmpty();
            assertThat(failure.hasFieldErrors()).isFalse();
        }

        @Test
        @DisplayName("one marked field becomes exactly one carrier entry, keeping its field name and "
                + "its screen label")
        void oneMarkedFieldBecomesOneCarrierEntry() {
            final FieldErrorMarks decoration = FieldErrorMarks.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FlagState.BLANK);

            final ValidationException failure = translator.toValidationException(decoration, SUMMARY);

            assertThat(failure.fieldErrors()).hasSize(1);
            final ValidationException.FieldError entry = failure.fieldErrors().getFirst();
            assertThat(entry.field()).isEqualTo(PROP_ACCT_STATUS);
            assertThat(entry.bmsFieldId()).isEqualTo(SCREEN_ACCT_STATUS);
        }

        @Test
        @DisplayName("a blank flag becomes MISSING and a not-OK flag becomes INVALID, so an operator "
                + "is told to supply a value in the one case and to correct one in the other")
        void theTwoFlagStatesBecomeTheTwoCarrierStates() {
            final FieldErrorMarks decoration = FieldErrorMarks.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FlagState.BLANK)
                    .mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT, FlagState.NOT_OK);

            final List<ValidationException.FieldError> entries =
                    translator.toValidationException(decoration, SUMMARY).fieldErrors();

            assertThat(entries).extracting(ValidationException.FieldError::state)
                    .containsExactly(ValidationException.FieldState.MISSING,
                            ValidationException.FieldState.INVALID);
        }

        @Test
        @DisplayName("no per-field message is invented, because the macro emitted none and the "
                + "explanatory text lived in the single summary line")
        void noPerFieldMessageIsInvented() {
            final FieldErrorMarks decoration = FieldErrorMarks.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FlagState.BLANK);

            assertThat(translator.toValidationException(decoration, SUMMARY).fieldErrors())
                    .extracting(ValidationException.FieldError::message)
                    .containsExactly((String) null);
        }

        @Test
        @DisplayName("an absent summary is carried rather than replaced, because inventing wording "
                + "here would put text this class does not own in front of an operator")
        void anAbsentSummaryIsCarriedRatherThanReplaced() {
            final ValidationException failure =
                    translator.toValidationException(FieldErrorMarks.none(), null);

            assertThat(failure.getMessage()).isNull();
        }

        @Test
        @DisplayName("an absent decoration is refused, because there is no honest failure to build "
                + "from nothing")
        void anAbsentDecorationIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> translator.toValidationException(null, SUMMARY))
                    .withMessage("decoration must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> translator.toFieldErrors(null))
                    .withMessage("decoration must not be null");
        }
    }

    @Nested
    @DisplayName("Marking sequence is the contract, not a convenience")
    class MarkingSequenceIsTheContract {

        @Test
        @DisplayName("entries arrive in the sequence they were marked, even when that sequence is the "
                + "irregular one the 39 legacy expansions run in")
        void entriesArriveInMarkingSequence() {
            final FieldErrorMarks decoration = FieldErrorMarks.none()
                    .mark(PROP_ADDRESS_LINE_1, SCREEN_ADDRESS_LINE_1, FlagState.BLANK)
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FlagState.NOT_OK)
                    .mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT, FlagState.BLANK);

            assertThat(translator.toFieldErrors(decoration))
                    .extracting(ValidationException.FieldError::field)
                    .containsExactly(PROP_ADDRESS_LINE_1, PROP_ACCT_STATUS, PROP_CREDIT_LIMIT);
        }

        @Test
        @DisplayName("the same field marked twice yields two entries, because suppressing one would "
                + "be a decision this class has no standing to make")
        void theSameFieldMarkedTwiceYieldsTwoEntries() {
            final FieldErrorMarks decoration = FieldErrorMarks.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FlagState.BLANK)
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FlagState.NOT_OK);

            assertThat(translator.toFieldErrors(decoration)).hasSize(2)
                    .extracting(ValidationException.FieldError::state)
                    .containsExactly(ValidationException.FieldState.MISSING,
                            ValidationException.FieldState.INVALID);
        }

        @Test
        @DisplayName("all 39 expansion sites can be carried at once, in order, because the legacy "
                + "program could decorate every one of them on a single re-submission")
        void all39ExpansionSitesCanBeCarriedAtOnce() {
            FieldErrorMarks decoration = FieldErrorMarks.none();
            final List<String> expected = new ArrayList<>(MACRO_EXPANSION_SITES);
            for (int site = 1; site <= MACRO_EXPANSION_SITES; site++) {
                final String field = "field" + site;
                expected.add(field);
                decoration = decoration.mark(field, "SCRN" + site,
                        site % 2 == 0 ? FlagState.NOT_OK : FlagState.BLANK);
            }

            assertThat(translator.toFieldErrors(decoration))
                    .hasSize(MACRO_EXPANSION_SITES)
                    .extracting(ValidationException.FieldError::field)
                    .containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("a screen label is carried untrimmed, because the legacy labels are fixed-width")
        void aScreenLabelIsCarriedUntrimmed() {
            final FieldErrorMarks decoration = FieldErrorMarks.none()
                    .mark(PROP_ACCT_STATUS, "ACSTTUS ", FlagState.BLANK);

            assertThat(translator.toFieldErrors(decoration))
                    .extracting(ValidationException.FieldError::bmsFieldId)
                    .containsExactly("ACSTTUS ");
        }
    }

    @Nested
    @DisplayName("The translated list is the caller's to read and no one's to change")
    class TheTranslatedListIsImmutable {

        @Test
        @DisplayName("the service-owned response carrier normalizes and exposes its immutable errors")
        void theServiceOwnedResponseCarrierNormalizesAndExposesItsErrors() {
            final ErrorResponse.FieldError fieldError = new ErrorResponse.FieldError(
                    PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, ErrorResponse.FieldState.MISSING);
            final List<ErrorResponse.FieldError> mutable = new ArrayList<>();
            mutable.add(fieldError);

            final ErrorResponse response =
                    new ErrorResponse(SUMMARY, mutable, SCREEN_ACCT_STATUS);
            mutable.clear();

            assertThat(response.hasFieldErrors()).isTrue();
            assertThat(response.fieldErrors()).containsExactly(fieldError).isUnmodifiable();
            assertThat(response.focusScreenFieldId()).isEqualTo(SCREEN_ACCT_STATUS);
            assertThat(new ErrorResponse(SUMMARY).hasFieldErrors()).isFalse();
            assertThat(new ErrorResponse(SUMMARY, null).fieldErrors()).isEmpty();
        }

        @Test
        @DisplayName("the returned list cannot be modified, so no holder can inject an error the "
                + "validation cascade never raised")
        void theReturnedListCannotBeModified() {
            final List<ValidationException.FieldError> entries =
                    translator.toFieldErrors(FieldErrorMarks.none()
                            .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FlagState.BLANK));

            assertThat(entries).isUnmodifiable();
        }

        @Test
        @DisplayName("a fresh list is built on each call, so two callers cannot observe one another")
        void aFreshListIsBuiltOnEachCall() {
            final FieldErrorMarks decoration = FieldErrorMarks.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FlagState.BLANK);

            assertThat(translator.toFieldErrors(decoration))
                    .isNotSameAs(translator.toFieldErrors(decoration))
                    .isEqualTo(translator.toFieldErrors(decoration));
        }

        @Test
        @DisplayName("a decoration built directly from marked fields translates identically to one "
                + "grown by marking, so neither construction path is privileged")
        void aDirectlyBuiltDecorationTranslatesIdentically() {
            final FieldErrorMarks grown = FieldErrorMarks.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FlagState.BLANK)
                    .mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT, FlagState.NOT_OK);
            final FieldErrorMarks built = new FieldErrorMarks(List.of(
                    new MarkedField(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FlagState.BLANK),
                    new MarkedField(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT, FlagState.NOT_OK)));

            assertThat(translator.toFieldErrors(built))
                    .isEqualTo(translator.toFieldErrors(grown));
        }

        @Test
        @DisplayName("the two entry points agree, so a caller that combines detail before throwing "
                + "cannot end up with a different failure from one that does not")
        void theTwoEntryPointsAgree() {
            final FieldErrorMarks decoration = FieldErrorMarks.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FlagState.BLANK)
                    .mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT, FlagState.NOT_OK);

            assertThat(translator.toValidationException(decoration, SUMMARY).fieldErrors())
                    .isEqualTo(translator.toFieldErrors(decoration));
        }
    }

    @Nested
    @DisplayName("The seam end to end: decoration, failure, response")
    class TheSeamEndToEnd {

        /** The boundary advice, instantiated directly because it holds no injected collaborator. */
        private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

        /** The API boundary's independent projection, used as the client-shape oracle. */
        private final ScreenStateAdapter screenStateAdapter = new ScreenStateAdapter();

        @Test
        @DisplayName("a decoration marked by a service reaches a client as the same entries in the "
                + "same sequence with the same states, which is the whole point of the seam")
        void aDecorationReachesAClientUnchanged() {
            final FieldErrorMarks decoration = FieldErrorMarks.none()
                    .mark(PROP_ADDRESS_LINE_1, SCREEN_ADDRESS_LINE_1, FlagState.BLANK)
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FlagState.NOT_OK)
                    .mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT, FlagState.BLANK);

            final ResponseEntity<com.carddemo.api.dto.ErrorResponse> response =
                    handler.handleValidation(
                    translator.toValidationException(decoration, SUMMARY));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().fieldErrors())
                    .as("the failure path and the direct API projection cannot be changed "
                            + "independently")
                    .isEqualTo(screenStateAdapter.toFieldErrors(decoration));
            assertThat(response.getBody().message()).isEqualTo(SUMMARY);
        }

        @Test
        @DisplayName("both legacy states survive the round trip as distinct published states, so a "
                + "field left blank and a field filled in wrongly stay different remedies")
        void bothLegacyStatesSurviveTheRoundTrip() {
            final FieldErrorMarks decoration = FieldErrorMarks.none()
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FlagState.BLANK)
                    .mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT, FlagState.NOT_OK);

            final com.carddemo.api.dto.ErrorResponse body = handler.handleValidation(
                    translator.toValidationException(decoration, SUMMARY)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.fieldErrors())
                    .extracting(com.carddemo.api.dto.ErrorResponse.FieldError::state)
                    .containsExactly(com.carddemo.api.dto.ErrorResponse.FieldState.MISSING,
                            com.carddemo.api.dto.ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("the screen label of the first marked field becomes the focus hint, reproducing "
                + "the legacy cursor landing on the first field its cascade rejected")
        void theFirstMarkedFieldBecomesTheFocusHint() {
            final FieldErrorMarks decoration = FieldErrorMarks.none()
                    .mark(PROP_ADDRESS_LINE_1, SCREEN_ADDRESS_LINE_1, FlagState.BLANK)
                    .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, FlagState.NOT_OK);

            final com.carddemo.api.dto.ErrorResponse body = handler.handleValidation(
                    translator.toValidationException(decoration, SUMMARY)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.focusScreenFieldId()).isEqualTo(SCREEN_ADDRESS_LINE_1);
        }

        @Test
        @DisplayName("a failure carrying no decoration answers with the summary alone and no focus "
                + "hint, so a caller that has marked nothing does not imply a field is at fault")
        void aFailureCarryingNoDecorationAnswersWithTheSummaryAlone() {
            final ErrorResponse body = handler.handleValidation(
                    translator.toValidationException(FieldErrorMarks.none(), SUMMARY))
                    .getBody();

            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).isEmpty();
            assertThat(body.focusScreenFieldId()).isNull();
            assertThat(body.message()).isEqualTo(SUMMARY);
        }

        @Test
        @DisplayName("all 39 expansion sites survive the round trip in order, so the largest "
                + "decoration the legacy program could produce is not truncated or re-grouped")
        void all39ExpansionSitesSurviveTheRoundTrip() {
            FieldErrorMarks decoration = FieldErrorMarks.none();
            for (int site = 1; site <= MACRO_EXPANSION_SITES; site++) {
                decoration = decoration.mark("field" + site, "SCRN" + site,
                        site % 2 == 0 ? FlagState.NOT_OK : FlagState.BLANK);
            }

            final com.carddemo.api.dto.ErrorResponse body = handler.handleValidation(
                    translator.toValidationException(decoration, SUMMARY)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.fieldErrors())
                    .hasSize(MACRO_EXPANSION_SITES)
                    .isEqualTo(screenStateAdapter.toFieldErrors(decoration));
        }

        @Test
        @DisplayName("the response body carries no submitted value and no internal name, so a "
                + "failure on a credential field cannot echo what was typed")
        void theResponseBodyCarriesNoSubmittedValue() {
            final FieldErrorMarks decoration = FieldErrorMarks.none()
                    .mark("password", "PASSWD", FlagState.NOT_OK);

            final com.carddemo.api.dto.ErrorResponse body = handler.handleValidation(
                    translator.toValidationException(decoration, SUMMARY)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.toString())
                    .doesNotContain("com.carddemo")
                    .doesNotContain("NOT_OK");
        }

        @Test
        @DisplayName("a carrier entry that names no screen field still crosses, because a service may "
                + "fail a value that no map field ever displayed")
        void anEntryNamingNoScreenFieldStillCrosses() {
            final ValidationException failure = new ValidationException(SUMMARY,
                    List.of(new ValidationException.FieldError(PROP_ACCT_STATUS, null,
                            ValidationException.FieldState.INVALID, null)));

            final com.carddemo.api.dto.ErrorResponse body =
                    handler.handleValidation(failure).getBody();

            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).hasSize(1);
            assertThat(body.fieldErrors().getFirst().screenFieldId()).isEmpty();
            assertThat(body.focusScreenFieldId()).isNull();
        }

        @Test
        @DisplayName("a decoration holding a null entry is refused where it is built rather than "
                + "where it is translated, so a malformed producer is located at once")
        void aDecorationHoldingANullEntryIsRefusedWhereItIsBuilt() {
            final List<MarkedField> withNull = new ArrayList<>();
            withNull.add(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new FieldErrorMarks(withNull));
        }
    }
}
