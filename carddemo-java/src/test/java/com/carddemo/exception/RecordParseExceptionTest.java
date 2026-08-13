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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RecordParseException}, the bounded diagnosis raised when a fixed-width record
 * cannot be mapped.
 *
 * <p><strong>What this type is for, and therefore what these tests are for.</strong> Spring Batch's
 * flat-file reader reports a mapping failure with an exception that carries the offending record
 * verbatim in both its message and its payload. Everything downstream publishes that: the framework
 * logs the failed step with the throwable attached, and the job repository stores the rendered stack
 * trace in the execution's exit message, so the record is copied into a log collector and persisted in
 * a database column. Four of the eleven layouts in this estate make that unacceptable - a full card
 * primary account number, a card verification code, a national identifier and a sign-on credential all
 * live inside record images. This type is what the reading layer raises instead, and the tests below
 * pin the two properties that make it a control rather than a rename: it carries the four facts an
 * operator needs, and it carries nothing that came out of the record.
 *
 * <p>The state is asserted through the accessors and through the fully rendered form - message plus
 * the complete stack trace of the throwable and any cause - because the rendered form is what an
 * appender writes and what the job repository stores. An assertion made only on {@code getMessage()}
 * would pass while a cause republished the record through both channels.
 */
@DisplayName("RecordParseException - a mapping failure described without reproducing the record")
class RecordParseExceptionTest {

    /** A stable reader name, standing in for the layout that rejected a record. */
    private static final String LAYOUT = "dailyTransactionFixedWidthItemReader";

    /** A resource description, as a resource reports its own. */
    private static final String RESOURCE = "file [/staging/AWS.M2.CARDDEMO.DALYTRAN.PS]";

    /** A bounded failure chain, in the form the module's failure-chain helper produces. */
    private static final String CHAIN = "IllegalArgumentException";

    /** The line the failure is attributed to. */
    private static final int LINE = 3;

    @Nested
    @DisplayName("the four facts an operator needs are carried and readable")
    class FactsCarried {

        @Test
        @DisplayName("every fact appears in the message and through its own accessor")
        void everyFactIsCarried() {
            final RecordParseException failure =
                    new RecordParseException(LAYOUT, RESOURCE, LINE, CHAIN);

            assertThat(failure.layout()).isEqualTo(LAYOUT);
            assertThat(failure.resourceDescription()).isEqualTo(RESOURCE);
            assertThat(failure.lineNumber()).isEqualTo(LINE);
            assertThat(failure.failureChain()).isEqualTo(CHAIN);
            assertThat(failure.getMessage())
                    .as("the message is what an operator reads first, so all four facts are in it")
                    .contains("layout=" + LAYOUT)
                    .contains("resource=[" + RESOURCE + "]")
                    .contains("line=" + LINE)
                    .contains("failureChain=" + CHAIN);
        }

        @Test
        @DisplayName("the message states that the record is withheld on purpose")
        void theOmissionIsExplained() {
            final RecordParseException failure =
                    new RecordParseException(LAYOUT, RESOURCE, LINE, CHAIN);

            assertThat(failure.getMessage())
                    .as("a bounded diagnosis must not read as a truncated or defective one")
                    .contains(RecordParseException.REDACTION_NOTICE);
            assertThat(RecordParseException.REDACTION_NOTICE)
                    .as("and the notice names what is being protected, so the reason survives the "
                            + "next reader of the log rather than only the next reader of the code")
                    .contains("card primary account number")
                    .contains("sign-on credential");
        }

        @Test
        @DisplayName("an absent fact is reported as unknown rather than leaving a half-formed message")
        void absentFactsAreNamed() {
            final RecordParseException nothingKnown = new RecordParseException(null, "  ",
                    RecordParseException.UNKNOWN_LINE, null);

            assertThat(nothingKnown.layout()).isEqualTo(RecordParseException.UNKNOWN);
            assertThat(nothingKnown.resourceDescription()).isEqualTo(RecordParseException.UNKNOWN);
            assertThat(nothingKnown.failureChain()).isEqualTo(RecordParseException.UNKNOWN);
            assertThat(nothingKnown.lineNumber()).isEqualTo(RecordParseException.UNKNOWN_LINE);
            assertThat(nothingKnown.getMessage())
                    .as("the message stays well formed, and says which facts were unavailable")
                    .contains("layout=" + RecordParseException.UNKNOWN)
                    .contains("line=" + RecordParseException.UNKNOWN)
                    .doesNotContain("null");
        }
    }

    @Nested
    @DisplayName("nothing out of the record can travel with it")
    class NothingLeaks {

        @Test
        @DisplayName("the type has no cause, so a rendered failure cannot republish a record through "
                + "one")
        void thereIsNoCause() {
            final RecordParseException failure =
                    new RecordParseException(LAYOUT, RESOURCE, LINE, CHAIN);

            assertThat(failure.getCause())
                    .as("A CAUSE IS THE DISCLOSURE PATH THIS TYPE EXISTS TO CLOSE. Retaining the "
                            + "framework's record-bearing parse exception as a cause would republish "
                            + "the record through every channel that renders a throwable, so this "
                            + "type offers no constructor that accepts one")
                    .isNull();
            assertThat(failure.getSuppressed())
                    .as("and nothing may arrive by the suppressed route either")
                    .isEmpty();
        }

        @Test
        @DisplayName("the fully rendered form - message and stack trace - carries only the four facts")
        void theRenderedFormIsBounded() {
            final String pan = "4111111111111111";
            final String credential = "PASSWORD";
            final RecordParseException failure =
                    new RecordParseException(LAYOUT, RESOURCE, LINE, CHAIN);

            final StringWriter rendered = new StringWriter();
            try (PrintWriter into = new PrintWriter(rendered)) {
                failure.printStackTrace(into);
            }

            assertThat(rendered.toString())
                    .as("this is the form an appender writes and the job repository stores")
                    .contains(LAYOUT)
                    .contains(RESOURCE)
                    .doesNotContain(pan)
                    .doesNotContain(credential)
                    .as("and it names no framework parse exception, because none is linked")
                    .doesNotContain("FlatFileParseException")
                    .doesNotContain("input=[");
        }

        @Test
        @DisplayName("the type is unchecked, so a read still fails terminally as it did before")
        void theVerdictIsUnchanged() {
            assertThat(RuntimeException.class)
                    .as("every legacy batch program treats a failed read as terminal; translating the "
                            + "diagnosis must not soften the verdict into something a step can ignore")
                    .isAssignableFrom(RecordParseException.class);
        }
    }
}
