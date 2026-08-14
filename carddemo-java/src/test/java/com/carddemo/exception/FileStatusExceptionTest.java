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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link FileStatusException}, which carries a raw two-character COBOL
 * {@code FILE STATUS} value and deliberately interprets nothing about it.
 *
 * <p><strong>Carry, never classify.</strong> The estate never branches on the raw two bytes. Each
 * batch program declares the status as a split group of two single-character items, normalises it
 * into a coarse numeric result - {@code "00"} becomes 0, {@code "10"} becomes 16, everything else 12
 * - and only then branches, on the coarse condition names ({@code CBACT01C} lines 29-33, 46-48,
 * 61-63 and 92-103). The open and close paragraphs seed that same coarse variable with 8 and have no
 * end-of-file arm at all. The coarse variable, referenced on roughly 223 lines estate-wide, is what
 * the read loops test; the operator display routine merely reformats the two bytes under a fixed
 * prefix and never asks what they mean. That two-level model is decision log entry D-21, and
 * collapsing the two levels is precisely what the migration forbids - which is why the coarse
 * tri-state outcome belongs to the layer above and nothing here asserts anything about it.
 *
 * <p><strong>The observed vocabulary is documentation, not a whitelist.</strong> Nine distinct
 * literals appear anywhere in the source - {@code 00}, {@code 01}, {@code 02}, {@code 04},
 * {@code 05}, {@code 10}, {@code 12}, {@code 23}, {@code 31} - but only three are ever compared in a
 * status-testing context: {@code 00}, {@code 10}, and {@code 23} in two programs. So the tests below
 * assert that <em>any</em> well-formed two-character value is carried, including values the source
 * never encountered. {@code 22} and {@code 35} appear in earlier project documentation but in zero
 * source members, carry no behaviour and no test depends on them; that correction is entry D-22.
 *
 * <p><strong>Two independent types, because logging and abending are two steps.</strong> Every legacy
 * I/O failure emits the diagnostic, moves the raw status into the display field, emits it, and only
 * then abends. Because that ordering is contractual, {@link FileStatusException} and
 * {@link AbendException} stay independent, and one test below pins that down. This type carries a raw
 * {@code String} and imports nothing at all, keeping the leaf layer free of dependencies on the
 * layers above; enumerating status codes belongs to the domain enumeration package.
 *
 * <p>Pure unit tests: no context, no container, no mocks - the class under test has no collaborators
 * to isolate.
 *
 * @see FileStatusException
 */
@DisplayName("FileStatusException carries the raw two-byte COBOL file status and interprets nothing")
class FileStatusExceptionTest {
    private static final String OPERATION_OPEN = "OPEN";

    private static final String OPERATION_READ = "READ";

    private static final String OPERATION_WRITE = "WRITE";

    private static final String OPERATION_REWRITE = "REWRITE";

    private static final String OPERATION_CLOSE = "CLOSE";

    private static final String OPERATION_STARTBR = "STARTBR";

    private static final String RESOURCE_ACCTFILE = "ACCTFILE";

    private static final String RESOURCE_CARDFILE = "CARDFILE";

    private static final String RESOURCE_TRANFILE = "TRANFILE";

    private static final String RESOURCE_XREFFILE = "XREFFILE";

    private static final String CAUSE_MESSAGE = "underlying channel failure";

    @Nested
    @DisplayName("the raw two-character code is carried verbatim")
    class RawCodeIsCarriedVerbatim {
        @Test
        @DisplayName("the declared code length is exactly two characters")
        void codeLengthIsTwo() {
            assertThat(FileStatusException.CODE_LENGTH).isEqualTo(2);
        }

