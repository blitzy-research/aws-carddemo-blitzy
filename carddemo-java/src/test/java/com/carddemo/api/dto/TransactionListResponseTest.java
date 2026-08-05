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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit tests for {@link TransactionListResponse} and its nested {@link
 * TransactionListResponse.TransactionRow}, the response contract of legacy transaction {@code CT00}.
 *
 * <p>A pure unit test. No application context, no servlet environment, no container, no database and
 * no message broker: every subject is built by calling a canonical constructor directly, and the only
 * collaborators are a locally built JSON mapper and a locally built Bean Validation validator. The
 * contract under test is a carrier, so a test of it needs nothing that a carrier does not have.
 *
 * <h2>Where the expected values come from</h2>
 *
 * <p>Every width, count, ordering rule and message text asserted below was read from the legacy
 * members that own the screen &mdash; the program {@code app/cbl/COTRN00C.cbl}, its symbolic map
 * {@code app/cpy-bms/COTRN00.CPY}, the mapset {@code app/bms/COTRN00.bms} and the transaction record
 * layout {@code app/cpy/CVTRA05Y.cpy} &mdash; and is restated here as an independent oracle. No
 * production component is used to compute an expected value: the module's zoned-decimal codec, its
 * record mappers and its formatters are all absent from this file by design, because a test that asked
 * the implementation what the answer should be would agree with the implementation whatever it did.
 * The checkout the figures were read from is commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, and the members carry the upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} of 2022-07-19. Those identifiers belong in the traceability
 * matrix header and in prose such as this; they are deliberately not declared as a constant, because
 * the stamp is not uniform across the estate and a constant would imply that it is.
 *
 * <h2>Why the assertions are phrased without run-time type inspection</h2>
 *
 * <p>Nothing here interrogates a class, a field, a record component or an annotation while the test is
 * running. The component inventory is proved <em>by construction</em> instead: a call to the
 * fifteen-argument canonical constructor cannot compile unless the record declares exactly fifteen
 * components in that order, a call to each accessor cannot compile unless that accessor exists under
 * that exact name, and assigning an accessor's result to an explicitly typed local cannot compile
 * unless its static type is the declared one. Where a run-time statement about the component set is
 * genuinely needed &mdash; "there is no sixth row component", "no page total is published" &mdash; the
 * oracle is the serialized payload, because the mapper keys a record by its components and an exact key
 * set is therefore an exact component set. That keeps the file clear of the run-time type-inspection
 * facilities, whose use the module's unsafe-code audit budgets at zero.
 *
 * <h2>The three fidelity facts this file exists to pin</h2>
 *
 * <p><strong>The row description is twenty-six characters, and that is not a mistake.</strong> The
 * same business value is presented at three different widths in three different places: twenty-six on
 * this list row, sixty on the transaction view surface and one hundred in the statement work area. The
 * list width is a genuine truncation of the stored description, so unifying any two of the three would
 * change what a screen presents. This file asserts the twenty-six and asserts explicitly that it is
 * neither of the other two.
 *
 * <p><strong>The row date is eight characters, where the view surface carries ten.</strong> Widening
 * eight to ten would be the same class of error as unifying the descriptions, and is asserted against
 * directly rather than left implicit.
 *
 * <p><strong>The row amount is an exact decimal at scale two, and nothing rescales it.</strong> The
 * amount's origin is a signed zoned-decimal record field with nine integer digits and two decimal
 * places, which no binary floating-point type can hold exactly. The contract therefore carries a
 * {@link BigDecimal}, publishes the scale, and refuses a value at any other scale rather than quietly
 * repairing one. Refusing and repairing are different acts and only the second would alter the bytes a
 * client receives, so both halves are asserted: an amount at the contract scale crosses untouched, and
 * an amount at any other scale is rejected.
 *
 * @see TransactionListResponse
 * @see PageMetadata
 * @see NavigationContext
 */
@DisplayName("TransactionListResponse :: transaction-list response contract of legacy transaction CT00")
class TransactionListResponseTest {

    // ---------------------------------------------------------------------------------------------
    // Row fixtures, each at the exact width the symbolic map declares for the item it echoes.
    // ---------------------------------------------------------------------------------------------

    /** Selection indicator echoed for a marked row, at the single character the map declares. */
    private static final String SELECTION = "S";

    /** Selection indicator in lower case, used to prove that no case folding is applied. */
    private static final String SELECTION_LOWER_CASE = "s";

    /** Transaction identifier at its full sixteen characters, with the leading zeros that matter. */
    private static final String TRANSACTION_ID = "0000000000000042";

    /** A second identifier, ordering before {@link #TRANSACTION_ID}, for ordering assertions. */
    private static final String TRANSACTION_ID_LOWER = "0000000000000031";

    /** The identifier of the very first transaction: proof that it is text and never the number one. */
    private static final String TRANSACTION_ID_ONE = "0000000000000001";

    /** Seventeen characters: one more than the identifier item declares. */
    private static final String TRANSACTION_ID_TOO_LONG = "00000000000000421";

    /** Row date at the eight characters this screen declares, never the ten the view screen uses. */
    private static final String ROW_DATE = "07/19/22";

    /** An entirely blank row date: the ordinary state of a row the browse never filled. */
    private static final String ROW_DATE_ALL_SPACES = "        ";

    /** Nine characters: one more than the row-date item declares, and short of the view's ten. */
    private static final String ROW_DATE_TOO_LONG = "07/19/223";

    /** Row description at exactly the twenty-six characters this screen presents. */
    private static final String DESCRIPTION = "GROCERIES AT STORE NO. 042";

    /** Row description padded to width with trailing spaces, which are contract and never trimmed. */
    private static final String DESCRIPTION_SPACE_PADDED = "PAYMENT                   ";

    /** Twenty-seven characters: one more than this screen presents, and far short of sixty. */
    private static final String DESCRIPTION_TOO_LONG = "GROCERIES AT STORE NO. 0421";

    // ---------------------------------------------------------------------------------------------
    // Amount fixtures. Every one is written as a decimal string, never parsed from a double, so the
    // scale each carries is the scale the literal states rather than whatever a binary
    // approximation happened to round to.
    // ---------------------------------------------------------------------------------------------

    /** A debit-shaped amount at the contract scale. */
    private static final BigDecimal AMOUNT = new BigDecimal("1234.56");

    /**
     * A credit-shaped amount at the contract scale. Fifty of the three hundred records in the seeded
     * daily-transaction fixture are operator-originated returns against two hundred and fifty
     * point-of-sale purchases, so both signed directions occur in production-representative data and
     * neither may be treated as exceptional.
     */
    private static final BigDecimal AMOUNT_NEGATIVE = new BigDecimal("-123.45");

    /** Zero at the contract scale, which is not the same value as an absent amount. */
    private static final BigDecimal AMOUNT_ZERO = new BigDecimal("0.00");

    /** A whole amount whose two trailing zeros must survive onto the wire. */
    private static final BigDecimal AMOUNT_WHOLE = new BigDecimal("100.00");

    /** The widest amount the record field can hold: nine integer digits and two decimal places. */
    private static final BigDecimal AMOUNT_WIDEST = new BigDecimal("999999999.99");

    /**
     * The same numeric quantity as {@link #AMOUNT_WHOLE} expressed with a negative scale, which is
     * the shape whose default rendering is exponential. Used to demonstrate that the locally built
     * mapper writes plain decimal text, exactly as the module's own configuration requires.
     */
    private static final BigDecimal AMOUNT_IN_EXPONENTIAL_SHAPE = new BigDecimal("1E+2");

    // ---------------------------------------------------------------------------------------------
    // Screen furniture and carried-state fixtures.
    // ---------------------------------------------------------------------------------------------

    /** Browse cursor for the backward direction: the key of the first row on the page. */
    private static final String PREVIOUS_CURSOR_KEY = "0000000000000031";

    /** Browse cursor for the forward direction: the key of the last row on the page. */
    private static final String NEXT_CURSOR_KEY = "0000000000000042";

    /** Page indicator at its full eight characters, space padded so trimming would be visible. */
    private static final String PAGE_INDICATOR = "1       ";

    /** The map field the cursor is nominated into, at the seven-character ceiling of this mapset. */
    private static final String FOCUS_FIELD = "TRNIDIN";

    private static final String TITLE_ONE = "View Transactions";

    private static final String TITLE_TWO = "Tran List";

    private static final String CURRENT_DATE = "07/19/22";

    private static final String CURRENT_TIME = "19:27:53";

    private static final String TRANSACTION_NAME = "CT00";

    private static final String PROGRAM_NAME = "COTRN00C";

    /** An opaque, service-owned route identifier. Nothing in the contract resolves or parses it. */
    private static final String NEXT_ROUTE = "/api/v1/transactions";

    /** Operator identifier echoed in the navigation state. Never a credential of any kind. */
    private static final String USER_ID = "OPER0007";

    /** Customer identifier at nine characters, carrying leading zeros. */
    private static final String CUSTOMER_ID = "000000011";

    /** Account identifier at eleven characters, carrying leading zeros. */
    private static final String ACCOUNT_ID = "00000000011";

    /** Card identifier at sixteen characters, carrying leading zeros. */
    private static final String CARD_NUMBER = "0000000000000011";

    /** The fixed stand-in both overridden renderings substitute for a withheld value. */
    private static final String REDACTED = "***REDACTED***";

    /**
     * The plural phrasing the administrative user-list screen uses for its own invalid-selection
     * condition. Present solely as a distinctness oracle: that screen accepts two selection
     * characters and this one accepts a single character, so the two texts are separate external
     * contracts and neither may be generalised into the other.
     */
    private static final String PLURAL_SELECTION_VARIANT = "Invalid selection. Valid values are U and D";

    /** Seventy-nine characters: one more than the message item declares. */
    private static final String MESSAGE_TOO_LONG = "X".repeat(TransactionListResponse.MESSAGE_LENGTH + 1);

    /**
     * The shape a JSON object is read back into so that its key set can be compared exactly. A typed
     * reference rather than a raw map, because a raw type would raise a diagnostic and this module
     * compiles every warning as an error.
     */
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() { };

    /**
     * A mapper configured exactly as the module configures its own, and built here rather than
     * borrowed from a shared helper so that this file states the whole of what it depends on.
     *
     * <p>Four settings are reproduced from {@code carddemo-java/src/main/resources/application.yml}:
     * absent values are omitted rather than written as nulls, temporal values are never written as
     * epoch numbers, an unknown incoming property is tolerated rather than fatal, and &mdash; the one
     * that decides whether this contract's amounts survive the wire &mdash; exact decimals are written
     * as plain decimal text rather than in exponential form.</p>
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .defaultPropertyInclusion(
                    JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    /**
     * Builds one fully populated row.
     *
     * @param transactionId the identifier to carry
     * @param amount        the amount to carry, already at the contract scale
     * @return a row with every component populated
     */
    private static TransactionListResponse.TransactionRow row(String transactionId, BigDecimal amount) {
        return new TransactionListResponse.TransactionRow(
                SELECTION, transactionId, ROW_DATE, DESCRIPTION, amount);
    }

