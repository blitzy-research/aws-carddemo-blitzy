/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.carddemo.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.carddemo.domain.enums.UserType;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Verifies the navigation context that replaces the legacy inter-program communication area.
 *
 * <p>Every one of the 17 online programs textually included the same communication-area copybook, and the
 * area it defined was the whole of the conversational state: which transaction and program control had come
 * from, which it was going to, who the operator was and what class of user they were, whether the program was
 * being entered for the first time or re-entered after a validation failure, and the customer, account and
 * card under navigation together with the last map displayed. That area travelled between pseudo-conversation
 * turns on the transaction-manager's own storage; in the migrated system it travels in a request and response
 * body, which is why it exists here as a value rather than as session state.</p>
 *
 * <p>The single most behaviourally significant field is the program context. In the copybook it is a
 * one-digit numeric field with two condition names - zero meaning a first entry and one meaning a re-entry -
 * and because a freshly acquired area is zero-initialised, the absence of any explicit value is
 * indistinguishable from a first entry. That is exactly what the field-error decoration depended on: the
 * account-update program marked fields in error only when it was on a re-entry, so a context that has not been
 * set must read as a first entry rather than as an unknown state. This test pins that.</p>
 *
 * <p>Scope: this exercises the record directly, plus the Bean Validation constraints it declares through a
 * validator built in this test alone. No Spring application context is started, no HTTP request is
 * dispatched, no database, file, network or container is touched, and this test performs no reflective
 * introspection of its own.</p>
 *
 * <p>Expectations are derived, never echoed. Every width is taken from the corresponding picture clause in
 * {@code app/cpy/COCOM01Y.cpy}: the transaction identifiers at 4, the program names at 8, the user identifier
 * at 8, the user type at 1, the customer identifier at 9, the three customer name parts at 25 each, the
 * account identifier at 11, the account status at 1, the card number at 16 and the two map names at 7 each.
 * The user-type vocabulary is taken from that copybook's two condition names for the type field, and the
 * program-context vocabulary from its two condition names for the context field. No expectation is read back
 * out of the class under test.</p>
 *
 * <p>Provenance: legacy checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced here.</p>
 */
@DisplayName("NavigationContext - the migrated inter-program communication area")
final class NavigationContextBaselineTest {

    /** Picture width of both transaction identifier fields. */
    private static final int COPYBOOK_TRANSACTION_ID_WIDTH = 4;

    /** Picture width of both program name fields. */
    private static final int COPYBOOK_PROGRAM_NAME_WIDTH = 8;

    /** Picture width of the user identifier field. */
    private static final int COPYBOOK_USER_ID_WIDTH = 8;

    /** Picture width of the user type field. */
    private static final int COPYBOOK_USER_TYPE_WIDTH = 1;

    /** Picture width of the customer identifier field. */
    private static final int COPYBOOK_CUSTOMER_ID_WIDTH = 9;

    /** Picture width of each of the three customer name fields. */
    private static final int COPYBOOK_CUSTOMER_NAME_WIDTH = 25;

    /** Picture width of the account identifier field. */
    private static final int COPYBOOK_ACCOUNT_ID_WIDTH = 11;

    /** Picture width of the account status field. */
    private static final int COPYBOOK_ACCOUNT_STATUS_WIDTH = 1;

    /** Picture width of the card number field. */
    private static final int COPYBOOK_CARD_NUMBER_WIDTH = 16;

    /** Picture width of each of the two map name fields. */
    private static final int COPYBOOK_MAP_NAME_WIDTH = 7;

    /**
     * Picture width of the program context field. The copybook declares it as a one-digit numeric field, so
     * it occupies one byte of the area even though the migrated record carries it as an enumerated value with
     * no width constant of its own.
     */
    private static final int COPYBOOK_PROGRAM_CONTEXT_WIDTH = 1;

    /**
     * Total byte width of the communication area, summed across all sixteen elementary fields: 34 bytes of
     * general information, 84 of customer information, 12 of account information, 16 of card information and
     * 14 of the trailing map names.
     */
    private static final int COPYBOOK_COMMAREA_WIDTH = 160;

    /** Byte width of the copybook's general-information group, which holds seven of the sixteen fields. */
    private static final int COPYBOOK_GENERAL_INFO_WIDTH = 34;

    /** Byte width of the copybook's customer-information group, which holds four fields. */
    private static final int COPYBOOK_CUSTOMER_INFO_WIDTH = 84;

    /** Byte width of the copybook's account-information group, which holds two fields. */
    private static final int COPYBOOK_ACCOUNT_INFO_WIDTH = 12;

    /** Byte width of the copybook's card-information group, which holds one field. */
    private static final int COPYBOOK_CARD_INFO_WIDTH = 16;

