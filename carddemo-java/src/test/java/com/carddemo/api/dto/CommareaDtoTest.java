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
package com.carddemo.api.dto;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Verifies the three types that carry what the legacy communication area and screen work area carried.
 *
 * <p>These replace textual copybook inclusion with injected, immutable state, and three of their
 * properties are contractual rather than incidental:
 *
 * <ol>
 *   <li><strong>The program-context flag drives error decoration.</strong> The field-decoration macro
 *       fires only on re-entry, so a first entry must never present field-level errors. Modelling the
 *       flag as a two-valued state rather than a boolean pair keeps the two legacy condition names
 *       distinguishable and makes the "not yet set" case answer first entry, which is what an empty
 *       communication area means.</li>
 *   <li><strong>The numeric aliases are derived views, never second stored components.</strong> The
 *       work area declares each identifier once with a numeric redefinition over the same bytes. A
 *       redefinition is one storage location read two ways, so a second stored field could drift from
 *       the first. Deriving on demand makes drift impossible, and a non-numeric image simply has no
 *       numeric view rather than raising.</li>
 *   <li><strong>Error decoration distinguishes two states, not one.</strong> The macro colours a field
 *       when its flag is not-OK and additionally writes a marker when the flag is specifically blank.
 *       Those are separate conditions in the source, so the response carries missing and invalid
 *       separately; collapsing them would lose which of the two the operator saw.</li>
 * </ol>
 */
@DisplayName("Communication-area and screen-work-area carriers")
final class CommareaDtoTest {

    // Field widths transcribed from the copybooks.

    /** {@code CDEMO-FROM-TRANID} and its peers: four characters. */
    private static final int ORACLE_TRANSACTION_ID_LENGTH = 4;

    /** {@code CDEMO-FROM-PROGRAM} and its peers: eight characters. */
    private static final int ORACLE_PROGRAM_NAME_LENGTH = 8;

    /** {@code CDEMO-USER-ID}: eight characters, matching the security record. */
    private static final int ORACLE_USER_ID_LENGTH = 8;

    /** {@code CDEMO-USER-TYPE}: one character. */
    private static final int ORACLE_USER_TYPE_LENGTH = 1;

    /** {@code CDEMO-CUST-ID}: nine digits, matching the customer record. */
    private static final int ORACLE_CUSTOMER_ID_LENGTH = 9;

    /** Each customer name component: twenty-five characters. */
    private static final int ORACLE_CUSTOMER_NAME_LENGTH = 25;

    /** {@code CDEMO-ACCT-ID}: eleven digits, matching the account record. */
    private static final int ORACLE_ACCOUNT_ID_LENGTH = 11;

    /** {@code CDEMO-ACCT-STATUS}: one character. */
    private static final int ORACLE_ACCOUNT_STATUS_LENGTH = 1;

    /** {@code CDEMO-CARD-NUM}: sixteen characters, matching the card record. */
    private static final int ORACLE_CARD_NUMBER_LENGTH = 16;

    /** {@code CDEMO-LAST-MAP} and the mapset name: seven characters. */
    private static final int ORACLE_MAP_NAME_LENGTH = 7;

    /** {@code CCARD-AID PIC X(5)}: five characters, padding included. */
    private static final int ORACLE_ATTENTION_ID_LENGTH = 5;

    /** The work area's error message field: seventy-five characters. */
    private static final int ORACLE_ERROR_MESSAGE_LENGTH = 75;

    /** The work area's return message field: seventy-five characters. */
    private static final int ORACLE_RETURN_MESSAGE_LENGTH = 75;

    /** The administrator type code, from the security record. */
    private static final String ORACLE_ADMIN_CODE = "A";

    /** The standard-user type code. */
    private static final String ORACLE_USER_CODE = "U";