    /**
     * Builds the paging state of a page reached by walking forward.
     *
     * <p>The row count is taken from {@link PageMetadata#TRANSACTION_LIST_PAGE_SIZE} rather than
     * written as a figure here, so this file restates no screen shape of its own.</p>
     *
     * @return forward-direction paging state with both boundary cursors populated
     */
    private static PageMetadata forwardPaging() {
        return PageMetadata.forward(PageMetadata.TRANSACTION_LIST_PAGE_SIZE, PREVIOUS_CURSOR_KEY,
                NEXT_CURSOR_KEY, true, false, PAGE_INDICATOR);
    }

    /**
     * Builds the paging state of a page reached by walking backward.
     *
     * @return backward-direction paging state with both boundary cursors populated
     */
    private static PageMetadata backwardPaging() {
        return PageMetadata.backward(PageMetadata.TRANSACTION_LIST_PAGE_SIZE, PREVIOUS_CURSOR_KEY,
                NEXT_CURSOR_KEY, false, true, PAGE_INDICATOR);
    }

    /**
     * Builds the client-echoed navigation state, with every identifier carrying leading zeros.
     *
     * @return navigation state populated on the re-entry branch
     */
    private static NavigationContext navigation() {
        return new NavigationContext(TRANSACTION_NAME, PROGRAM_NAME, TRANSACTION_NAME, PROGRAM_NAME,
                USER_ID, "A", NavigationContext.ProgramContext.REENTER, CUSTOMER_ID, "MARY", "ANN",
                "SMITH", ACCOUNT_ID, "Y", CARD_NUMBER, "COTRN0A", "COTRN00");
    }

    /**
     * Builds a fully populated response around the supplied rows and message.
     *
     * @param rows    the rows to carry, in presentation order
     * @param message the summary message to carry
     * @return a response with all fifteen components populated
     */
    private static TransactionListResponse response(
            List<TransactionListResponse.TransactionRow> rows, String message) {
        return new TransactionListResponse(rows, forwardPaging(), navigation(), NEXT_ROUTE,
                TRANSACTION_ID_LOWER, PAGE_INDICATOR, message, false, FOCUS_FIELD, TITLE_ONE, TITLE_TWO,
                CURRENT_DATE, CURRENT_TIME, TRANSACTION_NAME, PROGRAM_NAME);
    }

    /**
     * Builds a fully populated single-row response reporting the top-of-browse condition.
     *
     * @return a one-row response with all fifteen components populated
     */
    private static TransactionListResponse populatedResponse() {
        return response(List.of(row(TRANSACTION_ID, AMOUNT)), TransactionListResponse.MESSAGE_AT_TOP);
    }

    /**
     * Builds a response whose components are all absent, which every one of them tolerates.
     *
     * @return a response with a null in every reference position
     */
    private static TransactionListResponse emptyResponse() {
        return new TransactionListResponse(null, null, null, null, null, null, null, false, null, null,
                null, null, null, null, null);
    }