    /** Byte width of the copybook's trailing group, which holds the two map names. */
    private static final int COPYBOOK_MORE_INFO_WIDTH = 14;

    /** Number of elementary fields the copybook declares. */
    private static final int COPYBOOK_ELEMENTARY_FIELD_COUNT = 16;

    /** Number of group levels the copybook declares beneath the record. */
    private static final int COPYBOOK_GROUP_COUNT = 5;

    /** Number of program-context condition names the copybook declares. */
    private static final int COPYBOOK_PROGRAM_CONTEXT_STATES = 2;

    /** Numeric value the copybook's first-entry condition name carries. */
    private static final int COPYBOOK_FIRST_ENTRY_VALUE = 0;

    /** Numeric value the copybook's re-entry condition name carries. */
    private static final int COPYBOOK_REENTRY_VALUE = 1;

    /** The one-character code the copybook's administrator condition name carries. */
    private static final String COPYBOOK_ADMIN_TYPE_CODE = "A";

    /** The one-character code the copybook's standard-user condition name carries. */
    private static final String COPYBOOK_USER_TYPE_CODE = "U";

    /** The number of online programs that each included this copybook. */
    private static final int INCLUDING_PROGRAM_COUNT = 17;

    /** The validator used to exercise the declared width constraints. */
    private static ValidatorFactory validatorFactory;

    /** The validator drawn from that factory. */
    private static Validator validator;

    @BeforeAll
    static void buildValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    /**
     * Builds a fully populated context so that a copy operation can be checked slot by slot.
     *
     * @param programContext the program context to carry
     * @return the context
     */
    private static NavigationContext populated(final NavigationContext.ProgramContext programContext) {
        return new NavigationContext(
                "CC00",
                "COSGN00C",
                "CAUP",
                "COACTUPC",
                "ADMIN001",
                COPYBOOK_ADMIN_TYPE_CODE,
                programContext,
                "000000001",
                "Immanuel",
                "Madeline",
                "Kessler",
                "00000000001",
                "Y",
                "4859452612877065",
                "CACTUPA",
                "COACTUP");
    }

    /**
     * Builds a context whose only populated slot is the user type.
     *
     * @param userType the user type code, possibly {@code null}
     * @return the context
     */
    private static NavigationContext withUserType(final String userType) {
        return new NavigationContext(
                null, null, null, null, null, userType, null, null,
                null, null, null, null, null, null, null, null);
    }

    /**
     * Builds a context whose only populated slot is the program context.
     *
     * @param programContext the program context, possibly {@code null}
     * @return the context
     */
    private static NavigationContext withProgramContextOnly(
            final NavigationContext.ProgramContext programContext) {
        return new NavigationContext(
                null, null, null, null, null, null, programContext, null,
                null, null, null, null, null, null, null, null);
    }

    /**
     * Builds a context with the named component set to the supplied value and every other slot empty.
     *
     * @param component the record component to populate
     * @param value     the value to place in it
     * @return the context
     */
    private static NavigationContext withComponent(final String component, final String value) {
        return switch (component) {
            case "fromTransactionId" -> new NavigationContext(value, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);
            case "fromProgram" -> new NavigationContext(null, value, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);
            case "toTransactionId" -> new NavigationContext(null, null, value, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);
            case "toProgram" -> new NavigationContext(null, null, null, value, null, null, null,
                    null, null, null, null, null, null, null, null, null);
            case "userId" -> new NavigationContext(null, null, null, null, value, null, null,
                    null, null, null, null, null, null, null, null, null);
            case "userType" -> withUserType(value);
            case "customerId" -> new NavigationContext(null, null, null, null, null, null, null,
                    value, null, null, null, null, null, null, null, null);
            case "customerFirstName" -> new NavigationContext(null, null, null, null, null, null, null,
                    null, value, null, null, null, null, null, null, null);
            case "customerMiddleName" -> new NavigationContext(null, null, null, null, null, null, null,
                    null, null, value, null, null, null, null, null, null);
            case "customerLastName" -> new NavigationContext(null, null, null, null, null, null, null,
                    null, null, null, value, null, null, null, null, null);
            case "accountId" -> new NavigationContext(null, null, null, null, null, null, null,
                    null, null, null, null, value, null, null, null, null);
            case "accountStatus" -> new NavigationContext(null, null, null, null, null, null, null,
                    null, null, null, null, null, value, null, null, null);
            case "cardNumber" -> new NavigationContext(null, null, null, null, null, null, null,
                    null, null, null, null, null, null, value, null, null);
            case "lastMap" -> new NavigationContext(null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, value, null);
            case "lastMapset" -> new NavigationContext(null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, value);
            default -> throw new IllegalArgumentException("Unknown component: " + component);
        };
    }

