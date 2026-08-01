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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonInclude;
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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link StatementSummary}, the statement work area consumed by the statement
 * generator.
 *
 * <p>{@link StatementSummary} is the decoded value object for the reporting-altered transaction
 * record declared at lines 20 to 36 of {@code app/cpy/COSTM01.CPY}. It is a pure carrier: thirteen
 * live fields, no arithmetic, no scale, no case fold, no trim and no pad. Every assertion below
 * therefore checks a <em>carriage</em> property rather than a computation, because the whole value of
 * this type is that what a producer supplies is exactly what a consumer observes. The statement
 * generator {@code app/cbl/CBSTM03A.CBL} writes two fixed-width output records, one 80 bytes wide and
 * one 100 bytes wide, and both are compared byte for byte against golden fixtures in the end-to-end
 * pipeline test, never here. What lives here is the reason that comparison can succeed at all: a
 * field this type silently dropped, re-padded, re-scaled or case-folded would corrupt both records
 * long before the writers ever saw them.</p>
 *
 * <p><strong>Two acceptance assertions this class owns.</strong> First, the 20-byte record tail is
 * absent: the copybook closes with an unnamed 20-byte slack area at line 36 that carries no data name
 * and exists only to round the record image out to 350 bytes, and it must have no component, accessor
 * or serialised property here - proved positively by serialising a fully populated instance and
 * pinning the resulting property set to exactly the thirteen live names. Second, a 26-blank stamp
 * round-trips byte for byte: both stamp fields are 26 characters of opaque text, the seeded processing
 * stamps in the estate are precisely 26 blanks, and such a value must survive construction and a JSON
 * round trip unchanged - never collapsed to an empty value, never replaced by {@code null}, never
 * trimmed and never normalised.</p>
 *
 * <p><strong>Byte accounting.</strong> The thirteen live fields account for 330 of the record's 350
 * bytes: 16 for the card number, 16 for the transaction identifier, 2 for the type code, 4 for the
 * category code, 10 for the source channel, 100 for the description, 11 for the amount, 9 for the
 * merchant identifier, 50 for the merchant name, 50 for the merchant city, 10 for the merchant postal
 * code, 26 for the origination stamp and 26 for the processing stamp. The amount contributes 11 rather
 * than 12 because it is a signed zoned decimal of nine digits plus two whose sign is overpunched into
 * its final byte rather than occupying a byte of its own. Adding the 20-byte unnamed tail gives
 * 330 + 20 = 350, so the accounting balances exactly even though this type models only the 330 bytes
 * that carry data.</p>
 *
 * <p><strong>These expectations are an independent oracle.</strong> Every expected value is
 * hand-written from the measured copybook widths and from literals the estate is known to write. No
 * expectation is produced by calling a codec, a record mapper, a statement template holder or any
 * statement service, and no assertion compares a value to itself. The JSON shape is checked against a
 * plain {@link ObjectMapper} configured locally in this file to match
 * {@code carddemo-java/src/main/resources/application.yml}; no framework context is started, no
 * container is launched and no database is touched.</p>
 *
 * <p><strong>The traps this class exists to pin down.</strong></p>
 * <ol>
 *   <li><strong>The field order is reversed relative to the base transaction layout.</strong> In
 *       {@code app/cpy/CVTRA05Y.cpy} the transaction identifier is the leading field and the card
 *       number is the fifteenth. This layout hoists the card number to the front and pushes the
 *       identifier second, which is exactly what the statement job's reprojection does. The property
 *       order is asserted, so the reversal cannot be quietly "corrected" back.</li>
 *   <li><strong>Three widths are wider than the online view map, deliberately.</strong> The
 *       description is 100 here against 60 in {@code app/cpy-bms/COTRN01.CPY}, the merchant name 50
 *       against 30, and the merchant city 50 against 25. Both are real external contracts that
 *       merely describe overlapping concepts, so the wider widths are asserted and the narrower ones
 *       asserted to be strictly smaller, and no future refactor can unify them and truncate
 *       statement output.</li>
 *   <li><strong>Numeric pictures are text.</strong> A category code of {@code "0002"} and a
 *       transaction identifier of {@code "0000000000000001"} lose their meaning the instant they are
 *       parsed as numbers, so the static types are pinned by assignment, which makes a numeric type
 *       a compile error rather than a silent data defect.</li>
 *   <li><strong>Money is exact and is never rescaled here.</strong> The type carries the decimal it
 *       is handed, at whatever scale it arrives, and the wire form is always plain rather than
 *       scientific. The single place that applies the contractual scale of two, truncating towards
 *       zero, is the zoned-decimal codec reached through the service tier, so no scaling call appears
 *       anywhere in this package or in this test.</li>
 *   <li><strong>Size bounds are the only validation.</strong> A mandatory, pattern or digit
 *       constraint here would fire out of order and report several errors at once, whereas the legacy
 *       validation cascades are ordered and stop at the first failure. An all-null instance and an
 *       all-blank instance are both asserted to produce no violation at all.</li>
 * </ol>
 *
 * <p>Behaviour is verified by citation and never by transcription, so no legacy source text appears
 * in this file.</p>
 */
@DisplayName("StatementSummary: the 350-byte reporting statement work area, carried verbatim")
final class StatementSummaryTest {

    /**
     * The thirteen serialised property names, in copybook declaration order. The card number leads
     * and the transaction identifier follows it, which is the reprojected order this layout defines.
     */
    private static final List<String> LIVE_PROPERTIES = List.of(
            "cardNumber",
            "transactionId",
            "typeCode",
            "categoryCode",
            "source",
            "description",
            "amount",
            "merchantId",
            "merchantName",
            "merchantCity",
            "merchantZip",
            "originationTimestamp",
            "processingTimestamp");

    /** A stamp that was never set: exactly 26 blanks, which the estate seeds into processing stamps. */
    private static final String BLANK_STAMP = " ".repeat(26);

    /**
     * The online stamp shape, 26 characters: hyphens inside the date, a blank as the eleventh
     * character, colons inside the time and a full stop ahead of a six-digit fraction.
     */
    private static final String ONLINE_STAMP = "2022-06-10 19:27:53.000000";

    /**
     * The batch stamp shape, 26 characters: a hyphen between every date part and ahead of the hour,
     * then full stops inside the time. It differs from the online shape only at the eleventh,
     * fourteenth and seventeenth characters.
     */
    private static final String BATCH_STAMP = "2022-06-10-19.27.53.000000";

    /** Source channel literals the estate writes, all blank-filled to the full 10 characters. */
    private static final String SOURCE_POS_TERMINAL = "POS TERM  ";
    private static final String SOURCE_OPERATOR = "OPERATOR  ";
    private static final String SOURCE_SYSTEM = "System    ";

    /**
     * The literal the row emits in place of the withheld card, description, amount and merchant
     * components. The two stamps and the source channel are retained rather than withheld; the
     * rendering test records why.
     *
     * <p>Restated here rather than read from the production type, so that a change to that constant
     * has to be made deliberately in both places and cannot silently weaken these assertions.
     */
    private static final String REDACTION_PLACEHOLDER_TEXT = "***REDACTED***";

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /**
     * Releases the validator factory, tolerating the case where it was never opened.
     *
     * <p>The guard is not decoration. This method runs even when {@link #openValidator()} threw - a
     * missing provider on the classpath is the realistic cause - and an unguarded call would then
     * raise a second failure that hides the first. Reporting the real cause is worth one null test.
     */
    @AfterAll
    static void closeValidator() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    /**
     * Builds a plain object mapper configured exactly as the shared application configuration
     * declares: absent rather than null properties, dates never as epoch numbers, unknown input
     * tolerated, and decimals always written plainly rather than in scientific notation.
     *
     * <p>The mapper is built here rather than injected because this is a pure unit test: no
     * framework context is started, so the four settings are applied directly and stay visible at
     * the point of use.</p>
     *
     * <p>Because it is hand built, it evidences the settings this file believes are in force rather
     * than the settings a deployed instance has. {@link ApplicationJsonContractTest} supplies the
     * missing half: it takes the mapper from a real context that has read the module's own
     * {@code application.yml} and compares its output byte for byte with a mapper built exactly as
     * this one is, so a change to that file fails there rather than passing unnoticed here.</p>
     *
     * @return a mapper whose behaviour matches the deployed serialisation contract
     */
    private static ObjectMapper newMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    /**
     * A fully populated instance. Values are deliberately awkward: leading zeroes that a numeric
     * type would erase, trailing blanks that a trimming type would eat, mixed case that a folding
     * type would flatten, a 26-blank processing stamp and a negative amount.
     *
     * @return a populated statement row that exercises every carriage hazard at once
     */
    private static StatementSummary populated() {
        return new StatementSummary(
                "0000000000000001",
                "0000000000000002",
                "01",
                "0002",
                SOURCE_SYSTEM,
                "Int. for a/c 00000000011  ",
                new BigDecimal("-1234.56"),
                "999999999",
                "Some Merchant Name  ",
                "Some Merchant City  ",
                "12345     ",
                ONLINE_STAMP,
                BLANK_STAMP);
    }