        @ParameterizedTest
        @ValueSource(strings = {"01", "02", "04", "05", "12", "23", "31"})
        @DisplayName("every status observed in the estate is returned exactly as supplied")
        void observedVocabularyIsReturnedUnchanged(String code) {
            FileStatusException exception =
                    new FileStatusException(code, OPERATION_READ, RESOURCE_ACCTFILE);

            assertThat(exception.code())
                    .isEqualTo(code)
                    .hasSize(FileStatusException.CODE_LENGTH);
        }

        @Test
        @DisplayName("a leading zero survives, because the status is two characters and not a number")
        void leadingZeroIsPreserved() {
            FileStatusException exception =
                    new FileStatusException("01", OPERATION_READ, RESOURCE_ACCTFILE);

            assertThat(exception.code()).isEqualTo("01");
            assertThat(exception.code()).isNotEqualTo("1");
            assertThat(exception.code()).hasSize(FileStatusException.CODE_LENGTH);
            assertThat(exception.firstByte()).isEqualTo('0');
            assertThat(exception.secondByte()).isEqualTo('1');
        }

        @Test
        @DisplayName("the code is not trimmed - two blanks stay two blanks")
        void codeIsNotTrimmed() {
            FileStatusException exception =
                    new FileStatusException("  ", OPERATION_READ, RESOURCE_CARDFILE);

            assertThat(exception.code()).isEqualTo("  ");
            assertThat(exception.code()).isNotEqualTo("");
            assertThat(exception.code()).hasSize(FileStatusException.CODE_LENGTH);
        }

        @Test
        @DisplayName("the code is not case-folded, so a runtime status keeps the case it reported")
        void codeIsNotCaseFolded() {
            FileStatusException exception =
                    new FileStatusException("9a", OPERATION_READ, RESOURCE_CARDFILE);

            assertThat(exception.code()).isEqualTo("9a");
            assertThat(exception.code()).isNotEqualTo("9A");
            assertThat(exception.firstByte()).isEqualTo('9');
        }
    }

    @Nested
    @DisplayName("the split two-byte group is exposed as two individual characters")
    class SplitTwoByteGroupIsAddressable {
        @Test
        @DisplayName("both halves are returned for a status whose two bytes differ")
        void differingBytesAreReturnedSeparately() {
            FileStatusException exception =
                    new FileStatusException("31", OPERATION_STARTBR, RESOURCE_TRANFILE);

            assertThat(exception.firstByte()).isEqualTo('3');
            assertThat(exception.secondByte()).isEqualTo('1');
        }

        @Test
        @DisplayName("both halves are returned for a status whose two bytes are equal")
        void equalBytesAreReturnedSeparately() {
            FileStatusException exception =
                    new FileStatusException("44", OPERATION_STARTBR, RESOURCE_TRANFILE);

            assertThat(exception.firstByte()).isEqualTo('4');
            assertThat(exception.secondByte()).isEqualTo('4');
        }

        @Test
        @DisplayName("both halves are returned for the default-group fallback status")
        void fallbackStatusBytesAreReturnedSeparately() {
            FileStatusException exception =
                    new FileStatusException("23", OPERATION_READ, RESOURCE_XREFFILE);

            assertThat(exception.firstByte()).isEqualTo('2');
            assertThat(exception.secondByte()).isEqualTo('3');
        }

        @ParameterizedTest
        @ValueSource(strings = {"01", "02", "04", "05", "12", "23", "31", "44", "99"})
        @DisplayName("the byte accessors can never drift from the stored code")
        void byteAccessorsAgreeWithTheStoredCode(String code) {
            FileStatusException exception =
                    new FileStatusException(code, OPERATION_READ, RESOURCE_ACCTFILE);

            assertThat(exception.firstByte()).isEqualTo(exception.code().charAt(0));
            assertThat(exception.secondByte()).isEqualTo(exception.code().charAt(1));
        }
    }

    @Nested
    @DisplayName("the legacy display prefix is preserved character for character")
    class DisplayPrefixIsPreserved {
        @Test
        @DisplayName("the prefix is exactly the text the legacy display paragraph emits")
        void prefixIsVerbatim() {
            assertThat(FileStatusException.DISPLAY_PREFIX).isEqualTo("FILE STATUS IS: NNNN");
        }