    /**
     * Repeats a filler character to the requested width.
     *
     * @param width the width
     * @return a string of exactly that many characters
     */
    private static String filled(final int width) {
        return "X".repeat(width);
    }

    @Nested
    @DisplayName("The shape of the record")
    class RecordShape {

        @Test
        @DisplayName("the empty context leaves every slot unset, which is the state of a communication area "
                + "that has been acquired but not yet populated")
        void theEmptyContextLeavesEverySlotUnset() {
            final NavigationContext empty = NavigationContext.empty();

            assertThat(empty.fromTransactionId()).isNull();
            assertThat(empty.fromProgram()).isNull();
            assertThat(empty.toTransactionId()).isNull();
            assertThat(empty.toProgram()).isNull();
            assertThat(empty.userId()).isNull();
            assertThat(empty.userType()).isNull();
            assertThat(empty.programContext()).isNull();
            assertThat(empty.customerId()).isNull();
            assertThat(empty.customerFirstName()).isNull();
            assertThat(empty.customerMiddleName()).isNull();
            assertThat(empty.customerLastName()).isNull();
            assertThat(empty.accountId()).isNull();
            assertThat(empty.accountStatus()).isNull();
            assertThat(empty.cardNumber()).isNull();
            assertThat(empty.lastMap()).isNull();
            assertThat(empty.lastMapset()).isNull();
        }

        @Test
        @DisplayName("the empty context is a shared immutable value, so obtaining it costs nothing and no "
                + "caller can alter what a later caller receives")
        void theEmptyContextIsASharedImmutableValue() {
            assertThat(NavigationContext.empty()).isSameAs(NavigationContext.empty());
        }

        @Test
        @DisplayName("an explicitly all-empty context equals the shared one, so the two ways of expressing an "
                + "unpopulated area cannot diverge")
        void anExplicitlyAllEmptyContextEqualsTheSharedOne() {
            final NavigationContext explicit = new NavigationContext(
                    null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null);

            assertThat(explicit)
                    .isEqualTo(NavigationContext.empty())
                    .hasSameHashCodeAs(NavigationContext.empty());
        }

        @Test
        @DisplayName("every slot is stored verbatim in its own position, so the sixteen elementary fields "
                + "cannot be transposed - the two transaction identifiers, the two program names and the two "
                + "map names are each the same width as their partner and would transpose silently")
        void everySlotIsStoredVerbatimInItsOwnPosition() {
            final NavigationContext context = populated(NavigationContext.ProgramContext.REENTER);

            assertThat(context.fromTransactionId()).isEqualTo("CC00");
            assertThat(context.fromProgram()).isEqualTo("COSGN00C");
            assertThat(context.toTransactionId()).isEqualTo("CAUP");
            assertThat(context.toProgram()).isEqualTo("COACTUPC");
            assertThat(context.userId()).isEqualTo("ADMIN001");
            assertThat(context.userType()).isEqualTo(COPYBOOK_ADMIN_TYPE_CODE);
            assertThat(context.programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
            assertThat(context.customerId()).isEqualTo("000000001");
            assertThat(context.customerFirstName()).isEqualTo("Immanuel");
            assertThat(context.customerMiddleName()).isEqualTo("Madeline");
            assertThat(context.customerLastName()).isEqualTo("Kessler");
            assertThat(context.accountId()).isEqualTo("00000000001");
            assertThat(context.accountStatus()).isEqualTo("Y");
            assertThat(context.cardNumber()).isEqualTo("4859452612877065");
            assertThat(context.lastMap()).isEqualTo("CACTUPA");
            assertThat(context.lastMapset()).isEqualTo("COACTUP");
        }

        @Test
        @DisplayName("the rendering names exactly the sixteen elementary copybook fields")
        void theRenderingNamesExactlyTheSixteenElementaryFields() {
            final String rendered = NavigationContext.empty().toString();

            assertThat(rendered).startsWith("NavigationContext[").endsWith("]");
            assertThat(rendered).contains(
                    "fromTransactionId=", "fromProgram=", "toTransactionId=", "toProgram=",
                    "userId=", "userType=", "programContext=", "customerId=",
                    "customerFirstName=", "customerMiddleName=", "customerLastName=",
                    "accountId=", "accountStatus=", "cardNumber=", "lastMap=", "lastMapset=");
        }

        @Test
        @DisplayName("the program context occupies the seventh position, immediately after the user type, "
                + "which is where the copybook's general-information group ends")
        void theProgramContextOccupiesTheSeventhPosition() {
            final NavigationContext context = new NavigationContext(
                    "a", "b", "c", "d", "e", "f",
                    NavigationContext.ProgramContext.ENTER,
                    "h", "i", "j", "k", "l", "m", "n", "o", "p");

            assertThat(context.userType()).isEqualTo("f");
            assertThat(context.programContext()).isEqualTo(NavigationContext.ProgramContext.ENTER);
            assertThat(context.customerId()).isEqualTo("h");
        }

        @Test
        @DisplayName("the sixteen elementary fields divide into the five copybook groups as seven, four, two, "
                + "one and two, which together account for every field with none left over")
        void theSixteenFieldsDivideIntoTheFiveCopybookGroups() {
            final int generalInformation = 7;
            final int customerInformation = 4;
            final int accountInformation = 2;
            final int cardInformation = 1;
            final int moreInformation = 2;

            assertThat(generalInformation + customerInformation + accountInformation
                    + cardInformation + moreInformation).isEqualTo(COPYBOOK_ELEMENTARY_FIELD_COUNT);
            assertThat(COPYBOOK_GROUP_COUNT).isEqualTo(5);
        }

        @Test
        @DisplayName("seventeen online programs shared this one area, which is why it is a single injected "
                + "value here rather than seventeen textual duplications")
        void seventeenOnlineProgramsSharedThisOneArea() {
            assertThat(INCLUDING_PROGRAM_COUNT).isEqualTo(17);
        }
    }