    /** An instance whose every component is null, used to prove nothing here is mandatory. */
    private static StatementSummary allNull() {
        return new StatementSummary(null, null, null, null, null, null, null, null, null, null, null,
                null, null);
    }

    /**
     * Serialises a value and reads it back as a property map, preserving the emitted property order.
     *
     * @param value the statement row to serialise
     * @return the emitted properties, in emission order
     * @throws Exception if serialisation or parsing fails, which is itself a contract failure
     */
    private static Map<String, Object> propertiesOf(StatementSummary value) throws Exception {
        ObjectMapper mapper = newMapper();
        return mapper.readValue(mapper.writeValueAsString(value),
                new TypeReference<Map<String, Object>>() { });
    }

    /**
     * Sends a value out to JSON and back through a mapper configured like the deployed one.
     *
     * @param value the statement row to round-trip
     * @return the value as it survives serialisation and deserialisation
     * @throws Exception if either direction fails, which is itself a contract failure
     */
    private static StatementSummary roundTrip(StatementSummary value) throws Exception {
        ObjectMapper mapper = newMapper();
        return mapper.readValue(mapper.writeValueAsString(value), StatementSummary.class);
    }

    /**
     * One size-bounded field: the property name, the measured copybook width that bounds it, and a
     * way to build an otherwise-empty instance carrying only that field.
     *
     * @param property the serialised property name
     * @param maxWidth the measured width from the copybook
     * @param with     builds an instance carrying only this field
     */
    private record BoundedField(String property, int maxWidth,
            Function<String, StatementSummary> with) {

        @Override
        public String toString() {
            return property + " bounded at " + maxWidth;
        }
    }

    /**
     * The twelve size-bounded fields at their measured widths, in declaration order. The amount is
     * absent from this list on purpose: it carries no size bound and no numeric bound, because the
     * legacy record declares none.
     *
     * @return every bounded field paired with its width and a single-field builder
     */
    static Stream<BoundedField> boundedFields() {
        return Stream.of(
                new BoundedField("cardNumber", 16, v -> new StatementSummary(
                        v, null, null, null, null, null, null, null, null, null, null, null, null)),
                new BoundedField("transactionId", 16, v -> new StatementSummary(
                        null, v, null, null, null, null, null, null, null, null, null, null, null)),
                new BoundedField("typeCode", 2, v -> new StatementSummary(
                        null, null, v, null, null, null, null, null, null, null, null, null, null)),
                new BoundedField("categoryCode", 4, v -> new StatementSummary(
                        null, null, null, v, null, null, null, null, null, null, null, null, null)),
                new BoundedField("source", 10, v -> new StatementSummary(
                        null, null, null, null, v, null, null, null, null, null, null, null, null)),
                new BoundedField("description", 100, v -> new StatementSummary(
                        null, null, null, null, null, v, null, null, null, null, null, null, null)),
                new BoundedField("merchantId", 9, v -> new StatementSummary(
                        null, null, null, null, null, null, null, v, null, null, null, null, null)),
                new BoundedField("merchantName", 50, v -> new StatementSummary(
                        null, null, null, null, null, null, null, null, v, null, null, null, null)),
                new BoundedField("merchantCity", 50, v -> new StatementSummary(
                        null, null, null, null, null, null, null, null, null, v, null, null, null)),
                new BoundedField("merchantZip", 10, v -> new StatementSummary(
                        null, null, null, null, null, null, null, null, null, null, v, null, null)),
                new BoundedField("originationTimestamp", 26, v -> new StatementSummary(
                        null, null, null, null, null, null, null, null, null, null, null, v, null)),
                new BoundedField("processingTimestamp", 26, v -> new StatementSummary(
                        null, null, null, null, null, null, null, null, null, null, null, null, v)));
    }

    /**
     * Mandated acceptance assertion: the 20-byte record tail is absent, and the emitted shape is
     * exactly the thirteen live fields in reprojected order.
     */
    @Nested
    @DisplayName("The unnamed 20-byte record tail is absent from the type and from the wire")
    class FillerAbsenceAndJsonShape {

        @Test
        @DisplayName("a fully populated row emits exactly the thirteen live properties, in order")
        void emitsExactlyTheThirteenLiveProperties() throws Exception {
            Map<String, Object> properties = propertiesOf(populated());

            assertThat(properties)
                    .as("a fully populated statement row must emit one property per live copybook "
                            + "field and nothing else; an extra property means the 20-byte unnamed "
                            + "tail leaked into the type, and a missing one means a field was dropped")
                    .hasSize(13);
            assertThat(properties.keySet())
                    .as("the emitted property order is the copybook declaration order, which puts "
                            + "the card number first and the transaction identifier second; that "
                            + "reprojection is the contract and must not be reordered back to the "
                            + "base transaction layout")
                    .containsExactlyElementsOf(LIVE_PROPERTIES);
        }

        @Test
        @DisplayName("no filler, padding, reserved, spare, slack or unused property is emitted")
        void emitsNoRecordTailProperty() throws Exception {
            Map<String, Object> properties = propertiesOf(populated());

            for (String forbidden : List.of("filler", "filler1", "filler2", "padding", "pad",
                    "reserved", "spare", "slack", "unused", "tail")) {
                assertThat(properties.keySet())
                        .as("the 20-byte area that closes the record image carries no data name and "
                                + "exists only to round the record out to 350 bytes, so no property "
                                + "called '%s' may exist", forbidden)
                        .doesNotContain(forbidden);
            }
        }

        @Test
        @DisplayName("an all-null row emits no properties at all, so no tail is defaulted into place")
        void emitsNothingForAnAllNullRow() throws Exception {
            assertThat(propertiesOf(allNull()))
                    .as("with absent-rather-than-null inclusion an entirely empty row emits an empty "
                            + "object; any property surviving here would be a value this type "
                            + "invented rather than carried")
                    .isEmpty();
        }

        @Test
        @DisplayName("the live widths total 330, so adding the 20-byte tail balances the 350-byte record")
        void liveWidthsPlusTheTailBalanceTheRecordImage() {
            List<Integer> liveWidths = List.of(16, 16, 2, 4, 10, 100, 11, 9, 50, 50, 10, 26, 26);

            assertThat(liveWidths)
                    .as("one width per live field, so the width table and the property table cannot "
                            + "drift apart")
                    .hasSize(LIVE_PROPERTIES.size());
            int live = liveWidths.stream().mapToInt(Integer::intValue).sum();
            assertThat(live)
                    .as("the thirteen live fields carry 330 of the record's bytes; the amount "
                            + "contributes 11 rather than 12 because its sign is overpunched into "
                            + "its final byte instead of occupying one of its own")
                    .isEqualTo(330);
            assertThat(live + 20)
                    .as("the omitted 20-byte tail is what makes the record image 350 bytes wide, so "
                            + "the accounting still balances even though this type models only the "
                            + "330 bytes that carry data")
                    .isEqualTo(350);
        }

        @Test
        @DisplayName("the wire shape is thirteen flat scalars, so the key grouping is not a type")
        void theKeyGroupingDoesNotBecomeANestedType() throws Exception {
            String json = newMapper().writeValueAsString(populated());

            assertThat(json)
                    .as("the copybook splits the leading 32 bytes into a named key group and the "
                            + "remainder into a rest group; that is a mainframe record-splitting "
                            + "device for addressing the key bytes, not a structure, so neither "
                            + "group may become a Java type")
                    .startsWith("{")
                    .endsWith("}");
            assertThat(json.indexOf('{', 1))
                    .as("no opening brace occurs after the first, so no property value is itself "
                            + "an object: had the key group been modelled as a nested type the wire "
                            + "form would carry an inner object and every consumer would break")
                    .isEqualTo(-1);
            assertThat(json)
                    .as("and no property value is an array either, so nothing was grouped into a "
                            + "collection on the way out")
                    .doesNotContain("[")
                    .doesNotContain("]");
            assertThat(propertiesOf(populated()).keySet())
                    .as("no grouping-derived property is exposed under any spelling, so the two "
                            + "copybook group names leave no trace on the contract")
                    .doesNotContain("key", "trnxKey", "transactionKey", "rest", "trnxRest",
                            "trnxRecord", "record");
        }

    }