    /**
     * Builds a fully populated navigation context for a signed-on administrator.
     *
     * @param userTypeCode the raw one-character type code to carry
     * @return a populated context
     */
    private static NavigationContext aContextForType(final String userTypeCode) {
        return new NavigationContext("CC00", "COSGN00C", "CM00", "COMEN01C", "ADMIN001",
                userTypeCode, NavigationContext.ProgramContext.ENTER, "000000011", "MARY", "ANN",
                "SMITH", "00000000011", "Y", "4111111111111111", "COMEN1A", "COMEN01");
    }

    /**
     * Builds a screen work area carrying the supplied identifier images.
     *
     * @param keyAction  the attention key, or {@code null} when none was pressed
     * @param accountId  the account identifier image
     * @param cardNumber the card number image
     * @param customerId the customer identifier image
     * @return a populated work area
     */
    private static ScreenWorkArea aWorkArea(final KeyAction keyAction, final String accountId,
            final String cardNumber, final String customerId) {
        return new ScreenWorkArea(keyAction, "COCRDSLC", "COCRDSL", "CCRDSLA", "", "",
                accountId, cardNumber, customerId);
    }

    @Nested
    @DisplayName("NavigationContext: the communication area as one injected type")
    final class NavigationContextContract {

        @Test
        @DisplayName("every declared width matches the copybook field it carries")
        void everyDeclaredWidthMatchesTheCopybook() {
            assertThat(NavigationContext.TRANSACTION_ID_LENGTH)
                    .isEqualTo(ORACLE_TRANSACTION_ID_LENGTH);
            assertThat(NavigationContext.PROGRAM_NAME_LENGTH).isEqualTo(ORACLE_PROGRAM_NAME_LENGTH);
            assertThat(NavigationContext.USER_ID_LENGTH).isEqualTo(ORACLE_USER_ID_LENGTH);
            assertThat(NavigationContext.USER_TYPE_LENGTH).isEqualTo(ORACLE_USER_TYPE_LENGTH);
            assertThat(NavigationContext.CUSTOMER_ID_LENGTH).isEqualTo(ORACLE_CUSTOMER_ID_LENGTH);
            assertThat(NavigationContext.CUSTOMER_NAME_LENGTH)
                    .isEqualTo(ORACLE_CUSTOMER_NAME_LENGTH);
            assertThat(NavigationContext.ACCOUNT_ID_LENGTH).isEqualTo(ORACLE_ACCOUNT_ID_LENGTH);
            assertThat(NavigationContext.ACCOUNT_STATUS_LENGTH)
                    .isEqualTo(ORACLE_ACCOUNT_STATUS_LENGTH);
            assertThat(NavigationContext.CARD_NUMBER_LENGTH).isEqualTo(ORACLE_CARD_NUMBER_LENGTH);
            assertThat(NavigationContext.MAP_NAME_LENGTH).isEqualTo(ORACLE_MAP_NAME_LENGTH);
        }

        @Test
        @DisplayName("the widths agree with the records the identifiers are keys of")
        void theWidthsAgreeWithTheRecordKeys() {
            // A mismatch here would let a screen carry a key the record cannot hold.
            assertThat(NavigationContext.USER_ID_LENGTH).isEqualTo(ORACLE_USER_ID_LENGTH);
            assertThat(NavigationContext.ACCOUNT_ID_LENGTH)
                    .isEqualTo(ScreenWorkArea.ACCOUNT_ID_LENGTH);
            assertThat(NavigationContext.CARD_NUMBER_LENGTH)
                    .isEqualTo(ScreenWorkArea.CARD_NUMBER_LENGTH);
            assertThat(NavigationContext.CUSTOMER_ID_LENGTH)
                    .isEqualTo(ScreenWorkArea.CUSTOMER_ID_LENGTH);
        }