    @Nested
    @DisplayName("The declared field widths")
    class DeclaredFieldWidths {

        @Test
        @DisplayName("the transaction identifier width matches its picture clause, and both transaction slots "
                + "share it as the copybook declares")
        void theTransactionIdentifierWidthMatchesItsPictureClause() {
            assertThat(NavigationContext.TRANSACTION_ID_LENGTH)
                    .isEqualTo(COPYBOOK_TRANSACTION_ID_WIDTH);
        }

        @Test
        @DisplayName("the program name width matches its picture clause and equals the user identifier width, "
                + "as the copybook declares - which is why the two must never be swapped")
        void theProgramNameWidthMatchesItsPictureClause() {
            assertThat(NavigationContext.PROGRAM_NAME_LENGTH).isEqualTo(COPYBOOK_PROGRAM_NAME_WIDTH);
            assertThat(NavigationContext.USER_ID_LENGTH).isEqualTo(COPYBOOK_USER_ID_WIDTH);
            assertThat(NavigationContext.PROGRAM_NAME_LENGTH)
                    .isEqualTo(NavigationContext.USER_ID_LENGTH);
        }

        @Test
        @DisplayName("the user type and the account status are one byte each, because each held a single "
                + "classification character")
        void theUserTypeAndAccountStatusAreOneByteEach() {
            assertThat(NavigationContext.USER_TYPE_LENGTH).isEqualTo(COPYBOOK_USER_TYPE_WIDTH);
            assertThat(NavigationContext.ACCOUNT_STATUS_LENGTH)
                    .isEqualTo(COPYBOOK_ACCOUNT_STATUS_WIDTH);
        }

        @Test
        @DisplayName("the customer, account and card widths match their picture clauses and are pairwise "
                + "distinct, so no two identifiers can be interchanged by accident")
        void theIdentifierWidthsMatchAndArePairwiseDistinct() {
            assertThat(NavigationContext.CUSTOMER_ID_LENGTH).isEqualTo(COPYBOOK_CUSTOMER_ID_WIDTH);
            assertThat(NavigationContext.ACCOUNT_ID_LENGTH).isEqualTo(COPYBOOK_ACCOUNT_ID_WIDTH);
            assertThat(NavigationContext.CARD_NUMBER_LENGTH).isEqualTo(COPYBOOK_CARD_NUMBER_WIDTH);

            assertThat(Set.of(NavigationContext.CUSTOMER_ID_LENGTH,
                    NavigationContext.ACCOUNT_ID_LENGTH,
                    NavigationContext.CARD_NUMBER_LENGTH)).hasSize(3);
        }

        @Test
        @DisplayName("the customer name width matches its picture clause and is shared by all three name "
                + "parts, so a first, middle or last name is bounded identically")
        void theCustomerNameWidthMatchesItsPictureClause() {
            assertThat(NavigationContext.CUSTOMER_NAME_LENGTH)
                    .isEqualTo(COPYBOOK_CUSTOMER_NAME_WIDTH);
        }

        @Test
        @DisplayName("the map name width matches its picture clause and is shared by the map and the mapset, "
                + "which is why the two would transpose silently and must be positionally checked")
        void theMapNameWidthMatchesItsPictureClause() {
            assertThat(NavigationContext.MAP_NAME_LENGTH).isEqualTo(COPYBOOK_MAP_NAME_WIDTH);
        }