    /**
     * Mandated acceptance assertion: a 26-blank stamp round-trips byte for byte, on both stamp
     * fields, and both punctuation shapes survive without one being normalised into the other.
     */
    @Nested
    @DisplayName("Both 26-character stamps are opaque text and survive byte for byte")
    class TwentySixCharacterTimestamps {

        @Test
        @DisplayName("the origination stamp carries 26 blanks unchanged on construction")
        void originationStampCarriesTwentySixBlanks() {
            StatementSummary row = new StatementSummary(null, null, null, null, null, null, null,
                    null, null, null, null, BLANK_STAMP, null);

            String carried = row.originationTimestamp();

            assertThat(carried)
                    .as("a stamp that was never set is 26 blanks, and it must come back as the same "
                            + "26 blanks: not null, not empty, not trimmed and not collapsed")
                    .isNotNull()
                    .isNotEmpty()
                    .hasSize(26)
                    .isEqualTo(BLANK_STAMP);
            assertThat(carried.isBlank())
                    .as("every one of the 26 characters is a blank, which is precisely why this value "
                            + "is not a parseable stamp and precisely why the field stays raw text")
                    .isTrue();
        }

        @Test
        @DisplayName("the processing stamp carries 26 blanks unchanged on construction")
        void processingStampCarriesTwentySixBlanks() {
            StatementSummary row = new StatementSummary(null, null, null, null, null, null, null,
                    null, null, null, null, null, BLANK_STAMP);

            String carried = row.processingTimestamp();

            assertThat(carried)
                    .as("the seeded processing stamps in the estate are exactly 26 blanks, so this "
                            + "field must carry them without alteration")
                    .isNotNull()
                    .isNotEmpty()
                    .hasSize(26)
                    .isEqualTo(BLANK_STAMP);
            assertThat(carried.isBlank())
                    .as("all 26 characters remain blanks")
                    .isTrue();
        }

        @Test
        @DisplayName("26 blanks survive a JSON round trip on both stamps at once")
        void twentySixBlanksSurviveAJsonRoundTrip() throws Exception {
            StatementSummary row = new StatementSummary(null, null, null, null, null, null, null,
                    null, null, null, null, BLANK_STAMP, BLANK_STAMP);

            StatementSummary back = roundTrip(row);

            assertThat(back.originationTimestamp())
                    .as("serialisation must not turn a blank-filled stamp into null or into an "
                            + "empty string on the origination field")
                    .isEqualTo(BLANK_STAMP)
                    .hasSize(26);
            assertThat(back.processingTimestamp())
                    .as("serialisation must not turn a blank-filled stamp into null or into an "
                            + "empty string on the processing field")
                    .isEqualTo(BLANK_STAMP)
                    .hasSize(26);
            assertThat(propertiesOf(row))
                    .as("a blank-filled stamp is a present value, so it is emitted; only a null is "
                            + "omitted under absent-rather-than-null inclusion")
                    .containsEntry("originationTimestamp", BLANK_STAMP)
                    .containsEntry("processingTimestamp", BLANK_STAMP);
        }

        @Test
        @DisplayName("the online stamp shape is 26 characters punctuated at 5, 8, 11, 14, 17 and 20")
        void theOnlineStampShapeIsWhatTheOracleClaims() {
            assertThat(ONLINE_STAMP)
                    .as("the online shape is 26 characters wide")
                    .hasSize(26);
            assertThat(ONLINE_STAMP.charAt(4)).as("hyphen at position 5").isEqualTo('-');
            assertThat(ONLINE_STAMP.charAt(7)).as("hyphen at position 8").isEqualTo('-');
            assertThat(ONLINE_STAMP.charAt(10))
                    .as("the online shape separates date from time with a blank at position 11, "
                            + "which is the single character that most distinguishes it from the "
                            + "batch shape")
                    .isEqualTo(' ');
            assertThat(ONLINE_STAMP.charAt(13)).as("colon at position 14").isEqualTo(':');
            assertThat(ONLINE_STAMP.charAt(16)).as("colon at position 17").isEqualTo(':');
            assertThat(ONLINE_STAMP.charAt(19)).as("full stop at position 20").isEqualTo('.');
        }

        @Test
        @DisplayName("the batch stamp shape is 26 characters with hyphens at 5, 8, 11 and stops at 14, 17, 20")
        void theBatchStampShapeIsWhatTheOracleClaims() {
            assertThat(BATCH_STAMP)
                    .as("the batch shape is also 26 characters wide, which is why one can be "
                            + "mistaken for the other")
                    .hasSize(26);
            assertThat(BATCH_STAMP.charAt(4)).as("hyphen at position 5").isEqualTo('-');
            assertThat(BATCH_STAMP.charAt(7)).as("hyphen at position 8").isEqualTo('-');
            assertThat(BATCH_STAMP.charAt(10))
                    .as("the batch shape uses a hyphen at position 11 where the online shape uses a "
                            + "blank")
                    .isEqualTo('-');
            assertThat(BATCH_STAMP.charAt(13)).as("full stop at position 14").isEqualTo('.');
            assertThat(BATCH_STAMP.charAt(16)).as("full stop at position 17").isEqualTo('.');
            assertThat(BATCH_STAMP.charAt(19)).as("full stop at position 20").isEqualTo('.');
        }

        @Test
        @DisplayName("the two shapes differ at positions 11, 14 and 17 and are never confused")
        void theTwoShapesAreDistinctAndEquallyWide() {
            assertThat(ONLINE_STAMP)
                    .as("the two shapes are genuinely different values, so a test that accidentally "
                            + "compared one to the other would be vacuous")
                    .isNotEqualTo(BATCH_STAMP);
            assertThat(ONLINE_STAMP).hasSameSizeAs(BATCH_STAMP);
            for (int position : List.of(11, 14, 17)) {
                assertThat(ONLINE_STAMP.charAt(position - 1))
                        .as("the shapes must differ at position %d", position)
                        .isNotEqualTo(BATCH_STAMP.charAt(position - 1));
            }
            for (int position : List.of(1, 5, 8, 20, 26)) {
                assertThat(ONLINE_STAMP.charAt(position - 1))
                        .as("the shapes agree at position %d, which is what makes them so easy to "
                                + "confuse", position)
                        .isEqualTo(BATCH_STAMP.charAt(position - 1));
            }
        }

        @Test
        @DisplayName("each shape round-trips byte for byte on each stamp, with no normalisation")
        void bothShapesRoundTripOnBothStampsWithoutNormalisation() throws Exception {
            for (String shape : List.of(ONLINE_STAMP, BATCH_STAMP, BLANK_STAMP)) {
                StatementSummary onOrigination = new StatementSummary(null, null, null, null, null,
                        null, null, null, null, null, null, shape, null);
                StatementSummary onProcessing = new StatementSummary(null, null, null, null, null,
                        null, null, null, null, null, null, null, shape);

                assertThat(roundTrip(onOrigination).originationTimestamp())
                        .as("the origination stamp must carry '%s' through unchanged", shape)
                        .isEqualTo(shape);
                assertThat(roundTrip(onProcessing).processingTimestamp())
                        .as("the processing stamp must carry '%s' through unchanged", shape)
                        .isEqualTo(shape);
            }
        }

        @Test
        @DisplayName("carrying one shape never produces the other")
        void carryingOneShapeNeverProducesTheOther() throws Exception {
            StatementSummary mixed = new StatementSummary(null, null, null, null, null, null, null,
                    null, null, null, null, ONLINE_STAMP, BATCH_STAMP);

            StatementSummary back = roundTrip(mixed);

            assertThat(back.originationTimestamp())
                    .as("an online-shaped stamp must not be re-separated into the batch shape")
                    .isEqualTo(ONLINE_STAMP)
                    .isNotEqualTo(BATCH_STAMP);
            assertThat(back.processingTimestamp())
                    .as("a batch-shaped stamp must not be re-separated into the online shape")
                    .isEqualTo(BATCH_STAMP)
                    .isNotEqualTo(ONLINE_STAMP);
        }