        @Test
        @DisplayName("every component is reported back exactly as supplied")
        void everyComponentIsReportedBack() {
            final NavigationContext context = aContextForType(ORACLE_ADMIN_CODE);

            assertThat(context.fromTransactionId()).isEqualTo("CC00");
            assertThat(context.fromProgram()).isEqualTo("COSGN00C");
            assertThat(context.toTransactionId()).isEqualTo("CM00");
            assertThat(context.toProgram()).isEqualTo("COMEN01C");
            assertThat(context.userId()).isEqualTo("ADMIN001");
            assertThat(context.userType()).isEqualTo(ORACLE_ADMIN_CODE);
            assertThat(context.programContext())
                    .isEqualTo(NavigationContext.ProgramContext.ENTER);
            assertThat(context.customerId()).isEqualTo("000000011");
            assertThat(context.customerFirstName()).isEqualTo("MARY");
            assertThat(context.customerMiddleName()).isEqualTo("ANN");
            assertThat(context.customerLastName()).isEqualTo("SMITH");
            assertThat(context.accountId()).isEqualTo("00000000011");
            assertThat(context.accountStatus()).isEqualTo("Y");
            assertThat(context.cardNumber()).isEqualTo("4111111111111111");
            assertThat(context.lastMap()).isEqualTo("COMEN1A");
            assertThat(context.lastMapset()).isEqualTo("COMEN01");
        }

        @Test
        @DisplayName("the administrator code resolves and is reported as echoed - it grants nothing, "
                + "because the byte arrived from the client rather than from the user-security record")
        void theAdministratorCodeResolves() {
            final NavigationContext context = aContextForType(ORACLE_ADMIN_CODE);

            assertThat(context.resolvedUserType()).contains(UserType.ADMIN);
            assertThat(context.echoesAdministratorCode()).isTrue();
        }

        @Test
        @DisplayName("the standard-user code resolves and is not reported as the administrator code")
        void theStandardUserCodeResolves() {
            final NavigationContext context = aContextForType(ORACLE_USER_CODE);

            assertThat(context.resolvedUserType()).contains(UserType.USER);
            assertThat(context.echoesAdministratorCode()).isFalse();
        }

        @Test
        @DisplayName("an unrecognised type code resolves to nothing and is not an administrator")
        void anUnrecognisedTypeCodeIsNotAnAdministrator() {
            // The sign-on program routes any non-administrator type to the main menu, so an
            // unrecognised code must fail closed rather than defaulting upward.
            final NavigationContext context = aContextForType("X");

            assertThat(context.resolvedUserType()).isEmpty();
            assertThat(context.echoesAdministratorCode()).isFalse();
        }

        @Test
        @DisplayName("an absent type code resolves to nothing and is not an administrator")
        void anAbsentTypeCodeIsNotAnAdministrator() {
            final NavigationContext context = aContextForType(null);

            assertThat(context.resolvedUserType()).isEmpty();
            assertThat(context.echoesAdministratorCode()).isFalse();
        }

        @Test
        @DisplayName("a lowercase type code does not resolve, because the code is a raw byte")
        void aLowercaseTypeCodeDoesNotResolve() {
            assertThat(aContextForType("a").resolvedUserType()).isEmpty();
        }

        @Test
        @DisplayName("the empty state carries nothing and reads as a first entry")
        void theEmptyStateReadsAsAFirstEntry() {
            // An online program entered with an empty communication area has no signed-on user and no
            // selection, and routes to sign-on unconditionally.
            final NavigationContext empty = NavigationContext.empty();

            assertThat(empty.userId()).isNull();
            assertThat(empty.userType()).isNull();
            assertThat(empty.programContext()).isNull();
            assertThat(empty.echoesAdministratorCode()).isFalse();
            assertThat(empty.firstEntry()).isTrue();
            assertThat(empty.reEntry()).isFalse();
        }

        @Test
        @DisplayName("the empty state is a shared constant, so holding it allocates nothing")
        void theEmptyStateIsASharedConstant() {
            assertThat(NavigationContext.empty()).isSameAs(NavigationContext.empty());
        }

