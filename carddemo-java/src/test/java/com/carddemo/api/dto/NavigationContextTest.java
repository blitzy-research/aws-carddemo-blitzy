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

import java.util.Arrays;
import java.util.List;

import com.carddemo.domain.enums.UserType;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies {@link NavigationContext}, the replacement for the state the legacy programs carried between
 * pseudo-conversation turns.
 *
 * <p>{@code app/cpy/COCOM01Y.cpy} declares a communication area that all seventeen online programs
 * included textually and passed to one another on every transfer of control. Its sixteen elementary
 * fields carry no filler and their widths sum to the area's own 160 bytes, so a client echoing this
 * object back carries exactly the state the terminal session carried. The five groups the copybook
 * declares are structural only, which is why the record is flat.
 *
 * <p>Two components decide behaviour rather than describe it. The user-type byte decides whether sign-on
 * routes to the administrative menu or the main one, and the program-context digit decides whether a
 * screen is being shown for the first time or re-shown after a failed submission. The second is the gate
 * on field-level error decoration: the legacy macro that reddens a field and writes a marker fires only
 * on re-entry, so a context reporting first entry when it should report re-entry would silently suppress
 * every field error. Both are asserted, including their behaviour when the component is absent.
 *
 * <p>Why the absent program context means first entry: the legacy digit's condition names give zero the
 * meaning "entering" and one the meaning "re-entering", so a freshly initialised digit field reads as
 * entering and an absent context has to behave the same way or a first request would be treated as a
 * re-submission. The suite asserts the two predicates are exact complements and that an absent context
 * reports first entry. The digit itself is never surfaced: the nested two-constant enum is the whole of
 * the exposed contract, and even the diagnostic rendering names the state rather than a raw {@code 0} or
 * {@code 1}.
 *
 * <p>The two acceptance criteria this file owns. First, that the three identifiers survive verbatim. The
 * customer identifier is nine digits, the account identifier eleven and the card number sixteen; all
 * three are declared numeric in the copybook and all three are carried here as text, because they are
 * identifiers with contractual leading zeros and fixed external widths rather than quantities. A single
 * stripped leading zero shortens the external width, and that width is compared directly by the
 * byte-equivalence criterion, so the identifiers are asserted through every accessor and through a
 * serialise-and-read-back cycle, always by string equality and never by numeric comparison. Second, that
 * an unrecognised user-type character is tolerated rather than rejected: sign-on moves the persisted
 * character into the area, tests the administrative condition and supplies an
 * <strong>unconditional</strong> alternative, so there is no third branch and no error path and a
 * character the estate never declared reaches the main menu instead of failing. Construction with such a
 * character must complete, resolution must yield nothing rather than raise, and no case fold may turn a
 * lower-case administrative character into an administrator.
 *
 * <p><strong>How the serialisation contract is exercised.</strong> This is a pure unit test: no framework
 * context is started, no container is used and no database is touched. The mapper comes from
 * {@link JsonContractSupport#declaredSettingsMapper()}, the single place in the test tree where the four
 * settings the module's own {@code application.yml} declares are written out by hand - absent properties
 * omitted rather than sent as nulls, dates never as epoch numbers, unknown input tolerated, and decimals
 * written plainly. What that mapper evidences is the shape this type takes <em>under those settings</em>,
 * and nothing more; it is not evidence about the mapper a deployed instance holds, and no assertion below
 * is worded as though it were. {@code ApplicationJsonContractTest} carries that burden against a mapper
 * taken from a real context: it compares that mapper's output with this very factory's output, and it
 * additionally binds each covered type through the deployed object directly.
 *
 * <p>Deliberately not asserted: nothing runs a bean validator, because the declared maximum lengths are
 * enforced where a validator is actually built. Instead the absence of every presence, pattern and range
 * constraint is established behaviourally - an absent, an empty and a blank value are all accepted, one
 * component at a time and all sixteen at once. The depth of the diagnostic redaction belongs to the
 * security-facing suite for this type; one protective assertion appears here so a regression that
 * un-redacts cardholder data cannot slip through this file either. No token, claim or expiry is
 * asserted, because this type carries none: only the user identifier and the user-type character ever
 * become signed claims, and both travel as ordinary components here.
 */
@DisplayName("NavigationContext - the carried-over communication area")
class NavigationContextTest {

    /** The sixteen copybook widths in declaration order; the program-context digit counts as one. */
    private static final List<Integer> COPYBOOK_WIDTHS =
            List.of(4, 8, 4, 8, 8, 1, 1, 9, 25, 25, 25, 11, 1, 16, 7, 7);

    private static final int COMMAREA_WIDTH = 160;

    /** The legacy condition-name value meaning the program is being entered. */
    private static final int LEGACY_ENTER_VALUE = 0;

    /** The legacy condition-name value meaning the program is being re-entered. */
    private static final int LEGACY_REENTER_VALUE = 1;

    /** Number of elementary fields the copybook declares, none of them filler. */
    private static final int COMPONENT_COUNT = 16;

    private static final int CUSTOMER_ID_POSITION = 7;

    private static final String FROM_TRANSACTION_ID = "CC00";

    private static final String FROM_PROGRAM = "COSGN00C";

    private static final String TO_TRANSACTION_ID = "CAUP";

    private static final String TO_PROGRAM = "COACTUPC";

    /** The signed-on identifier. No credential of any kind appears anywhere in this file. */
    private static final String USER_ID = "ADMIN001";

    /**
     * A nine-character customer identifier whose leading zeros a numeric component would erase, leaving
     * a value the reference data cannot be looked up by.
     */
    private static final String CUSTOMER_ID = "000000042";

    private static final String FIRST_NAME = "Mary Ann                 ";

    private static final String MIDDLE_NAME = "Ann                      ";

    private static final String LAST_NAME = "Gold                     ";

    /**
     * An eleven-character account identifier that a numeric component would shorten to a single
     * character, breaking the fixed external width.
     */
    private static final String ACCOUNT_ID = "00000000001";

    /** A one-character account status, echoed rather than interpreted by this type. */
    private static final String ACCOUNT_STATUS = "Y";

    /**
     * A sixteen-character card number that a numeric component would shorten to a single character,
     * breaking the fixed external width.
     */
    private static final String CARD_NUMBER = "0000000000000001";

    private static final String LAST_MAP = "CACTUPA";

    private static final String LAST_MAPSET = "COACTUP";

    /** The one-character code the copybook declares for an administrator. */
    private static final String ADMIN_CODE = "A";

    /** The one-character code the copybook declares for a standard user. */
    private static final String USER_CODE = "U";

    /**
     * Codes the copybook declares nowhere, each of which the legacy program routes to the main menu
     * rather than rejecting: an unexpected letter, a single blank, and the lower-case form of the
     * administrative character, which is not folded up into one.
     */
    private static final List<String> UNDECLARED_CODES = List.of("X", " ", "a");

    /** The placeholder the diagnostic rendering substitutes for each regulated component. */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * The wire form of the fully populated fixture, assembled from the same constants the fixture uses so
     * that it pins the property names, their emission order and their quoting rather than restating the
     * values. Every identifier is quoted, which is the whole point: an unquoted number would mean the
     * component had been carried numerically and its leading zeros lost in transit.
     */
    private static final String EXPECTED_JSON =
            "{\"fromTransactionId\":\"" + FROM_TRANSACTION_ID + "\""
            + ",\"fromProgram\":\"" + FROM_PROGRAM + "\""
            + ",\"toTransactionId\":\"" + TO_TRANSACTION_ID + "\""
            + ",\"toProgram\":\"" + TO_PROGRAM + "\""
            + ",\"userId\":\"" + USER_ID + "\""
            + ",\"userType\":\"" + ADMIN_CODE + "\""
            + ",\"programContext\":\"ENTER\""
            + ",\"customerId\":\"" + CUSTOMER_ID + "\""
            + ",\"customerFirstName\":\"" + FIRST_NAME + "\""
            + ",\"customerMiddleName\":\"" + MIDDLE_NAME + "\""
            + ",\"customerLastName\":\"" + LAST_NAME + "\""
            + ",\"accountId\":\"" + ACCOUNT_ID + "\""
            + ",\"accountStatus\":\"" + ACCOUNT_STATUS + "\""
            + ",\"cardNumber\":\"" + CARD_NUMBER + "\""
            + ",\"lastMap\":\"" + LAST_MAP + "\""
            + ",\"lastMapset\":\"" + LAST_MAPSET + "\"}";

    /**
     * Supplies a mapper carrying the four settings the module declares in its own
     * {@code application.yml}: absent rather than null properties, dates never as epoch numbers,
     * unknown input tolerated, and decimals always written plainly rather than in scientific notation.
     *
     * <p>It is obtained from {@link JsonContractSupport#declaredSettingsMapper()} rather than built
     * here, so those settings exist in exactly one place in the test tree and this file cannot
     * transcribe them differently from a sibling suite. No context is started, which is what keeps
     * this suite fast.</p>
     *
     * <p>This mapper is not the mapper a deployed instance holds, and nothing below treats it as
     * though it were. The correspondence between the two is established in
     * {@code ApplicationJsonContractTest}, which obtains a mapper from a real context that has read
     * the module's file and compares it against this very factory.</p>
     *
     * @return a mapper carrying the module's four declared serialisation settings
     */
    private static ObjectMapper newMapper() {
        return JsonContractSupport.declaredSettingsMapper();
    }

    /**
     * Sends a context out to JSON and reads it back through a mapper configured like the deployed one.
     * This is the round trip a client actually performs: the server returns the state in a response body,
     * the client holds it and sends it back on the next call. Anything the trip alters is state the legacy
     * terminal session would have preserved.
     *
     * @return the context as it survives serialisation and deserialisation
     * @throws Exception if either direction fails, which is itself a contract failure
     */
    private static NavigationContext roundTrip(final NavigationContext value) throws Exception {
        final ObjectMapper mapper = newMapper();
        return mapper.readValue(mapper.writeValueAsString(value), NavigationContext.class);
    }

    /**
     * Builds a fully populated context in which every component fills its copybook field exactly, so a
     * transformation can be shown to preserve every component it is not meant to change and the widest
     * legal value of every field is exercised.
     *
     * @param userType the raw one-character user-type code, which may be absent or undeclared
     * @param context  the program context, which may be absent
     */
    private static NavigationContext populated(final String userType,
            final NavigationContext.ProgramContext context) {
        return new NavigationContext(
                FROM_TRANSACTION_ID,
                FROM_PROGRAM,
                TO_TRANSACTION_ID,
                TO_PROGRAM,
                USER_ID,
                userType,
                context,
                CUSTOMER_ID,
                FIRST_NAME,
                MIDDLE_NAME,
                LAST_NAME,
                ACCOUNT_ID,
                ACCOUNT_STATUS,
                CARD_NUMBER,
                LAST_MAP,
                LAST_MAPSET);
    }

    /**
     * Builds the fully populated administrative context with exactly one component replaced by an absent
     * value, addressed by its position among the sixteen. Written as sixteen explicit selections rather
     * than by inspecting the type at run time, because run-time type inspection is forbidden in this
     * module and an explicit selection keeps the component order visible and checkable against the
     * copybook.
     *
     * @param position the zero-based position of the component to leave absent
     */
    private static NavigationContext populatedWithout(final int position) {
        return new NavigationContext(
                position == 0 ? null : FROM_TRANSACTION_ID,
                position == 1 ? null : FROM_PROGRAM,
                position == 2 ? null : TO_TRANSACTION_ID,
                position == 3 ? null : TO_PROGRAM,
                position == 4 ? null : USER_ID,
                position == 5 ? null : ADMIN_CODE,
                position == 6 ? null : NavigationContext.ProgramContext.ENTER,
                position == 7 ? null : CUSTOMER_ID,
                position == 8 ? null : FIRST_NAME,
                position == 9 ? null : MIDDLE_NAME,
                position == 10 ? null : LAST_NAME,
                position == 11 ? null : ACCOUNT_ID,
                position == 12 ? null : ACCOUNT_STATUS,
                position == 13 ? null : CARD_NUMBER,
                position == 14 ? null : LAST_MAP,
                position == 15 ? null : LAST_MAPSET);
    }

    /**
     * Reads the sixteen components out of a context in copybook declaration order, using a list that
     * admits absent entries because absence is a legitimate value for every one of the sixteen and an
     * immutable list factory would reject it. The values are read through the record's own accessors, one
     * call each, so nothing here inspects the type at run time.
     *
     * @return the sixteen component values, in declaration order, absences included
     */
    private static List<Object> componentsOf(final NavigationContext context) {
        return Arrays.asList(
                context.fromTransactionId(),
                context.fromProgram(),
                context.toTransactionId(),
                context.toProgram(),
                context.userId(),
                context.userType(),
                context.programContext(),
                context.customerId(),
                context.customerFirstName(),
                context.customerMiddleName(),
                context.customerLastName(),
                context.accountId(),
                context.accountStatus(),
                context.cardNumber(),
                context.lastMap(),
                context.lastMapset());
    }

    /**
     * Counts how many of a context's sixteen components are absent.
     */
    private static long absentComponentCount(final NavigationContext context) {
        return componentsOf(context).stream().filter(value -> value == null).count();
    }

    /**
     * Verifies the declared widths against the communication area they reproduce.
     */
    @Nested
    @DisplayName("copybook geometry")
    class CopybookGeometry {

        @Test
        @DisplayName("the sixteen declared widths sum to the communication area's own width")
        void theWidthsSumToTheCommareaWidth() {
            assertThat(COPYBOOK_WIDTHS).hasSize(COMPONENT_COUNT);
            assertThat(COPYBOOK_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(COMMAREA_WIDTH);
        }

        @Test
        @DisplayName("every published width constant matches its copybook field")
        void everyPublishedWidthMatchesItsCopybookField() {
            assertThat(NavigationContext.TRANSACTION_ID_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(0));
            assertThat(NavigationContext.PROGRAM_NAME_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(1));
            assertThat(NavigationContext.USER_ID_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(4));
            assertThat(NavigationContext.USER_TYPE_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(5));
            assertThat(NavigationContext.CUSTOMER_ID_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(7));
            assertThat(NavigationContext.CUSTOMER_NAME_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(8));
            assertThat(NavigationContext.ACCOUNT_ID_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(11));
            assertThat(NavigationContext.ACCOUNT_STATUS_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(12));
            assertThat(NavigationContext.CARD_NUMBER_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(13));
            assertThat(NavigationContext.MAP_NAME_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(14));
        }

        @Test
        @DisplayName("the two transaction identifiers share a width and the two program names share "
                + "another, because each pair reproduces one copybook field twice")
        void thePairedFieldsShareTheirWidths() {
            assertThat(COPYBOOK_WIDTHS.get(0)).isEqualTo(COPYBOOK_WIDTHS.get(2));
            assertThat(COPYBOOK_WIDTHS.get(1)).isEqualTo(COPYBOOK_WIDTHS.get(3));
            assertThat(COPYBOOK_WIDTHS.get(14)).isEqualTo(COPYBOOK_WIDTHS.get(15));
            assertThat(NavigationContext.MAP_NAME_LENGTH)
                    .as("a map and a mapset name are both seven characters")
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("the three customer names share one width, so a forename cannot be distinguished "
                + "from a surname by length and must be distinguished by position")
        void theThreeCustomerNamesShareOneWidth() {
            assertThat(COPYBOOK_WIDTHS.get(8))
                    .isEqualTo(COPYBOOK_WIDTHS.get(9))
                    .isEqualTo(COPYBOOK_WIDTHS.get(10))
                    .isEqualTo(NavigationContext.CUSTOMER_NAME_LENGTH);
        }

        @Test
        @DisplayName("the user-type and account-status fields are one byte each, so each carries a single "
                + "decision character")
        void theSingleByteFieldsAreOneByteEach() {
            assertThat(NavigationContext.USER_TYPE_LENGTH).isOne();
            assertThat(NavigationContext.ACCOUNT_STATUS_LENGTH).isOne();
        }

        @Test
        @DisplayName("the three identifier widths are pairwise distinct, so no identifier can be "
                + "substituted for another without changing the external width")
        void theThreeIdentifierWidthsArePairwiseDistinct() {
            assertThat(List.of(
                    NavigationContext.CUSTOMER_ID_LENGTH,
                    NavigationContext.ACCOUNT_ID_LENGTH,
                    NavigationContext.CARD_NUMBER_LENGTH))
                    .containsExactly(9, 11, 16)
                    .doesNotHaveDuplicates();
        }
    }

    /**
     * Verifies the shared empty context, which is a real legacy state rather than a placeholder: an online
     * program entered with an empty communication area has no operator, no selection and no previous
     * screen, and routes to sign-on unconditionally.
     */
    @Nested
    @DisplayName("the absent context")
    class AbsentContext {

        @Test
        @DisplayName("the empty context carries no component at all")
        void theEmptyContextCarriesNoComponent() {
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
            assertThat(absentComponentCount(empty)).isEqualTo(COMPONENT_COUNT);
        }

        @Test
        @DisplayName("the empty context is shared rather than rebuilt, which is safe because the record "
                + "is immutable")
        void theEmptyContextIsShared() {
            assertThat(NavigationContext.empty()).isSameAs(NavigationContext.empty());
        }

        @Test
        @DisplayName("the empty context resolves no user type and is not administrative, so an absent "
                + "context cannot be mistaken for an authorised one")
        void theEmptyContextIsNotAdministrative() {
            assertThat(NavigationContext.empty().resolvedUserType()).isEmpty();
            assertThat(NavigationContext.empty().echoesAdministratorCode()).isFalse();
        }

        @Test
        @DisplayName("the empty context reports first entry, matching a freshly initialised digit field")
        void theEmptyContextReportsFirstEntry() {
            assertThat(NavigationContext.empty().firstEntry()).isTrue();
            assertThat(NavigationContext.empty().reEntry()).isFalse();
        }

        @Test
        @DisplayName("the empty context serialises to no properties at all, because an absent component "
                + "is omitted rather than sent as a null literal")
        void theEmptyContextSerialisesToNoProperties() throws Exception {
            assertThat(newMapper().writeValueAsString(NavigationContext.empty())).isEqualTo("{}");
        }

        @Test
        @DisplayName("the empty context survives a round trip as the empty context, so a client may echo "
                + "back a state that carries nothing yet")
        void theEmptyContextSurvivesARoundTrip() throws Exception {
            assertThat(roundTrip(NavigationContext.empty())).isEqualTo(NavigationContext.empty());
        }
    }

    /**
     * Verifies that every component crosses the boundary byte for byte. Nothing here is trimmed, padded,
     * case-folded, stripped or re-formatted, because a fixed-width legacy field carried its blanks as
     * contract and a client echoing the state back must return exactly what it received.
     */
    @Nested
    @DisplayName("verbatim carriage of all sixteen components")
    class VerbatimCarriage {

        @Test
        @DisplayName("every one of the sixteen components reads back exactly as it was supplied")
        void everyComponentReadsBackExactlyAsSupplied() {
            final NavigationContext context =
                    populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER);

            assertThat(context.fromTransactionId()).isEqualTo(FROM_TRANSACTION_ID);
            assertThat(context.fromProgram()).isEqualTo(FROM_PROGRAM);
            assertThat(context.toTransactionId()).isEqualTo(TO_TRANSACTION_ID);
            assertThat(context.toProgram()).isEqualTo(TO_PROGRAM);
            assertThat(context.userId()).isEqualTo(USER_ID);
            assertThat(context.userType()).isEqualTo(ADMIN_CODE);
            assertThat(context.programContext())
                    .isEqualTo(NavigationContext.ProgramContext.ENTER);
            assertThat(context.customerId()).isEqualTo(CUSTOMER_ID);
            assertThat(context.customerFirstName()).isEqualTo(FIRST_NAME);
            assertThat(context.customerMiddleName()).isEqualTo(MIDDLE_NAME);
            assertThat(context.customerLastName()).isEqualTo(LAST_NAME);
            assertThat(context.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(context.accountStatus()).isEqualTo(ACCOUNT_STATUS);
            assertThat(context.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(context.lastMap()).isEqualTo(LAST_MAP);
            assertThat(context.lastMapset()).isEqualTo(LAST_MAPSET);
            assertThat(absentComponentCount(context))
                    .as("a fully populated context leaves nothing absent")
                    .isZero();
        }

        @Test
        @DisplayName("each supplied value fills its copybook field exactly, so the fixture exercises the "
                + "widest legal value of every component rather than a comfortable short one")
        void eachSuppliedValueFillsItsFieldExactly() {
            final NavigationContext context =
                    populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER);

            assertThat(context.fromTransactionId()).hasSize(COPYBOOK_WIDTHS.get(0));
            assertThat(context.fromProgram()).hasSize(COPYBOOK_WIDTHS.get(1));
            assertThat(context.toTransactionId()).hasSize(COPYBOOK_WIDTHS.get(2));
            assertThat(context.toProgram()).hasSize(COPYBOOK_WIDTHS.get(3));
            assertThat(context.userId()).hasSize(COPYBOOK_WIDTHS.get(4));
            assertThat(context.userType()).hasSize(COPYBOOK_WIDTHS.get(5));
            assertThat(context.customerId()).hasSize(COPYBOOK_WIDTHS.get(7));
            assertThat(context.customerFirstName()).hasSize(COPYBOOK_WIDTHS.get(8));
            assertThat(context.customerMiddleName()).hasSize(COPYBOOK_WIDTHS.get(9));
            assertThat(context.customerLastName()).hasSize(COPYBOOK_WIDTHS.get(10));
            assertThat(context.accountId()).hasSize(COPYBOOK_WIDTHS.get(11));
            assertThat(context.accountStatus()).hasSize(COPYBOOK_WIDTHS.get(12));
            assertThat(context.cardNumber()).hasSize(COPYBOOK_WIDTHS.get(13));
            assertThat(context.lastMap()).hasSize(COPYBOOK_WIDTHS.get(14));
            assertThat(context.lastMapset()).hasSize(COPYBOOK_WIDTHS.get(15));
        }

        @Test
        @DisplayName("trailing blanks survive carriage, because the padding of a fixed-width field is "
                + "contract rather than noise")
        void trailingBlanksSurviveCarriage() {
            final NavigationContext context =
                    populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER);

            assertThat(context.customerFirstName())
                    .isEqualTo(FIRST_NAME)
                    .hasSize(NavigationContext.CUSTOMER_NAME_LENGTH)
                    .endsWith(" ")
                    .as("the padded form is not the unpadded one, so nothing here trims")
                    .isNotEqualTo("Mary Ann");
            assertThat(context.customerMiddleName()).isEqualTo(MIDDLE_NAME).endsWith(" ");
            assertThat(context.customerLastName()).isEqualTo(LAST_NAME).endsWith(" ");
        }

        @Test
        @DisplayName("a leading blank survives carriage too, so a value is never left-stripped")
        void aLeadingBlankSurvivesCarriage() {
            final String leadingBlankName = " Ann                     ";
            final NavigationContext context = new NavigationContext(null, null, null, null, null, null,
                    null, null, leadingBlankName, null, null, null, null, null, null, null);

            assertThat(context.customerFirstName())
                    .isEqualTo(leadingBlankName)
                    .hasSize(NavigationContext.CUSTOMER_NAME_LENGTH)
                    .startsWith(" ");
        }

        @Test
        @DisplayName("mixed case survives carriage, because nothing here folds case in either direction")
        void mixedCaseSurvivesCarriage() {
            final NavigationContext context =
                    populated(USER_CODE, NavigationContext.ProgramContext.ENTER);

            assertThat(context.customerFirstName())
                    .isEqualTo(FIRST_NAME)
                    .as("neither an upper nor a lower fold of the same twenty-five characters")
                    .isNotEqualTo("MARY ANN                 ")
                    .isNotEqualTo("mary ann                 ");
            assertThat(context.userType())
                    .as("the declared standard-user code is carried in the case it was declared in")
                    .isEqualTo(USER_CODE);
        }

        @Test
        @DisplayName("a value shorter than its field is not padded out to the field width, because "
                + "padding is the caller's decision and not this type's")
        void aShortValueIsNotPaddedOut() {
            final NavigationContext context = new NavigationContext(null, null, null, null, null, null,
                    null, null, "Ann", null, null, null, null, null, null, null);

            assertThat(context.customerFirstName())
                    .isEqualTo("Ann")
                    .hasSize(3)
                    .isNotEqualTo(MIDDLE_NAME);
        }

        @Test
        @DisplayName("an empty value stays empty and stays distinct from an absent one, so a cleared "
                + "field and an unsent field are not confused")
        void anEmptyValueStaysDistinctFromAnAbsentOne() {
            final NavigationContext emptied = new NavigationContext("", "", "", "", "", "", null,
                    "", "", "", "", "", "", "", "", "");

            assertThat(emptied.customerId()).isNotNull();
            assertThat(emptied.customerId()).isEmpty();
            assertThat(emptied.userType()).isEmpty();
            assertThat(emptied.resolvedUserType()).isEmpty();
            assertThat(absentComponentCount(emptied))
                    .as("only the program context is absent; the fifteen text components are present")
                    .isOne();
            assertThat(emptied).isNotEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("a whole populated context survives the round trip unchanged, blanks and case "
                + "included")
        void aWholePopulatedContextSurvivesTheRoundTrip() throws Exception {
            final NavigationContext before =
                    populated(ADMIN_CODE, NavigationContext.ProgramContext.REENTER);

            final NavigationContext after = roundTrip(before);

            assertThat(after).isEqualTo(before);
            assertThat(after.customerFirstName()).isEqualTo(FIRST_NAME).endsWith(" ");
            assertThat(after.customerMiddleName()).isEqualTo(MIDDLE_NAME);
            assertThat(after.customerLastName()).isEqualTo(LAST_NAME);
            assertThat(after.reEntry()).isTrue();
        }
    }

    /**
     * The first of the two acceptance criteria this file owns. The three identifiers are declared numeric
     * in the copybook - nine digits, eleven and sixteen - and are carried here as text. Every assertion
     * compares strings, never numbers, because a numeric comparison would pass on exactly the value a
     * numeric component would have produced and so could never detect the defect it exists to catch. One
     * stripped leading zero shortens the external width, and that width is compared directly by the
     * byte-equivalence criterion.
     */
    @Nested
    @DisplayName("leading-zero identifiers, which a numeric component would silently destroy")
    class LeadingZeroIdentifiers {

        @Test
        @DisplayName("the three identifiers read back with every leading zero intact")
        void theThreeIdentifiersReadBackWithEveryLeadingZeroIntact() {
            final NavigationContext context =
                    populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER);

            assertThat(context.customerId()).isEqualTo(CUSTOMER_ID);
            assertThat(context.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(context.cardNumber()).isEqualTo(CARD_NUMBER);
        }

        @Test
        @DisplayName("no identifier collapses to its numeric value, which is precisely the failure a "
                + "numeric component would produce")
        void noIdentifierCollapsesToItsNumericValue() {
            final NavigationContext context =
                    populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER);

            assertThat(context.customerId())
                    .isNotEqualTo("42")
                    .startsWith("0")
                    .hasSize(NavigationContext.CUSTOMER_ID_LENGTH);
            assertThat(context.accountId())
                    .isNotEqualTo("1")
                    .startsWith("0")
                    .hasSize(NavigationContext.ACCOUNT_ID_LENGTH);
            assertThat(context.cardNumber())
                    .isNotEqualTo("1")
                    .startsWith("0")
                    .hasSize(NavigationContext.CARD_NUMBER_LENGTH);
        }

        @Test
        @DisplayName("a round trip through the serialisation contract returns the three identifiers "
                + "character for character")
        void aRoundTripReturnsTheThreeIdentifiersCharacterForCharacter() throws Exception {
            final NavigationContext after =
                    roundTrip(populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER));

            assertThat(after.customerId()).isEqualTo(CUSTOMER_ID);
            assertThat(after.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(after.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(after.customerId()).hasSize(NavigationContext.CUSTOMER_ID_LENGTH);
            assertThat(after.accountId()).hasSize(NavigationContext.ACCOUNT_ID_LENGTH);
            assertThat(after.cardNumber()).hasSize(NavigationContext.CARD_NUMBER_LENGTH);
        }

        @Test
        @DisplayName("the serialised form carries each identifier as a quoted string rather than as a "
                + "number, because an unquoted number is where the zeros would be lost")
        void theSerialisedFormCarriesEachIdentifierAsAQuotedString() throws Exception {
            final String json = newMapper()
                    .writeValueAsString(populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER));

            assertThat(json)
                    .contains("\"customerId\":\"" + CUSTOMER_ID + "\"")
                    .contains("\"accountId\":\"" + ACCOUNT_ID + "\"")
                    .contains("\"cardNumber\":\"" + CARD_NUMBER + "\"")
                    .doesNotContain("\"customerId\":42")
                    .doesNotContain("\"accountId\":1")
                    .doesNotContain("\"cardNumber\":1");
        }

        @Test
        @DisplayName("the whole wire form names the sixteen components in copybook order and quotes "
                + "every one of them")
        void theWholeWireFormNamesTheSixteenComponentsInCopybookOrder() throws Exception {
            assertThat(newMapper()
                    .writeValueAsString(populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER)))
                    .isEqualTo(EXPECTED_JSON);
        }

        @Test
        @DisplayName("an identifier that is nothing but zeros survives, so a zero is never mistaken for "
                + "an absent selection")
        void anIdentifierThatIsNothingButZerosSurvives() throws Exception {
            final String allZeroCustomerId = "000000000";
            final NavigationContext before = new NavigationContext(null, null, null, null, null, null,
                    null, allZeroCustomerId, null, null, null, null, null, null, null, null);

            assertThat(before.customerId()).isEqualTo(allZeroCustomerId).isNotEqualTo("0").isNotNull();
            assertThat(roundTrip(before).customerId()).isEqualTo(allZeroCustomerId);
        }

        @Test
        @DisplayName("an identifier read back from the wire is still a string, so a client that echoes "
                + "it cannot have widened it into a number on the way through")
        void anIdentifierReadBackFromTheWireIsStillAString() throws Exception {
            final String json = "{\"customerId\":\"" + CUSTOMER_ID + "\",\"accountId\":\"" + ACCOUNT_ID
                    + "\",\"cardNumber\":\"" + CARD_NUMBER + "\"}";

            final NavigationContext parsed = newMapper().readValue(json, NavigationContext.class);

            assertThat(parsed.customerId()).isEqualTo(CUSTOMER_ID);
            assertThat(parsed.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(parsed.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(absentComponentCount(parsed))
                    .as("the thirteen components the payload omits stay absent")
                    .isEqualTo(COMPONENT_COUNT - 3);
        }
    }

    /**
     * Verifies the one byte that decides which menu a signed-on operator reaches. Its two declared values
     * are the condition names in {@code app/cpy/COCOM01Y.cpy}, and its persisted origin is the single
     * character at byte offset 57 of the eighty-byte credential record described by
     * {@code app/cpy/CSUSR01Y.cpy}.
     */
    @Nested
    @DisplayName("the user-type decision")
    class UserTypeDecision {

        @Test
        @DisplayName("the administrative code resolves administrative")
        void theAdministrativeCodeResolvesAdministrative() {
            final NavigationContext context =
                    populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER);

            assertThat(context.resolvedUserType()).contains(UserType.ADMIN);
            assertThat(context.echoesAdministratorCode()).isTrue();
        }

        @Test
        @DisplayName("the ordinary code resolves ordinary and is not administrative")
        void theOrdinaryCodeResolvesOrdinary() {
            final NavigationContext context =
                    populated(USER_CODE, NavigationContext.ProgramContext.ENTER);

            assertThat(context.resolvedUserType()).contains(UserType.USER);
            assertThat(context.echoesAdministratorCode()).isFalse();
        }

        @Test
        @DisplayName("the raw code is carried beside the resolution rather than replaced by it, so a "
                + "client echoes back the byte it received")
        void theRawCodeIsCarriedBesideTheResolution() {
            assertThat(populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER).userType())
                    .isEqualTo(ADMIN_CODE)
                    .hasSize(NavigationContext.USER_TYPE_LENGTH);
            assertThat(populated(USER_CODE, NavigationContext.ProgramContext.ENTER).userType())
                    .isEqualTo(USER_CODE);
        }

        @Test
        @DisplayName("each declared code matches the code its resolved type publishes, so the raw byte "
                + "and the typed form never drift apart")
        void eachDeclaredCodeMatchesTheCodeItsResolvedTypePublishes() {
            for (final UserType role : UserType.values()) {
                final NavigationContext context =
                        populated(role.getCode(), NavigationContext.ProgramContext.ENTER);

                assertThat(context.resolvedUserType()).as("role %s", role).contains(role);
                assertThat(context.userType()).as("role %s", role).isEqualTo(role.getCode());
                assertThat(context.echoesAdministratorCode()).as("role %s", role).isEqualTo(role.isAdmin());
            }
        }

        @Test
        @DisplayName("an absent code resolves nothing and is not administrative")
        void anAbsentCodeIsNotAdministrative() {
            final NavigationContext context =
                    populated(null, NavigationContext.ProgramContext.ENTER);

            assertThat(context.resolvedUserType()).isEmpty();
            assertThat(context.echoesAdministratorCode()).isFalse();
        }

        @Test
        @DisplayName("exactly one of the two declared roles is privileged, so the administrative menu has "
                + "a single key")
        void exactlyOneDeclaredRoleIsPrivileged() {
            int privileged = 0;
            for (final UserType role : UserType.values()) {
                if (populated(role.getCode(), NavigationContext.ProgramContext.ENTER).echoesAdministratorCode()) {
                    privileged++;
                }
            }

            assertThat(UserType.values()).hasSize(2);
            assertThat(privileged).isOne();
        }
    }

    /**
     * The second of the two acceptance criteria this file owns. Sign-on moves the persisted character
     * straight into the area, tests the administrative condition and supplies an
     * <strong>unconditional</strong> alternative. That alternative is not a second test of the
     * standard-user condition, so there is no third branch and no error path: every character other than
     * the administrative one reaches the main menu, including one the estate never declared. Tolerance is
     * the faithful behaviour and rejection would be the regression - a membership constraint or a throwing
     * lookup would abort a sign-on the legacy program completes.
     */
    @Nested
    @DisplayName("an unrecognised user-type character, which is routed rather than rejected")
    class UnrecognisedUserType {

        @Test
        @DisplayName("construction with an undeclared character completes without throwing")
        void constructionWithAnUndeclaredCharacterCompletesWithoutThrowing() {
            for (final String code : UNDECLARED_CODES) {
                assertThatCode(() -> populated(code, NavigationContext.ProgramContext.ENTER))
                        .as("code [%s]", code)
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("resolving an undeclared character completes without throwing and yields nothing "
                + "rather than a synthetic third state")
        void resolvingAnUndeclaredCharacterYieldsNothing() {
            for (final String code : UNDECLARED_CODES) {
                final NavigationContext context =
                        populated(code, NavigationContext.ProgramContext.ENTER);

                assertThatCode(context::resolvedUserType)
                        .as("code [%s]", code)
                        .doesNotThrowAnyException();
                assertThat(context.resolvedUserType()).as("code [%s]", code).isEmpty();
            }
        }

        @Test
        @DisplayName("an undeclared character is never administrative, so an unexpected byte cannot "
                + "escalate to the administrative menu")
        void anUndeclaredCharacterIsNeverAdministrative() {
            for (final String code : UNDECLARED_CODES) {
                assertThatCode(() -> populated(code, NavigationContext.ProgramContext.ENTER)
                        .echoesAdministratorCode())
                        .as("code [%s]", code)
                        .doesNotThrowAnyException();
                assertThat(populated(code, NavigationContext.ProgramContext.ENTER).echoesAdministratorCode())
                        .as("code [%s]", code)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the lower-case administrative character is not folded up into an administrator, "
                + "because sign-on compares the raw character and applies no fold")
        void theLowerCaseAdministrativeCharacterIsNotFoldedUp() {
            final NavigationContext context =
                    populated("a", NavigationContext.ProgramContext.ENTER);

            assertThat(context.userType()).isEqualTo("a").isNotEqualTo(ADMIN_CODE);
            assertThat(context.resolvedUserType()).isEmpty();
            assertThat(context.echoesAdministratorCode()).isFalse();
        }

        @Test
        @DisplayName("a single blank is a tolerated character rather than an absent one, and neither "
                + "resolves nor throws")
        void aSingleBlankIsToleratedAndDistinctFromAbsence() {
            final NavigationContext blank =
                    populated(" ", NavigationContext.ProgramContext.ENTER);

            assertThat(blank.userType()).isEqualTo(" ").isNotNull().hasSize(1);
            assertThat(blank.resolvedUserType()).isEmpty();
            assertThat(blank.echoesAdministratorCode()).isFalse();
            assertThat(blank)
                    .as("a blank character is not the same carried state as an absent one")
                    .isNotEqualTo(populated(null, NavigationContext.ProgramContext.ENTER));
        }

        @Test
        @DisplayName("an undeclared character is carried through unchanged and survives a round trip, "
                + "so the client returns exactly the byte the server sent")
        void anUndeclaredCharacterSurvivesTheRoundTrip() throws Exception {
            for (final String code : UNDECLARED_CODES) {
                final NavigationContext before =
                        populated(code, NavigationContext.ProgramContext.ENTER);

                assertThat(before.userType()).as("code [%s]", code).isEqualTo(code);
                assertThat(roundTrip(before).userType()).as("code [%s]", code).isEqualTo(code);
                assertThat(roundTrip(before).echoesAdministratorCode()).as("code [%s]", code).isFalse();
            }
        }

        @Test
        @DisplayName("an over-long character sequence is measured, not rejected at construction, because "
                + "the declared bound is checked where a validator runs and not in the constructor")
        void anOverLongSequenceIsNotRejectedAtConstruction() {
            assertThatCode(() -> populated("AA", NavigationContext.ProgramContext.ENTER))
                    .doesNotThrowAnyException();

            final NavigationContext context =
                    populated("AA", NavigationContext.ProgramContext.ENTER);

            assertThat(context.userType()).isEqualTo("AA");
            assertThat(context.resolvedUserType()).isEmpty();
            assertThat(context.echoesAdministratorCode()).isFalse();
        }
    }

    /**
     * Every one of the sixteen components may be absent - the behavioural evidence that the record
     * declares no presence constraint. The only constraint it may carry is a maximum length, which
     * measures and never alters, so an absent, an empty and a blank value all pass construction. A
     * presence constraint would reject input the legacy area accepted, an entirely uninitialised
     * communication area being a real state, and a declarative cascade would report several violations at
     * once where the legacy programs report the first only, in source order - a service-layer
     * responsibility rather than this type's.
     */
    @Nested
    @DisplayName("absent components, none of which is mandatory")
    class AbsentComponents {

        @Test
        @DisplayName("constructing with all sixteen components absent completes without throwing")
        void constructingWithAllSixteenComponentsAbsentCompletesWithoutThrowing() {
            assertThatCode(() -> new NavigationContext(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("each component in turn may be absent while the other fifteen are populated")
        void eachComponentInTurnMayBeAbsent() {
            for (int position = 0; position < COMPONENT_COUNT; position++) {
                final int absentPosition = position;

                assertThatCode(() -> populatedWithout(absentPosition))
                        .as("component %d absent", absentPosition)
                        .doesNotThrowAnyException();

                final List<Object> components = componentsOf(populatedWithout(absentPosition));

                assertThat(components).as("component %d absent", absentPosition).hasSize(COMPONENT_COUNT);
                assertThat(components.get(absentPosition))
                        .as("component %d absent", absentPosition)
                        .isNull();
                assertThat(absentComponentCount(populatedWithout(absentPosition)))
                        .as("exactly one component absent, at position %d", absentPosition)
                        .isOne();
            }
        }

        @Test
        @DisplayName("reading every derived value off a wholly absent context completes without throwing, "
                + "so no accessor assumes a component is present")
        void readingEveryDerivedValueOffAWhollyAbsentContextDoesNotThrow() {
            final NavigationContext absent = NavigationContext.empty();

            assertThatCode(absent::resolvedUserType).doesNotThrowAnyException();
            assertThatCode(absent::echoesAdministratorCode).doesNotThrowAnyException();
            assertThatCode(absent::firstEntry).doesNotThrowAnyException();
            assertThatCode(absent::reEntry).doesNotThrowAnyException();
            assertThatCode(absent::withFirstEntry).doesNotThrowAnyException();
            assertThatCode(absent::withReEntry).doesNotThrowAnyException();
            assertThatCode(absent::toString).doesNotThrowAnyException();
            assertThatCode(() -> componentsOf(absent)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an absent identifier is not turned into a zero, so absence and zero stay distinct")
        void anAbsentIdentifierIsNotTurnedIntoAZero() {
            final NavigationContext withoutCustomer = populatedWithout(CUSTOMER_ID_POSITION);

            assertThat(withoutCustomer.customerId()).isNull();
            assertThat(withoutCustomer.accountId())
                    .as("nulling one identifier leaves the others alone")
                    .isEqualTo(ACCOUNT_ID);
            assertThat(withoutCustomer.cardNumber()).isEqualTo(CARD_NUMBER);
        }

        @Test
        @DisplayName("a blank value is accepted, which is the behavioural proof that no blank-rejecting "
                + "constraint is declared on any component")
        void aBlankValueIsAccepted() {
            final String blank = " ";
            final NavigationContext blanks = new NavigationContext(blank, blank, blank, blank, blank,
                    blank, null, blank, blank, blank, blank, blank, blank, blank, blank, blank);

            assertThat(blanks.fromTransactionId()).isEqualTo(blank);
            assertThat(blanks.userId()).isEqualTo(blank);
            assertThat(blanks.customerId()).isEqualTo(blank);
            assertThat(blanks.accountId()).isEqualTo(blank);
            assertThat(blanks.cardNumber()).isEqualTo(blank);
            assertThat(blanks.lastMapset()).isEqualTo(blank);
            assertThat(absentComponentCount(blanks))
                    .as("only the program context is absent")
                    .isOne();
        }

        @Test
        @DisplayName("a partially populated context survives a round trip with its absences still absent")
        void aPartiallyPopulatedContextSurvivesARoundTrip() throws Exception {
            final NavigationContext before = populatedWithout(CUSTOMER_ID_POSITION);

            final NavigationContext after = roundTrip(before);

            assertThat(after).isEqualTo(before);
            assertThat(after.customerId()).isNull();
            assertThat(absentComponentCount(after)).isOne();
        }
    }

    /**
     * Verifies the state that gates field-level error decoration. The legacy digit's condition names are
     * declared in {@code app/cpy/COCOM01Y.cpy}, and sign-on establishes the first-entry state before
     * handing control to a menu.
     */
    @Nested
    @DisplayName("the entry decision")
    class EntryDecision {

        @Test
        @DisplayName("the two predicates are exact complements for every possible context value")
        void thePredicatesAreExactComplements() {
            final List<NavigationContext> contexts = List.of(
                    populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER),
                    populated(ADMIN_CODE, NavigationContext.ProgramContext.REENTER),
                    populated(ADMIN_CODE, null));

            for (final NavigationContext context : contexts) {
                assertThat(context.firstEntry())
                        .as("context %s", context.programContext())
                        .isNotEqualTo(context.reEntry());
            }
        }

        @Test
        @DisplayName("an entering context reports first entry and a re-entering one reports re-entry, so "
                + "the distinction that gates field errors is representable in both directions")
        void eachContextValueReportsItsOwnEntryState() {
            assertThat(populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER).firstEntry())
                    .isTrue();
            assertThat(populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER).reEntry())
                    .isFalse();
            assertThat(populated(ADMIN_CODE, NavigationContext.ProgramContext.REENTER).reEntry())
                    .isTrue();
            assertThat(populated(ADMIN_CODE, NavigationContext.ProgramContext.REENTER).firstEntry())
                    .isFalse();
        }

        @Test
        @DisplayName("an absent context reports first entry rather than re-entry, so a first request is "
                + "never treated as a re-submission and never shows field errors it should suppress")
        void anAbsentContextReportsFirstEntry() {
            assertThat(populated(ADMIN_CODE, null).firstEntry()).isTrue();
            assertThat(populated(ADMIN_CODE, null).reEntry()).isFalse();
        }

        @Test
        @DisplayName("the two context values keep the legacy condition-name numbering, entering before "
                + "re-entering")
        void theContextValuesKeepTheLegacyNumbering() {
            assertThat(NavigationContext.ProgramContext.values()).hasSize(2);
            assertThat(NavigationContext.ProgramContext.ENTER.ordinal())
                    .isEqualTo(LEGACY_ENTER_VALUE);
            assertThat(NavigationContext.ProgramContext.REENTER.ordinal())
                    .isEqualTo(LEGACY_REENTER_VALUE);
        }

        @Test
        @DisplayName("the state is surfaced as a named value and never as the underlying digit, in the "
                + "carried component, in the wire form and in the diagnostic rendering alike")
        void theStateIsSurfacedAsANamedValueAndNeverAsADigit() throws Exception {
            final NavigationContext entering =
                    populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER);
            final NavigationContext reEntering =
                    populated(ADMIN_CODE, NavigationContext.ProgramContext.REENTER);

            assertThat(newMapper().writeValueAsString(entering))
                    .contains("\"programContext\":\"ENTER\"")
                    .doesNotContain("\"programContext\":0")
                    .doesNotContain("\"programContext\":\"0\"");
            assertThat(newMapper().writeValueAsString(reEntering))
                    .contains("\"programContext\":\"REENTER\"")
                    .doesNotContain("\"programContext\":1")
                    .doesNotContain("\"programContext\":\"1\"");
            assertThat(entering.toString())
                    .contains("programContext=ENTER")
                    .doesNotContain("programContext=0");
            assertThat(reEntering.toString())
                    .contains("programContext=REENTER")
                    .doesNotContain("programContext=1");
        }

        @Test
        @DisplayName("the named state survives a round trip in both directions")
        void theNamedStateSurvivesARoundTrip() throws Exception {
            assertThat(roundTrip(populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER))
                    .programContext())
                    .isEqualTo(NavigationContext.ProgramContext.ENTER);
            assertThat(roundTrip(populated(ADMIN_CODE, NavigationContext.ProgramContext.REENTER))
                    .reEntry())
                    .isTrue();
            assertThat(roundTrip(populated(ADMIN_CODE, null)).programContext()).isNull();
            assertThat(roundTrip(populated(ADMIN_CODE, null)).firstEntry()).isTrue();
        }
    }

    /**
     * Verifies that changing the entry state changes nothing else, and that it changes nothing in place.
     * The record exposes no mutator at all, so every transition below is a new value and the receiver is
     * demonstrably still the state it was.
     */
    @Nested
    @DisplayName("entry-state transitions")
    class EntryStateTransitions {

        @Test
        @DisplayName("marking re-entry changes only the entry state")
        void markingReEntryChangesOnlyTheEntryState() {
            final NavigationContext before =
                    populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER);
            final NavigationContext after = before.withReEntry();

            assertThat(after.reEntry()).isTrue();
            assertThat(after.programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
            assertThat(after.withFirstEntry())
                    .as("every other component is carried through unchanged")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("marking first entry changes only the entry state")
        void markingFirstEntryChangesOnlyTheEntryState() {
            final NavigationContext before =
                    populated(ADMIN_CODE, NavigationContext.ProgramContext.REENTER);
            final NavigationContext after = before.withFirstEntry();

            assertThat(after.firstEntry()).isTrue();
            assertThat(after.programContext()).isEqualTo(NavigationContext.ProgramContext.ENTER);
            assertThat(after.withReEntry()).isEqualTo(before);
        }

        @Test
        @DisplayName("each transition preserves all fifteen other components individually")
        void eachTransitionPreservesTheOtherComponents() {
            final NavigationContext before =
                    populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER);
            final NavigationContext after = before.withReEntry();

            assertThat(after.fromTransactionId()).isEqualTo(before.fromTransactionId());
            assertThat(after.fromProgram()).isEqualTo(before.fromProgram());
            assertThat(after.toTransactionId()).isEqualTo(before.toTransactionId());
            assertThat(after.toProgram()).isEqualTo(before.toProgram());
            assertThat(after.userId()).isEqualTo(before.userId());
            assertThat(after.userType()).isEqualTo(before.userType());
            assertThat(after.customerId()).isEqualTo(before.customerId());
            assertThat(after.customerFirstName()).isEqualTo(before.customerFirstName());
            assertThat(after.customerMiddleName()).isEqualTo(before.customerMiddleName());
            assertThat(after.customerLastName()).isEqualTo(before.customerLastName());
            assertThat(after.accountId()).isEqualTo(before.accountId());
            assertThat(after.accountStatus()).isEqualTo(before.accountStatus());
            assertThat(after.cardNumber()).isEqualTo(before.cardNumber());
            assertThat(after.lastMap()).isEqualTo(before.lastMap());
            assertThat(after.lastMapset()).isEqualTo(before.lastMapset());
        }

        @Test
        @DisplayName("a transition leaves the original untouched, so a context can be safely shared and "
                + "no accessor on the receiver reports a changed value")
        void aTransitionLeavesTheOriginalUntouched() {
            final NavigationContext before =
                    populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER);

            final NavigationContext derived = before.withReEntry();

            assertThat(derived).isNotSameAs(before);
            assertThat(before.programContext()).isEqualTo(NavigationContext.ProgramContext.ENTER);
            assertThat(before.firstEntry()).isTrue();
            assertThat(before.reEntry()).isFalse();
            assertThat(before)
                    .as("the receiver still equals a freshly built copy of what it was")
                    .isEqualTo(populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER));
        }

        @Test
        @DisplayName("marking an already-marked state is idempotent in value")
        void markingAnAlreadyMarkedStateIsIdempotent() {
            final NavigationContext reEntering =
                    populated(ADMIN_CODE, NavigationContext.ProgramContext.REENTER);
            final NavigationContext entering =
                    populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER);

            assertThat(reEntering.withReEntry()).isEqualTo(reEntering);
            assertThat(entering.withFirstEntry()).isEqualTo(entering);
        }

        @Test
        @DisplayName("a transition applied to the absent context populates only the entry state")
        void aTransitionOnTheAbsentContextPopulatesOnlyTheEntryState() {
            final NavigationContext marked = NavigationContext.empty().withReEntry();

            assertThat(marked.reEntry()).isTrue();
            assertThat(marked.userId()).isNull();
            assertThat(marked.accountId()).isNull();
            assertThat(absentComponentCount(marked)).isEqualTo(COMPONENT_COUNT - 1);
            assertThat(marked).isNotEqualTo(NavigationContext.empty());
            assertThat(marked.withFirstEntry().programContext())
                    .isEqualTo(NavigationContext.ProgramContext.ENTER);
            assertThat(NavigationContext.empty())
                    .as("the shared empty value is not disturbed by deriving from it")
                    .isSameAs(NavigationContext.empty());
            assertThat(NavigationContext.empty().programContext()).isNull();
        }

        @Test
        @DisplayName("a transition preserves every leading zero, so re-presenting a screen cannot reshape "
                + "the selection it carries")
        void aTransitionPreservesEveryLeadingZero() {
            final NavigationContext marked =
                    populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER).withReEntry();

            assertThat(marked.customerId()).isEqualTo(CUSTOMER_ID);
            assertThat(marked.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(marked.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(marked.customerFirstName()).isEqualTo(FIRST_NAME);
        }
    }

    /**
     * Verifies that the context behaves as a value: equal components mean equal contexts, and the
     * diagnostic rendering names the transition while withholding the identity that travels beside it.
     */
    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("two contexts with the same components are equal and hash alike")
        void twoContextsWithTheSameComponentsAreEqual() {
            assertThat(populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER))
                    .isEqualTo(populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER))
                    .hasSameHashCodeAs(populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER));
        }

        @Test
        @DisplayName("a difference in the user-type byte alone makes two contexts unequal")
        void aDifferenceInTheUserTypeByteMakesContextsUnequal() {
            assertThat(populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER))
                    .isNotEqualTo(populated(USER_CODE, NavigationContext.ProgramContext.ENTER));
        }

        @Test
        @DisplayName("a difference in the entry state alone makes two contexts unequal")
        void aDifferenceInTheEntryStateMakesContextsUnequal() {
            assertThat(populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER))
                    .isNotEqualTo(populated(ADMIN_CODE, NavigationContext.ProgramContext.REENTER));
        }

        @Test
        @DisplayName("a difference in any single component makes two contexts unequal, so equality "
                + "compares the whole carried state rather than a subset of it")
        void aDifferenceInAnySingleComponentMakesContextsUnequal() {
            final NavigationContext whole =
                    populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER);

            for (int position = 0; position < COMPONENT_COUNT; position++) {
                assertThat(populatedWithout(position))
                        .as("component %d absent", position)
                        .isNotEqualTo(whole);
            }
        }

        @Test
        @DisplayName("the rendered form names the record and the transition it describes")
        void theRenderedFormNamesTheRecordAndTheTransition() {
            assertThat(populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER).toString())
                    .startsWith("NavigationContext[")
                    .endsWith("]")
                    .contains(FROM_TRANSACTION_ID)
                    .contains(FROM_PROGRAM)
                    .contains(TO_TRANSACTION_ID)
                    .contains(TO_PROGRAM)
                    .contains(USER_ID)
                    .contains("ENTER")
                    .contains(LAST_MAP)
                    .contains(LAST_MAPSET);
        }

        @Test
        @DisplayName("the rendered form withholds the identifiers and the names, so a navigation trace "
                + "cannot put a card number or a customer into a log line")
        void theRenderedFormWithholdsTheIdentifiersAndTheNames() {
            final String rendered =
                    populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER).toString();

            assertThat(rendered)
                    .doesNotContain(CUSTOMER_ID)
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain(FIRST_NAME)
                    .doesNotContain(MIDDLE_NAME)
                    .doesNotContain(LAST_NAME)
                    .contains(REDACTION_PLACEHOLDER);
        }

        @Test
        @DisplayName("withholding a value from the rendering does not withhold it from equality, so "
                + "echoed state still compares correctly across a turn")
        void withholdingAValueFromTheRenderingDoesNotWithholdItFromEquality() {
            final NavigationContext whole =
                    populated(ADMIN_CODE, NavigationContext.ProgramContext.ENTER);

            assertThat(populatedWithout(CUSTOMER_ID_POSITION))
                    .as("the two differ only in a component the rendering redacts")
                    .isNotEqualTo(whole);
            assertThat(populatedWithout(CUSTOMER_ID_POSITION).toString())
                    .as("yet both render the same placeholder in that position")
                    .contains(REDACTION_PLACEHOLDER);
        }
    }
}