    /**
     * Builds the requested number of rows, each with a distinct identifier.
     *
     * @param count how many rows to build
     * @return a mutable list of that many rows
     */
    private static List<TransactionListResponse.TransactionRow> rows(int count) {
        List<TransactionListResponse.TransactionRow> built = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            built.add(row(TRANSACTION_ID_ONE, AMOUNT));
        }
        return built;
    }

    /**
     * Serializes a value and reads its top-level object back as a map, so that the exact set of keys
     * it published can be asserted. This is the file's substitute for enumerating record components
     * at run time: the mapper keys a record by its components, so an exact key set is an exact
     * component set.
     *
     * @param value the value to serialize
     * @return the published object as a map of key to value
     * @throws JsonProcessingException if the value cannot be written or read back
     */
    private static Map<String, Object> published(Object value) throws JsonProcessingException {
        return MAPPER.readValue(MAPPER.writeValueAsString(value), JSON_OBJECT);
    }

    /**
     * Validates a response with a validator built from the Bean Validation bootstrap rather than from
     * any framework context, so the outcome is governed by the annotations the contract declares.
     *
     * @param response the response to validate
     * @return every violation the contract reports, which for a carrier is expected to be none
     */
    private static Set<ConstraintViolation<TransactionListResponse>> violationsOf(
            TransactionListResponse response) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(response);
        }
    }

    /**
     * Validates one row on the same terms as {@link #violationsOf(TransactionListResponse)}.
     *
     * @param row the row to validate
     * @return every violation the row reports
     */
    private static Set<ConstraintViolation<TransactionListResponse.TransactionRow>> violationsOf(
            TransactionListResponse.TransactionRow row) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(row);
        }
    }

    /**
     * Asserts that one published key does not name an aggregate over the browse.
     *
     * <p>The legacy browse never counts the cluster: it discovers whether a further page exists by
     * attempting one more access and then reports the outcome through one of the five boundary texts.
     * Publishing a count of rows or of pages would therefore be fabricated information and would force
     * a counting query the original never issued, so no such component exists on either the response or
     * its paging state.
     *
     * <p>Phrased as a prefix-and-infix check rather than as a list of specific names, because the
     * category is what must stay absent: any name announcing a total or a count of anything is caught,
     * including one nobody thought to enumerate.
     *
     * @param key one key from a published payload
     */
    private static void assertNamesNoAggregate(String key) {
        assertThat(key)
                .doesNotStartWith("total")
                .doesNotContain("Total")
                .doesNotContain("count")
                .doesNotContain("Count");
    }

    /**
     * Asserts that one operator-visible message crosses the contract byte for byte at its measured
     * length, both through the accessor and through a full JSON round trip.
     *
     * @param literal        the published constant under test
     * @param measuredLength the length measured directly from the emitting legacy program
     * @throws JsonProcessingException if the round trip fails
     */
    private static void assertMessageTravelsVerbatim(String literal, int measuredLength)
            throws JsonProcessingException {
        assertThat(literal).hasSize(measuredLength);

        TransactionListResponse carried = response(List.of(row(TRANSACTION_ID, AMOUNT)), literal);
        assertThat(carried.message()).isEqualTo(literal).hasSize(measuredLength);

        String json = MAPPER.writeValueAsString(carried);
        TransactionListResponse revived = MAPPER.readValue(json, TransactionListResponse.class);
        assertThat(revived.message()).isEqualTo(literal).hasSize(measuredLength);
        assertThat(json).contains(literal);
    }

    // =============================================================================================
    // THE NESTED ROW: FOUR DISPLAY COLUMNS PLUS THE ECHOED SELECTION
    // =============================================================================================

    /**
     * A row family of this screen presents four values and echoes back a fifth. Those five, and
     * nothing else, are what the nested row type carries: the generated per-family name suffixes, the
     * generated length, flag and attribute items, and the terminal-area filler are all screen plumbing
     * with no counterpart in a REST contract.
     */
    @Nested
    @DisplayName("the nested row carries the four display columns and the echoed selection")
    class NestedRowShape {

        @Test
        @DisplayName("publishes exactly those five values and no sixth, and no filler")
        void publishesExactlyFiveValues() throws JsonProcessingException {
            TransactionListResponse.TransactionRow subject = row(TRANSACTION_ID, AMOUNT);

            assertThat(published(subject)).containsOnlyKeys(
                    "selection", "transactionId", "displayedDate", "description", "amount");
        }

        @Test
        @DisplayName("returns every one of the five through its own accessor")
        void returnsEveryValueThroughItsAccessor() {
            TransactionListResponse.TransactionRow subject = new TransactionListResponse.TransactionRow(
                    SELECTION, TRANSACTION_ID, ROW_DATE, DESCRIPTION, AMOUNT);

            assertThat(subject.selection()).isEqualTo(SELECTION);
            assertThat(subject.transactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(subject.displayedDate()).isEqualTo(ROW_DATE);
            assertThat(subject.description()).isEqualTo(DESCRIPTION);
            assertThat(subject.amount()).isEqualTo(AMOUNT);
        }

        @Test
        @DisplayName("carries each value at its own width, byte for byte")
        void carriesEachValueAtItsOwnWidth() {
            TransactionListResponse.TransactionRow subject = new TransactionListResponse.TransactionRow(
                    SELECTION, TRANSACTION_ID, ROW_DATE, DESCRIPTION, AMOUNT);

            assertThat(subject.selection()).hasSize(TransactionListResponse.SELECTION_LENGTH);
            assertThat(subject.transactionId()).hasSize(TransactionListResponse.TRANSACTION_ID_LENGTH);
            assertThat(subject.displayedDate()).hasSize(TransactionListResponse.DISPLAYED_DATE_LENGTH);
            assertThat(subject.description()).hasSize(TransactionListResponse.DESCRIPTION_LENGTH);
        }

        @Test
        @DisplayName("leaves trailing spaces in place, because padding on a fixed-width field is the value")
        void leavesTrailingSpacesInPlace() {
            TransactionListResponse.TransactionRow subject = new TransactionListResponse.TransactionRow(
                    SELECTION, TRANSACTION_ID, ROW_DATE, DESCRIPTION_SPACE_PADDED, AMOUNT);

            assertThat(subject.description())
                    .isEqualTo(DESCRIPTION_SPACE_PADDED)
                    .hasSize(TransactionListResponse.DESCRIPTION_LENGTH)
                    .endsWith(" ");
        }

        @Test
        @DisplayName("pads no short value up to its declared width")
        void padsNoShortValueUp() {
            TransactionListResponse.TransactionRow subject = new TransactionListResponse.TransactionRow(
                    SELECTION, "42", "07/19", "COFFEE", AMOUNT);

            assertThat(subject.transactionId()).isEqualTo("42").hasSize(2);
            assertThat(subject.displayedDate()).isEqualTo("07/19").hasSize(5);
            assertThat(subject.description()).isEqualTo("COFFEE").hasSize(6);
        }

        @Test
        @DisplayName("folds no case, so a lower-case selection stays lower case")
        void foldsNoCase() {
            TransactionListResponse.TransactionRow subject = new TransactionListResponse.TransactionRow(
                    SELECTION_LOWER_CASE, TRANSACTION_ID, ROW_DATE, DESCRIPTION, AMOUNT);

            assertThat(subject.selection()).isEqualTo(SELECTION_LOWER_CASE).isNotEqualTo(SELECTION);
        }

        @Test
        @DisplayName("keeps the identifier's leading zeros, because it is text and never a number")
        void keepsTheIdentifiersLeadingZeros() throws JsonProcessingException {
            TransactionListResponse.TransactionRow subject = row(TRANSACTION_ID_ONE, AMOUNT);

            assertThat(subject.transactionId())
                    .isEqualTo(TRANSACTION_ID_ONE)
                    .isNotEqualTo("1")
                    .startsWith("0")
                    .hasSize(TransactionListResponse.TRANSACTION_ID_LENGTH);
            assertThat(published(subject)).containsEntry("transactionId", TRANSACTION_ID_ONE);
        }

        @Test
        @DisplayName("carries the row date as opaque text, including one that is entirely blank")
        void carriesTheRowDateAsOpaqueText() throws JsonProcessingException {
            TransactionListResponse.TransactionRow subject = new TransactionListResponse.TransactionRow(
                    SELECTION, TRANSACTION_ID, ROW_DATE_ALL_SPACES, DESCRIPTION, AMOUNT);

            String opaque = subject.displayedDate();
            assertThat(opaque)
                    .isEqualTo(ROW_DATE_ALL_SPACES)
                    .isNotNull()
                    .isNotEmpty()
                    .isBlank()
                    .hasSize(TransactionListResponse.DISPLAYED_DATE_LENGTH);
            assertThat(published(subject)).containsEntry("displayedDate", ROW_DATE_ALL_SPACES);
        }

        @Test
        @DisplayName("tolerates an absent value in every one of the five positions")
        void toleratesAnAbsentValueEverywhere() {
            assertThatNoException().isThrownBy(
                    () -> new TransactionListResponse.TransactionRow(null, null, null, null, null));

            TransactionListResponse.TransactionRow blank =
                    new TransactionListResponse.TransactionRow(null, null, null, null, null);
            assertThat(blank.selection()).isNull();
            assertThat(blank.transactionId()).isNull();
            assertThat(blank.displayedDate()).isNull();
            assertThat(blank.description()).isNull();
            assertThat(blank.amount()).isNull();
        }

        @Test
        @DisplayName("compares and hashes by value, as an immutable row must")
        void comparesAndHashesOneRowByValue() {
            TransactionListResponse.TransactionRow one = row(TRANSACTION_ID, AMOUNT);
            TransactionListResponse.TransactionRow same = row(TRANSACTION_ID, AMOUNT);
            TransactionListResponse.TransactionRow other = row(TRANSACTION_ID_LOWER, AMOUNT);

            assertThat(one).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(one).isNotEqualTo(other);
        }
    }

    // =============================================================================================
    // THE WIDTHS THAT COINCIDE WITH OTHER SCREENS AND MUST NOT BE UNIFIED
    // =============================================================================================

    /**
     * Two of this row's widths have larger counterparts elsewhere in the estate, and substituting
     * either would silently change what the screen presents. The description is twenty-six here,
     * sixty on the transaction view surface and one hundred in the statement work area; the row date
     * is eight here and ten on the view surface. Both are asserted at this screen's own figure and
     * asserted against the others by name.
     */
    @Nested
    @DisplayName("the widths that coincide with other screens are declared independently")
    class IndependentWidths {

        @Test
        @DisplayName("presents the description at twenty-six, which is neither sixty nor one hundred")
        void presentsTheDescriptionAtTwentySix() {
            assertThat(TransactionListResponse.DESCRIPTION_LENGTH)
                    .isEqualTo(26)
                    .isNotEqualTo(60)
                    .isNotEqualTo(100);
        }

        @Test
        @DisplayName("accepts a description of exactly twenty-six characters without complaint")
        void acceptsADescriptionAtTwentySix() {
            TransactionListResponse.TransactionRow subject = new TransactionListResponse.TransactionRow(
                    SELECTION, TRANSACTION_ID, ROW_DATE, DESCRIPTION, AMOUNT);

            assertThat(DESCRIPTION).hasSize(TransactionListResponse.DESCRIPTION_LENGTH);
            assertThat(violationsOf(subject)).isEmpty();
        }

        @Test
        @DisplayName("reports a twenty-seventh character, so the bound is this screen's and not the view's")
        void reportsATwentySeventhCharacter() {
            TransactionListResponse.TransactionRow subject = new TransactionListResponse.TransactionRow(
                    SELECTION, TRANSACTION_ID, ROW_DATE, DESCRIPTION_TOO_LONG, AMOUNT);

            assertThat(DESCRIPTION_TOO_LONG).hasSize(TransactionListResponse.DESCRIPTION_LENGTH + 1);
            assertThat(violationsOf(subject)).isNotEmpty().allSatisfy(violation -> {
                assertThat(violation.getPropertyPath()).hasToString("description");
                assertThat(violation.getInvalidValue()).isEqualTo(DESCRIPTION_TOO_LONG);
            });
        }

        @Test
        @DisplayName("presents the row date at eight, and never widens it to the view screen's ten")
        void presentsTheRowDateAtEight() {
            assertThat(TransactionListResponse.DISPLAYED_DATE_LENGTH).isEqualTo(8).isNotEqualTo(10);
        }

        @Test
        @DisplayName("reports a ninth date character, so eight is a bound and not a coincidence")
        void reportsANinthDateCharacter() {
            TransactionListResponse.TransactionRow subject = new TransactionListResponse.TransactionRow(
                    SELECTION, TRANSACTION_ID, ROW_DATE_TOO_LONG, DESCRIPTION, AMOUNT);

            assertThat(ROW_DATE_TOO_LONG).hasSize(TransactionListResponse.DISPLAYED_DATE_LENGTH + 1);
            assertThat(violationsOf(subject)).isNotEmpty().allSatisfy(violation ->
                    assertThat(violation.getPropertyPath()).hasToString("displayedDate"));
        }

        @Test
        @DisplayName("reports a seventeenth identifier character, holding the identifier at sixteen")
        void reportsASeventeenthIdentifierCharacter() {
            TransactionListResponse.TransactionRow subject = new TransactionListResponse.TransactionRow(
                    SELECTION, TRANSACTION_ID_TOO_LONG, ROW_DATE, DESCRIPTION, AMOUNT);

            assertThat(violationsOf(subject)).isNotEmpty().allSatisfy(violation ->
                    assertThat(violation.getPropertyPath()).hasToString("transactionId"));
        }
    }

    // =============================================================================================
    // THE SCREEN ROW COUNT
    // =============================================================================================

    /**
     * This screen shows ten rows, and that figure was established from loop bounds alone.
     *
     * <p>The program declares no row table for these rows at all: the only occurrence clause in the
     * member is an unrelated redefinition of the communication area sized by its own length field. The
     * ten comes from the fill loop, which runs while the index is not greater than ten at line 290 of
     * {@code app/cbl/COTRN00C.cbl}, resets the index at line 295 and stops the row walk at eleven at
     * line 297, and it is corroborated by the ten row families the symbolic map declares. It is the
     * shape of the screen &mdash; how many lines an operator sees &mdash; and the module asserts no
     * numeric performance or capacity target of any kind.</p>
     *
     * <p>The figure is read from {@link PageMetadata#TRANSACTION_LIST_PAGE_SIZE} throughout, so this
     * file restates no screen shape of its own.</p>
     */
    @Nested
    @DisplayName("the screen row count is ten, established from loop bounds with no row table")
    class ScreenRowCount {

        /**
         * A page must also agree with the paging state travelling beside it.
         *
         * <p>The structural bound alone is not enough. A response carrying eight rows beside metadata
         * that declares a page size of three describes two different pages at once, and a client that
         * believed the metadata - which is the whole reason the metadata is published - would either
         * drop rows it was sent or attribute them to a page they do not belong to. The disagreement is
         * a producer defect and is reported where it can still be corrected. The check is skipped
         * entirely when no metadata travels with the response, which is the ordinary shape for an error
         * or first-entry screen that presents no page at all.</p>
         */
        @Test
        @DisplayName("refuses a page carrying more rows than its own paging metadata declares")
        void refusesAPageWiderThanItsOwnPagingMetadataDeclares() {
            int declaredPageSize = 3;
            int rowsCarried = 8;
            PageMetadata narrowerThanThePage = PageMetadata.forward(declaredPageSize,
                    PREVIOUS_CURSOR_KEY, NEXT_CURSOR_KEY, true, false, PAGE_INDICATOR);

            // Deliberately within the structural bound, so the first check cannot be what fires: eight
            // rows fit the screen's ten slots and are refused only because the metadata says three.
            assertThat(rowsCarried).isLessThan(PageMetadata.TRANSACTION_LIST_PAGE_SIZE);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionListResponse(rows(rowsCarried),
                            narrowerThanThePage, navigation(), NEXT_ROUTE, TRANSACTION_ID_LOWER,
                            PAGE_INDICATOR, null, false, FOCUS_FIELD, TITLE_ONE, TITLE_TWO,
                            CURRENT_DATE, CURRENT_TIME, TRANSACTION_NAME, PROGRAM_NAME))
                    .withMessageContaining(String.valueOf(declaredPageSize))
                    .withMessageContaining(String.valueOf(rowsCarried));
        }

        @Test
        @DisplayName("accepts a page exactly as deep as its own paging metadata declares")
        void acceptsAPageExactlyAsDeepAsItsPagingMetadataDeclares() {
            int declaredPageSize = 3;
            PageMetadata matchingThePage = PageMetadata.forward(declaredPageSize, PREVIOUS_CURSOR_KEY,
                    NEXT_CURSOR_KEY, true, false, PAGE_INDICATOR);

            TransactionListResponse response = new TransactionListResponse(rows(declaredPageSize),
                    matchingThePage, navigation(), NEXT_ROUTE, TRANSACTION_ID_LOWER, PAGE_INDICATOR,
                    null, false, FOCUS_FIELD, TITLE_ONE, TITLE_TWO, CURRENT_DATE, CURRENT_TIME,
                    TRANSACTION_NAME, PROGRAM_NAME);

            assertThat(response.rows()).hasSize(declaredPageSize);

            // A short page is still a valid page: the final page of a browse is routinely shorter than
            // the page size, so only exceeding the declared size is a defect.
            assertThat(new TransactionListResponse(rows(1), matchingThePage, navigation(), NEXT_ROUTE,
                            TRANSACTION_ID_LOWER, PAGE_INDICATOR, null, false, FOCUS_FIELD, TITLE_ONE,
                            TITLE_TWO, CURRENT_DATE, CURRENT_TIME, TRANSACTION_NAME, PROGRAM_NAME)
                    .rows())
                    .hasSize(1);
        }

        @Test
        @DisplayName("applies no metadata comparison when no paging metadata travels with the page")
        void appliesNoMetadataComparisonWhenNoPagingMetadataTravels() {
            TransactionListResponse response = new TransactionListResponse(
                    rows(PageMetadata.TRANSACTION_LIST_PAGE_SIZE), null, navigation(), NEXT_ROUTE,
                    TRANSACTION_ID_LOWER, PAGE_INDICATOR, null, false, FOCUS_FIELD, TITLE_ONE,
                    TITLE_TWO, CURRENT_DATE, CURRENT_TIME, TRANSACTION_NAME, PROGRAM_NAME);

            assertThat(response.pageMetadata()).isNull();
            assertThat(response.rows()).hasSize(PageMetadata.TRANSACTION_LIST_PAGE_SIZE);
        }

        @Test
        @DisplayName("reads the count from the paging contract and restates it nowhere of its own")
        void agreesWithThePagingContract() {
            assertThat(PageMetadata.TRANSACTION_LIST_PAGE_SIZE)
                    .as("the paging contract is the single place this screen's depth is stated")
                    .isEqualTo(10);

            // The cap is proved to read that constant rather than a private copy by the refusal test
            // below, which names the published figure in the message. What this test adds is that no
            // private copy exists to read: an earlier revision declared a duplicate depth constant on
            // this body, and the duplication was the stated reason a later revision removed the cap
            // altogether, so singularity is what stops the same argument being available again.
            assertThat(Arrays.stream(TransactionListResponse.class.getDeclaredFields())
                            .filter(field -> Modifier.isStatic(field.getModifiers()))
                            .filter(field -> !field.isSynthetic())
                            .map(Field::getName)
                            .filter(name -> name.contains("ROW_COUNT")
                                    || name.contains("PAGE_SIZE")
                                    || name.contains("MAX_ROWS"))
                            .toList())
                    .as("no depth constant of any spelling is published on the response body")
                    .isEmpty();
        }

        @Test
        @DisplayName("is not the card-list screen's count, which is a different screen's shape")
        void isNotTheCardListScreensCount() {
            assertThat(PageMetadata.TRANSACTION_LIST_PAGE_SIZE)
                    .isNotEqualTo(PageMetadata.CARD_LIST_PAGE_SIZE);
        }

        @Test
        @DisplayName("carries a complete screen of rows")
        void carriesACompleteScreenOfRows() {
            List<TransactionListResponse.TransactionRow> full =
                    rows(PageMetadata.TRANSACTION_LIST_PAGE_SIZE);

            TransactionListResponse subject = response(full, TransactionListResponse.MESSAGE_AT_TOP);

            assertThat(subject.rows()).hasSize(PageMetadata.TRANSACTION_LIST_PAGE_SIZE);
        }

        @Test
        @DisplayName("refuses one row more than the screen has lines, rather than discarding it")
        void refusesOneRowMoreThanTheScreenHasLines() {
            List<TransactionListResponse.TransactionRow> overfull =
                    rows(PageMetadata.TRANSACTION_LIST_PAGE_SIZE + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response(overfull, TransactionListResponse.MESSAGE_AT_TOP))
                    .withMessageContaining(String.valueOf(PageMetadata.TRANSACTION_LIST_PAGE_SIZE))
                    .withMessageContaining(String.valueOf(PageMetadata.TRANSACTION_LIST_PAGE_SIZE + 1));
        }

        @Test
        @DisplayName("leaves a short final page short and never pads it with blank rows")
        void leavesAShortFinalPageShort() throws JsonProcessingException {
            List<TransactionListResponse.TransactionRow> partial = rows(3);

            TransactionListResponse subject = response(partial, TransactionListResponse.MESSAGE_REACHED_BOTTOM);

            assertThat(subject.rows()).hasSize(3);
            assertThat(MAPPER.writeValueAsString(subject.rows()))
                    .isEqualTo(MAPPER.writeValueAsString(partial));
        }

        @Test
        @DisplayName("carries no rows at all when the browse yielded none")
        void carriesNoRowsAtAll() {
            TransactionListResponse subject = response(List.of(), TransactionListResponse.MESSAGE_AT_TOP);

            assertThat(subject.rows()).isEmpty();
        }

        @Test
        @DisplayName("presents the rows in exactly the order supplied, sorting nothing")
        void presentsTheRowsInExactlyTheOrderSupplied() {
            TransactionListResponse.TransactionRow higher = row(TRANSACTION_ID, AMOUNT);
            TransactionListResponse.TransactionRow lower = row(TRANSACTION_ID_LOWER, AMOUNT_NEGATIVE);
            TransactionListResponse.TransactionRow first = row(TRANSACTION_ID_ONE, AMOUNT_ZERO);

            TransactionListResponse subject = response(List.of(higher, lower, first),
                    TransactionListResponse.MESSAGE_AT_TOP);

            assertThat(subject.rows()).containsExactly(higher, lower, first);
        }

        @Test
        @DisplayName("keeps two identical rows, de-duplicating nothing")
        void keepsTwoIdenticalRows() {
            TransactionListResponse.TransactionRow repeated = row(TRANSACTION_ID, AMOUNT);

            TransactionListResponse subject = response(List.of(repeated, repeated),
                    TransactionListResponse.MESSAGE_AT_TOP);

            assertThat(subject.rows()).hasSize(2).containsExactly(repeated, repeated);
        }
    }

    // =============================================================================================
    // BROWSE DIRECTION
    // =============================================================================================

    /**
     * The backward path of this program fills the screen from its last line upward: the backward
     * paragraph at line 333 of {@code app/cbl/COTRN00C.cbl} initialises the index to ten at line 349
     * and walks it down to one across lines 351 to 357, reading in reverse at line 352. A translation
     * that lost that descending fill would present a page walked backward in the opposite sequence to
     * the one the legacy screen showed, which is a visible behavioural regression rather than a
     * cosmetic difference.
     *
     * <p>The contract's part in preserving it is to carry the direction as data and to impose no
     * ordering of its own, which is what these assertions establish. Performing the reversal is the
     * service's responsibility, and this carrier neither sorts, reverses nor accepts a comparator.
     */
    @Nested
    @DisplayName("browse direction travels as data and no ordering is imposed")
    class BrowseDirection {

        @Test
        @DisplayName("distinguishes a page walked forward from one walked backward")
        void distinguishesForwardFromBackward() {
            PageMetadata forward = forwardPaging();
            PageMetadata backward = backwardPaging();

            assertThat(forward.direction()).isEqualTo(PageMetadata.PagingDirection.FORWARD);
            assertThat(backward.direction()).isEqualTo(PageMetadata.PagingDirection.BACKWARD);
            assertThat(forward.direction()).isNotEqualTo(backward.direction());
        }

        @Test
        @DisplayName("carries either direction through the response unchanged")
        void carriesEitherDirectionThroughTheResponse() {
            TransactionListResponse walkedForward = new TransactionListResponse(
                    List.of(row(TRANSACTION_ID, AMOUNT)), forwardPaging(), navigation(), NEXT_ROUTE,
                    TRANSACTION_ID_LOWER, PAGE_INDICATOR, TransactionListResponse.MESSAGE_AT_TOP, false,
                    FOCUS_FIELD, TITLE_ONE, TITLE_TWO, CURRENT_DATE, CURRENT_TIME, TRANSACTION_NAME,
                    PROGRAM_NAME);
            TransactionListResponse walkedBackward = new TransactionListResponse(
                    List.of(row(TRANSACTION_ID, AMOUNT)), backwardPaging(), navigation(), NEXT_ROUTE,
                    TRANSACTION_ID_LOWER, PAGE_INDICATOR, TransactionListResponse.MESSAGE_AT_TOP, false,
                    FOCUS_FIELD, TITLE_ONE, TITLE_TWO, CURRENT_DATE, CURRENT_TIME, TRANSACTION_NAME,
                    PROGRAM_NAME);

            assertThat(walkedForward.pageMetadata().direction())
                    .isEqualTo(PageMetadata.PagingDirection.FORWARD);
            assertThat(walkedBackward.pageMetadata().direction())
                    .isEqualTo(PageMetadata.PagingDirection.BACKWARD);
        }

        @Test
        @DisplayName("preserves a descending sequence exactly, forcing no ascending order")
        void preservesADescendingSequenceExactly() {
            TransactionListResponse.TransactionRow last = row(TRANSACTION_ID, AMOUNT);
            TransactionListResponse.TransactionRow middle = row(TRANSACTION_ID_LOWER, AMOUNT);
            TransactionListResponse.TransactionRow firstOfAll = row(TRANSACTION_ID_ONE, AMOUNT);
            List<TransactionListResponse.TransactionRow> descending = List.of(last, middle, firstOfAll);

            TransactionListResponse subject = new TransactionListResponse(descending, backwardPaging(),
                    navigation(), NEXT_ROUTE, TRANSACTION_ID_LOWER, PAGE_INDICATOR,
                    TransactionListResponse.MESSAGE_REACHED_TOP, false, FOCUS_FIELD, TITLE_ONE,
                    TITLE_TWO, CURRENT_DATE, CURRENT_TIME, TRANSACTION_NAME, PROGRAM_NAME);

            assertThat(subject.rows()).containsExactlyElementsOf(descending);
            assertThat(subject.rows()).isNotEqualTo(List.of(firstOfAll, middle, last));
        }

        @Test
        @DisplayName("names both directions on the wire as text, never as an ordinal")
        void namesBothDirectionsOnTheWireAsText() throws JsonProcessingException {
            assertThat(published(forwardPaging())).containsEntry("direction", "FORWARD");
            assertThat(published(backwardPaging())).containsEntry("direction", "BACKWARD");
        }
    }

    // =============================================================================================
    // THE OPERATOR-VISIBLE MESSAGES
    // =============================================================================================

    /**
     * Seven texts this screen puts in front of an operator, each reproduced character for character at
     * the length measured from the program that emits it.
     *
     * <p>They are an external interface contract rather than incidental strings: operators read them
     * and downstream tooling matches on them, so a well-meant rewording is a contract break. Five of
     * the seven report a browse boundary, and they are the legacy substitute for a total-page count
     * &mdash; the browse never counts the cluster, it discovers exhaustion by attempting one more
     * access and then says so &mdash; which is precisely why this contract publishes no total.</p>
     *
     * <p><strong>Two properties of these texts look like defects and are contract, not defects.</strong>
     * They are <em>mixed case</em>, whereas the card-list program's equivalents are upper case
     * throughout: the two programs simply differ, the difference is what each screen actually showed an
     * operator, and neither may be normalised to the other or folded to a common case. And the
     * invalid-selection text is <em>singular</em>, because this screen accepts one selection character,
     * whereas the administrative user-list screen accepts two and phrases its own text in the plural;
     * harmonising the two would break whichever contract lost.</p>
     */
    @Nested
    @DisplayName("the operator-visible messages are reproduced character for character")
    class OperatorMessages {

        @Test
        @DisplayName("reproduces the invalid-selection text, singular, at thirty-five characters")
        void reproducesTheInvalidSelectionText() throws JsonProcessingException {
            assertThat(TransactionListResponse.MESSAGE_INVALID_SELECTION)
                    .isEqualTo("Invalid selection. Valid value is S");
            assertMessageTravelsVerbatim(TransactionListResponse.MESSAGE_INVALID_SELECTION, 35);
        }

        @Test
        @DisplayName("keeps the singular text distinct from the user-list screen's plural phrasing")
        void keepsTheSingularTextDistinct() {
            assertThat(TransactionListResponse.MESSAGE_INVALID_SELECTION)
                    .isNotEqualTo(PLURAL_SELECTION_VARIANT)
                    .hasSize(35);
            assertThat(PLURAL_SELECTION_VARIANT).hasSize(43);
        }

        @Test
        @DisplayName("reproduces the not-numeric text at twenty-seven characters, spaced dots included")
        void reproducesTheNotNumericText() throws JsonProcessingException {
            assertThat(TransactionListResponse.MESSAGE_TRAN_ID_NOT_NUMERIC)
                    .isEqualTo("Tran ID must be Numeric ...")
                    .contains(" ...");
            assertMessageTravelsVerbatim(TransactionListResponse.MESSAGE_TRAN_ID_NOT_NUMERIC, 27);
        }

        @Test
        @DisplayName("reproduces the already-at-top text at forty-one characters")
        void reproducesTheAlreadyAtTopText() throws JsonProcessingException {
            assertThat(TransactionListResponse.MESSAGE_ALREADY_AT_TOP)
                    .isEqualTo("You are already at the top of the page...");
            assertMessageTravelsVerbatim(TransactionListResponse.MESSAGE_ALREADY_AT_TOP, 41);
        }

        @Test
        @DisplayName("reproduces the already-at-bottom text at forty-four characters")
        void reproducesTheAlreadyAtBottomText() throws JsonProcessingException {
            assertThat(TransactionListResponse.MESSAGE_ALREADY_AT_BOTTOM)
                    .isEqualTo("You are already at the bottom of the page...");
            assertMessageTravelsVerbatim(TransactionListResponse.MESSAGE_ALREADY_AT_BOTTOM, 44);
        }

        @Test
        @DisplayName("reproduces the at-top text at thirty-three characters, without the word already")
        void reproducesTheAtTopText() throws JsonProcessingException {
            assertThat(TransactionListResponse.MESSAGE_AT_TOP)
                    .isEqualTo("You are at the top of the page...")
                    .doesNotContain("already");
            assertMessageTravelsVerbatim(TransactionListResponse.MESSAGE_AT_TOP, 33);
        }

        @Test
        @DisplayName("reproduces the reached-bottom text at forty-two characters")
        void reproducesTheReachedBottomText() throws JsonProcessingException {
            assertThat(TransactionListResponse.MESSAGE_REACHED_BOTTOM)
                    .isEqualTo("You have reached the bottom of the page...");
            assertMessageTravelsVerbatim(TransactionListResponse.MESSAGE_REACHED_BOTTOM, 42);
        }

        @Test
        @DisplayName("reproduces the reached-top text at thirty-nine characters")
        void reproducesTheReachedTopText() throws JsonProcessingException {
            assertThat(TransactionListResponse.MESSAGE_REACHED_TOP)
                    .isEqualTo("You have reached the top of the page...");
            assertMessageTravelsVerbatim(TransactionListResponse.MESSAGE_REACHED_TOP, 39);
        }

        @Test
        @DisplayName("keeps all five boundary texts distinct from one another")
        void keepsAllFiveBoundaryTextsDistinct() {
            assertThat(List.of(
                    TransactionListResponse.MESSAGE_ALREADY_AT_TOP,
                    TransactionListResponse.MESSAGE_ALREADY_AT_BOTTOM,
                    TransactionListResponse.MESSAGE_AT_TOP,
                    TransactionListResponse.MESSAGE_REACHED_BOTTOM,
                    TransactionListResponse.MESSAGE_REACHED_TOP)).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("leaves every text in mixed case, folding none of them to upper case")
        void leavesEveryTextInMixedCase() {
            assertThat(TransactionListResponse.MESSAGE_AT_TOP)
                    .isNotEqualTo("YOU ARE AT THE TOP OF THE PAGE...");
            assertThat(TransactionListResponse.MESSAGE_REACHED_BOTTOM)
                    .isNotEqualTo("YOU HAVE REACHED THE BOTTOM OF THE PAGE...");
            assertThat(TransactionListResponse.MESSAGE_INVALID_SELECTION)
                    .isNotEqualTo("INVALID SELECTION. VALID VALUE IS S");
        }

        @Test
        @DisplayName("keeps every text clear of any component name, so a rename cannot reach inside one")
        void keepsEveryTextClearOfAnyComponentName() {
            List<String> texts = List.of(
                    TransactionListResponse.MESSAGE_INVALID_SELECTION,
                    TransactionListResponse.MESSAGE_TRAN_ID_NOT_NUMERIC,
                    TransactionListResponse.MESSAGE_ALREADY_AT_TOP,
                    TransactionListResponse.MESSAGE_ALREADY_AT_BOTTOM,
                    TransactionListResponse.MESSAGE_AT_TOP,
                    TransactionListResponse.MESSAGE_REACHED_BOTTOM,
                    TransactionListResponse.MESSAGE_REACHED_TOP);

            assertThat(texts).hasSize(7).allSatisfy(text -> assertThat(text)
                    .doesNotContain("displayedPageNumber")
                    .doesNotContain("transactionIdFilter")
                    .doesNotContain("nextRoute"));
        }

        @Test
        @DisplayName("carries a boundary text without marking the response as an error")
        void carriesABoundaryTextWithoutMarkingAnError() {
            TransactionListResponse subject = response(List.of(row(TRANSACTION_ID, AMOUNT)),
                    TransactionListResponse.MESSAGE_REACHED_BOTTOM);

            assertThat(subject.message()).isEqualTo(TransactionListResponse.MESSAGE_REACHED_BOTTOM);
            assertThat(subject.error()).isFalse();
        }

        @Test
        @DisplayName("leaves the message absent when the screen has nothing to report")
        void leavesTheMessageAbsent() {
            assertThat(emptyResponse().message()).isNull();
        }

        @Test
        @DisplayName("reports a seventy-ninth message character, holding the message at seventy-eight")
        void reportsASeventyNinthMessageCharacter() {
            TransactionListResponse subject = response(List.of(row(TRANSACTION_ID, AMOUNT)),
                    MESSAGE_TOO_LONG);

            assertThat(violationsOf(subject)).isNotEmpty().allSatisfy(violation -> {
                assertThat(violation.getPropertyPath()).hasToString("message");
                assertThat(violation.getInvalidValue()).isEqualTo(MESSAGE_TOO_LONG);
            });
        }
    }

    // =============================================================================================
    // THE ROW AMOUNT: EXACT DECIMAL, SCALE TWO, NEVER RESCALED
    // =============================================================================================

    /**
     * The row amount originates in a signed zoned-decimal record field with nine integer digits and
     * two decimal places, so it is carried as an exact decimal at a published scale and never as a
     * binary floating-point value, which could not represent it exactly.
     *
     * <p><strong>The scale is asserted directly rather than compared numerically.</strong> A
     * comparison that ignored scale would treat a value that had drifted to a different number of
     * decimal places as equal to one that had not, which is exactly the drift these assertions exist
     * to catch, so the scale is read and asserted as a figure in its own right.</p>
     *
     * <p><strong>Nothing rescales, in either direction.</strong> An amount already at the contract
     * scale crosses the boundary byte for byte; an amount at any other scale is <em>refused</em> rather
     * than repaired, so a producer is told at construction instead of a client receiving a payload
     * whose precision silently contradicts the published schema. Where rescaling is genuinely needed it
     * happens in exactly one component of the module, reached through the service layer, and that
     * component is deliberately absent from this file: a test that generated its expected values with
     * the implementation's own arithmetic would agree with that arithmetic whatever it did.</p>
     *
     * <p>Both signed directions are exercised, because the seeded daily-transaction fixture holds two
     * hundred and fifty purchases against fifty operator-originated returns and a contract that only
     * worked for debits would fail on one sixth of production-representative data.</p>
     */
    @Nested
    @DisplayName("the row amount is an exact decimal at scale two and is never rescaled")
    class RowAmount {

        @Test
        @DisplayName("publishes the scale and the integer-digit width of the record field it represents")
        void publishesTheScaleAndTheIntegerDigitWidth() {
            assertThat(TransactionListResponse.AMOUNT_SCALE).isEqualTo(2);
            assertThat(TransactionListResponse.AMOUNT_INTEGER_DIGITS).isEqualTo(9);
        }

        @Test
        @DisplayName("returns the amount as an exact decimal, so no binary approximation is possible")
        void returnsTheAmountAsAnExactDecimal() {
            BigDecimal exact = row(TRANSACTION_ID, AMOUNT).amount();

            assertThat(exact).isEqualTo(new BigDecimal("1234.56"));
            assertThat(exact.unscaledValue()).hasToString("123456");
            assertThat(exact).hasToString("1234.56");
        }

        @Test
        @DisplayName("returns the identical value at the identical scale, rescaling nothing on the way in")
        void returnsTheIdenticalValueAtTheIdenticalScale() {
            BigDecimal carried = row(TRANSACTION_ID, AMOUNT).amount();

            assertThat(carried).isSameAs(AMOUNT);
            assertThat(carried.scale()).isEqualTo(TransactionListResponse.AMOUNT_SCALE);
            assertThat(carried).isEqualTo(AMOUNT);
        }

        @Test
        @DisplayName("carries a negative amount, because an operator-originated return is ordinary")
        void carriesANegativeAmount() {
            BigDecimal carried = row(TRANSACTION_ID, AMOUNT_NEGATIVE).amount();

            assertThat(carried).isEqualTo(new BigDecimal("-123.45")).isNegative();
            assertThat(carried.scale()).isEqualTo(TransactionListResponse.AMOUNT_SCALE);
        }

        @Test
        @DisplayName("carries a zero amount, which is not the same thing as an absent one")
        void carriesAZeroAmount() {
            BigDecimal carried = row(TRANSACTION_ID, AMOUNT_ZERO).amount();

            assertThat(carried).isEqualTo(new BigDecimal("0.00")).isNotNull();
            assertThat(carried.scale()).isEqualTo(TransactionListResponse.AMOUNT_SCALE);
            assertThat(carried).isNotEqualTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("carries the widest amount the record field can hold")
        void carriesTheWidestAmount() {
            BigDecimal carried = row(TRANSACTION_ID, AMOUNT_WIDEST).amount();

            assertThat(carried).isEqualTo(new BigDecimal("999999999.99"));
            assertThat(carried.precision() - carried.scale())
                    .isEqualTo(TransactionListResponse.AMOUNT_INTEGER_DIGITS);
        }

        @Test
        @DisplayName("accepts an absent amount, because a row the browse never filled carries none")
        void acceptsAnAbsentAmount() {
            assertThat(new TransactionListResponse.TransactionRow(
                    SELECTION, TRANSACTION_ID, ROW_DATE, DESCRIPTION, null).amount()).isNull();
        }

        @Test
        @DisplayName("refuses a coarser scale instead of padding it out to the record scale")
        void refusesACoarserScale() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> row(TRANSACTION_ID, new BigDecimal("1234.5")))
                    .withMessageContaining(String.valueOf(TransactionListResponse.AMOUNT_SCALE));
        }

        @Test
        @DisplayName("refuses a finer scale instead of rounding it away")
        void refusesAFinerScale() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> row(TRANSACTION_ID, new BigDecimal("1234.567")))
                    .withMessageContaining(String.valueOf(TransactionListResponse.AMOUNT_SCALE));
        }

        @Test
        @DisplayName("refuses a value needing a tenth integer digit")
        void refusesATenthIntegerDigit() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> row(TRANSACTION_ID, new BigDecimal("1000000000.00")))
                    .withMessageContaining(String.valueOf(TransactionListResponse.AMOUNT_INTEGER_DIGITS));
        }

        @Test
        @DisplayName("writes the amount as plain decimal text, keeping the trailing zeros the scale requires")
        void writesTheAmountAsPlainDecimalText() throws JsonProcessingException {
            String json = MAPPER.writeValueAsString(row(TRANSACTION_ID, AMOUNT_WHOLE));

            assertThat(json).contains("\"amount\":100.00");
            assertThat(json).doesNotContain("E+").doesNotContain("e+").doesNotContain("E-");
        }

        @Test
        @DisplayName("writes an exponentially shaped decimal in plain form, as the module's mapper must")
        void writesAnExponentiallyShapedDecimalInPlainForm() throws JsonProcessingException {
            assertThat(AMOUNT_IN_EXPONENTIAL_SHAPE).hasToString("1E+2");

            assertThat(MAPPER.writeValueAsString(AMOUNT_IN_EXPONENTIAL_SHAPE))
                    .isEqualTo("100")
                    .doesNotContain("E+");
        }

        @Test
        @DisplayName("groups no digits, prefixes no sign and adds no currency symbol")
        void groupsNoDigitsAndAddsNoSymbol() throws JsonProcessingException {
            String json = MAPPER.writeValueAsString(row(TRANSACTION_ID, AMOUNT_WIDEST));

            assertThat(json).contains("\"amount\":999999999.99");
            assertThat(json).doesNotContain(",99").doesNotContain("+9").doesNotContain("$");
        }

        @Test
        @DisplayName("reads a plain decimal back as the identical value at the identical scale")
        void readsAPlainDecimalBackAtTheIdenticalScale() throws JsonProcessingException {
            String json = "{\"selection\":\"S\",\"transactionId\":\"" + TRANSACTION_ID
                    + "\",\"displayedDate\":\"" + ROW_DATE + "\",\"description\":\"" + DESCRIPTION
                    + "\",\"amount\":100.00}";

            TransactionListResponse.TransactionRow revived =
                    MAPPER.readValue(json, TransactionListResponse.TransactionRow.class);

            assertThat(revived.amount()).isEqualTo(new BigDecimal("100.00"));
            assertThat(revived.amount().scale()).isEqualTo(TransactionListResponse.AMOUNT_SCALE);
        }

        @Test
        @DisplayName("round-trips a negative and a zero amount through the wire without drift")
        void roundTripsANegativeAndAZeroAmount() throws JsonProcessingException {
            for (BigDecimal amount : List.of(AMOUNT_NEGATIVE, AMOUNT_ZERO, AMOUNT_WHOLE, AMOUNT_WIDEST)) {
                TransactionListResponse.TransactionRow revived = MAPPER.readValue(
                        MAPPER.writeValueAsString(row(TRANSACTION_ID, amount)),
                        TransactionListResponse.TransactionRow.class);

                assertThat(revived.amount()).isEqualTo(amount);
                assertThat(revived.amount().scale()).isEqualTo(TransactionListResponse.AMOUNT_SCALE);
            }
        }
    }

    // =============================================================================================
    // THE PAGING STATE IS CARRIED, NOT RE-IMPLEMENTED
    // =============================================================================================

    /**
     * The paging state travels in the shared {@link PageMetadata} contract, which this response carries
     * without re-declaring, re-deriving or re-interpreting any part of it.
     *
     * <p>Two absences are as much part of the contract as anything present. There is no total row count
     * and no total page count, because the legacy browse never counts the cluster: it discovers whether
     * a further page exists by attempting one more access, and reports the outcome through the five
     * boundary texts asserted above. Publishing a total would be fabricated information and would force
     * a counting query the original never issued.
     */
    @Nested
    @DisplayName("the paging state is carried in the shared contract, not re-implemented")
    class CarriedPagingState {

        @Test
        @DisplayName("returns the paging state it was handed, unaltered")
        void returnsThePagingStateItWasHanded() {
            PageMetadata supplied = forwardPaging();

            TransactionListResponse subject = new TransactionListResponse(
                    List.of(row(TRANSACTION_ID, AMOUNT)), supplied, navigation(), NEXT_ROUTE,
                    TRANSACTION_ID_LOWER, PAGE_INDICATOR, TransactionListResponse.MESSAGE_AT_TOP, false,
                    FOCUS_FIELD, TITLE_ONE, TITLE_TWO, CURRENT_DATE, CURRENT_TIME, TRANSACTION_NAME,
                    PROGRAM_NAME);

            assertThat(subject.pageMetadata()).isSameAs(supplied);
        }

        @Test
        @DisplayName("carries both opaque cursors byte for byte, leading zeros included")
        void carriesBothOpaqueCursorsByteForByte() {
            PageMetadata paging = populatedResponse().pageMetadata();

            assertThat(paging.previousCursorKey())
                    .isEqualTo(PREVIOUS_CURSOR_KEY)
                    .startsWith("0")
                    .hasSize(16);
            assertThat(paging.nextCursorKey())
                    .isEqualTo(NEXT_CURSOR_KEY)
                    .startsWith("0")
                    .hasSize(16);
        }

        @Test
        @DisplayName("carries the eight-character page indicator without trimming its padding")
        void carriesThePageIndicatorWithoutTrimming() {
            PageMetadata paging = populatedResponse().pageMetadata();

            assertThat(paging.displayedPageNumber())
                    .isEqualTo(PAGE_INDICATOR)
                    .endsWith(" ")
                    .hasSize(TransactionListResponse.DISPLAYED_PAGE_NUMBER_LENGTH);
            assertThat(TransactionListResponse.DISPLAYED_PAGE_NUMBER_LENGTH)
                    .isEqualTo(PageMetadata.DISPLAYED_PAGE_NUMBER_MAX_LENGTH);
        }

        @Test
        @DisplayName("carries the response's own page indicator without trimming it either")
        void carriesTheResponsesOwnPageIndicator() {
            assertThat(populatedResponse().displayedPageNumber())
                    .isEqualTo(PAGE_INDICATOR)
                    .endsWith(" ")
                    .hasSize(TransactionListResponse.DISPLAYED_PAGE_NUMBER_LENGTH);
        }

        @Test
        @DisplayName("reports the two boundary conditions as independent flags")
        void reportsTheTwoBoundaryConditionsAsIndependentFlags() {
            PageMetadata forward = forwardPaging();
            PageMetadata backward = backwardPaging();

            assertThat(forward.hasMorePages()).isTrue();
            assertThat(forward.hasPreviousPages()).isFalse();
            assertThat(backward.hasMorePages()).isFalse();
            assertThat(backward.hasPreviousPages()).isTrue();
        }

        @Test
        @DisplayName("publishes no aggregate over the browse, on the response or on its paging state")
        void publishesNoAggregateOverTheBrowse() throws JsonProcessingException {
            assertThat(published(populatedResponse()).keySet())
                    .allSatisfy(TransactionListResponseTest::assertNamesNoAggregate);
            assertThat(published(forwardPaging()).keySet())
                    .allSatisfy(TransactionListResponseTest::assertNamesNoAggregate);
        }

        @Test
        @DisplayName("reports exhaustion through a boundary text, which is what stands in for an aggregate")
        void reportsExhaustionThroughABoundaryText() {
            PageMetadata exhausted = PageMetadata.forward(PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                    PREVIOUS_CURSOR_KEY, null, false, true, PAGE_INDICATOR);

            TransactionListResponse subject = new TransactionListResponse(
                    List.of(row(TRANSACTION_ID, AMOUNT)), exhausted, navigation(), NEXT_ROUTE,
                    TRANSACTION_ID_LOWER, PAGE_INDICATOR,
                    TransactionListResponse.MESSAGE_REACHED_BOTTOM, false, FOCUS_FIELD, TITLE_ONE,
                    TITLE_TWO, CURRENT_DATE, CURRENT_TIME, TRANSACTION_NAME, PROGRAM_NAME);

            assertThat(subject.pageMetadata().hasMorePages()).isFalse();
            assertThat(subject.pageMetadata().nextCursorKey()).isNull();
            assertThat(subject.message()).isEqualTo(TransactionListResponse.MESSAGE_REACHED_BOTTOM);
        }

        @Test
        @DisplayName("publishes exactly the seven paging components the shared contract declares")
        void publishesExactlySevenPagingComponents() throws JsonProcessingException {
            assertThat(published(forwardPaging())).containsOnlyKeys("pageSize", "previousCursorKey",
                    "nextCursorKey", "direction", "hasMorePages", "hasPreviousPages",
                    "displayedPageNumber");
        }

        @Test
        @DisplayName("tolerates absent paging state, as a redisplay without a browse has none")
        void toleratesAbsentPagingState() {
            assertThat(emptyResponse().pageMetadata()).isNull();
        }
    }

    // =============================================================================================
    // THE NAVIGATION STATE IS CARRIED, NOT RE-IMPLEMENTED
    // =============================================================================================

    /**
     * The client-echoed navigation state travels in the shared {@link NavigationContext} contract,
     * which replaces the communication area the legacy transaction carried across a
     * pseudo-conversational turn. This response carries it and re-declares no part of it.
     */
    @Nested
    @DisplayName("the navigation state is carried in the shared contract, not re-implemented")
    class CarriedNavigationState {

        @Test
        @DisplayName("returns the navigation state it was handed, unaltered")
        void returnsTheNavigationStateItWasHanded() {
            NavigationContext supplied = navigation();

            TransactionListResponse subject = new TransactionListResponse(
                    List.of(row(TRANSACTION_ID, AMOUNT)), forwardPaging(), supplied, NEXT_ROUTE,
                    TRANSACTION_ID_LOWER, PAGE_INDICATOR, TransactionListResponse.MESSAGE_AT_TOP, false,
                    FOCUS_FIELD, TITLE_ONE, TITLE_TWO, CURRENT_DATE, CURRENT_TIME, TRANSACTION_NAME,
                    PROGRAM_NAME);

            assertThat(subject.navigationContext()).isSameAs(supplied);
        }

        @Test
        @DisplayName("carries every echoed identifier unchanged, leading zeros included")
        void carriesEveryEchoedIdentifierUnchanged() {
            NavigationContext carried = populatedResponse().navigationContext();

            assertThat(carried.userId()).isEqualTo(USER_ID);
            assertThat(carried.customerId()).isEqualTo(CUSTOMER_ID).startsWith("0").hasSize(9);
            assertThat(carried.accountId()).isEqualTo(ACCOUNT_ID).startsWith("0").hasSize(11);
            assertThat(carried.cardNumber()).isEqualTo(CARD_NUMBER).startsWith("0").hasSize(16);
            assertThat(carried.fromTransactionId()).isEqualTo(TRANSACTION_NAME);
            assertThat(carried.toProgram()).isEqualTo(PROGRAM_NAME);
        }

        @Test
        @DisplayName("carries the re-entry state, which is what governs field-level error decoration")
        void carriesTheReEntryState() {
            NavigationContext carried = populatedResponse().navigationContext();

            assertThat(carried.programContext()).isEqualTo(NavigationContext.ProgramContext.REENTER);
            assertThat(carried.reEntry()).isTrue();
            assertThat(carried.firstEntry()).isFalse();
        }

        @Test
        @DisplayName("tolerates absent navigation state, and an entirely empty one")
        void toleratesAbsentNavigationState() {
            assertThat(emptyResponse().navigationContext()).isNull();

            TransactionListResponse withEmpty = new TransactionListResponse(
                    List.of(), forwardPaging(), NavigationContext.empty(), null, null, null, null, false,
                    null, null, null, null, null, null, null);

            assertThat(withEmpty.navigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(withEmpty.navigationContext().userId()).isNull();
        }
    }

    // =============================================================================================
    // THE ROW COLLECTION
    // =============================================================================================

    /**
     * The row list is defensively copied into an unmodifiable list at construction and an absent list
     * becomes an empty one, so an instance is deeply immutable and no caller has to distinguish "no
     * rows" from "absent". Immutability is demonstrated by construction and by behaviour: the caller's
     * own list is mutated after the fact and the response is shown to be unaffected, and the returned
     * list is shown to refuse modification.
     */
    @Nested
    @DisplayName("the row collection is unmodifiable, defensively copied and null-tolerant")
    class RowCollection {

        @Test
        @DisplayName("refuses an addition through the accessor")
        void refusesAnAdditionThroughTheAccessor() {
            List<TransactionListResponse.TransactionRow> returned = populatedResponse().rows();
            TransactionListResponse.TransactionRow extra = row(TRANSACTION_ID_ONE, AMOUNT);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> returned.add(extra));
        }

        @Test
        @DisplayName("refuses a removal and a clear through the accessor as well")
        void refusesARemovalAndAClear() {
            List<TransactionListResponse.TransactionRow> returned = populatedResponse().rows();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> returned.remove(0));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(returned::clear);
        }

        @Test
        @DisplayName("is unaffected by a later mutation of the caller's own list")
        void isUnaffectedByALaterMutation() {
            List<TransactionListResponse.TransactionRow> callerOwned = new ArrayList<>();
            callerOwned.add(row(TRANSACTION_ID, AMOUNT));

            TransactionListResponse subject = response(callerOwned,
                    TransactionListResponse.MESSAGE_AT_TOP);
            callerOwned.add(row(TRANSACTION_ID_ONE, AMOUNT_NEGATIVE));
            callerOwned.clear();

            assertThat(subject.rows()).hasSize(1);
            assertThat(subject.rows().get(0).transactionId()).isEqualTo(TRANSACTION_ID);
        }

        @Test
        @DisplayName("rejects a null row, as a defensive copy of a list must")
        void rejectsANullRow() {
            List<TransactionListResponse.TransactionRow> withHole = new ArrayList<>();
            withHole.add(row(TRANSACTION_ID, AMOUNT));
            withHole.add(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response(withHole, TransactionListResponse.MESSAGE_AT_TOP));
        }

        @Test
        @DisplayName("turns an absent list into an empty unmodifiable one, never into a null")
        void turnsAnAbsentListIntoAnEmptyUnmodifiableOne() {
            List<TransactionListResponse.TransactionRow> returned = emptyResponse().rows();
            TransactionListResponse.TransactionRow extra = row(TRANSACTION_ID, AMOUNT);

            assertThat(returned).isNotNull().isEmpty();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> returned.add(extra));
        }
    }

    // =============================================================================================
    // BEAN VALIDATION: A MAXIMUM LENGTH AND NOTHING ELSE
    // =============================================================================================

    /**
     * The only constraint this contract uses is a maximum length, which measures and never alters, so
     * space padding on a legacy fixed-width value survives untouched.
     *
     * <p>No presence, blankness, emptiness, pattern, digit, range or sign constraint appears anywhere.
     * That is a deliberate design decision rather than an omission: every check the legacy program
     * performs is a message-bearing validation emitted in source order and stopping at the first
     * failure, and Bean Validation reports its violations as an unordered set, so expressing the
     * cascade as annotations would replace an ordered, first-error-wins sequence with an unordered one.
     * The cascade belongs to the service layer. What these assertions establish is that a response with
     * nothing populated is perfectly valid, which is what proves no such constraint is present.
     */
    @Nested
    @DisplayName("bean validation bounds lengths and asserts nothing else")
    class BeanValidationSurface {

        @Test
        @DisplayName("reports nothing at all for a response with every component absent")
        void reportsNothingForAnEntirelyAbsentResponse() {
            assertThat(violationsOf(emptyResponse())).isEmpty();
        }

        @Test
        @DisplayName("reports nothing at all for a row with every component absent")
        void reportsNothingForAnEntirelyAbsentRow() {
            assertThat(violationsOf(new TransactionListResponse.TransactionRow(
                    null, null, null, null, null))).isEmpty();
        }

        @Test
        @DisplayName("reports nothing for a fully populated response at every declared width")
        void reportsNothingForAFullyPopulatedResponse() {
            assertThat(violationsOf(populatedResponse())).isEmpty();
        }

        @Test
        @DisplayName("reports nothing for a blank row, because a blank line is an ordinary screen state")
        void reportsNothingForABlankRow() {
            assertThat(violationsOf(new TransactionListResponse.TransactionRow(
                    " ", "                ", ROW_DATE_ALL_SPACES, DESCRIPTION_SPACE_PADDED,
                    AMOUNT_ZERO))).isEmpty();
        }

        @Test
        @DisplayName("reports nothing for a negative amount, because no sign constraint applies")
        void reportsNothingForANegativeAmount() {
            assertThat(violationsOf(row(TRANSACTION_ID, AMOUNT_NEGATIVE))).isEmpty();
        }

        @Test
        @DisplayName("reports nothing for an empty string, because no blankness constraint applies")
        void reportsNothingForAnEmptyString() {
            TransactionListResponse subject = new TransactionListResponse(List.of(), null, null, "", "",
                    "", "", false, "", "", "", "", "", "", "");

            assertThat(violationsOf(subject)).isEmpty();
        }

        @Test
        @DisplayName("reports nothing for an unpopulated row list, because no emptiness constraint applies")
        void reportsNothingForAnUnpopulatedRowList() {
            assertThat(violationsOf(response(List.of(), null))).isEmpty();
        }

        @Test
        @DisplayName("reports nothing for text that is not numeric where a legacy program tests for it")
        void reportsNothingForTextThatIsNotNumeric() {
            assertThat(violationsOf(row("ABCDEFGHIJKLMNOP", AMOUNT))).isEmpty();
        }
    }

    // =============================================================================================
    // THE SERIALIZED FORM
    // =============================================================================================

    /**
     * What the client actually receives. The key set is the file's run-time statement of the
     * component set, so it doubles as the assertion that none of the things this contract deliberately
     * omits has crept in: no generated screen-plumbing item, no terminal attribute, no map coordinate,
     * no function-key legend, no aggregate count and no problem-document field.
     */
    @Nested
    @DisplayName("the serialized form")
    class SerializedForm {

        @Test
        @DisplayName("publishes exactly the sixteen components the contract declares")
        void publishesExactlyFifteenComponents() throws JsonProcessingException {
            assertThat(published(populatedResponse())).containsOnlyKeys("rows", "pageMetadata",
                    "continuation", "navigationContext", "nextRoute", "transactionIdFilter",
                    "displayedPageNumber", "message", "error", "focusScreenFieldId", "title01",
                    "title02", "currentDate", "currentTime", "transactionName", "programName");
        }

        @Test
        @DisplayName("publishes no screen-plumbing item from the generated map")
        void publishesNoScreenPlumbingItem() throws JsonProcessingException {
            Map<String, Object> body = published(populatedResponse());

            assertThat(body).doesNotContainKeys("length", "flag", "attribute", "filler", "row", "column",
                    "colour", "color", "highlight", "protection", "cursor");
            assertThat(body.keySet()).noneSatisfy(key -> assertThat(key).endsWith("Length"));
            assertThat(body.keySet()).noneSatisfy(key -> assertThat(key).endsWith("Attribute"));
        }

        @Test
        @DisplayName("publishes no function-key legend field")
        void publishesNoFunctionKeyLegendField() throws JsonProcessingException {
            Map<String, Object> body = published(populatedResponse());

            assertThat(body).doesNotContainKeys("functionKeys", "keyLegend", "pfKeys", "attentionKeys");
            assertThat(body.keySet()).noneSatisfy(key -> assertThat(key).startsWith("pf"));
        }

        @Test
        @DisplayName("is a screen response and never a problem document")
        void isAScreenResponseAndNeverAProblemDocument() throws JsonProcessingException {
            assertThat(published(populatedResponse()))
                    .doesNotContainKeys("type", "title", "status", "detail", "instance");
        }

        @Test
        @DisplayName("publishes no source-type column, because the list shows only four columns")
        void publishesNoSourceTypeColumn() throws JsonProcessingException {
            assertThat(published(row(TRANSACTION_ID, AMOUNT)))
                    .doesNotContainKeys("source", "sourceType", "transactionSourceType", "typeCode",
                            "categoryCode", "merchantId", "merchantName", "cardNumber");
        }

        @Test
        @DisplayName("omits every absent value rather than writing it as a null")
        void omitsEveryAbsentValue() throws JsonProcessingException {
            Map<String, Object> body = published(emptyResponse());

            assertThat(body).doesNotContainKeys("pageMetadata", "navigationContext", "nextRoute",
                    "transactionIdFilter", "displayedPageNumber", "message", "focusScreenFieldId",
                    "title01", "title02", "currentDate", "currentTime", "transactionName",
                    "programName");
            assertThat(body).containsOnlyKeys("rows", "error");
            assertThat(MAPPER.writeValueAsString(emptyResponse())).doesNotContain("null");
        }

        @Test
        @DisplayName("always publishes the error indicator, which is stated and never inferred")
        void alwaysPublishesTheErrorIndicator() throws JsonProcessingException {
            assertThat(published(emptyResponse())).containsEntry("error", Boolean.FALSE);

            TransactionListResponse failing = new TransactionListResponse(List.of(), null, null, null,
                    null, null, TransactionListResponse.MESSAGE_INVALID_SELECTION, true, null, null,
                    null, null, null, null, null);

            assertThat(published(failing)).containsEntry("error", Boolean.TRUE);
            assertThat(failing.error()).isTrue();
        }

        @Test
        @DisplayName("round-trips a fully populated response without altering a single component")
        void roundTripsAFullyPopulatedResponse() throws JsonProcessingException {
            TransactionListResponse original = populatedResponse();

            TransactionListResponse revived = MAPPER.readValue(
                    MAPPER.writeValueAsString(original), TransactionListResponse.class);

            assertThat(revived).isEqualTo(original);
            assertThat(revived.rows()).containsExactlyElementsOf(original.rows());
            assertThat(revived.pageMetadata()).isEqualTo(original.pageMetadata());
            assertThat(revived.navigationContext()).isEqualTo(original.navigationContext());
        }

        @Test
        @DisplayName("tolerates a property it does not recognise instead of rejecting the body")
        void toleratesAPropertyItDoesNotRecognise() throws JsonProcessingException {
            String body = "{\"rows\":[],\"error\":false,\"message\":\""
                    + TransactionListResponse.MESSAGE_AT_TOP
                    + "\",\"anItemThisContractHasNeverDeclared\":\"ignored\"}";

            TransactionListResponse revived = MAPPER.readValue(body, TransactionListResponse.class);

            assertThat(revived.rows()).isEmpty();
            assertThat(revived.message()).isEqualTo(TransactionListResponse.MESSAGE_AT_TOP);
            assertThat(revived.error()).isFalse();
        }

        @Test
        @DisplayName("publishes the rows as an array in presentation order")
        void publishesTheRowsAsAnArrayInPresentationOrder() throws JsonProcessingException {
            TransactionListResponse subject = response(
                    List.of(row(TRANSACTION_ID, AMOUNT), row(TRANSACTION_ID_ONE, AMOUNT_NEGATIVE)),
                    TransactionListResponse.MESSAGE_AT_TOP);

            String json = MAPPER.writeValueAsString(subject);

            assertThat(json.indexOf(TRANSACTION_ID)).isLessThan(json.indexOf(TRANSACTION_ID_ONE));
        }
    }

    // =============================================================================================
    // THE ROUTE OUTCOME IS DATA
    // =============================================================================================

    /**
     * The legacy transaction dispatched by transferring control to another program and re-arming itself
     * for the next turn. In the REST translation there is no server-side forwarding at all: the route
     * the client should call next travels back as an opaque value, and the client drives the next call.
     *
     * <p>That makes the route data rather than behaviour, which is what these assertions establish. The
     * contract declares no route table, no route enumeration and no dispatch method, and it neither
     * resolves nor validates what it is handed &mdash; a value that is not a route at all crosses
     * unchanged, which is the plainest possible demonstration that nothing here interprets it.
     */
    @Nested
    @DisplayName("the next route is declarative data and never dispatch logic")
    class RouteOutcome {

        @Test
        @DisplayName("carries the next route back to the client unchanged")
        void carriesTheNextRouteBackUnchanged() throws JsonProcessingException {
            TransactionListResponse subject = populatedResponse();

            assertThat(subject.nextRoute()).isEqualTo(NEXT_ROUTE);
            assertThat(published(subject)).containsEntry("nextRoute", NEXT_ROUTE);
        }

        @Test
        @DisplayName("carries a value that is not a route at all, resolving and validating nothing")
        void carriesAValueThatIsNotARouteAtAll() {
            String notARoute = "  not a route at all  ";

            TransactionListResponse subject = new TransactionListResponse(List.of(), null, null,
                    notARoute, null, null, null, false, null, null, null, null, null, null, null);

            assertThat(subject.nextRoute()).isEqualTo(notARoute);
        }

        @Test
        @DisplayName("publishes the route as text, so no route enumeration is implied")
        void publishesTheRouteAsText() throws JsonProcessingException {
            assertThat(published(populatedResponse()))
                    .extractingByKey("nextRoute")
                    .isInstanceOf(String.class);
        }

        @Test
        @DisplayName("tolerates an absent route, because not every response nominates one")
        void toleratesAnAbsentRoute() {
            assertThat(emptyResponse().nextRoute()).isNull();
        }
    }

    // =============================================================================================
    // VALUE SEMANTICS AND THE TWO DIAGNOSTIC RENDERINGS
    // =============================================================================================

    /**
     * The contract is an immutable value: it is a record, so it has no setter to call &mdash; a fact
     * established at compile time by the absence of any such call from this file rather than by
     * inspecting the type at run time &mdash; and it compares, hashes and renders accordingly.
     *
     * <p>Both renderings are overridden rather than generated, and each withholds what a diagnostic
     * channel must not carry while retaining what makes a diagnostic useful.
     */
    @Nested
    @DisplayName("value semantics and the two diagnostic renderings")
    class ValueSemantics {

        @Test
        @DisplayName("returns every one of the fifteen components through its own accessor")
        void returnsEveryComponentThroughItsAccessor() {
            TransactionListResponse subject = populatedResponse();

            assertThat(subject.rows()).hasSize(1);
            assertThat(subject.pageMetadata()).isNotNull();
            assertThat(subject.navigationContext()).isNotNull();
            assertThat(subject.nextRoute()).isEqualTo(NEXT_ROUTE);
            assertThat(subject.transactionIdFilter()).isEqualTo(TRANSACTION_ID_LOWER);
            assertThat(subject.displayedPageNumber()).isEqualTo(PAGE_INDICATOR);
            assertThat(subject.message()).isEqualTo(TransactionListResponse.MESSAGE_AT_TOP);
            assertThat(subject.error()).isFalse();
            assertThat(subject.focusScreenFieldId()).isEqualTo(FOCUS_FIELD);
            assertThat(subject.title01()).isEqualTo(TITLE_ONE);
            assertThat(subject.title02()).isEqualTo(TITLE_TWO);
            assertThat(subject.currentDate()).isEqualTo(CURRENT_DATE);
            assertThat(subject.currentTime()).isEqualTo(CURRENT_TIME);
            assertThat(subject.transactionName()).isEqualTo(TRANSACTION_NAME);
            assertThat(subject.programName()).isEqualTo(PROGRAM_NAME);
        }

        @Test
        @DisplayName("echoes the search key back exactly as typed, never parsed as a number")
        void echoesTheSearchKeyBackExactlyAsTyped() {
            assertThat(populatedResponse().transactionIdFilter())
                    .isEqualTo(TRANSACTION_ID_LOWER)
                    .startsWith("0")
                    .hasSize(TransactionListResponse.TRANSACTION_ID_LENGTH);
        }

        @Test
        @DisplayName("nominates a field by its map name, never by a screen position")
        void nominatesAFieldByItsMapName() {
            assertThat(populatedResponse().focusScreenFieldId())
                    .isEqualTo(FOCUS_FIELD)
                    .hasSize(TransactionListResponse.SCREEN_FIELD_ID_LENGTH);
            assertThat(TransactionListResponse.SCREEN_FIELD_ID_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("declares both title lines separately, as the map declares two independent fields")
        void declaresBothTitleLinesSeparately() {
            TransactionListResponse subject = populatedResponse();

            assertThat(subject.title01()).isNotEqualTo(subject.title02());
            assertThat(TransactionListResponse.SCREEN_TITLE_LENGTH).isEqualTo(40);
        }

        @Test
        @DisplayName("carries both screen timestamps as opaque text at eight characters")
        void carriesBothScreenTimestampsAsOpaqueText() {
            TransactionListResponse subject = populatedResponse();

            assertThat(subject.currentDate())
                    .hasSize(TransactionListResponse.CURRENT_DATE_LENGTH)
                    .isEqualTo(CURRENT_DATE);
            assertThat(subject.currentTime())
                    .hasSize(TransactionListResponse.CURRENT_TIME_LENGTH)
                    .isEqualTo(CURRENT_TIME);
        }

        @Test
        @DisplayName("compares and hashes by value across every component")
        void comparesAndHashesByValue() {
            TransactionListResponse one = populatedResponse();
            TransactionListResponse same = populatedResponse();

            assertThat(one).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(one).isNotEqualTo(emptyResponse());
            assertThat(one).isNotEqualTo(response(List.of(row(TRANSACTION_ID, AMOUNT)),
                    TransactionListResponse.MESSAGE_REACHED_TOP));
        }

        @Test
        @DisplayName("renders the row count in place of the rows, and withholds the rows themselves")
        void rendersTheRowCountInPlaceOfTheRows() {
            String rendered = populatedResponse().toString();

            assertThat(rendered)
                    .startsWith("TransactionListResponse[")
                    .contains("rowCount=1")
                    .contains("rows=" + REDACTED)
                    .contains("pageMetadata=" + REDACTED)
                    .contains("transactionIdFilter=" + REDACTED)
                    .doesNotContain(TRANSACTION_ID)
                    .doesNotContain(TRANSACTION_ID_LOWER)
                    .doesNotContain(DESCRIPTION)
                    .endsWith("]");
        }

        @Test
        @DisplayName("retains the presentation state a diagnostic actually needs")
        void retainsThePresentationStateADiagnosticNeeds() {
            String rendered = populatedResponse().toString();

            assertThat(rendered)
                    .contains("message=" + TransactionListResponse.MESSAGE_AT_TOP)
                    .contains("error=false")
                    .contains("nextRoute=" + NEXT_ROUTE)
                    .contains("focusScreenFieldId=" + FOCUS_FIELD)
                    .contains("displayedPageNumber=" + PAGE_INDICATOR)
                    .contains("transactionName=" + TRANSACTION_NAME)
                    .contains("programName=" + PROGRAM_NAME)
                    .contains("title01=" + TITLE_ONE)
                    .contains("title02=" + TITLE_TWO)
                    .contains("currentDate=" + CURRENT_DATE)
                    .contains("currentTime=" + CURRENT_TIME);
        }

        @Test
        @DisplayName("delegates the navigation state, which withholds its own identifiers")
        void delegatesTheNavigationState() {
            TransactionListResponse subject = populatedResponse();

            assertThat(subject.toString())
                    .contains("navigationContext=" + subject.navigationContext().toString());
        }

        @Test
        @DisplayName("renders an absent response without printing a placeholder for the row count")
        void rendersAnAbsentResponse() {
            assertThat(emptyResponse().toString())
                    .contains("rowCount=0")
                    .contains("navigationContext=null")
                    .contains("message=null");
        }

        @Test
        @DisplayName("withholds a row's identifier, description and amount from its own rendering")
        void withholdsARowsRegulatedValues() {
            String rendered = row(TRANSACTION_ID, AMOUNT).toString();

            assertThat(rendered)
                    .startsWith("TransactionRow[")
                    .contains("transactionId=" + REDACTED)
                    .contains("description=" + REDACTED)
                    .contains("amount=" + REDACTED)
                    .doesNotContain(TRANSACTION_ID)
                    .doesNotContain(DESCRIPTION)
                    .doesNotContain("1234.56")
                    .endsWith("]");
        }

        @Test
        @DisplayName("retains a row's selection marker and displayed date, which identify nobody")
        void retainsARowsSelectionMarkerAndDate() {
            assertThat(row(TRANSACTION_ID, AMOUNT).toString())
                    .contains("selection=" + SELECTION)
                    .contains("displayedDate=" + ROW_DATE);
        }

        @Test
        @DisplayName("changes nothing an accessor returns, because withholding is confined to rendering")
        void changesNothingAnAccessorReturns() {
            TransactionListResponse.TransactionRow subject = row(TRANSACTION_ID, AMOUNT);
            String ignoredRendering = subject.toString();

            assertThat(ignoredRendering).contains(REDACTED);
            assertThat(subject.transactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(subject.description()).isEqualTo(DESCRIPTION);
            assertThat(subject.amount()).isEqualTo(AMOUNT);

            TransactionListResponse enclosing = populatedResponse();
            String alsoIgnored = enclosing.toString();

            assertThat(alsoIgnored).contains(REDACTED);
            assertThat(enclosing.transactionIdFilter()).isEqualTo(TRANSACTION_ID_LOWER);
            assertThat(enclosing.pageMetadata()).isEqualTo(forwardPaging());
            assertThat(enclosing.rows()).containsExactly(row(TRANSACTION_ID, AMOUNT));
        }
    }
}