        @Test
        @DisplayName("first entry and re-entry are exact complements, never both and never neither")
        void firstEntryAndReEntryAreExactComplements() {
            final NavigationContext entered =
                    aContextForType(ORACLE_ADMIN_CODE).withFirstEntry();
            final NavigationContext reentered =
                    aContextForType(ORACLE_ADMIN_CODE).withReEntry();

            assertThat(entered.firstEntry()).isTrue();
            assertThat(entered.reEntry()).isFalse();
            assertThat(reentered.reEntry()).isTrue();
            assertThat(reentered.firstEntry()).isFalse();
        }

        @Test
        @DisplayName("switching the program context preserves every other component")
        void switchingTheProgramContextPreservesEverythingElse() {
            // The flag is the only thing a re-submission changes; losing the carried selection would
            // send the operator back to an empty screen.
            final NavigationContext original = aContextForType(ORACLE_ADMIN_CODE);
            final NavigationContext reentered = original.withReEntry();

            assertThat(reentered.userId()).isEqualTo(original.userId());
            assertThat(reentered.accountId()).isEqualTo(original.accountId());
            assertThat(reentered.cardNumber()).isEqualTo(original.cardNumber());
            assertThat(reentered.customerId()).isEqualTo(original.customerId());
            assertThat(reentered.lastMap()).isEqualTo(original.lastMap());
            assertThat(reentered.lastMapset()).isEqualTo(original.lastMapset());
            assertThat(reentered.programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
        }

        @Test
        @DisplayName("switching the context yields a new instance and leaves the original untouched")
        void switchingTheContextLeavesTheOriginalUntouched() {
            final NavigationContext original = aContextForType(ORACLE_ADMIN_CODE);

            final NavigationContext reentered = original.withReEntry();

            assertThat(reentered).isNotSameAs(original);
            assertThat(original.programContext())
                    .isEqualTo(NavigationContext.ProgramContext.ENTER);
        }

        @Test
        @DisplayName("switching to the state already held is idempotent in value")
        void switchingToTheStateAlreadyHeldIsIdempotentInValue() {
            final NavigationContext reentered = aContextForType(ORACLE_ADMIN_CODE).withReEntry();

            assertThat(reentered.withReEntry()).isEqualTo(reentered);
        }

        @Test
        @DisplayName("value equality holds across independently built instances")
        void valueEqualityHoldsAcrossInstances() {
            assertThat(aContextForType(ORACLE_ADMIN_CODE))
                    .isEqualTo(aContextForType(ORACLE_ADMIN_CODE))
                    .hasSameHashCodeAs(aContextForType(ORACLE_ADMIN_CODE));
            assertThat(aContextForType(ORACLE_ADMIN_CODE))
                    .isNotEqualTo(aContextForType(ORACLE_USER_CODE));
        }

        @Test
        @DisplayName("the rendering names the carried route")
        void theRenderingNamesTheCarriedRoute() {
            assertThat(aContextForType(ORACLE_ADMIN_CODE).toString())
                    .contains("COSGN00C")
                    .contains("COMEN01C");
        }

        @Test
        @DisplayName("the program context has exactly the two states the copybook declares")
        void theProgramContextHasExactlyTwoStates() {
            // The copybook declares an enter flag and a re-enter flag and nothing else.
            assertThat(NavigationContext.ProgramContext.values())
                    .containsExactly(NavigationContext.ProgramContext.ENTER,
                            NavigationContext.ProgramContext.REENTER);
            assertThat(NavigationContext.ProgramContext.valueOf("REENTER"))
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
        }
    }

    @Nested
    @DisplayName("ScreenWorkArea: the work area, with its numeric redefinitions as derived views")
    final class ScreenWorkAreaContract {

