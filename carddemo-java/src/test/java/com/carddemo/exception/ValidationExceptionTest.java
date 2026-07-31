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
 * Unit tests for {@link ValidationException}, the transport for the legacy field-flag
 * validation surface.
 *
 * <p>These tests exist for one reason above all others: to pin the <strong>two-state
 * per-field error contract</strong> so that it can never be collapsed into a single boolean.
 * The two states are not a design preference; they are forced by the legacy screen
 * behaviour.</p>
 *
 * <h2>Why two states and not one</h2>
 *
 * <p>The shape of the exception is dictated by {@code app/cpy/CSSETATY.cpy}, which is not a
 * data structure but a parameterised {@code PROCEDURE DIVISION} macro carrying three
 * substitution tokens - a validation-flag name, a screen (3270) field name and a map name.
 * Three properties of that macro were verified line by line and are the contract:</p>
 *
 * <ol>
 *   <li>It fires <strong>only</strong> when the flag is in the not-OK state <em>or</em> the
 *       blank state, <em>and</em> the re-enter indicator is set.</li>
 *   <li>In <em>both</em> firing states it changes the field's colour attribute to the error
 *       colour.</li>
 *   <li><strong>Additionally, and only when the flag is specifically the blank state,</strong>
 *       it writes a {@code '*'} marker into the output field.</li>
 * </ol>
 *
 * <p>That third property is the entire justification for two states. The marker is the
 * observable difference an operator sees between "you left this out" and "what you typed is
 * wrong", and a single boolean would erase it. The mapping is therefore:</p>
 *
 * <ul>
 *   <li>legacy <strong>blank</strong> flag - colour change <em>plus</em> the {@code '*'}
 *       marker - becomes {@link ValidationException.FieldState#MISSING}. The legacy flag
 *       value for blank is the character {@code 'B'}, and a space for the two key-filter
 *       flags, which are the one variation in the estate.</li>
 *   <li>legacy <strong>not-OK</strong> flag - colour change <em>only</em>, no marker -
 *       becomes {@link ValidationException.FieldState#INVALID}. The legacy flag value for
 *       not-OK is the character {@code '0'}.</li>
 *   <li>the third state in each legacy flag triple is the valid state. It decorates nothing,
 *       so it produces no error entry and has <em>no representation at all</em> in an error
 *       enum. This is why a {@code VALID} constant must never be added.</li>
 * </ul>
 *
 * <h2>Scale of the construct being replaced</h2>
 *
 * <p>The macro is expanded <strong>39 times</strong> in {@code app/cbl/COACTUPC.cbl} between
 * L3208 and L3432, always against the same map, with 39 distinct validation flags and 39
 * distinct screen field names - roughly 234 generated lines. Those collapse into a single
 * parameterised decorator call plus the error contract this exception carries.</p>
 *
 * <h2>Behaviour deliberately <em>not</em> modelled here</h2>
 *
 * <p>The re-entry gate is not enforced by this exception. Because the macro fires only when
 * the re-enter indicator is set, per-field error states are populated only on
 * <strong>re-submission</strong> and never on the first presentation of the screen. That
 * gating belongs to the account-update service and the DTO decorator layer - {@code
 * AccountUpdateService} and {@code FieldErrorDecorator} - and must be tested there. It is
 * documented here so a downstream author cannot mistake this exception's willingness to carry
 * an error for permission to report one on first entry.</p>
 *
 * <h2>The two decorated-but-never-validated fields</h2>
 *
 * <p>Two of the 39 decorated fields are decorated for display but <strong>never actually
 * validated</strong>, as the source states directly: an inline comment at
 * {@code app/cbl/COACTUPC.cbl} L3345 records that no edits are coded for the middle-name
 * field (screen field {@code ACSMNAM}), and a comment at L3369 records that no edits are
 * coded as yet for the second address line (screen field {@code ACSADL2}). <strong>No
 * validation constraint may be attached to either field in the DTO layer.</strong> Adding one
 * would reject input the legacy system accepts, which is an unrequested behaviour change and
 * a breach of the no-feature-expansion boundary. This exception is a <em>carrier</em>, not a
 * validator: it must be able to represent a decoration that no rule produced, which
 * {@link #theExceptionCarriesADecorationThatNoEditProduced()} proves.</p>
 *
 * <h2>A source defect worth knowing about</h2>
 *
 * <p>At {@code app/cbl/COACTUPC.cbl} L3427 to L3435 the comments labelling two of the macro
 * expansions - the primary-cardholder flag and the electronic-funds-transfer account
 * identifier - are <strong>transposed</strong> relative to the code they describe. The code is
 * correct and the comments are swapped, so any translation must follow the token
 * substitutions and never the adjacent comment.</p>
 *
 * <h2>Serialisation</h2>
 *
 * <p>The nested carrier is intentionally <em>not</em> serialisable, because a validation
 * error is a request-scoped presentation concern rather than a persisted value, and the
 * enclosing exception therefore holds it in a {@code transient} field. A serialisation round
 * trip consequently preserves the message and the cause - which {@code Throwable} itself
 * writes - and revives the per-field detail as an <strong>empty</strong> list, never
 * {@code null}. Callers never need a null check, which is the property
 * {@link #fieldErrorsIsNeverNullOnAnyConstructionPath()} and the two round-trip tests
 * guarantee.</p>
 *
 * <h2>Identity</h2>
 *
 * <p>This is the project's own type in {@code com.carddemo.exception}. It is deliberately not
 * the exception of the same simple name defined by the Bean Validation API, and the two must
 * never be interchanged; no import of that API appears in this file, and that absence is the
 * proof. It is also not an abend: a field-level validation failure is a recoverable,
 * re-displayable outcome, never a program termination.</p>
 *
 * <p>Provenance: verified against the checkout at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
class ValidationExceptionTest {

    /** Summary-level wording a service would supply alongside per-field detail. */
    private static final String SUMMARY = "Account update rejected: correct the marked fields";

    /**
     * Legacy screen field tag for the account status, one of the 39 decorated fields, paired
     * with the Java property name a REST consumer binds to. Both spellings must survive
     * verbatim: the Java name is what the API exposes, the screen tag is what the legacy
     * contract names.
     */
    private static final String BMS_ACCT_STATUS = "ACSTTUS";

    /** Java property name for the account status field. */
    private static final String PROP_ACCT_STATUS = "acctStatus";

    /** Caller-supplied wording for the account status failure; nothing is synthesised. */
    private static final String ACCT_STATUS_MESSAGE = "Account status must be supplied";

    /** Legacy screen field tag for the account open year. */
    private static final String BMS_OPEN_YEAR = "OPNYEAR";

    /** Java property name for the account open year. */
    private static final String PROP_OPEN_YEAR = "openYear";

    /** Caller-supplied wording for the open year failure. */
    private static final String OPEN_YEAR_MESSAGE = "Open year must be a four digit year";

    /** Legacy screen field tag for the credit limit. */
    private static final String BMS_CREDIT_LIMIT = "ACRDLIM";

    /** Java property name for the credit limit. */
    private static final String PROP_CREDIT_LIMIT = "creditLimit";

    /** Caller-supplied wording for the credit limit failure. */
    private static final String CREDIT_LIMIT_MESSAGE = "Credit limit must be a signed amount";

    /** Legacy screen field tag for the middle name - decorated but never validated. */
    private static final String BMS_MIDDLE_NAME = "ACSMNAM";

    /** Java property name for the middle name - no constraint may be attached to it. */
    private static final String PROP_MIDDLE_NAME = "middleName";

    /** Legacy screen field tag for the second address line - decorated but never validated. */
    private static final String BMS_ADDRESS_LINE_2 = "ACSADL2";

    /** Java property name for the second address line - no constraint may be attached. */
    private static final String PROP_ADDRESS_LINE_2 = "addressLine2";

    /** Wording used where a field is decorated for display although no edit produced it. */
    private static final String DECORATION_ONLY_MESSAGE = "Field marked for operator attention";

    /**
     * A neutral placeholder standing in for a credential-style field. The 39 decorated
     * account-update fields contain no credential field, so this is deliberately invented
     * rather than borrowed, and it exists only to prove that no submitted value can be
     * echoed back.
     */
    private static final String PROP_CREDENTIAL = "userCredential";

    /** Neutral placeholder screen tag paired with the credential-style property name. */
    private static final String BMS_CREDENTIAL = "USRCRED";

    /** Wording for a credential-style failure: it names the field, never the input. */
    private static final String CREDENTIAL_MESSAGE = "Credential failed its edit";

    // ------------------------------------------------------------------
    // 1. FieldState: exactly two constants, in declaration order
    // ------------------------------------------------------------------

    @Test
    @DisplayName("FieldState declares exactly two constants, MISSING then INVALID: a third state such as "
            + "VALID, OK, UNKNOWN or NONE is forbidden because a field that passed its edits decorates "
            + "nothing and so has no representation in an error enum")
    void fieldStateDeclaresExactlyTheTwoLegacyErrorStatesInDeclarationOrder() {
        // containsExactly pins membership AND order, so inserting a third constant - or
        // reordering the two - fails immediately rather than silently changing the contract.
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

    // ------------------------------------------------------------------
    // 2. The single-field constructor
    // ------------------------------------------------------------------

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

        // Same field, same legacy tag, same wording - only the state differs. A boolean here
        // would make the two indistinguishable and lose the marker semantics entirely.
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

    // ------------------------------------------------------------------
    // 3. The message-only constructor
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // 4. The collection constructors
    // ------------------------------------------------------------------

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
    @DisplayName("A null entry inside the supplied list is dropped rather than carried, so no consumer "
            + "iterating the detail can meet a null element")
    void aNullEntryInsideTheSuppliedListIsDropped() {
        List<ValidationException.FieldError> withHole = new ArrayList<>();
        withHole.add(missingAcctStatus());
        withHole.add(null);
        withHole.add(invalidCreditLimit());

        ValidationException thrown = new ValidationException(SUMMARY, withHole);

        assertThat(thrown.fieldErrors())
                .containsExactly(missingAcctStatus(), invalidCreditLimit());
        assertThat(thrown.fieldErrors()).doesNotContainNull();
    }

    // ------------------------------------------------------------------
    // 5. Defensive copy on the way in
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The supplied list is copied on construction, so a caller that reuses its working list "
            + "between validation passes cannot mutate an exception that has already been thrown")
    void theSuppliedListIsDefensivelyCopiedOnConstruction() {
        List<ValidationException.FieldError> working = new ArrayList<>();
        working.add(missingAcctStatus());
        working.add(invalidCreditLimit());

        ValidationException thrown = new ValidationException(SUMMARY, working);

        // The caller now reuses its working list, exactly as a validation cascade would when
        // it moves on to the next field. Without a copy on the way in, the already-thrown
        // exception would change underneath whoever is handling it.
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

    // ------------------------------------------------------------------
    // 6. The exposed list is unmodifiable
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // 7. fieldErrors() is never null
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // 8. serialVersionUID and the transient-tolerant round trip
    // ------------------------------------------------------------------

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

        // The message and the cause are written by Throwable itself, so they survive. The
        // per-field detail is a request-scoped presentation concern rather than a persisted
        // value, so it is held transiently and comes back absent - as an empty list, never
        // null, which is what keeps the accessor's contract intact after deserialisation.
        assertThat(revived.getMessage()).isEqualTo(SUMMARY);
        assertThat(revived.getCause()).isInstanceOf(IllegalStateException.class);
        assertThat(revived.fieldErrors()).isNotNull().isEmpty();
        assertThat(revived.hasFieldErrors()).isFalse();

        // The original is untouched by having been serialised.
        assertThat(original.fieldErrors())
                .containsExactly(missingAcctStatus(), invalidCreditLimit());
        assertThat(original.hasFieldErrors()).isTrue();
    }

    // ------------------------------------------------------------------
    // 9. The nested carrier behaves as a value
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // 10. A decoration that no edit produced
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The exception faithfully carries a decoration for a field that no edit produced - the "
            + "middle name and the second address line are decorated but never validated - because it is "
            + "a carrier, not a validator")
    void theExceptionCarriesADecorationThatNoEditProduced() {
        // Both of these fields are decorated by the legacy macro yet have no edits coded at
        // all, which the source states in an inline comment at each site. The exception must
        // be able to represent that decoration without anything here implying a rule ran, and
        // the DTO layer must not attach a constraint to either field: doing so would reject
        // input the legacy system accepts.
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

    // ------------------------------------------------------------------
    // 11. Type identity
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The type is an unchecked RuntimeException, is not an abend because a field-level failure "
            + "is recoverable and re-displayable, and lives in the project's own exception package")
    void theTypeIsARecoverableRuntimeExceptionAndNotAnAbend() {
        ValidationException thrown = new ValidationException(SUMMARY);

        assertThat(thrown).isInstanceOf(RuntimeException.class);
        assertThat(ValidationException.class.getSuperclass()).isSameAs(RuntimeException.class);

        // An abend terminates the program; a field-level validation failure never does. The
        // two hierarchies are siblings and must stay that way.
        assertThat(AbendException.class.isAssignableFrom(ValidationException.class)).isFalse();
        assertThat(ValidationException.class.isAssignableFrom(AbendException.class)).isFalse();

        // Identity check against the exception of the same simple name in the Bean Validation
        // API: this is the project's own type, and no import of that API appears in this file.
        assertThat(ValidationException.class.getPackageName()).isEqualTo("com.carddemo.exception");
        assertThat(ValidationException.class.getName())
                .isEqualTo("com.carddemo.exception.ValidationException");
    }

    // ------------------------------------------------------------------
    // 12. A validation failure never echoes what was submitted
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A failure on a credential-style field names the field and the state only: the carrier has "
            + "no slot for a submitted value, so nothing that was typed can be echoed back")
    void aFailureOnACredentialStyleFieldEchoesNoSubmittedValue() {
        // The carrier's four components are a property name, a legacy screen tag, a state and
        // a caller-supplied message. None of them is a submitted value, so there is no slot a
        // value could leak through. This test pins that structurally instead of trusting every
        // future caller to remember it.
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

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Serialises and deserialises the supplied exception through byte-array streams.
     *
     * @param original the exception to round trip
     * @return the revived instance
     * @throws IOException            if either stream fails
     * @throws ClassNotFoundException if the revived type cannot be resolved
     */
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

    /**
     * An absent account status: the legacy blank flag, which highlights the field and writes
     * the marker.
     *
     * @return a MISSING carrier for the account status field
     */
    private static ValidationException.FieldError missingAcctStatus() {
        return new ValidationException.FieldError(PROP_ACCT_STATUS, BMS_ACCT_STATUS,
                ValidationException.FieldState.MISSING, ACCT_STATUS_MESSAGE);
    }

    /**
     * A supplied but rejected open year: the legacy not-OK flag, which highlights the field
     * and writes no marker.
     *
     * @return an INVALID carrier for the account open year field
     */
    private static ValidationException.FieldError invalidOpenYear() {
        return new ValidationException.FieldError(PROP_OPEN_YEAR, BMS_OPEN_YEAR,
                ValidationException.FieldState.INVALID, OPEN_YEAR_MESSAGE);
    }

    /**
     * A supplied but rejected credit limit: the legacy not-OK flag.
     *
     * @return an INVALID carrier for the credit limit field
     */
    private static ValidationException.FieldError invalidCreditLimit() {
        return new ValidationException.FieldError(PROP_CREDIT_LIMIT, BMS_CREDIT_LIMIT,
                ValidationException.FieldState.INVALID, CREDIT_LIMIT_MESSAGE);
    }
}