        @Test
        @DisplayName("the sixteen field widths sum to the byte width of the whole communication area, which is "
                + "the arithmetic check that no field was widened or narrowed in translation")
        void theSixteenFieldWidthsSumToTheCommareaWidth() {
            final int generalInformation = NavigationContext.TRANSACTION_ID_LENGTH
                    + NavigationContext.PROGRAM_NAME_LENGTH
                    + NavigationContext.TRANSACTION_ID_LENGTH
                    + NavigationContext.PROGRAM_NAME_LENGTH
                    + NavigationContext.USER_ID_LENGTH
                    + NavigationContext.USER_TYPE_LENGTH
                    + COPYBOOK_PROGRAM_CONTEXT_WIDTH;
            final int customerInformation = NavigationContext.CUSTOMER_ID_LENGTH
                    + (3 * NavigationContext.CUSTOMER_NAME_LENGTH);
            final int accountInformation = NavigationContext.ACCOUNT_ID_LENGTH
                    + NavigationContext.ACCOUNT_STATUS_LENGTH;
            final int cardInformation = NavigationContext.CARD_NUMBER_LENGTH;
            final int moreInformation = 2 * NavigationContext.MAP_NAME_LENGTH;

            assertThat(generalInformation).isEqualTo(COPYBOOK_GENERAL_INFO_WIDTH);
            assertThat(customerInformation).isEqualTo(COPYBOOK_CUSTOMER_INFO_WIDTH);
            assertThat(accountInformation).isEqualTo(COPYBOOK_ACCOUNT_INFO_WIDTH);
            assertThat(cardInformation).isEqualTo(COPYBOOK_CARD_INFO_WIDTH);
            assertThat(moreInformation).isEqualTo(COPYBOOK_MORE_INFO_WIDTH);
            assertThat(generalInformation + customerInformation + accountInformation
                    + cardInformation + moreInformation).isEqualTo(COPYBOOK_COMMAREA_WIDTH);
            assertThat(COPYBOOK_GENERAL_INFO_WIDTH + COPYBOOK_CUSTOMER_INFO_WIDTH
                    + COPYBOOK_ACCOUNT_INFO_WIDTH + COPYBOOK_CARD_INFO_WIDTH
                    + COPYBOOK_MORE_INFO_WIDTH).isEqualTo(COPYBOOK_COMMAREA_WIDTH);
        }
    }

    @Nested
    @DisplayName("Enforcement of the declared widths")
    class WidthEnforcement {

        @ParameterizedTest(name = "{0} accepts a value of exactly {1} characters")
        @CsvSource({
            "fromTransactionId, 4", "fromProgram, 8", "toTransactionId, 4", "toProgram, 8",
            "userId, 8", "userType, 1", "customerId, 9", "customerFirstName, 25",
            "customerMiddleName, 25", "customerLastName, 25", "accountId, 11", "accountStatus, 1",
            "cardNumber, 16", "lastMap, 7", "lastMapset, 7"
        })
        @DisplayName("a value filling its field exactly is accepted, because a fixed-width legacy field was "
                + "routinely full")
        void aValueFillingItsFieldExactlyIsAccepted(final String component, final int width) {
            assertThat(validator.validate(withComponent(component, filled(width)))).isEmpty();
        }

        @ParameterizedTest(name = "{0} rejects a value of {1} characters, one over its width")
        @CsvSource({
            "fromTransactionId, 5", "fromProgram, 9", "toTransactionId, 5", "toProgram, 9",
            "userId, 9", "userType, 2", "customerId, 10", "customerFirstName, 26",
            "customerMiddleName, 26", "customerLastName, 26", "accountId, 12", "accountStatus, 2",
            "cardNumber, 17", "lastMap, 8", "lastMapset, 8"
        })
        @DisplayName("a value one character over its field is rejected, because a legacy field could not hold "
                + "it and accepting it here would let a value through that no record layout can carry")
        void aValueOneCharacterOverItsFieldIsRejected(final String component, final int width) {
            final Set<ConstraintViolation<NavigationContext>> violations =
                    validator.validate(withComponent(component, filled(width)));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo(component);
        }

        @Test
        @DisplayName("the empty context is valid, so a first request needs no placeholder values")
        void theEmptyContextIsValid() {
            assertThat(validator.validate(NavigationContext.empty())).isEmpty();
        }