        @Test
        @DisplayName("every declared width matches the copybook field it carries")
        void everyDeclaredWidthMatchesTheCopybook() {
            assertThat(ScreenWorkArea.ATTENTION_ID_LENGTH).isEqualTo(ORACLE_ATTENTION_ID_LENGTH);
            assertThat(ScreenWorkArea.NEXT_PROGRAM_LENGTH).isEqualTo(ORACLE_PROGRAM_NAME_LENGTH);
            assertThat(ScreenWorkArea.NEXT_MAPSET_LENGTH).isEqualTo(ORACLE_MAP_NAME_LENGTH);
            assertThat(ScreenWorkArea.NEXT_MAP_LENGTH).isEqualTo(ORACLE_MAP_NAME_LENGTH);
            assertThat(ScreenWorkArea.ERROR_MESSAGE_LENGTH).isEqualTo(ORACLE_ERROR_MESSAGE_LENGTH);
            assertThat(ScreenWorkArea.RETURN_MESSAGE_LENGTH)
                    .isEqualTo(ORACLE_RETURN_MESSAGE_LENGTH);
            assertThat(ScreenWorkArea.ACCOUNT_ID_LENGTH).isEqualTo(ORACLE_ACCOUNT_ID_LENGTH);
            assertThat(ScreenWorkArea.CARD_NUMBER_LENGTH).isEqualTo(ORACLE_CARD_NUMBER_LENGTH);
            assertThat(ScreenWorkArea.CUSTOMER_ID_LENGTH).isEqualTo(ORACLE_CUSTOMER_ID_LENGTH);
        }

        @Test
        @DisplayName("every component is reported back exactly as supplied")
        void everyComponentIsReportedBack() {
            final ScreenWorkArea area = new ScreenWorkArea(KeyAction.PFK03, "COCRDSLC", "COCRDSL",
                    "CCRDSLA", "NOT FOUND", "RETURNING", "00000000011", "4111111111111111",
                    "000000011");

            assertThat(area.keyAction()).isEqualTo(KeyAction.PFK03);
            assertThat(area.nextProgram()).isEqualTo("COCRDSLC");
            assertThat(area.nextMapset()).isEqualTo("COCRDSL");
            assertThat(area.nextMap()).isEqualTo("CCRDSLA");
            assertThat(area.errorMessage()).isEqualTo("NOT FOUND");
            assertThat(area.returnMessage()).isEqualTo("RETURNING");
            assertThat(area.accountId()).isEqualTo("00000000011");
            assertThat(area.cardNumber()).isEqualTo("4111111111111111");
            assertThat(area.customerId()).isEqualTo("000000011");
        }

        @Test
        @DisplayName("a pressed key is surfaced, together with its five-character identifier")
        void aPressedKeyIsSurfacedWithItsIdentifier() {
            final ScreenWorkArea area = aWorkArea(KeyAction.PFK03, "", "", "");

            assertThat(area.attentionKey()).contains(KeyAction.PFK03);
            assertThat(area.attentionIdText()).contains("PFK03");
            assertThat(area.attentionIdText().orElseThrow())
                    .hasSize(ORACLE_ATTENTION_ID_LENGTH);
        }

        @Test
        @DisplayName("the padded attention identifiers keep their trailing spaces untrimmed")
        void thePaddedIdentifiersKeepTheirTrailingSpaces() {
            // The identifier is a five-character picture, so the shorter literals carry padding that
            // is part of the value. Trimming it would change the comparison the source performs.
            final ScreenWorkArea firstAttention = aWorkArea(KeyAction.PA1, "", "", "");
            final ScreenWorkArea secondAttention = aWorkArea(KeyAction.PA2, "", "", "");

            assertThat(firstAttention.attentionIdText()).contains("PA1  ");
            assertThat(secondAttention.attentionIdText()).contains("PA2  ");
            assertThat(firstAttention.attentionIdText().orElseThrow())
                    .hasSize(ORACLE_ATTENTION_ID_LENGTH);
        }

        @Test
        @DisplayName("no pressed key is surfaced as absence, never as a fallback constant")
        void noPressedKeyIsSurfacedAsAbsence() {
            final ScreenWorkArea area = aWorkArea(null, "", "", "");

            assertThat(area.attentionKey()).isEmpty();
            assertThat(area.attentionIdText()).isEmpty();
        }

