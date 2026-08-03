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
package com.carddemo.exception;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ValidationException}, the transport for the legacy field-flag validation
 * surface. They exist above all to pin the <strong>two-state per-field error contract</strong> so that
 * it can never be collapsed into a single boolean - decision log entry D-33.
 *
 * <p><strong>Why two states and not one.</strong> The shape is dictated by
 * {@code app/cpy/CSSETATY.cpy}, a parameterised {@code PROCEDURE DIVISION} macro carrying three
 * substitution tokens. Three verified properties are the contract: it fires only when the flag is
 * not-OK <em>or</em> blank <em>and</em> the re-enter indicator is set; in both firing states it changes
 * the field's colour attribute; and <strong>additionally, only when the flag is specifically
 * blank</strong>, it writes a {@code '*'} marker. That third property is the entire justification for
 * two states - the marker is the observable difference between "you left this out" and "what you typed
 * is wrong", and a single boolean would erase it. So the legacy blank flag becomes
 * {@link ValidationException.FieldState#MISSING} (flag value {@code 'B'}, or a space for the two
 * key-filter flags, the one variation in the estate), the not-OK flag becomes
 * {@link ValidationException.FieldState#INVALID} (flag value {@code '0'}), and the third state in each
 * legacy triple is the valid state, which decorates nothing and therefore has no representation at all
 * in an error enum. This is why a {@code VALID} constant must never be added.
 *
 * <p>The macro is expanded <strong>39 times</strong> in {@code app/cbl/COACTUPC.cbl} between L3208 and
 * L3432 - roughly 234 generated lines - which collapse into a single parameterised decorator call plus
 * the error contract this exception carries.
 *
 * <p><strong>The re-entry gate is deliberately not enforced here.</strong> Because the macro fires only
 * when the re-enter indicator is set, per-field error states are populated only on re-submission and
 * never on first presentation. That gating belongs to the account-update service and to
 * {@code api/dto/FieldErrorDecorator}; it is recorded here so a downstream author cannot mistake this
 * exception's willingness to carry an error for permission to report one on first entry.
 *
 * <p><strong>Two of the 39 decorated fields are never validated</strong>, as the source states directly
 * at {@code app/cbl/COACTUPC.cbl} L3345 (middle name, screen field {@code ACSMNAM}) and L3369 (second
 * address line, {@code ACSADL2}). <strong>No validation constraint may be attached to either field in
 * the DTO layer</strong>, because adding one would reject input the legacy system accepts - decision log
 * entry D-34. This exception is a carrier, not a validator: it must be able to represent a decoration
 * that no rule produced, which {@link #theExceptionCarriesADecorationThatNoEditProduced()} proves.
 *
 * <p>A source defect worth knowing about: at {@code app/cbl/COACTUPC.cbl} L3427-L3435 the comments
 * labelling the primary-cardholder and electronic-funds-transfer expansions are transposed relative to
 * the code they describe. The code is correct and the comments are swapped, so a translation must follow
 * the token substitutions and never the adjacent comment. Row 15 of the source anomaly register.
 *
 * <p><strong>Serialisation.</strong> The nested carrier is intentionally not serialisable, a validation
 * error being a request-scoped presentation concern rather than a persisted value, so the enclosing
 * exception holds it in a {@code transient} field. A round trip therefore preserves the message and the
 * cause and revives the per-field detail as an <strong>empty</strong> list, never {@code null}, so
 * callers need no null check.
 *
 * <p><strong>Identity.</strong> This is the project's own type in {@code com.carddemo.exception},
 * deliberately not the Bean Validation exception of the same simple name; no import of that API appears
 * in this file, which is the proof. It is also not an abend - a field-level validation failure is
 * recoverable and re-displayable, never a program termination.
 */
@DisplayName("ValidationException :: two-state per-field validation transport")
class ValidationExceptionTest {
    private static final String SUMMARY = "Account update rejected: correct the marked fields";

    private static final String BMS_ACCT_STATUS = "ACSTTUS";

    private static final String PROP_ACCT_STATUS = "acctStatus";

    private static final String ACCT_STATUS_MESSAGE = "Account status must be supplied";

    private static final String BMS_OPEN_YEAR = "OPNYEAR";

    private static final String PROP_OPEN_YEAR = "openYear";

    private static final String OPEN_YEAR_MESSAGE = "Open year must be a four digit year";

    private static final String BMS_CREDIT_LIMIT = "ACRDLIM";

    private static final String PROP_CREDIT_LIMIT = "creditLimit";

    private static final String CREDIT_LIMIT_MESSAGE = "Credit limit must be a signed amount";

    private static final String BMS_MIDDLE_NAME = "ACSMNAM";

    private static final String PROP_MIDDLE_NAME = "middleName";

    private static final String BMS_ADDRESS_LINE_2 = "ACSADL2";

    private static final String PROP_ADDRESS_LINE_2 = "addressLine2";

    private static final String DECORATION_ONLY_MESSAGE = "Field marked for operator attention";

    private static final String PROP_CREDENTIAL = "userCredential";

    private static final String BMS_CREDENTIAL = "USRCRED";

    private static final String CREDENTIAL_MESSAGE = "Credential failed its edit";

    @Test
    @DisplayName("FieldState declares exactly two constants, MISSING then INVALID: a third state such as "
            + "VALID, OK, UNKNOWN or NONE is forbidden because a field that passed its edits decorates "
            + "nothing and so has no representation in an error enum")
    void fieldStateDeclaresExactlyTheTwoLegacyErrorStatesInDeclarationOrder() {
        assertThat(ValidationException.FieldState.values())
                .containsExactly(ValidationException.FieldState.MISSING,
                        ValidationException.FieldState.INVALID);

        assertThat(ValidationException.FieldState.values().length).isEqualTo(2);
    }

    @Test
    @DisplayName("FieldState.valueOf resolves MISSING (legacy blank flag: colour change plus the marker) "
            + "and INVALID (legacy not-OK flag: colour change only), and rejects any third name")
    void fieldStateValueOfResolvesBothStatesAndRejectsAnyThirdName() {
        assertThat(ValidationException.FieldState.valueOf("MISSING"))
                .isSameAs(ValidationException.FieldState.MISSING);
        assertThat(ValidationException.FieldState.valueOf("INVALID"))
                .isSameAs(ValidationException.FieldState.INVALID);

        assertThatThrownBy(() -> ValidationException.FieldState.valueOf("VALID"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ValidationException.FieldState.valueOf("OK"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ValidationException.FieldState.valueOf("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ValidationException.FieldState.valueOf("NONE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("The two states are distinct values with stable names, so a serialised error contract "
            + "can name them without ambiguity")
    void theTwoStatesAreDistinctValuesWithStableNames() {
        assertThat(ValidationException.FieldState.MISSING)
                .isNotEqualTo(ValidationException.FieldState.INVALID);
        assertThat(ValidationException.FieldState.MISSING.name()).isEqualTo("MISSING");
        assertThat(ValidationException.FieldState.INVALID.name()).isEqualTo("INVALID");
    }

    @Test
    @DisplayName("The single-field constructor carries exactly one MISSING entry with the Java property "
            + "name, the legacy screen tag, the state and the message all intact")
    void singleFieldConstructorCarriesExactlyOneMissingEntry() {
        ValidationException thrown = new ValidationException(PROP_ACCT_STATUS, BMS_ACCT_STATUS,
                ValidationException.FieldState.MISSING, ACCT_STATUS_MESSAGE);

        assertThat(thrown.getMessage()).isEqualTo(ACCT_STATUS_MESSAGE);
        assertThat(thrown.hasFieldErrors()).isTrue();
        assertThat(thrown.fieldErrors()).hasSize(1);

        ValidationException.FieldError carried = thrown.fieldErrors().get(0);
        assertThat(carried.field()).isEqualTo(PROP_ACCT_STATUS);
        assertThat(carried.bmsFieldId()).isEqualTo(BMS_ACCT_STATUS);
        assertThat(carried.state()).isSameAs(ValidationException.FieldState.MISSING);
        assertThat(carried.message()).isEqualTo(ACCT_STATUS_MESSAGE);
    }

    @Test
    @DisplayName("The single-field constructor carries exactly one INVALID entry with all four components "
            + "intact, so a supplied-but-rejected field is reported distinctly from an absent one")
    void singleFieldConstructorCarriesExactlyOneInvalidEntry() {
        ValidationException thrown = new ValidationException(PROP_CREDIT_LIMIT, BMS_CREDIT_LIMIT,
                ValidationException.FieldState.INVALID, CREDIT_LIMIT_MESSAGE);

        assertThat(thrown.getMessage()).isEqualTo(CREDIT_LIMIT_MESSAGE);
        assertThat(thrown.fieldErrors()).hasSize(1);

        ValidationException.FieldError carried = thrown.fieldErrors().get(0);
        assertThat(carried.field()).isEqualTo(PROP_CREDIT_LIMIT);
        assertThat(carried.bmsFieldId()).isEqualTo(BMS_CREDIT_LIMIT);
        assertThat(carried.state()).isSameAs(ValidationException.FieldState.INVALID);
        assertThat(carried.message()).isEqualTo(CREDIT_LIMIT_MESSAGE);
    }

    @Test
    @DisplayName("MISSING and INVALID are independently observable through the public API: the same field "
            + "reported in the two states yields two different carried states")
    void theTwoStatesAreIndependentlyObservableThroughThePublicApi() {
        ValidationException absent = new ValidationException(PROP_ACCT_STATUS, BMS_ACCT_STATUS,
                ValidationException.FieldState.MISSING, ACCT_STATUS_MESSAGE);
        ValidationException rejected = new ValidationException(PROP_ACCT_STATUS, BMS_ACCT_STATUS,
                ValidationException.FieldState.INVALID, ACCT_STATUS_MESSAGE);

        ValidationException.FieldError absentEntry = absent.fieldErrors().get(0);
        ValidationException.FieldError rejectedEntry = rejected.fieldErrors().get(0);

        assertThat(absentEntry.state()).isSameAs(ValidationException.FieldState.MISSING);
        assertThat(rejectedEntry.state()).isSameAs(ValidationException.FieldState.INVALID);
        assertThat(absentEntry.state()).isNotEqualTo(rejectedEntry.state());

        assertThat(absentEntry.field()).isEqualTo(rejectedEntry.field());
        assertThat(absentEntry.bmsFieldId()).isEqualTo(rejectedEntry.bmsFieldId());
        assertThat(absentEntry).isNotEqualTo(rejectedEntry);
    }

    @Test
    @DisplayName("A legacy screen tag supplied as null is normalised to the empty string, so a validation "
            + "that is not screen-bound still yields a non-null tag")
    void aNullLegacyScreenTagIsNormalisedToTheEmptyString() {
        ValidationException.FieldError notScreenBound = new ValidationException.FieldError(
                PROP_OPEN_YEAR, null, ValidationException.FieldState.INVALID, OPEN_YEAR_MESSAGE);

        assertThat(notScreenBound.bmsFieldId()).isNotNull().isEmpty();
        assertThat(notScreenBound.field()).isEqualTo(PROP_OPEN_YEAR);

        ValidationException thrown = new ValidationException(PROP_OPEN_YEAR, null,
                ValidationException.FieldState.INVALID, OPEN_YEAR_MESSAGE);

        assertThat(thrown.fieldErrors().get(0).bmsFieldId()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("An absent state is rejected at construction rather than normalised, because the legacy "
            + "flag is either blank or not-OK whenever the decoration fires and there is no third state")
    void anAbsentStateIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new ValidationException.FieldError(
                PROP_ACCT_STATUS, BMS_ACCT_STATUS, null, ACCT_STATUS_MESSAGE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("state");

        assertThatThrownBy(() -> new ValidationException(
                PROP_ACCT_STATUS, BMS_ACCT_STATUS, null, ACCT_STATUS_MESSAGE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("state");

        assertThat(new ValidationException.FieldError(PROP_ACCT_STATUS, BMS_ACCT_STATUS,
                ValidationException.FieldState.MISSING, ACCT_STATUS_MESSAGE).state())
                .isSameAs(ValidationException.FieldState.MISSING);
        assertThat(new ValidationException.FieldError(PROP_ACCT_STATUS, null,
                ValidationException.FieldState.INVALID, null).state())
                .isSameAs(ValidationException.FieldState.INVALID);
    }

    @Test
    @DisplayName("The message-only constructor reports the message exactly and an empty, non-null detail "
            + "list, so a summary-level failure needs no per-field state")
    void messageOnlyConstructorReportsNoPerFieldDetail() {
        ValidationException thrown = new ValidationException(SUMMARY);

        assertThat(thrown.getMessage()).isEqualTo(SUMMARY);
        assertThat(thrown.fieldErrors()).isNotNull().isEmpty();
        assertThat(thrown.hasFieldErrors()).isFalse();
        assertThat(thrown.getCause()).isNull();
    }

    @Test
    @DisplayName("Per-field detail keeps the order it was supplied in, because the legacy macro expansions "
            + "run in source order down the screen and the error list is therefore positional")
    void perFieldDetailKeepsTheOrderItWasSuppliedIn() {
        List<ValidationException.FieldError> inScreenOrder = List.of(
                missingAcctStatus(), invalidOpenYear(), invalidCreditLimit());

        ValidationException thrown = new ValidationException(SUMMARY, inScreenOrder);

        assertThat(thrown.getMessage()).isEqualTo(SUMMARY);
        assertThat(thrown.fieldErrors())
                .containsExactly(missingAcctStatus(), invalidOpenYear(), invalidCreditLimit());
        assertThat(thrown.fieldErrors().get(0).bmsFieldId()).isEqualTo(BMS_ACCT_STATUS);
        assertThat(thrown.fieldErrors().get(1).bmsFieldId()).isEqualTo(BMS_OPEN_YEAR);
        assertThat(thrown.fieldErrors().get(2).bmsFieldId()).isEqualTo(BMS_CREDIT_LIMIT);
    }

    @Test
    @DisplayName("One exception carries a MISSING entry and an INVALID entry side by side, because the "
            + "contract is per field and a single submission can mix the two")
    void oneExceptionCarriesBothStatesSideBySide() {
        ValidationException thrown = new ValidationException(SUMMARY,
                List.of(missingAcctStatus(), invalidCreditLimit()));

        assertThat(thrown.fieldErrors()).hasSize(2);
        assertThat(thrown.fieldErrors().get(0).state())
                .isSameAs(ValidationException.FieldState.MISSING);
        assertThat(thrown.fieldErrors().get(1).state())
                .isSameAs(ValidationException.FieldState.INVALID);
        assertThat(thrown.hasFieldErrors()).isTrue();
    }

    @Test
    @DisplayName("The cause-carrying constructor keeps the message, the ordered detail and the identity of "
            + "the cause it wrapped")
    void theCauseCarryingConstructorKeepsDetailAndCauseIdentity() {
        IllegalStateException cause = new IllegalStateException("record image changed since it was read");

        ValidationException thrown = new ValidationException(SUMMARY,
                List.of(missingAcctStatus(), invalidCreditLimit()), cause);

        assertThat(thrown.getMessage()).isEqualTo(SUMMARY);
        assertThat(thrown.getCause()).isSameAs(cause);
        assertThat(thrown.fieldErrors())
                .containsExactly(missingAcctStatus(), invalidCreditLimit());
    }

    @Test
    @DisplayName("An empty list and a null list both yield an empty, non-null detail list rather than a "
            + "thrown error, which is the behaviour the production funnel specifies")
    void anEmptyListAndANullListBothYieldAnEmptyDetailList() {
        List<ValidationException.FieldError> none = List.of();
        ValidationException fromEmpty = new ValidationException(SUMMARY, none);

        assertThat(fromEmpty.fieldErrors()).isNotNull().isEmpty();
        assertThat(fromEmpty.hasFieldErrors()).isFalse();

        List<ValidationException.FieldError> absent = null;
        ValidationException fromNull = new ValidationException(SUMMARY, absent);

        assertThat(fromNull.fieldErrors()).isNotNull().isEmpty();
        assertThat(fromNull.hasFieldErrors()).isFalse();

        ValidationException fromNullWithCause = new ValidationException(SUMMARY, absent,
                new IllegalStateException("no detail available"));

        assertThat(fromNullWithCause.fieldErrors()).isNotNull().isEmpty();
        assertThat(fromNullWithCause.hasFieldErrors()).isFalse();
    }

    @Test
    @DisplayName("A null entry inside the supplied list is rejected rather than dropped, because "
            + "silently shortening the detail would lose one of the 39 decorated fields between the "
            + "service that failed it and the boundary that reports it, with nothing to show for it")
    void aNullEntryInsideTheSuppliedListIsRejected() {
        List<ValidationException.FieldError> withHole = new ArrayList<>();
        withHole.add(missingAcctStatus());
        withHole.add(null);
        withHole.add(invalidCreditLimit());

        assertThatThrownBy(() -> new ValidationException(SUMMARY, withHole))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ValidationException(SUMMARY, withHole,
                new IllegalStateException("detail was incomplete")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("A null entry is rejected wherever it sits, so a hole at the head or the tail of the "
            + "detail is no more survivable than one in the middle")
    void aNullEntryIsRejectedWhereverItSits() {
        List<ValidationException.FieldError> holeAtHead = new ArrayList<>();
        holeAtHead.add(null);
        holeAtHead.add(missingAcctStatus());

        List<ValidationException.FieldError> holeAtTail = new ArrayList<>();
        holeAtTail.add(missingAcctStatus());
        holeAtTail.add(null);

        List<ValidationException.FieldError> onlyAHole = new ArrayList<>();
        onlyAHole.add(null);

        assertThatThrownBy(() -> new ValidationException(SUMMARY, holeAtHead))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ValidationException(SUMMARY, holeAtTail))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ValidationException(SUMMARY, onlyAHole))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("A null list is still no detail at all, so rejecting a null element did not make the "
            + "absent-detail case an error too")
    void aNullListIsStillNoDetailAtAll() {
        ValidationException thrown = new ValidationException(SUMMARY, (List<ValidationException.FieldError>) null);

        assertThat(thrown.fieldErrors()).isEmpty();
        assertThat(thrown.hasFieldErrors()).isFalse();
    }

    @Test
    @DisplayName("The supplied list is copied on construction, so a caller that reuses its working list "
            + "between validation passes cannot mutate an exception that has already been thrown")
    void theSuppliedListIsDefensivelyCopiedOnConstruction() {
        List<ValidationException.FieldError> working = new ArrayList<>();
        working.add(missingAcctStatus());
        working.add(invalidCreditLimit());

        ValidationException thrown = new ValidationException(SUMMARY, working);

        working.add(invalidOpenYear());
        working.remove(0);
        working.clear();

        assertThat(working).isEmpty();
        assertThat(thrown.fieldErrors())
                .containsExactly(missingAcctStatus(), invalidCreditLimit());
        assertThat(thrown.hasFieldErrors()).isTrue();
    }

    @Test
    @DisplayName("The cause-carrying constructor copies defensively too, so neither collection overload "
            + "aliases the caller's list")
    void theCauseCarryingConstructorAlsoCopiesDefensively() {
        List<ValidationException.FieldError> working = new ArrayList<>();
        working.add(invalidOpenYear());

        ValidationException thrown = new ValidationException(SUMMARY, working,
                new IllegalStateException("underlying read failed"));

        working.clear();

        assertThat(working).isEmpty();
        assertThat(thrown.fieldErrors()).containsExactly(invalidOpenYear());
    }

    @Test
    @DisplayName("The exposed detail list is unmodifiable: add, set, remove and clear all refuse, so the "
            + "error contract cannot be edited by a handler on its way to the response")
    void theExposedDetailListIsUnmodifiable() {
        ValidationException thrown = new ValidationException(SUMMARY, List.of(missingAcctStatus()));
        List<ValidationException.FieldError> exposed = thrown.fieldErrors();
        ValidationException.FieldError intruder = invalidCreditLimit();

        assertThatThrownBy(() -> exposed.add(intruder))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> exposed.set(0, intruder))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> exposed.remove(0))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(exposed::clear)
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(exposed).containsExactly(missingAcctStatus());
    }

    @Test
    @DisplayName("The empty detail list is unmodifiable as well, so the summary-level path offers no "
            + "back door into a mutable collection")
    void theEmptyDetailListIsUnmodifiableAsWell() {
        ValidationException thrown = new ValidationException(SUMMARY);
        List<ValidationException.FieldError> exposed = thrown.fieldErrors();
        ValidationException.FieldError intruder = missingAcctStatus();

        assertThatThrownBy(() -> exposed.add(intruder))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(exposed::clear)
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(exposed).isEmpty();
    }

    @Test
    @DisplayName("fieldErrors() is never null on any of the four construction paths, nor on the null-list "
            + "path, so a caller iterating the detail never needs a null check")
    void fieldErrorsIsNeverNullOnAnyConstructionPath() {
        assertThat(new ValidationException(SUMMARY).fieldErrors()).isNotNull().isEmpty();

        assertThat(new ValidationException(PROP_ACCT_STATUS, BMS_ACCT_STATUS,
                ValidationException.FieldState.MISSING, ACCT_STATUS_MESSAGE).fieldErrors())
                .isNotNull().hasSize(1);

        assertThat(new ValidationException(SUMMARY, List.of(invalidOpenYear())).fieldErrors())
                .isNotNull().hasSize(1);

        assertThat(new ValidationException(SUMMARY, List.of(invalidOpenYear()),
                new IllegalStateException("wrapped")).fieldErrors())
                .isNotNull().hasSize(1);

        List<ValidationException.FieldError> absent = null;
        assertThat(new ValidationException(SUMMARY, absent).fieldErrors()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("serialVersionUID is the explicitly declared 1L rather than a compiler-generated hash, so "
            + "adding a field later cannot silently break an already-serialised instance")
    void serialVersionUidIsTheExplicitlyDeclaredOne() {
        ObjectStreamClass descriptor = ObjectStreamClass.lookup(ValidationException.class);

        assertThat(descriptor).isNotNull();
        assertThat(descriptor.getSerialVersionUID()).isEqualTo(1L);
    }

    @Test
    @DisplayName("A summary-level failure survives a serialisation round trip with its message intact and "
            + "an empty, non-null detail list")
    void aSummaryLevelFailureSurvivesASerialisationRoundTrip()
            throws IOException, ClassNotFoundException {
        ValidationException revived = roundTrip(new ValidationException(SUMMARY));

        assertThat(revived.getMessage()).isEqualTo(SUMMARY);
        assertThat(revived.fieldErrors()).isNotNull().isEmpty();
        assertThat(revived.hasFieldErrors()).isFalse();
    }

    @Test
    @DisplayName("A field-level failure serialises without its per-field detail because the carrier is "
            + "intentionally not serialisable, and the revived detail list is empty rather than null")
    void aFieldLevelFailureSerialisesWithoutItsPerFieldDetail()
            throws IOException, ClassNotFoundException {
        IllegalStateException cause = new IllegalStateException("record image changed since it was read");
        ValidationException original = new ValidationException(SUMMARY,
                List.of(missingAcctStatus(), invalidCreditLimit()), cause);

        ValidationException revived = roundTrip(original);

        assertThat(revived.getMessage()).isEqualTo(SUMMARY);
        assertThat(revived.getCause()).isInstanceOf(IllegalStateException.class);
        assertThat(revived.fieldErrors()).isNotNull().isEmpty();
        assertThat(revived.hasFieldErrors()).isFalse();

        assertThat(original.fieldErrors())
                .containsExactly(missingAcctStatus(), invalidCreditLimit());
        assertThat(original.hasFieldErrors()).isTrue();
    }

    @Test
    @DisplayName("The carrier is a value: identical components compare and hash equal, a differing state "
            + "compares unequal, and its rendering names the field, the legacy tag and the state")
    void theCarrierBehavesAsAValue() {
        ValidationException.FieldError first = invalidCreditLimit();
        ValidationException.FieldError same = invalidCreditLimit();
        ValidationException.FieldError differingState = new ValidationException.FieldError(
                PROP_CREDIT_LIMIT, BMS_CREDIT_LIMIT, ValidationException.FieldState.MISSING,
                CREDIT_LIMIT_MESSAGE);

        assertThat(first).isEqualTo(same);
        assertThat(first.hashCode()).isEqualTo(same.hashCode());
        assertThat(first).isNotEqualTo(differingState);
        assertThat(first).isNotEqualTo(missingAcctStatus());

        assertThat(first.toString())
                .contains(PROP_CREDIT_LIMIT, BMS_CREDIT_LIMIT, "INVALID", CREDIT_LIMIT_MESSAGE);
    }

    @Test
    @DisplayName("The exception faithfully carries a decoration for a field that no edit produced - the "
            + "middle name and the second address line are decorated but never validated - because it is "
            + "a carrier, not a validator")
    void theExceptionCarriesADecorationThatNoEditProduced() {
        ValidationException thrown = new ValidationException(SUMMARY, List.of(
                new ValidationException.FieldError(PROP_MIDDLE_NAME, BMS_MIDDLE_NAME,
                        ValidationException.FieldState.MISSING, DECORATION_ONLY_MESSAGE),
                new ValidationException.FieldError(PROP_ADDRESS_LINE_2, BMS_ADDRESS_LINE_2,
                        ValidationException.FieldState.MISSING, DECORATION_ONLY_MESSAGE)));

        assertThat(thrown.fieldErrors()).hasSize(2);
        assertThat(thrown.hasFieldErrors()).isTrue();

        ValidationException.FieldError middleName = thrown.fieldErrors().get(0);
        assertThat(middleName.field()).isEqualTo(PROP_MIDDLE_NAME);
        assertThat(middleName.bmsFieldId()).isEqualTo(BMS_MIDDLE_NAME);
        assertThat(middleName.state()).isSameAs(ValidationException.FieldState.MISSING);
        assertThat(middleName.message()).isEqualTo(DECORATION_ONLY_MESSAGE);

        ValidationException.FieldError secondAddressLine = thrown.fieldErrors().get(1);
        assertThat(secondAddressLine.field()).isEqualTo(PROP_ADDRESS_LINE_2);
        assertThat(secondAddressLine.bmsFieldId()).isEqualTo(BMS_ADDRESS_LINE_2);
        assertThat(secondAddressLine.state()).isSameAs(ValidationException.FieldState.MISSING);
        assertThat(secondAddressLine.message()).isEqualTo(DECORATION_ONLY_MESSAGE);
    }

    @Test
    @DisplayName("The type is an unchecked RuntimeException, is not an abend because a field-level failure "
            + "is recoverable and re-displayable, and lives in the project's own exception package")
    void theTypeIsARecoverableRuntimeExceptionAndNotAnAbend() {
        ValidationException thrown = new ValidationException(SUMMARY);

        assertThat(thrown).isInstanceOf(RuntimeException.class);
        assertThat(ValidationException.class.getSuperclass()).isSameAs(RuntimeException.class);

        assertThat(AbendException.class.isAssignableFrom(ValidationException.class)).isFalse();
        assertThat(ValidationException.class.isAssignableFrom(AbendException.class)).isFalse();

        assertThat(ValidationException.class.getPackageName()).isEqualTo("com.carddemo.exception");
        assertThat(ValidationException.class.getName())
                .isEqualTo("com.carddemo.exception.ValidationException");
    }

    @Test
    @DisplayName("A failure on a credential-style field names the field and the state only: the carrier has "
            + "no slot for a submitted value, so nothing that was typed can be echoed back")
    void aFailureOnACredentialStyleFieldEchoesNoSubmittedValue() {
        String submitted = "supplied-value-that-must-never-be-echoed";

        ValidationException thrown = new ValidationException(PROP_CREDENTIAL, BMS_CREDENTIAL,
                ValidationException.FieldState.INVALID, CREDENTIAL_MESSAGE);
        ValidationException.FieldError carried = thrown.fieldErrors().get(0);

        assertThat(thrown.getMessage()).isEqualTo(CREDENTIAL_MESSAGE).doesNotContain(submitted);
        assertThat(thrown.toString()).doesNotContain(submitted);

        assertThat(carried.field()).isEqualTo(PROP_CREDENTIAL);
        assertThat(carried.bmsFieldId()).isEqualTo(BMS_CREDENTIAL);
        assertThat(carried.state()).isSameAs(ValidationException.FieldState.INVALID);
        assertThat(carried.message()).isEqualTo(CREDENTIAL_MESSAGE).doesNotContain(submitted);
        assertThat(carried.toString())
                .doesNotContain(submitted)
                .contains(PROP_CREDENTIAL, BMS_CREDENTIAL, "INVALID");
    }

    private static ValidationException roundTrip(ValidationException original)
            throws IOException, ClassNotFoundException {
        ByteArrayOutputStream serialised = new ByteArrayOutputStream();
        try (ObjectOutputStream writer = new ObjectOutputStream(serialised)) {
            writer.writeObject(original);
        }
        try (ObjectInputStream reader =
                     new ObjectInputStream(new ByteArrayInputStream(serialised.toByteArray()))) {
            Object revived = reader.readObject();
            assertThat(revived).isInstanceOf(ValidationException.class);
            return (ValidationException) revived;
        }
    }

    private static ValidationException.FieldError missingAcctStatus() {
        return new ValidationException.FieldError(PROP_ACCT_STATUS, BMS_ACCT_STATUS,
                ValidationException.FieldState.MISSING, ACCT_STATUS_MESSAGE);
    }

    private static ValidationException.FieldError invalidOpenYear() {
        return new ValidationException.FieldError(PROP_OPEN_YEAR, BMS_OPEN_YEAR,
                ValidationException.FieldState.INVALID, OPEN_YEAR_MESSAGE);
    }

    private static ValidationException.FieldError invalidCreditLimit() {
        return new ValidationException.FieldError(PROP_CREDIT_LIMIT, BMS_CREDIT_LIMIT,
                ValidationException.FieldState.INVALID, CREDIT_LIMIT_MESSAGE);
    }
}