        @Test
        @DisplayName("a fully populated realistic context is valid, so the widths accommodate genuine seeded "
                + "data rather than only synthetic values")
        void aFullyPopulatedRealisticContextIsValid() {
            assertThat(validator.validate(populated(NavigationContext.ProgramContext.ENTER))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Resolution of the user type")
    class UserTypeResolution {

        @Test
        @DisplayName("the administrator code resolves to the administrator type and reports administrator "
                + "authority, which is the routing decision the sign-on program made")
        void theAdministratorCodeResolvesAndReportsAuthority() {
            final NavigationContext context = withUserType(COPYBOOK_ADMIN_TYPE_CODE);

            assertThat(context.resolvedUserType()).contains(UserType.ADMIN);
            assertThat(context.administrator()).isTrue();
        }

        @Test
        @DisplayName("the standard-user code resolves to the standard type and reports no administrator "
                + "authority, so an ordinary operator cannot reach an administrative route")
        void theStandardUserCodeResolvesWithoutAuthority() {
            final NavigationContext context = withUserType(COPYBOOK_USER_TYPE_CODE);

            assertThat(context.resolvedUserType()).contains(UserType.USER);
            assertThat(context.administrator()).isFalse();
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", " ", "a", "u", "X", "AU", "0", "1", "ADMIN"})
        @DisplayName("any code the copybook does not declare resolves to nothing and reports no administrator "
                + "authority, so an unrecognised classification fails closed rather than being promoted")
        void anUndeclaredCodeResolvesToNothingAndFailsClosed(final String code) {
            final NavigationContext context = withUserType(code);

            assertThat(context.resolvedUserType()).isEmpty();
            assertThat(context.administrator()).isFalse();
        }

        @Test
        @DisplayName("the lower-case administrator code is not accepted, because the classification was a "
                + "single stored character compared exactly and no case folding took place")
        void theLowerCaseAdministratorCodeIsNotAccepted() {
            assertThat(withUserType("a").resolvedUserType()).isEmpty();
            assertThat(withUserType("a").administrator()).isFalse();
        }

        @Test
        @DisplayName("both declared codes are one character wide, matching the picture clause of the field "
                + "that carried them")
        void bothDeclaredCodesAreOneCharacterWide() {
            assertThat(COPYBOOK_ADMIN_TYPE_CODE).hasSize(NavigationContext.USER_TYPE_LENGTH);
            assertThat(COPYBOOK_USER_TYPE_CODE).hasSize(NavigationContext.USER_TYPE_LENGTH);
            assertThat(COPYBOOK_ADMIN_TYPE_CODE).isNotEqualTo(COPYBOOK_USER_TYPE_CODE);
        }

        @ParameterizedTest(name = "{0} is resolvable from its own code")
        @EnumSource(UserType.class)
        @DisplayName("every declared user type is reachable through the context, so no classification exists "
                + "that the navigation state cannot express")
        void everyDeclaredUserTypeIsReachable(final UserType userType) {
            assertThat(withUserType(userType.getCode()).resolvedUserType()).contains(userType);
        }
    }

    @Nested
    @DisplayName("The program context and the entry state")
    class ProgramContextAndEntryState {

        @Test
        @DisplayName("exactly two program contexts are declared, matching the copybook's two condition names "
                + "on the one-digit context field")
        void exactlyTwoProgramContextsAreDeclared() {
            assertThat(NavigationContext.ProgramContext.values())
                    .hasSize(COPYBOOK_PROGRAM_CONTEXT_STATES);
        }

        @Test
        @DisplayName("the two contexts are declared in the order the copybook's numeric values give them, so "
                + "the first-entry state carries the ordinal the zero-valued condition name carried")
        void theTwoContextsFollowTheCopybookNumericOrder() {
            assertThat(NavigationContext.ProgramContext.ENTER.ordinal())
                    .isEqualTo(COPYBOOK_FIRST_ENTRY_VALUE);
            assertThat(NavigationContext.ProgramContext.REENTER.ordinal())
                    .isEqualTo(COPYBOOK_REENTRY_VALUE);
            assertThat(NavigationContext.ProgramContext.values())
                    .extracting(Enum::name)
                    .containsExactly("ENTER", "REENTER");
        }

        @Test
        @DisplayName("an unset program context reads as a first entry, because a freshly acquired "
                + "communication area was zero-initialised and zero was the first-entry condition - this is "
                + "what stops field-error decoration from firing on an initial request")
        void anUnsetProgramContextReadsAsAFirstEntry() {
            final NavigationContext context = withProgramContextOnly(null);

            assertThat(context.programContext()).isNull();
            assertThat(context.firstEntry()).isTrue();
            assertThat(context.reEntry()).isFalse();
        }

        @Test
        @DisplayName("the shared empty context likewise reads as a first entry, so the default state of the "
                + "value agrees with the default state of the legacy storage")
        void theSharedEmptyContextReadsAsAFirstEntry() {
            assertThat(NavigationContext.empty().firstEntry()).isTrue();
            assertThat(NavigationContext.empty().reEntry()).isFalse();
        }

        @Test
        @DisplayName("an explicit first-entry context reads as a first entry, so setting the state explicitly "
                + "agrees with leaving it unset")
        void anExplicitFirstEntryContextReadsAsAFirstEntry() {
            final NavigationContext context =
                    withProgramContextOnly(NavigationContext.ProgramContext.ENTER);

            assertThat(context.firstEntry()).isTrue();
            assertThat(context.reEntry()).isFalse();
        }

        @Test
        @DisplayName("a re-entry context reads as a re-entry, which is the one state in which the "
                + "account-update screen decorated its fields in error")
        void aReEntryContextReadsAsAReEntry() {
            final NavigationContext context =
                    withProgramContextOnly(NavigationContext.ProgramContext.REENTER);

            assertThat(context.reEntry()).isTrue();
            assertThat(context.firstEntry()).isFalse();
        }

        @ParameterizedTest(name = "with context {0} the two entry predicates disagree")
        @EnumSource(NavigationContext.ProgramContext.class)
        @DisplayName("the two entry predicates are exact complements for every declared context, so there is "
                + "no state in which both or neither holds")
        void theTwoEntryPredicatesAreExactComplements(
                final NavigationContext.ProgramContext programContext) {
            final NavigationContext context = withProgramContextOnly(programContext);

            assertThat(context.firstEntry()).isNotEqualTo(context.reEntry());
        }

        @Test
        @DisplayName("the predicates are complements for the unset context as well, so the absent state is "
                + "covered by the same invariant")
        void thePredicatesAreComplementsForTheUnsetContextToo() {
            final NavigationContext context = withProgramContextOnly(null);

            assertThat(context.firstEntry()).isNotEqualTo(context.reEntry());
        }
    }

    @Nested
    @DisplayName("Deriving a context with a different entry state")
    class DerivingADifferentEntryState {

        @Test
        @DisplayName("switching to a first entry sets the context and leaves the other fifteen slots exactly "
                + "as they were, so re-arming a screen cannot lose the navigation state")
        void switchingToAFirstEntryPreservesTheOtherFifteenSlots() {
            final NavigationContext original = populated(NavigationContext.ProgramContext.REENTER);

            final NavigationContext derived = original.withFirstEntry();

            assertThat(derived.programContext()).isEqualTo(NavigationContext.ProgramContext.ENTER);
            assertThat(derived.firstEntry()).isTrue();
            assertThat(derived.fromTransactionId()).isEqualTo(original.fromTransactionId());
            assertThat(derived.fromProgram()).isEqualTo(original.fromProgram());
            assertThat(derived.toTransactionId()).isEqualTo(original.toTransactionId());
            assertThat(derived.toProgram()).isEqualTo(original.toProgram());
            assertThat(derived.userId()).isEqualTo(original.userId());
            assertThat(derived.userType()).isEqualTo(original.userType());
            assertThat(derived.customerId()).isEqualTo(original.customerId());
            assertThat(derived.customerFirstName()).isEqualTo(original.customerFirstName());
            assertThat(derived.customerMiddleName()).isEqualTo(original.customerMiddleName());
            assertThat(derived.customerLastName()).isEqualTo(original.customerLastName());
            assertThat(derived.accountId()).isEqualTo(original.accountId());
            assertThat(derived.accountStatus()).isEqualTo(original.accountStatus());
            assertThat(derived.cardNumber()).isEqualTo(original.cardNumber());
            assertThat(derived.lastMap()).isEqualTo(original.lastMap());
            assertThat(derived.lastMapset()).isEqualTo(original.lastMapset());
        }

        @Test
        @DisplayName("switching to a re-entry sets the context and leaves the other fifteen slots exactly as "
                + "they were, so a validation failure can re-present the screen with its data intact")
        void switchingToAReEntryPreservesTheOtherFifteenSlots() {
            final NavigationContext original = populated(NavigationContext.ProgramContext.ENTER);

            final NavigationContext derived = original.withReEntry();

            assertThat(derived.programContext()).isEqualTo(NavigationContext.ProgramContext.REENTER);
            assertThat(derived.reEntry()).isTrue();
            assertThat(derived.fromTransactionId()).isEqualTo(original.fromTransactionId());
            assertThat(derived.fromProgram()).isEqualTo(original.fromProgram());
            assertThat(derived.toTransactionId()).isEqualTo(original.toTransactionId());
            assertThat(derived.toProgram()).isEqualTo(original.toProgram());
            assertThat(derived.userId()).isEqualTo(original.userId());
            assertThat(derived.userType()).isEqualTo(original.userType());
            assertThat(derived.customerId()).isEqualTo(original.customerId());
            assertThat(derived.customerFirstName()).isEqualTo(original.customerFirstName());
            assertThat(derived.customerMiddleName()).isEqualTo(original.customerMiddleName());
            assertThat(derived.customerLastName()).isEqualTo(original.customerLastName());
            assertThat(derived.accountId()).isEqualTo(original.accountId());
            assertThat(derived.accountStatus()).isEqualTo(original.accountStatus());
            assertThat(derived.cardNumber()).isEqualTo(original.cardNumber());
            assertThat(derived.lastMap()).isEqualTo(original.lastMap());
            assertThat(derived.lastMapset()).isEqualTo(original.lastMapset());
        }

        @Test
        @DisplayName("deriving leaves the original untouched, so a request context cannot be mutated by the "
                + "act of preparing a response context")
        void derivingLeavesTheOriginalUntouched() {
            final NavigationContext original = populated(NavigationContext.ProgramContext.ENTER);

            final NavigationContext derived = original.withReEntry();

            assertThat(original.programContext()).isEqualTo(NavigationContext.ProgramContext.ENTER);
            assertThat(original.reEntry()).isFalse();
            assertThat(derived).isNotSameAs(original).isNotEqualTo(original);
        }

        @Test
        @DisplayName("deriving from an unset context supplies the state explicitly, so a context that arrived "
                + "without one leaves with one")
        void derivingFromAnUnsetContextSuppliesTheStateExplicitly() {
            assertThat(NavigationContext.empty().withFirstEntry().programContext())
                    .isEqualTo(NavigationContext.ProgramContext.ENTER);
            assertThat(NavigationContext.empty().withReEntry().programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
        }

        @Test
        @DisplayName("deriving the state already held yields an equal context, so the operation is idempotent")
        void derivingTheStateAlreadyHeldYieldsAnEqualContext() {
            final NavigationContext firstEntry = populated(NavigationContext.ProgramContext.ENTER);
            final NavigationContext reEntry = populated(NavigationContext.ProgramContext.REENTER);

            assertThat(firstEntry.withFirstEntry()).isEqualTo(firstEntry);
            assertThat(reEntry.withReEntry()).isEqualTo(reEntry);
            assertThat(firstEntry.withFirstEntry().withFirstEntry()).isEqualTo(firstEntry);
        }

        @Test
        @DisplayName("switching back and forth returns to the starting value, so the two derivations are "
                + "mutual inverses over the context slot")
        void switchingBackAndForthReturnsToTheStartingValue() {
            final NavigationContext start = populated(NavigationContext.ProgramContext.ENTER);

            assertThat(start.withReEntry().withFirstEntry()).isEqualTo(start);
        }

        @Test
        @DisplayName("a derived context is still valid against the declared widths, so deriving cannot "
                + "produce a value the record layouts could not carry")
        void aDerivedContextIsStillValid() {
            assertThat(validator.validate(
                    populated(NavigationContext.ProgramContext.ENTER).withReEntry())).isEmpty();
        }
    }

    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("two contexts built from the same values are equal and agree on hash code")
        void twoContextsBuiltFromTheSameValuesAreEqual() {
            final NavigationContext first = populated(NavigationContext.ProgramContext.ENTER);
            final NavigationContext second = populated(NavigationContext.ProgramContext.ENTER);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second).isNotSameAs(second);
        }

        @Test
        @DisplayName("contexts differing only in program context are not equal, so the entry state is part of "
                + "the value rather than incidental")
        void contextsDifferingOnlyInProgramContextAreNotEqual() {
            assertThat(populated(NavigationContext.ProgramContext.ENTER))
                    .isNotEqualTo(populated(NavigationContext.ProgramContext.REENTER));
        }

        @Test
        @DisplayName("contexts differing only in user type are not equal, so the authority classification "
                + "cannot be lost in a comparison")
        void contextsDifferingOnlyInUserTypeAreNotEqual() {
            assertThat(withUserType(COPYBOOK_ADMIN_TYPE_CODE))
                    .isNotEqualTo(withUserType(COPYBOOK_USER_TYPE_CODE));
        }

        @Test
        @DisplayName("a context with the map and mapset transposed is a different value, which is the check "
                + "two same-width neighbouring fields need")
        void aContextWithMapAndMapsetTransposedIsADifferentValue() {
            final NavigationContext straight = withComponent("lastMap", "CACTUPA");
            final NavigationContext transposed = withComponent("lastMapset", "CACTUPA");

            assertThat(straight).isNotEqualTo(transposed);
        }

        @Test
        @DisplayName("a context with the two transaction identifiers transposed is a different value, for the "
                + "same reason")
        void aContextWithTransactionIdentifiersTransposedIsADifferentValue() {
            assertThat(withComponent("fromTransactionId", "CC00"))
                    .isNotEqualTo(withComponent("toTransactionId", "CC00"));
        }

        @Test
        @DisplayName("a context is not equal to an unrelated value")
        void aContextIsNotEqualToAnUnrelatedValue() {
            assertThat(NavigationContext.empty())
                    .isNotEqualTo("NavigationContext")
                    .isNotEqualTo(NavigationContext.ProgramContext.ENTER);
        }
    }
}