        @Test
        @DisplayName("each numeric alias reads the same bytes as the character component")
        void eachNumericAliasReadsTheSameBytes() {
            // A redefinition is one storage location read two ways, so the two views must agree.
            final ScreenWorkArea area =
                    aWorkArea(null, "00000000011", "4111111111111111", "000000011");

            assertThat(area.accountIdNumeric()).contains(new BigInteger("11"));
            assertThat(area.cardNumberNumeric())
                    .contains(new BigInteger("4111111111111111"));
            assertThat(area.customerIdNumeric()).contains(new BigInteger("11"));
        }

        @Test
        @DisplayName("the card number exceeds a 32-bit range, which is why the view is arbitrary width")
        void theCardNumberExceedsA32BitRange() {
            final ScreenWorkArea area =
                    aWorkArea(null, "00000000011", "4111111111111111", "000000011");

            assertThat(area.cardNumberNumeric().orElseThrow())
                    .isGreaterThan(BigInteger.valueOf(Integer.MAX_VALUE));
        }

        @Test
        @DisplayName("a leading-zero image is preserved in the character view and dropped in the numeric")
        void aLeadingZeroImageIsPreservedInTheCharacterView() {
            final ScreenWorkArea area = aWorkArea(null, "00000000011", "", "");

            assertThat(area.accountId()).isEqualTo("00000000011")
                    .hasSize(ORACLE_ACCOUNT_ID_LENGTH);
            assertThat(area.accountIdNumeric().orElseThrow().toString()).isEqualTo("11");
        }

        @Test
        @DisplayName("an all-zero image yields a numeric zero rather than absence")
        void anAllZeroImageYieldsNumericZero() {
            final ScreenWorkArea area = aWorkArea(null, "00000000000", "", "");

            assertThat(area.accountIdNumeric()).contains(BigInteger.ZERO);
        }