        @Test
        @DisplayName("the prefix keeps its single space after the colon and its four placeholders")
        void prefixShapeIsUnaltered() {
            assertThat(FileStatusException.DISPLAY_PREFIX).hasSize(20);
            assertThat(FileStatusException.DISPLAY_PREFIX).startsWith("FILE STATUS IS:");
            assertThat(FileStatusException.DISPLAY_PREFIX).contains(": ");
            assertThat(FileStatusException.DISPLAY_PREFIX).endsWith("NNNN");
            assertThat(FileStatusException.DISPLAY_PREFIX).doesNotContain("%");
        }
    }

    @Nested
    @DisplayName("statuses that are not errors, and statuses that are not two characters, are refused")
    class NonErrorAndMisshapenStatusesAreRefused {
        @Test
        @DisplayName("an absent status is refused, because there is nothing to carry")
        void absentStatusIsRefused() {
            assertThatThrownBy(
                    () -> new FileStatusException(null, OPERATION_OPEN, RESOURCE_ACCTFILE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("FILE STATUS")
                    .hasMessageContaining("null");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "0", "000", "23000"})
        @DisplayName("a status that is not exactly two characters is refused")
        void misshapenStatusIsRefused(String candidate) {
            assertThatThrownBy(
                    () -> new FileStatusException(candidate, OPERATION_OPEN, RESOURCE_ACCTFILE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactly " + FileStatusException.CODE_LENGTH)
                    .hasMessageContaining("length " + candidate.length());
        }

        @Test
        @DisplayName("the success status is refused, because a successful operation is not an error")
        void successStatusIsRefused() {
            assertThatThrownBy(() -> new FileStatusException(
                    FileStatusException.STATUS_SUCCESS, OPERATION_READ, RESOURCE_ACCTFILE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(FileStatusException.STATUS_SUCCESS)
                    .hasMessageContaining("success");
        }

        @Test
        @DisplayName("the end-of-file status is refused, because end of file is the NORMAL way a "
                + "sequential read terminates and must never be reported as a failure")
        void endOfFileStatusIsRefused() {
            assertThatThrownBy(() -> new FileStatusException(
                    FileStatusException.STATUS_END_OF_FILE, OPERATION_READ, RESOURCE_ACCTFILE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(FileStatusException.STATUS_END_OF_FILE)
                    .hasMessageContaining("end of file");
        }

        @Test
        @DisplayName("the constructor that chains a cause applies exactly the same four refusals")
        void theCauseChainingConstructorRefusesTheSameStatuses() {
            IOException cause = new IOException(CAUSE_MESSAGE);

            assertThatThrownBy(() -> new FileStatusException(
                    null, OPERATION_CLOSE, RESOURCE_CARDFILE, cause))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("null");
            assertThatThrownBy(() -> new FileStatusException(
                    "000", OPERATION_CLOSE, RESOURCE_CARDFILE, cause))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("length 3");
            assertThatThrownBy(() -> new FileStatusException(
                    FileStatusException.STATUS_SUCCESS, OPERATION_CLOSE, RESOURCE_CARDFILE, cause))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("success");
            assertThatThrownBy(() -> new FileStatusException(
                    FileStatusException.STATUS_END_OF_FILE, OPERATION_CLOSE, RESOURCE_CARDFILE,
                    cause))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("end of file");
        }
    }

    @Nested
    @DisplayName("every other well-formed two-character status is accepted - there is no whitelist")
    class AnyOtherWellFormedStatusIsAccepted {
        @ParameterizedTest
        @ValueSource(strings = {"01", "02", "04", "05", "12", "23", "31"})
        @DisplayName("each error status observed in the estate is accepted and reaches the message")
        void observedVocabularyIsAccepted(String code) {
            FileStatusException exception =
                    new FileStatusException(code, OPERATION_WRITE, RESOURCE_TRANFILE);

            assertThat(exception.code()).isEqualTo(code);
            assertThat(exception.getMessage()).contains(code);
        }

        @ParameterizedTest
        @ValueSource(strings = {"44", "97", "99"})
        @DisplayName("a status the legacy source never compared is accepted just the same")
        void unlistedButWellFormedStatusIsAccepted(String code) {
            FileStatusException exception =
                    new FileStatusException(code, OPERATION_WRITE, RESOURCE_TRANFILE);

            assertThat(exception.code()).isEqualTo(code);
            assertThat(exception.getMessage()).contains(code);
        }

        @Test
        @DisplayName("even a blank status yields a fully usable instance, proving shape is what is "
                + "validated and not content")
        void blankStatusYieldsAFullyUsableInstance() {
            FileStatusException exception =
                    new FileStatusException("  ", OPERATION_WRITE, RESOURCE_TRANFILE);

            assertThat(exception.code()).isEqualTo("  ");
            assertThat(exception.firstByte()).isEqualTo(' ');
            assertThat(exception.secondByte()).isEqualTo(' ');
            assertThat(exception.operation()).isEqualTo(OPERATION_WRITE);
            assertThat(exception.resourceName()).isEqualTo(RESOURCE_TRANFILE);
            assertThat(exception.getMessage()).contains(RESOURCE_TRANFILE);
        }
    }

    @Nested
    @DisplayName("the two-level status model is not collapsed into one")
    class TwoLevelModelIsNotCollapsed {
        @Test
        @DisplayName("the type is structurally incapable of representing success or end of file, "
                + "so no instance of it can ever stand for a non-error outcome")
        void theTypeCannotRepresentANonErrorOutcome() {
            IOException cause = new IOException(CAUSE_MESSAGE);

            assertThatThrownBy(() -> new FileStatusException(
                    FileStatusException.STATUS_SUCCESS, OPERATION_READ, RESOURCE_ACCTFILE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new FileStatusException(
                    FileStatusException.STATUS_SUCCESS, OPERATION_READ, RESOURCE_ACCTFILE, cause))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new FileStatusException(
                    FileStatusException.STATUS_END_OF_FILE, OPERATION_READ, RESOURCE_ACCTFILE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new FileStatusException(
                    FileStatusException.STATUS_END_OF_FILE, OPERATION_READ, RESOURCE_ACCTFILE,
                    cause))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the entire surface is the code, its two halves, the operation, the resource, "
                + "the message and the cause - and nothing that classifies the status")
        void theEntireSurfaceCarriesAndNeverClassifies() {
            IOException cause = new IOException(CAUSE_MESSAGE);
            FileStatusException exception =
                    new FileStatusException("31", OPERATION_READ, RESOURCE_TRANFILE, cause);

            assertThat(exception.code()).isEqualTo("31");
            assertThat(exception.firstByte()).isEqualTo('3');
            assertThat(exception.secondByte()).isEqualTo('1');
            assertThat(exception.operation()).isEqualTo(OPERATION_READ);
            assertThat(exception.resourceName()).isEqualTo(RESOURCE_TRANFILE);
            assertThat(exception.getMessage()).contains("31");
            assertThat(exception.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("the carried constants describe the two non-error statuses without ranking, "
                + "grading or otherwise interpreting any status")
        void theConstantsDescribeWithoutInterpreting() {
            assertThat(FileStatusException.STATUS_SUCCESS)
                    .isEqualTo("00")
                    .hasSize(FileStatusException.CODE_LENGTH);
            assertThat(FileStatusException.STATUS_END_OF_FILE)
                    .isEqualTo("10")
                    .hasSize(FileStatusException.CODE_LENGTH);
            assertThat(FileStatusException.STATUS_SUCCESS)
                    .isNotEqualTo(FileStatusException.STATUS_END_OF_FILE);
        }
    }

    @Nested
    @DisplayName("the operation and resource context travels with the status")
    class ContextTravelsWithTheStatus {
        @Test
        @DisplayName("both context values are returned exactly as supplied")
        void contextIsReturnedUnchanged() {
            FileStatusException exception =
                    new FileStatusException("12", OPERATION_REWRITE, RESOURCE_CARDFILE);

            assertThat(exception.operation()).isEqualTo(OPERATION_REWRITE);
            assertThat(exception.resourceName()).isEqualTo(RESOURCE_CARDFILE);
        }

        @Test
        @DisplayName("an absent operation becomes the empty string and never the text of a null")
        void absentOperationBecomesTheEmptyString() {
            FileStatusException exception =
                    new FileStatusException("12", null, RESOURCE_CARDFILE);

            assertThat(exception.operation()).isEmpty();
            assertThat(exception.operation()).isNotEqualTo("null");
            assertThat(exception.resourceName()).isEqualTo(RESOURCE_CARDFILE);
            assertThat(exception.getMessage()).doesNotContain("null");
            assertThat(exception.getMessage()).doesNotContain("operation=");
            assertThat(exception.getMessage()).contains(RESOURCE_CARDFILE);
        }

        @Test
        @DisplayName("an absent resource name becomes the empty string and never the text of a null")
        void absentResourceNameBecomesTheEmptyString() {
            FileStatusException exception =
                    new FileStatusException("12", OPERATION_REWRITE, null);

            assertThat(exception.resourceName()).isEmpty();
            assertThat(exception.resourceName()).isNotEqualTo("null");
            assertThat(exception.operation()).isEqualTo(OPERATION_REWRITE);
            assertThat(exception.getMessage()).doesNotContain("null");
            assertThat(exception.getMessage()).doesNotContain("resource=");
            assertThat(exception.getMessage()).contains(OPERATION_REWRITE);
        }

        @Test
        @DisplayName("when no context at all is supplied the message reports only the status")
        void absentContextIsOmittedEntirely() {
            FileStatusException exception = new FileStatusException("31", null, null);

            assertThat(exception.operation()).isEmpty();
            assertThat(exception.resourceName()).isEmpty();
            assertThat(exception.getMessage()).contains("31");
            assertThat(exception.getMessage()).doesNotContain("null");
            assertThat(exception.getMessage()).doesNotContain("operation=");
            assertThat(exception.getMessage()).doesNotContain("resource=");
        }

        @Test
        @DisplayName("the message always carries the raw two bytes an operator would have seen")
        void theMessageCarriesTheRawStatus() {
            FileStatusException exception =
                    new FileStatusException("23", OPERATION_READ, RESOURCE_XREFFILE);

            assertThat(exception.getMessage()).contains("23");
            assertThat(exception.getMessage()).contains(OPERATION_READ);
            assertThat(exception.getMessage()).contains(RESOURCE_XREFFILE);
        }
    }

    @Nested
    @DisplayName("an underlying failure is chained as a cause")
    class UnderlyingFailureIsChained {
        @Test
        @DisplayName("the chaining constructor keeps the very instance it was given")
        void theChainingConstructorKeepsTheGivenCause() {
            IOException cause = new IOException(CAUSE_MESSAGE);
            FileStatusException exception =
                    new FileStatusException("31", OPERATION_READ, RESOURCE_ACCTFILE, cause);

            assertThat(exception.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("the constructor without a cause chains nothing at all")
        void theConstructorWithoutACauseChainsNothing() {
            FileStatusException exception =
                    new FileStatusException("31", OPERATION_READ, RESOURCE_ACCTFILE);

            assertThat(exception.getCause()).isNull();
        }

        @Test
        @DisplayName("the chaining constructor accepts an absent cause without complaint")
        void theChainingConstructorAcceptsAnAbsentCause() {
            FileStatusException exception =
                    new FileStatusException("31", OPERATION_READ, RESOURCE_ACCTFILE, null);

            assertThat(exception.getCause()).isNull();
            assertThat(exception.code()).isEqualTo("31");
        }
    }

    @Nested
    @DisplayName("the type is an unchecked exception and is unrelated to the abend type")
    class TypeIdentityIsPinnedDown {
        @Test
        @DisplayName("it is unchecked, so a read loop is not forced to declare it")
        void itIsUnchecked() {
            FileStatusException exception =
                    new FileStatusException("31", OPERATION_READ, RESOURCE_ACCTFILE);

            assertThat(exception).isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("it extends the unchecked base directly, adding no intermediate layer")
        void itExtendsTheUncheckedBaseDirectly() {
            assertThat(FileStatusException.class.getSuperclass()).isEqualTo(RuntimeException.class);
        }

        @Test
        @DisplayName("it is neither a kind of abend nor a supertype of one, because logging the "
                + "status and abending are two distinct steps in that order")
        void itIsUnrelatedToTheAbendType() {
            assertThat(AbendException.class.isAssignableFrom(FileStatusException.class)).isFalse();
            assertThat(FileStatusException.class.isAssignableFrom(AbendException.class)).isFalse();
        }
    }

    @Nested
    @DisplayName("serialisation identity is explicit and the carried state survives a round trip")
    class SerialisationIdentityIsExplicit {
        @Test
        @DisplayName("the serialisation identity is the declared value, not a generated hash")
        void theSerialisationIdentityIsDeclared() {
            ObjectStreamClass descriptor = ObjectStreamClass.lookup(FileStatusException.class);

            assertThat(descriptor).isNotNull();
            assertThat(descriptor.getSerialVersionUID()).isEqualTo(1L);
        }

        @Test
        @DisplayName("the status, both halves, the context, the message and the cause all survive "
                + "a serialisation round trip")
        void theCarriedStateSurvivesARoundTrip() throws IOException, ClassNotFoundException {
            FileStatusException original = new FileStatusException(
                    "23", OPERATION_READ, RESOURCE_XREFFILE, new IOException(CAUSE_MESSAGE));

            FileStatusException restored = deserialise(serialise(original));

            assertThat(restored.code()).isEqualTo("23");
            assertThat(restored.firstByte()).isEqualTo('2');
            assertThat(restored.secondByte()).isEqualTo('3');
            assertThat(restored.operation()).isEqualTo(OPERATION_READ);
            assertThat(restored.resourceName()).isEqualTo(RESOURCE_XREFFILE);
            assertThat(restored.getMessage()).isEqualTo(original.getMessage());
            assertThat(restored.getCause())
                    .isInstanceOf(IOException.class)
                    .hasMessage(CAUSE_MESSAGE);
        }

        @Test
        @DisplayName("an instance carrying no context and no cause also survives a round trip")
        void anInstanceWithoutContextAlsoSurvivesARoundTrip()
                throws IOException, ClassNotFoundException {
            FileStatusException original = new FileStatusException("31", null, null);

            FileStatusException restored = deserialise(serialise(original));

            assertThat(restored.code()).isEqualTo("31");
            assertThat(restored.operation()).isEmpty();
            assertThat(restored.resourceName()).isEmpty();
            assertThat(restored.getMessage()).isEqualTo(original.getMessage());
            assertThat(restored.getCause()).isNull();
        }
    }

    private static byte[] serialise(FileStatusException original) throws IOException {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
                out.writeObject(original);
            }
            return buffer.toByteArray();
        }
    }

    private static FileStatusException deserialise(byte[] serialised)
            throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream source = new ByteArrayInputStream(serialised)) {
            try (ObjectInputStream in = new ObjectInputStream(source)) {
                return (FileStatusException) in.readObject();
            }
        }
    }
}