        @Test
        @DisplayName("a stamp is plain text on the wire, never a structured date or an epoch number")
        void aStampIsPlainTextOnTheWire() throws Exception {
            Map<String, Object> properties = propertiesOf(new StatementSummary(null, null, null,
                    null, null, null, null, null, null, null, null, ONLINE_STAMP, BLANK_STAMP));

            assertThat(properties.get("originationTimestamp"))
                    .as("the stamp is emitted as a string; a date or time object type would reformat "
                            + "it, and an epoch number could not represent 26 blanks at all")
                    .isInstanceOf(String.class)
                    .isEqualTo(ONLINE_STAMP);
            assertThat(properties.get("processingTimestamp"))
                    .as("a 26-blank stamp is emitted as a 26-blank string")
                    .isInstanceOf(String.class)
                    .isEqualTo(BLANK_STAMP);
        }
    }


    /**
     * Group A: fields declared with a numeric picture are carried as bounded text, so leading zeroes
     * and full widths survive.
     */
    @Nested
    @DisplayName("Numeric-picture fields are bounded text, so leading zeroes survive")
    class NumericPicturesAsBoundedText {

        @Test
        @DisplayName("the identifier and code accessors are text, pinned at compile time by assignment")
        void theIdentifierAndCodeAccessorsAreText() {
            StatementSummary row = populated();

            String cardNumber = row.cardNumber();
            String transactionId = row.transactionId();
            String categoryCode = row.categoryCode();
            String merchantId = row.merchantId();

            assertThat(cardNumber)
                    .as("these four assignments only compile while the accessors return text; a "
                            + "whole-number or big-integer type here would be a compile error rather "
                            + "than a silent data defect")
                    .isNotNull();
            assertThat(transactionId).isNotNull();
            assertThat(categoryCode).isNotNull();
            assertThat(merchantId).isNotNull();
        }

        @Test
        @DisplayName("a category code of 0002 keeps its leading zeroes and never becomes 2")
        void categoryCodeKeepsItsLeadingZeroes() throws Exception {
            StatementSummary row = new StatementSummary(null, null, null, "0002", null, null, null,
                    null, null, null, null, null, null);

            assertThat(row.categoryCode())
                    .as("the bill-payment path writes this category, and its leading zeroes are part "
                            + "of the fixed-width value")
                    .isEqualTo("0002")
                    .isNotEqualTo("2")
                    .hasSize(4);
            assertThat(roundTrip(row).categoryCode())
                    .as("a numeric wire form would arrive back as 2 and destroy the width")
                    .isEqualTo("0002");
            assertThat(propertiesOf(row).get("categoryCode"))
                    .as("the category code is emitted as a string, not as a JSON number")
                    .isInstanceOf(String.class)
                    .isEqualTo("0002");
        }

        @Test
        @DisplayName("a 16-digit identifier keeps all sixteen characters and never becomes 1")
        void identifiersKeepTheirFullWidth() throws Exception {
            StatementSummary row = new StatementSummary("0000000000000001", "0000000000000002", null,
                    null, null, null, null, null, null, null, null, null, null);

            StatementSummary back = roundTrip(row);

            assertThat(back.cardNumber())
                    .as("a card number is 16 characters of text; parsed as a number it would collapse "
                            + "to 1")
                    .isEqualTo("0000000000000001")
                    .isNotEqualTo("1")
                    .hasSize(16);
            assertThat(back.transactionId())
                    .as("a transaction identifier is 16 characters of text; parsed as a number it "
                            + "would collapse to 2")
                    .isEqualTo("0000000000000002")
                    .isNotEqualTo("2")
                    .hasSize(16);
        }

        @Test
        @DisplayName("a merchant identifier keeps its full nine characters")
        void merchantIdentifierKeepsItsFullWidth() throws Exception {
            StatementSummary allNines = new StatementSummary(null, null, null, null, null, null, null,
                    "999999999", null, null, null, null, null);
            StatementSummary leadingZeroes = new StatementSummary(null, null, null, null, null, null,
                    null, "000000042", null, null, null, null, null);

            assertThat(roundTrip(allNines).merchantId())
                    .as("the widest merchant identifier the estate writes keeps all nine characters")
                    .isEqualTo("999999999")
                    .hasSize(9);
            assertThat(roundTrip(leadingZeroes).merchantId())
                    .as("a merchant identifier with leading zeroes keeps them; a numeric type would "
                            + "render this as 42")
                    .isEqualTo("000000042")
                    .isNotEqualTo("42")
                    .hasSize(9);
        }

        @Test
        @DisplayName("every identifier and code is emitted as a JSON string, never as a JSON number")
        void everyCodeIsEmittedAsAString() throws Exception {
            Map<String, Object> properties = propertiesOf(populated());

            for (String property : List.of("cardNumber", "transactionId", "typeCode", "categoryCode",
                    "merchantId")) {
                assertThat(properties.get(property))
                        .as("'%s' must reach the wire as a string; a JSON number would drop leading "
                                + "zeroes irrecoverably", property)
                        .isInstanceOf(String.class);
            }
        }
    }

    /**
     * Group B: the amount is an exact decimal, is never rescaled by this type, and always reaches the
     * wire in plain rather than scientific form.
     */
    @Nested
    @DisplayName("The amount is an exact decimal, carried as given and written plainly")
    class MoneyIsExactDecimal {

        @Test
        @DisplayName("the amount accessor is an exact decimal, pinned at compile time by assignment")
        void theAmountAccessorIsAnExactDecimal() {
            BigDecimal amount = populated().amount();

            assertThat(amount)
                    .as("this assignment only compiles while the accessor returns an exact decimal; "
                            + "a binary floating-point type here is prohibited outright because it "
                            + "cannot represent the legacy zoned decimal faithfully")
                    .isNotNull();
        }

        @Test
        @DisplayName("a scale-two amount round-trips with scale exactly two preserved")
        void scaleTwoIsPreservedAcrossARoundTrip() throws Exception {
            BigDecimal contractual = new BigDecimal("1234.56");
            StatementSummary row = new StatementSummary(null, null, null, null, null, null,
                    contractual, null, null, null, null, null, null);

            assertThat(row.amount().scale())
                    .as("two decimal places is the contract for this field, and the type must carry "
                            + "the value at the scale it was handed")
                    .isEqualTo(2);
            assertThat(row.amount())
                    .as("the carried value compares equal including its scale")
                    .isEqualTo(contractual);

            BigDecimal back = roundTrip(row).amount();

            assertThat(back.scale())
                    .as("the wire form must preserve two decimal places; losing the scale would "
                            + "change how the amount is written into the fixed-width statement record")
                    .isEqualTo(2);
            assertThat(back).isEqualTo(contractual);
        }

        @Test
        @DisplayName("the type rescales nothing: an unusual scale comes back exactly as supplied")
        void theTypeRescalesNothing() {
            BigDecimal unscaled = new BigDecimal("1E+2");
            StatementSummary row = new StatementSummary(null, null, null, null, null, null, unscaled,
                    null, null, null, null, null, null);

            assertThat(row.amount().scale())
                    .as("this value arrives with a negative scale, and the type must neither raise "
                            + "nor lower it; the only place that applies the contractual scale, "
                            + "truncating towards zero, is the zoned-decimal codec in the utility "
                            + "layer, reached through the service tier")
                    .isEqualTo(-2);
            assertThat(row.amount())
                    .as("the identical value, at the identical scale, comes back out")
                    .isEqualTo(unscaled);
            assertThat(row.amount().toString())
                    .as("the carried value still renders in its original exponent form, which is the "
                            + "clearest possible evidence that the type applied no scaling of its "
                            + "own; a rescaling type would have normalised this to 100.00")
                    .isEqualTo("1E+2");
            assertThat(row.amount().toPlainString())
                    .as("the same value written plainly is 100, and it is this plain form that "
                            + "reaches the wire")
                    .isEqualTo("100");
        }