        @Test
        @DisplayName("a blank image has no numeric view, because spaces are not digits")
        void aBlankImageHasNoNumericView() {
            // An unentered numeric screen field arrives as spaces. Reading it as a number would either
            // raise or invent a zero; the source treats it as not yet supplied.
            final ScreenWorkArea area = aWorkArea(null, "           ", "                ", "   ");

            assertThat(area.accountIdNumeric()).isEmpty();
            assertThat(area.cardNumberNumeric()).isEmpty();
            assertThat(area.customerIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("an empty image has no numeric view")
        void anEmptyImageHasNoNumericView() {
            final ScreenWorkArea area = aWorkArea(null, "", "", "");

            assertThat(area.accountIdNumeric()).isEmpty();
            assertThat(area.cardNumberNumeric()).isEmpty();
            assertThat(area.customerIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("an absent image has no numeric view")
        void anAbsentImageHasNoNumericView() {
            final ScreenWorkArea area = aWorkArea(null, null, null, null);

            assertThat(area.accountIdNumeric()).isEmpty();
            assertThat(area.cardNumberNumeric()).isEmpty();
            assertThat(area.customerIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("a partially numeric image has no numeric view, and never a truncated one")
        void aPartiallyNumericImageHasNoNumericView() {
            // Parsing the leading digits would silently accept a typed identifier as a different one.
            assertThat(aWorkArea(null, "0000000001A", "", "").accountIdNumeric()).isEmpty();
            assertThat(aWorkArea(null, "A0000000011", "", "").accountIdNumeric()).isEmpty();
            assertThat(aWorkArea(null, "00000 00011", "", "").accountIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("a signed image has no numeric view, because the picture is unsigned")
        void aSignedImageHasNoNumericView() {
            assertThat(aWorkArea(null, "-0000000011", "", "").accountIdNumeric()).isEmpty();
            assertThat(aWorkArea(null, "+0000000011", "", "").accountIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("value equality holds across independently built instances")
        void valueEqualityHoldsAcrossInstances() {
            final ScreenWorkArea first = aWorkArea(KeyAction.ENTER, "00000000011", "", "");
            final ScreenWorkArea second = aWorkArea(KeyAction.ENTER, "00000000011", "", "");

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isNotEqualTo(aWorkArea(KeyAction.PFK03, "00000000011", "", ""));
        }

        @Test
        @DisplayName("the rendering names the carried route")
        void theRenderingNamesTheCarriedRoute() {
            assertThat(aWorkArea(KeyAction.ENTER, "", "", "").toString()).contains("COCRDSLC");
        }
    }

    @Nested
    @DisplayName("FieldErrorDecorator: thirty-nine macro expansions collapsed into one call")
    final class FieldErrorDecoratorContract {

        @Test
        @DisplayName("nothing marked is the starting state, and it is empty")
        void nothingMarkedIsTheStartingState() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none();

            assertThat(errors.isEmpty()).isTrue();
            assertThat(errors.fieldErrors()).isEmpty();
        }

        @Test
        @DisplayName("a blank flag becomes the missing state, which the marker distinguished")
        void aBlankFlagBecomesMissing() {
            // The macro writes a marker character when the flag is specifically blank, which is a
            // different operator-visible condition from merely failing an edit.
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark("acctStatus", "ACSTTUS", FieldErrorDecorator.FlagState.BLANK);

            assertThat(errors.fieldErrors()).hasSize(1);
            assertThat(errors.fieldErrors().get(0).state())
                    .isEqualTo(ErrorResponse.FieldState.MISSING);
            assertThat(errors.fieldErrors().get(0).fieldName()).isEqualTo("acctStatus");
            assertThat(errors.fieldErrors().get(0).screenFieldId()).isEqualTo("ACSTTUS");
        }

        @Test
        @DisplayName("a not-OK flag becomes the invalid state")
        void aNotOkFlagBecomesInvalid() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark("creditLimit", "ACRDLIM", FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(errors.fieldErrors().get(0).state())
                    .isEqualTo(ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("the two states stay distinguishable when both are marked")
        void theTwoStatesStayDistinguishable() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark("acctStatus", "ACSTTUS", FieldErrorDecorator.FlagState.BLANK)
                    .mark("creditLimit", "ACRDLIM", FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(errors.fieldErrors()).hasSize(2);
            assertThat(errors.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsExactly(ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("marks accumulate in the order the validation cascade produced them")
        void marksAccumulateInCascadeOrder() {
            // The screen presents its errors in field order, which is the order the cascade ran.
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark("first", "AFIRST", FieldErrorDecorator.FlagState.NOT_OK)
                    .mark("second", "ASECOND", FieldErrorDecorator.FlagState.NOT_OK)
                    .mark("third", "ATHIRD", FieldErrorDecorator.FlagState.BLANK);

            assertThat(errors.fieldErrors())
                    .extracting(ErrorResponse.FieldError::fieldName)
                    .containsExactly("first", "second", "third");
        }

        @Test
        @DisplayName("marking yields a new decorator and leaves the previous one untouched")
        void markingYieldsANewDecorator() {
            final FieldErrorDecorator empty = FieldErrorDecorator.none();

            final FieldErrorDecorator marked =
                    empty.mark("acctStatus", "ACSTTUS", FieldErrorDecorator.FlagState.BLANK);

            assertThat(marked).isNotSameAs(empty);
            assertThat(empty.isEmpty()).as("accumulating must not mutate what a caller still holds")
                    .isTrue();
            assertThat(marked.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("the same field can be marked twice, because the source decorates per expansion")
        void theSameFieldCanBeMarkedTwice() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark("acctStatus", "ACSTTUS", FieldErrorDecorator.FlagState.BLANK)
                    .mark("acctStatus", "ACSTTUS", FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(errors.fieldErrors()).hasSize(2);
        }

        @Test
        @DisplayName("an absent argument is refused rather than producing a nameless error")
        void anAbsentArgumentIsRefused() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none();

            assertThatNullPointerException()
                    .isThrownBy(() -> errors.mark(null, "ACSTTUS",
                            FieldErrorDecorator.FlagState.BLANK))
                    .withMessageContaining("field");
            assertThatNullPointerException()
                    .isThrownBy(() -> errors.mark("acctStatus", null,
                            FieldErrorDecorator.FlagState.BLANK))
                    .withMessageContaining("bmsFieldId");
            assertThatNullPointerException()
                    .isThrownBy(() -> errors.mark("acctStatus", "ACSTTUS", null))
                    .withMessageContaining("flagState");
        }

        @Test
        @DisplayName("an absent list is normalised to nothing marked, so callers never see a null")
        void anAbsentListIsNormalisedToNothingMarked() {
            final FieldErrorDecorator errors = new FieldErrorDecorator(null);

            assertThat(errors.fieldErrors()).isNotNull().isEmpty();
            assertThat(errors.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("the carried list is defensively copied, so a later caller edit cannot reach in")
        void theCarriedListIsDefensivelyCopied() {
            final List<FieldErrorDecorator.MarkedField> supplied = new ArrayList<>();
            supplied.add(new FieldErrorDecorator.MarkedField("acctStatus", "ACSTTUS",
                    FieldErrorDecorator.FlagState.BLANK));

            final FieldErrorDecorator errors = new FieldErrorDecorator(supplied);
            supplied.clear();

            assertThat(errors.fieldErrors()).hasSize(1);
        }

        @Test
        @DisplayName("the carried list is unmodifiable")
        void theCarriedListIsUnmodifiable() {
            final FieldErrorDecorator errors = FieldErrorDecorator.none()
                    .mark("acctStatus", "ACSTTUS", FieldErrorDecorator.FlagState.BLANK);

            assertThat(errors.fieldErrors()).isUnmodifiable();
        }

        @Test
        @DisplayName("value equality holds across independently built decorators")
        void valueEqualityHoldsAcrossDecorators() {
            final FieldErrorDecorator first = FieldErrorDecorator.none()
                    .mark("acctStatus", "ACSTTUS", FieldErrorDecorator.FlagState.BLANK);
            final FieldErrorDecorator second = FieldErrorDecorator.none()
                    .mark("acctStatus", "ACSTTUS", FieldErrorDecorator.FlagState.BLANK);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isNotEqualTo(FieldErrorDecorator.none());
            assertThat(first.toString()).contains("acctStatus");
        }

        @Test
        @DisplayName("the flag state has exactly the two values the macro distinguishes")
        void theFlagStateHasExactlyTwoValues() {
            assertThat(FieldErrorDecorator.FlagState.values())
                    .containsExactly(FieldErrorDecorator.FlagState.BLANK,
                            FieldErrorDecorator.FlagState.NOT_OK);
            assertThat(FieldErrorDecorator.FlagState.valueOf("NOT_OK"))
                    .isEqualTo(FieldErrorDecorator.FlagState.NOT_OK);
        }

        @Test
        @DisplayName("the two flag states map onto the two response states one for one")
        void theTwoFlagStatesMapOntoTheTwoResponseStates() {
            // Neither state may collapse onto the other, and neither may reach a third value.
            assertThat(ErrorResponse.FieldState.values())
                    .containsExactly(ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID);

            final FieldErrorDecorator both = FieldErrorDecorator.none()
                    .mark("a", "A", FieldErrorDecorator.FlagState.BLANK)
                    .mark("b", "B", FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(both.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a run of thirty-nine marks accumulates every one, as the expansion count implies")
        void aRunOfThirtyNineMarksAccumulatesEveryOne() {
            // The account-update screen expands the macro thirty-nine times against thirty-nine
            // distinct flags, so a worst-case submission carries thirty-nine field errors.
            FieldErrorDecorator errors = FieldErrorDecorator.none();
            for (int expansion = 1; expansion <= 39; expansion++) {
                errors = errors.mark("field" + expansion, "AFLD" + expansion,
                        FieldErrorDecorator.FlagState.NOT_OK);
            }

            assertThat(errors.fieldErrors()).hasSize(39);
            assertThat(errors.fieldErrors())
                    .extracting(ErrorResponse.FieldError::fieldName)
                    .doesNotHaveDuplicates();
        }
    }
}