        @Test
        @DisplayName("an amount is written plainly, never in scientific notation")
        void anAmountIsWrittenPlainly() throws Exception {
            StatementSummary row = new StatementSummary(null, null, null, null, null, null,
                    new BigDecimal("1E+2"), null, null, null, null, null, null);

            String json = newMapper().writeValueAsString(row);

            assertThat(json)
                    .as("left to its own devices this value prints as 1E+2, which no consumer of a "
                            + "fixed-width monetary field can interpret; the plain form is the "
                            + "contract")
                    .contains("100")
                    .doesNotContain("1E+2")
                    .doesNotContain("E+")
                    .doesNotContain("e+");
        }

        @Test
        @DisplayName("a trailing-zero amount keeps both decimal places on the wire")
        void aTrailingZeroAmountKeepsBothDecimalPlaces() throws Exception {
            StatementSummary row = new StatementSummary(null, null, null, null, null, null,
                    new BigDecimal("100.00"), null, null, null, null, null, null);

            assertThat(newMapper().writeValueAsString(row))
                    .as("a whole-pound amount still occupies two decimal places in the record, so "
                            + "the trailing zeroes must not be normalised away")
                    .contains("100.00");
            assertThat(roundTrip(row).amount().scale())
                    .as("and they survive the return journey")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a negative amount round-trips, because the estate posts returns")
        void aNegativeAmountRoundTrips() throws Exception {
            BigDecimal refund = new BigDecimal("-1234.56");
            StatementSummary row = new StatementSummary(null, null, null, null, null, null, refund,
                    null, null, null, null, null, null);

            BigDecimal back = roundTrip(row).amount();

            assertThat(back)
                    .as("the estate's operator-originated returns are negative amounts, so the sign "
                            + "must survive; the legacy field is a signed zoned decimal")
                    .isEqualTo(refund)
                    .isNegative();
            assertThat(back.scale()).as("with its scale intact").isEqualTo(2);
        }

        @Test
        @DisplayName("a zero amount round-trips and keeps its two decimal places")
        void aZeroAmountRoundTrips() throws Exception {
            BigDecimal zero = new BigDecimal("0.00");
            StatementSummary row = new StatementSummary(null, null, null, null, null, null, zero,
                    null, null, null, null, null, null);

            BigDecimal back = roundTrip(row).amount();

            assertThat(back)
                    .as("a zero amount is a real value, not an absent one, so it must be carried and "
                            + "emitted rather than omitted")
                    .isEqualTo(zero)
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(back.scale()).as("a zero still occupies two decimal places").isEqualTo(2);
            assertThat(propertiesOf(row)).containsKey("amount");
        }

        @Test
        @DisplayName("the widest amount the picture allows survives without loss")
        void theWidestAmountSurvivesWithoutLoss() throws Exception {
            BigDecimal widest = new BigDecimal("999999999.99");
            StatementSummary row = new StatementSummary(null, null, null, null, null, null, widest,
                    null, null, null, null, null, null);

            assertThat(roundTrip(row).amount())
                    .as("nine digits ahead of the separator and two behind it is the full width the "
                            + "legacy picture allows, and every digit must survive exactly")
                    .isEqualTo(widest);
        }
    }

    /**
     * Group C: the source channel stays raw, blank-filled text and is never narrowed to an
     * enumerated type.
     */
    @Nested
    @DisplayName("The source channel stays raw blank-filled text, never an enumerated type")
    class SourceChannelStaysRawText {

        @Test
        @DisplayName("the source accessor is text, pinned at compile time by assignment")
        void theSourceAccessorIsText() {
            String source = populated().source();

            assertThat(source)
                    .as("this assignment only compiles while the accessor returns text; narrowing "
                            + "this field to an enumerated type would reject any channel literal the "
                            + "estate has not written yet")
                    .isNotNull();
        }

        @Test
        @DisplayName("each 10-character channel literal round-trips untrimmed and unfolded")
        void eachChannelLiteralRoundTripsVerbatim() throws Exception {
            for (String channel : List.of(SOURCE_POS_TERMINAL, SOURCE_OPERATOR, SOURCE_SYSTEM)) {
                StatementSummary row = new StatementSummary(null, null, null, null, channel, null,
                        null, null, null, null, null, null, null);

                assertThat(row.source())
                        .as("the channel literal '%s' is blank-filled to the full field width and "
                                + "must be carried with its trailing blanks intact", channel)
                        .isEqualTo(channel)
                        .hasSize(10);
                assertThat(roundTrip(row).source())
                        .as("and it must survive the wire unchanged")
                        .isEqualTo(channel);
            }
        }

        @Test
        @DisplayName("the mixed-case channel keeps its case and its trailing blanks exactly")
        void theMixedCaseChannelKeepsItsCase() throws Exception {
            StatementSummary row = new StatementSummary(null, null, null, null, SOURCE_SYSTEM, null,
                    null, null, null, null, null, null, null);

            String back = roundTrip(row).source();

            assertThat(back)
                    .as("the interest job writes this channel in mixed case with four trailing "
                            + "blanks; upper-casing it, trimming it or mapping it onto an enumerated "
                            + "constant would each change the value the statement writer receives")
                    .isEqualTo(SOURCE_SYSTEM)
                    .isNotEqualTo("SYSTEM")
                    .isNotEqualTo("System")
                    .isNotEqualTo("system    ")
                    .hasSize(10);
        }

        @Test
        @DisplayName("a channel value the estate has never written still passes through")
        void anUnknownChannelStillPassesThrough() throws Exception {
            StatementSummary row = new StatementSummary(null, null, null, null, "MOBILE    ", null,
                    null, null, null, null, null, null, null);

            assertThat(roundTrip(row).source())
                    .as("raw text accepts a channel outside the literals the estate happens to write "
                            + "today, which is exactly the tolerance an enumerated type would remove")
                    .isEqualTo("MOBILE    ");
        }
    }


    /**
     * Group D: every text field is carried verbatim, and the three deliberately widened widths are
     * never narrowed to the online view map's.
     */
    @Nested
    @DisplayName("Text fields are carried verbatim, and the widened widths stay wide")
    class TextFieldsRoundTripVerbatim {

        @Test
        @DisplayName("trailing blanks survive on every text field")
        void trailingBlanksSurviveOnEveryTextField() throws Exception {
            StatementSummary row = new StatementSummary("1234567890123456", "6543210987654321", "0 ",
                    "02  ", "OPERATOR  ", "Purchase at store   ", new BigDecimal("10.00"),
                    "12345    ", "Merchant Name   ", "Merchant City   ", "ZIP99     ", ONLINE_STAMP,
                    BLANK_STAMP);

            StatementSummary back = roundTrip(row);

            assertThat(back.typeCode()).as("a two-character type code ending in a blank").isEqualTo("0 ");
            assertThat(back.categoryCode()).as("a blank-filled category code").isEqualTo("02  ");
            assertThat(back.description())
                    .as("a description's trailing blanks are part of the fixed-width value and must "
                            + "not be eaten")
                    .isEqualTo("Purchase at store   ");
            assertThat(back.merchantId()).as("a blank-filled merchant identifier").isEqualTo("12345    ");
            assertThat(back.merchantName()).isEqualTo("Merchant Name   ");
            assertThat(back.merchantCity()).isEqualTo("Merchant City   ");
            assertThat(back.merchantZip()).isEqualTo("ZIP99     ");
            assertThat(back)
                    .as("and the whole row compares equal, so nothing anywhere in it was altered")
                    .isEqualTo(row);
        }

        @Test
        @DisplayName("a short value is never padded up to its declared width")
        void aShortValueIsNeverPaddedUp() throws Exception {
            StatementSummary row = new StatementSummary("1", "2", "3", "4", "5", "6",
                    new BigDecimal("7.00"), "8", "9", "A", "B", "C", "D");

            StatementSummary back = roundTrip(row);

            assertThat(back.description())
                    .as("the description field is 100 characters wide in the record, but this type "
                            + "carries a decoded value and must not blank-fill it; the writers apply "
                            + "the padding when they assemble the fixed-width output record")
                    .isEqualTo("6")
                    .hasSize(1);
            assertThat(back.merchantName()).hasSize(1);
            assertThat(back.merchantCity()).hasSize(1);
            assertThat(back.originationTimestamp())
                    .as("even a stamp is not padded out to 26 characters by this type")
                    .isEqualTo("C")
                    .hasSize(1);
            assertThat(back.processingTimestamp()).isEqualTo("D").hasSize(1);
        }

        @Test
        @DisplayName("an empty value stays empty and is never turned into null")
        void anEmptyValueStaysEmpty() throws Exception {
            StatementSummary row = new StatementSummary("", "", "", "", "", "", null, "", "", "", "",
                    "", "");

            StatementSummary back = roundTrip(row);

            assertThat(back.description())
                    .as("an empty value is a present value and must be distinguishable from an "
                            + "absent one")
                    .isNotNull()
                    .isEmpty();
            assertThat(back.originationTimestamp()).isNotNull().isEmpty();
            assertThat(back.processingTimestamp()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("case is never folded on any text field")
        void caseIsNeverFolded() throws Exception {
            StatementSummary row = new StatementSummary(null, null, null, null, null,
                    "MiXeD cAsE dEsCrIpTiOn", null, null, "MiXeD mErChAnT", "MiXeD cItY", "aB1cD",
                    null, null);

            StatementSummary back = roundTrip(row);

            assertThat(back.description())
                    .as("no field on this type folds case; the one place the estate does fold case "
                            + "is the embossed-name path, which uses a strict 26-letter table and "
                            + "lives elsewhere entirely")
                    .isEqualTo("MiXeD cAsE dEsCrIpTiOn");
            assertThat(back.merchantName()).isEqualTo("MiXeD mErChAnT");
            assertThat(back.merchantCity()).isEqualTo("MiXeD cItY");
            assertThat(back.merchantZip()).isEqualTo("aB1cD");
        }

        @Test
        @DisplayName("the widened description, merchant name and merchant city widths stay wide")
        void theWidenedWidthsAreNotNarrowedToTheOnlineViewMap() {
            int reportingDescription = 100;
            int reportingMerchantName = 50;
            int reportingMerchantCity = 50;
            int onlineDescription = 60;
            int onlineMerchantName = 30;
            int onlineMerchantCity = 25;

            assertThat(reportingDescription)
                    .as("this layout is the reporting-altered one, so its description is 100 "
                            + "characters against the online view map's 60; narrowing it would "
                            + "truncate 40 characters of every statement line")
                    .isGreaterThan(onlineDescription)
                    .isEqualTo(100);
            assertThat(reportingMerchantName)
                    .as("the merchant name is 50 here against the online view map's 30")
                    .isGreaterThan(onlineMerchantName)
                    .isEqualTo(50);
            assertThat(reportingMerchantCity)
                    .as("the merchant city is 50 here against the online view map's 25")
                    .isGreaterThan(onlineMerchantCity)
                    .isEqualTo(50);
        }

        @Test
        @DisplayName("a value longer than the online view map's width is carried, not truncated")
        void aValueWiderThanTheOnlineMapIsStillCarried() throws Exception {
            String seventyCharacterDescription = "D".repeat(70);
            String fortyCharacterMerchant = "M".repeat(40);
            StatementSummary row = new StatementSummary(null, null, null, null, null,
                    seventyCharacterDescription, null, null, fortyCharacterMerchant,
                    fortyCharacterMerchant, null, null, null);

            StatementSummary back = roundTrip(row);

            assertThat(back.description())
                    .as("70 characters exceeds the online view map's 60 and is perfectly legal here; "
                            + "truncating to the narrower contract would silently lose statement text")
                    .isEqualTo(seventyCharacterDescription)
                    .hasSize(70);
            assertThat(back.merchantName())
                    .as("40 characters exceeds the online view map's 30 and is legal here")
                    .hasSize(40);
            assertThat(back.merchantCity())
                    .as("40 characters exceeds the online view map's 25 and is legal here")
                    .hasSize(40);
            assertThat(validator.validate(row))
                    .as("and none of the three raises a violation, because the bounds here are the "
                            + "reporting widths, not the screen widths")
                    .isEmpty();
        }
    }

    /**
     * Group E: a size bound at each measured width is the only validation, and nothing at all is
     * mandatory.
     */
    @Nested
    @DisplayName("A size bound at each measured width is the only validation")
    class SizeBoundsAreTheOnlyConstraint {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.api.dto.StatementSummaryTest#boundedFields")
        @DisplayName("each field accepts a value at exactly its measured width")
        void eachFieldAcceptsAValueAtItsMeasuredWidth(BoundedField field) {
            StatementSummary row = field.with().apply("X".repeat(field.maxWidth()));

            assertThat(validator.validate(row))
                    .as("'%s' is bounded at %d characters by the measured copybook width, so a value "
                            + "of exactly that width must be accepted; a violation here means the "
                            + "bound was set one short of the record layout",
                            field.property(), field.maxWidth())
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.api.dto.StatementSummaryTest#boundedFields")
        @DisplayName("each field rejects a value one character over its measured width")
        void eachFieldRejectsOneCharacterTooMany(BoundedField field) {
            StatementSummary row = field.with().apply("X".repeat(field.maxWidth() + 1));

            Set<ConstraintViolation<StatementSummary>> violations = validator.validate(row);

            assertThat(violations)
                    .as("'%s' must reject %d characters, one more than the record layout allows; no "
                            + "violation here means the bound is missing or too generous, and a "
                            + "value that wide would overflow the fixed-width output record",
                            field.property(), field.maxWidth() + 1)
                    .hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .as("and the violation must name the offending field, so a diagnostic points at "
                            + "the right one")
                    .isEqualTo(field.property());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.api.dto.StatementSummaryTest#boundedFields")
        @DisplayName("no field is mandatory: null and blank both pass on every field")
        void noFieldIsMandatory(BoundedField field) {
            assertThat(validator.validate(field.with().apply(null)))
                    .as("'%s' carries no mandatory constraint, because the legacy record tolerates an "
                            + "absent value throughout", field.property())
                    .isEmpty();
            assertThat(validator.validate(field.with().apply("")))
                    .as("'%s' carries no non-empty constraint either", field.property())
                    .isEmpty();
            assertThat(validator.validate(field.with().apply(" ".repeat(field.maxWidth()))))
                    .as("'%s' accepts a fully blank-filled value at its full width, which is how the "
                            + "estate represents a field that was never set", field.property())
                    .isEmpty();
        }

        @Test
        @DisplayName("the bounded-field table covers twelve of the thirteen fields")
        void theBoundedFieldTableCoversTwelveFields() {
            List<BoundedField> fields = boundedFields().toList();

            assertThat(fields)
                    .as("twelve of the thirteen live fields carry a size bound; the amount is the "
                            + "one that does not, because a size bound measures character length and "
                            + "the amount is a decimal")
                    .hasSize(12);
            assertThat(fields.stream().map(BoundedField::property).toList())
                    .as("the bounded fields are the live fields minus the amount, in declaration "
                            + "order, so this table cannot drift away from the type")
                    .containsExactly("cardNumber", "transactionId", "typeCode", "categoryCode",
                            "source", "description", "merchantId", "merchantName", "merchantCity",
                            "merchantZip", "originationTimestamp", "processingTimestamp");
            assertThat(fields.stream().map(BoundedField::maxWidth).toList())
                    .as("and the widths are exactly the measured copybook widths, in the same order")
                    .containsExactly(16, 16, 2, 4, 10, 100, 9, 50, 50, 10, 26, 26);
        }

        @Test
        @DisplayName("an all-null row raises no violation at all")
        void anAllNullRowRaisesNoViolation() {
            assertThat(validator.validate(allNull()))
                    .as("an entirely empty row is valid. A mandatory constraint anywhere here would "
                            + "fire out of order and report several failures at once, whereas the "
                            + "legacy validation cascades are ordered and stop at the first failure")
                    .isEmpty();
        }

        @Test
        @DisplayName("an all-blank row raises no violation at all")
        void anAllBlankRowRaisesNoViolation() {
            StatementSummary blank = new StatementSummary(" ".repeat(16), " ".repeat(16), "  ",
                    "    ", " ".repeat(10), " ".repeat(100), null, " ".repeat(9), " ".repeat(50),
                    " ".repeat(50), " ".repeat(10), BLANK_STAMP, BLANK_STAMP);

            assertThat(validator.validate(blank))
                    .as("a blank-filled row at every full width is valid, which is what lets a record "
                            + "whose optional fields were never set pass through untouched")
                    .isEmpty();
        }

        @Test
        @DisplayName("a fully populated row raises no violation")
        void aFullyPopulatedRowRaisesNoViolation() {
            assertThat(validator.validate(populated()))
                    .as("the representative populated row must itself be valid, otherwise every other "
                            + "assertion in this class would be built on an invalid fixture")
                    .isEmpty();
        }

        @Test
        @DisplayName("the amount carries no bound, so extreme and negative values both validate")
        void theAmountCarriesNoBound() {
            for (String amount : List.of("0.00", "-999999999.99", "999999999.99", "-0.01")) {
                StatementSummary row = new StatementSummary(null, null, null, null, null, null,
                        new BigDecimal(amount), null, null, null, null, null, null);

                assertThat(validator.validate(row))
                        .as("the amount %s must validate: the legacy record declares no minimum, no "
                                + "maximum and no digit constraint on this field", amount)
                        .isEmpty();
            }
        }
    }

    /**
     * Groups F and G: every component tolerates absence, the type is immutable, absent values are
     * omitted from the wire, and every accessor is exercised.
     */
    @Nested
    @DisplayName("Absence is tolerated, the value is immutable, and every accessor is exercised")
    class NullToleranceImmutabilityAndCoverage {

        @Test
        @DisplayName("every one of the thirteen components tolerates absence")
        void everyComponentToleratesAbsence() {
            StatementSummary empty = allNull();

            assertThat(empty.cardNumber()).isNull();
            assertThat(empty.transactionId()).isNull();
            assertThat(empty.typeCode()).isNull();
            assertThat(empty.categoryCode()).isNull();
            assertThat(empty.source()).isNull();
            assertThat(empty.description()).isNull();
            assertThat(empty.amount()).isNull();
            assertThat(empty.merchantId()).isNull();
            assertThat(empty.merchantName()).isNull();
            assertThat(empty.merchantCity()).isNull();
            assertThat(empty.merchantZip()).isNull();
            assertThat(empty.originationTimestamp()).isNull();
            assertThat(empty.processingTimestamp())
                    .as("the canonical constructor defaults nothing, so an absent component stays "
                            + "absent rather than becoming an empty value")
                    .isNull();
        }

        @Test
        @DisplayName("all thirteen accessors return the values the constructor was given")
        void allThirteenAccessorsReturnWhatWasSupplied() {
            StatementSummary row = populated();

            String cardNumber = row.cardNumber();
            String transactionId = row.transactionId();
            String typeCode = row.typeCode();
            String categoryCode = row.categoryCode();
            String source = row.source();
            String description = row.description();
            BigDecimal amount = row.amount();
            String merchantId = row.merchantId();
            String merchantName = row.merchantName();
            String merchantCity = row.merchantCity();
            String merchantZip = row.merchantZip();
            String originationTimestamp = row.originationTimestamp();
            String processingTimestamp = row.processingTimestamp();

            assertThat(cardNumber).isEqualTo("0000000000000001");
            assertThat(transactionId).isEqualTo("0000000000000002");
            assertThat(typeCode).isEqualTo("01");
            assertThat(categoryCode).isEqualTo("0002");
            assertThat(source).isEqualTo(SOURCE_SYSTEM);
            assertThat(description).isEqualTo("Int. for a/c 00000000011  ");
            assertThat(amount).isEqualTo(new BigDecimal("-1234.56"));
            assertThat(merchantId).isEqualTo("999999999");
            assertThat(merchantName).isEqualTo("Some Merchant Name  ");
            assertThat(merchantCity).isEqualTo("Some Merchant City  ");
            assertThat(merchantZip).isEqualTo("12345     ");
            assertThat(originationTimestamp).isEqualTo(ONLINE_STAMP);
            assertThat(processingTimestamp)
                    .as("thirteen accessors, thirteen supplied values, no transformation between them")
                    .isEqualTo(BLANK_STAMP);
        }

        @Test
        @DisplayName("the value is immutable: changing a field means building a new instance")
        void theValueIsImmutable() {
            StatementSummary original = populated();

            StatementSummary altered = new StatementSummary(original.cardNumber(),
                    original.transactionId(), original.typeCode(), original.categoryCode(),
                    original.source(), "A DIFFERENT DESCRIPTION", original.amount(),
                    original.merchantId(), original.merchantName(), original.merchantCity(),
                    original.merchantZip(), original.originationTimestamp(),
                    original.processingTimestamp());

            assertThat(original.description())
                    .as("there is no way to change a field in place: the type exposes no mutator at "
                            + "all, so the only way to vary one field is to construct a new value, "
                            + "and the original is provably untouched by that")
                    .isEqualTo("Int. for a/c 00000000011  ");
            assertThat(altered.description()).isEqualTo("A DIFFERENT DESCRIPTION");
            assertThat(altered)
                    .as("and the two are different values")
                    .isNotEqualTo(original);
            assertThat(altered.cardNumber())
                    .as("while every field that was copied across is identical")
                    .isEqualTo(original.cardNumber());
        }

        @Test
        @DisplayName("equality and hashing are value semantics across all thirteen components")
        void equalityAndHashingAreValueSemantics() {
            StatementSummary first = populated();
            StatementSummary second = populated();

            assertThat(first)
                    .as("two independently constructed rows carrying the same thirteen values are "
                            + "equal")
                    .isEqualTo(second)
                    .hasSameHashCodeAs(second);
            assertThat(first)
                    .as("and a row is equal to itself")
                    .isEqualTo(first);
            StatementSummary absent = null;
            assertThat(first)
                    .as("but never equal to a differently populated row, to absence, or to an "
                            + "unrelated type")
                    .isNotEqualTo(allNull())
                    .isNotEqualTo(absent)
                    .isNotEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("a difference in any single component breaks equality")
        void aDifferenceInAnySingleComponentBreaksEquality() {
            StatementSummary reference = allNull();

            for (BoundedField field : boundedFields().toList()) {
                assertThat(field.with().apply("Z"))
                        .as("a row differing from an empty row only in '%s' must not be equal to it, "
                                + "which proves that component participates in equality",
                                field.property())
                        .isNotEqualTo(reference);
            }
            assertThat(new StatementSummary(null, null, null, null, null, null, BigDecimal.ONE, null,
                    null, null, null, null, null))
                    .as("and the amount participates in equality too")
                    .isNotEqualTo(reference);
        }

        @Test
        @DisplayName("the text rendering names every component but redacts the cardholder-bearing values")
        void theTextRenderingRedactsTheCardholderBearingValues() {
            StatementSummary summary = populated();

            String rendered = summary.toString();

            assertThat(rendered)
                    .as("every component name is still present, so a failure diagnostic stays readable")
                    .contains("cardNumber")
                    .contains("transactionId")
                    .contains("amount")
                    .contains("originationTimestamp")
                    .contains("processingTimestamp");
            assertThat(rendered)
                    .as("the retained components identify the line: which transaction, how classified, "
                            + "where it entered and when it was originated and processed")
                    .contains("transactionId=" + summary.transactionId())
                    .contains("typeCode=" + summary.typeCode())
                    .contains("categoryCode=" + summary.categoryCode())
                    .contains("source=" + summary.source())
                    .contains("originationTimestamp=" + summary.originationTimestamp())
                    .contains("processingTimestamp=" + summary.processingTimestamp());
            assertThat(rendered)
                    .as("and it mentions no record tail, because there is none to mention")
                    .doesNotContain("filler");
        }

        /**
         * The primary account number must not reach a rendering surface, and neither must the values
         * that reconstruct a cardholder's spending alongside it.
         *
         * <p>This assertion is negative on purpose and is the security half of the rendering contract.
         * The card number is a primary account number; the amount, the description and the four
         * merchant components combine with the retained transaction identifier to describe what a
         * specific cardholder spent and where. None of them appears in the rendered text, in whole or
         * in part - a truncated primary account number is still cardholder data, so no partial mask is
         * accepted either.</p>
         */
        @Test
        @DisplayName("the text rendering discloses no primary account number and no spending detail")
        void theTextRenderingDisclosesNoPrimaryAccountNumberOrSpendingDetail() {
            StatementSummary summary = populated();

            String rendered = summary.toString();

            assertThat(rendered)
                    .as("the primary account number must appear nowhere, whole or partial")
                    .doesNotContain(summary.cardNumber())
                    .doesNotContain(summary.cardNumber().substring(8));
            assertThat(rendered)
                    .as("nor the spending detail that would reconstruct the statement line")
                    .doesNotContain(summary.description())
                    .doesNotContain(summary.amount().toPlainString())
                    .doesNotContain(summary.merchantId())
                    .doesNotContain(summary.merchantName())
                    .doesNotContain(summary.merchantCity())
                    .doesNotContain(summary.merchantZip());
            assertThat(rendered)
                    .as("each withheld component renders as the fixed placeholder instead")
                    .contains("cardNumber=***REDACTED***")
                    .contains("amount=***REDACTED***");
        }

        /**
         * Pins the boundary between what the rendering withholds and what it retains, in both
         * directions, because a one-directional assertion cannot detect the boundary moving.
         *
         * <p><strong>Where the boundary sits, and why it sits there.</strong> Withheld: the card
         * number, the amount, the description and the four merchant components. Retained: the
         * transaction identifier, the type and category codes, the source channel and the two
         * 26-character stamps.
         *
         * <p>The withheld set is the set that reconstructs a statement line - what was spent, on what,
         * and where. The retained set identifies and classifies the line without describing it. The
         * source channel is {@code TRAN-SOURCE PIC X(10)} [{@code app/cpy/CVTRA05Y.cpy}:L8], a channel
         * literal such as {@code POS TERM} or {@code System}, which is a property of how the record
         * entered the estate and not an attribute of any person - the same category as the account
         * status that DL-011 retains for exactly that reason. The stamps are
         * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS PIC X(26)} at record offsets 278 and 304
         * [{@code app/cpy/CVTRA05Y.cpy}:L16-L17], and they are what the batch tier keys and filters on:
         * the reporting procedure's {@code INCLUDE COND} selects rows by {@code TRAN-PROC-DT} at offset
         * 305, so a diagnostic that omitted them could not answer whether a line fell inside the
         * requested window, which is the most common batch-parity question there is.
         *
         * <p>The marginal disclosure from retaining them is nil, and that is the decisive point rather
         * than a convenience: {@code transactionId} is retained by any reading of this contract, and it
         * is the primary key of the row, so anything withheld is one keyed read away for a reader who
         * has database access and unavailable to a reader who does not. Withholding a stamp while
         * publishing the key that resolves it buys no privacy and costs the diagnostic its usefulness.
         *
         * <p>What the security half continues to guarantee is unchanged and is asserted first: no
         * primary account number, no monetary amount, no description and no merchant component, whole
         * or partial.
         */
        @Test
        @DisplayName("neither the card number nor the amount nor any merchant value is rendered, while "
                + "the line's own identity, classification, channel and stamps are retained")
        void neitherTheCardNumberNorTheAmountNorAnyMerchantValueIsRendered() {
            String rendered = populated().toString();

            assertThat(rendered)
                    .as("a primary account number must never be rendered")
                    .doesNotContain("0000000000000001")
                    .as("financial data must never be rendered")
                    .doesNotContain("-1234.56")
                    .as("where a cardholder spent money must never be rendered")
                    .doesNotContain("999999999")
                    .doesNotContain("Some Merchant Name")
                    .doesNotContain("Some Merchant City")
                    .doesNotContain("12345")
                    .as("nor the description")
                    .doesNotContain("Int. for a/c");

            assertThat(rendered)
                    .as("each withheld component renders as the same fixed placeholder, so neither the "
                            + "value nor its length survives")
                    .contains("cardNumber=" + REDACTION_PLACEHOLDER_TEXT)
                    .contains("description=" + REDACTION_PLACEHOLDER_TEXT)
                    .contains("amount=" + REDACTION_PLACEHOLDER_TEXT)
                    .contains("merchantId=" + REDACTION_PLACEHOLDER_TEXT)
                    .contains("merchantName=" + REDACTION_PLACEHOLDER_TEXT)
                    .contains("merchantCity=" + REDACTION_PLACEHOLDER_TEXT)
                    .contains("merchantZip=" + REDACTION_PLACEHOLDER_TEXT);

            assertThat(rendered)
                    .as("and the identifying, classifying and locating components are retained, because "
                            + "a diagnostic that named none of them could not find the line again")
                    .contains("transactionId=0000000000000002")
                    .contains("typeCode=01")
                    .contains("categoryCode=0002")
                    .contains("source=" + SOURCE_SYSTEM)
                    .contains("originationTimestamp=" + ONLINE_STAMP)
                    .contains("processingTimestamp=" + BLANK_STAMP);
        }

        @Test
        @DisplayName("two rows differing only in withheld components render identically, and a row "
                + "differing in a retained component does not")
        void twoRowsDifferingOnlyInWithheldComponentsRenderIdentically() {
            // Differs from populated() in every withheld component and in none of the retained ones.
            StatementSummary sameIdentityDifferentDetail = new StatementSummary(
                    "9999999999999999",
                    "0000000000000002",
                    "01",
                    "0002",
                    SOURCE_SYSTEM,
                    "A different description",
                    new BigDecimal("98765.43"),
                    "111111111",
                    "Other Merchant",
                    "Other City",
                    "99999     ",
                    ONLINE_STAMP,
                    BLANK_STAMP);

            assertThat(sameIdentityDifferentDetail.toString()).isEqualTo(populated().toString());
            assertThat(sameIdentityDifferentDetail)
                    .as("so a rendering can never be used as an equality proxy: these two are unequal "
                            + "and render identically")
                    .isNotEqualTo(populated());

            // The converse half, which the identical-rendering assertion alone cannot give: a change in
            // a retained component must be visible, or the retained set is not really retained.
            StatementSummary differentIdentitySameDetail = new StatementSummary(
                    "0000000000000001",
                    "0000000000000003",
                    "01",
                    "0002",
                    SOURCE_POS_TERMINAL,
                    "Int. for a/c 00000000011  ",
                    new BigDecimal("-1234.56"),
                    "999999999",
                    "Some Merchant Name  ",
                    "Some Merchant City  ",
                    "12345     ",
                    BATCH_STAMP,
                    BLANK_STAMP);

            assertThat(differentIdentitySameDetail.toString())
                    .as("a different line must be distinguishable from this one")
                    .isNotEqualTo(populated().toString());
        }

        @Test
        @DisplayName("the accessors still return every withheld value, so redaction is presentational only")
        void theAccessorsStillReturnEveryWithheldValue() {
            StatementSummary row = populated();

            assertThat(row.cardNumber()).isEqualTo("0000000000000001");
            assertThat(row.amount()).isEqualTo(new BigDecimal("-1234.56"));
            assertThat(row.merchantName()).isEqualTo("Some Merchant Name  ");
            assertThat(row.originationTimestamp()).isEqualTo(ONLINE_STAMP);
            assertThat(row.processingTimestamp()).isEqualTo(BLANK_STAMP);
        }

        @Test
        @DisplayName("absent components are omitted from the wire and present ones are carried")
        void absentComponentsAreOmittedAndPresentOnesCarried() throws Exception {
            StatementSummary partial = new StatementSummary("0000000000000001", null, null, null,
                    null, "Only two fields set", null, null, null, null, null, null, null);

            Map<String, Object> properties = propertiesOf(partial);

            assertThat(properties)
                    .as("under absent-rather-than-null inclusion only the two supplied fields are "
                            + "emitted, so a consumer can tell a field the record never carried from "
                            + "one that carried an empty value")
                    .containsOnlyKeys("cardNumber", "description")
                    .containsEntry("cardNumber", "0000000000000001")
                    .containsEntry("description", "Only two fields set");
            assertThat(propertiesOf(populated()))
                    .as("while a fully populated row emits every one of the thirteen")
                    .hasSize(13);
        }

        @Test
        @DisplayName("a fully populated row survives a round trip completely unchanged")
        void aFullyPopulatedRowSurvivesARoundTrip() throws Exception {
            StatementSummary original = populated();

            assertThat(roundTrip(original))
                    .as("this is the whole point of the type: every awkward value in the fixture, the "
                            + "leading zeroes, the trailing blanks, the mixed case, the negative "
                            + "amount and the 26-blank stamp, comes back byte for byte")
                    .isEqualTo(original);
        }

        @Test
        @DisplayName("unknown input is tolerated rather than rejected")
        void unknownInputIsTolerated() throws Exception {
            String withAnExtraProperty =
                    "{\"cardNumber\":\"0000000000000001\",\"somePropertyThisTypeDoesNotCarry\":\"x\"}";

            StatementSummary parsed = newMapper().readValue(withAnExtraProperty,
                    StatementSummary.class);

            assertThat(parsed.cardNumber())
                    .as("a caller may echo back a property this type does not consume, and that must "
                            + "not fail the request")
                    .isEqualTo("0000000000000001");
            assertThat(parsed.description())
                    .as("while everything it did not supply stays absent")
                    .isNull();
        }
    }

}
