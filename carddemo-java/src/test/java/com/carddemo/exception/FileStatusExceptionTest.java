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
 * Unit tests for {@link FileStatusException}, the type that carries a raw two-character COBOL
 * {@code FILE STATUS} value and deliberately interprets nothing about it.
 *
 * <p>Pure unit tests: no Spring context, no containers, no mocks. The class under test has no
 * collaborators to isolate, so every assertion is made against a real instance.
 *
 * <p><strong>Carry, never classify.</strong> The legacy estate never branches on the raw two bytes.
 * Each batch program declares the status as a split group of two single-character items
 * ({@code CBACT01C} lines 46-48, named on the {@code SELECT} at lines 29-33), normalises it into a
 * coarse numeric result in the read paragraph at lines 92-103 - {@code "00"} becomes 0, {@code "10"}
 * becomes 16, everything else becomes 12 - and only then branches, on the coarse condition names
 * {@code APPL-AOK} (value 0) and {@code APPL-EOF} (value 16) declared at lines 61-63. The open and
 * close paragraphs seed that same coarse variable with 8 before normalising and have no
 * end-of-file arm at all. The coarse variable, not the raw status, is what the read loops test: it
 * is referenced on roughly 223 lines across the estate, out of 229 raw occurrences of its name once
 * the eight declarations are discounted. The display routine behaves the same way -
 * {@code 9910-DISPLAY-IO-STATUS} ({@code CBACT01C} lines 176-189) merely reformats the two bytes
 * for the operator under a fixed prefix, packing the second byte as a binary value when the pair is
 * non-numeric or the first byte is {@code '9'} and zero-padding it otherwise, and never asks what
 * the code <em>means</em>. That two-level model is decision log entry D-21.
 *
 * <p><strong>Only the fine level is tested here.</strong> The coarse tri-state outcome - all-OK,
 * end-of-file, error, mirroring the legacy coarse values 0, 16 and 12 - is a nested type belonging
 * to the layer above and is exercised through its owning class in that package's tests. Nothing
 * here asserts anything about it, because collapsing the two levels into one is precisely the
 * mistake the migration forbids.
 *
 * <p><strong>The observed vocabulary is documentation, not a whitelist.</strong> A census of the
 * estate finds nine distinct two-character status literals anywhere in the source: {@code 00},
 * {@code 01}, {@code 02}, {@code 04}, {@code 05}, {@code 10}, {@code 12}, {@code 23} and
 * {@code 31}. In an actual status-testing context only three are ever compared: {@code 00} on 73
 * lines, {@code 10} on 7, and {@code 23} on three lines in two programs - {@code CBACT04C} lines
 * 422 and 436, which fold {@code 00} and {@code 23} together as the non-error outcome and then
 * re-test {@code 23} on its own to select the default disclosure group, and {@code CBTRN02C} line
 * 481. The tests below assert that any well-formed two-character value is carried, including values
 * the source never encountered. Two further values, {@code 22} and {@code 35}, appear in earlier
 * project documentation but in zero source members, so they carry no behaviour and no test depends
 * on them; that correction is decision log entry D-22.
 *
 * <p><strong>No status enumeration is imported.</strong> {@link FileStatusException} carries a raw
 * {@code String} and imports nothing at all, which keeps this leaf layer free of dependencies on
 * the layers above it, and these tests bind to {@code String} for the same reason. Enumerating
 * status codes belongs to the domain enumeration package and is covered by its own tests.
 *
 * <p><strong>Logging the status and abending are two steps.</strong> At every legacy I/O failure the
 * sequence is identical: emit the diagnostic, move the raw two-byte status into the display field,
 * emit the status, and only then abend - the read arm at {@code CBACT01C} lines 110-113, the open
 * arm at lines 144-147, the close arm at lines 162-165, and again in the three invalid-key handlers
 * of {@code CBTRN03C} at lines 488, 498 and 508. Because that ordering is contractual,
 * {@link FileStatusException} and {@link AbendException} are independent types, and one of the tests
 * below pins that independence down.
 *
 * @see FileStatusException
 */
@DisplayName("FileStatusException carries the raw two-byte COBOL file status and interprets nothing")
class FileStatusExceptionTest {

    /** Neutral operation label for the open path, matching the legacy open paragraph's intent. */
    private static final String OPERATION_OPEN = "OPEN";

    /** Neutral operation label for the sequential read path. */
    private static final String OPERATION_READ = "READ";

    /** Neutral operation label for the write path. */
    private static final String OPERATION_WRITE = "WRITE";

    /** Neutral operation label for the update-in-place path. */
    private static final String OPERATION_REWRITE = "REWRITE";

    /** Neutral operation label for the close path. */
    private static final String OPERATION_CLOSE = "CLOSE";

    /** Neutral operation label for the browse-start path. */
    private static final String OPERATION_STARTBR = "STARTBR";

    /** Neutral resource label naming the account dataset by its legacy DD name. */
    private static final String RESOURCE_ACCTFILE = "ACCTFILE";

    /** Neutral resource label naming the card dataset by its legacy DD name. */
    private static final String RESOURCE_CARDFILE = "CARDFILE";

    /** Neutral resource label naming the transaction dataset by its legacy DD name. */
    private static final String RESOURCE_TRANFILE = "TRANFILE";

    /** Neutral resource label naming the cross-reference dataset by its legacy DD name. */
    private static final String RESOURCE_XREFFILE = "XREFFILE";

    /** Detail text for the chained lower-level failure used by the cause and round-trip tests. */
    private static final String CAUSE_MESSAGE = "underlying channel failure";

    /**
     * The raw code is carried through byte for byte - no normalisation, no numeric conversion.
     *
     * <p>This is the primary reason the type exists, so it is asserted first and asserted broadly:
     * across the whole observed vocabulary, and specifically against the one transformation that
     * would destroy the display contract, namely turning a two-character status into a number and
     * losing its leading zero.
     */
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

    /**
     * The two halves of the status are individually addressable.
     *
     * <p>This mirrors the legacy declaration, which is not a single two-character field but a group
     * of two one-character fields. The display routine inspects the halves separately - it tests
     * whether the first byte is {@code '9'} and treats the second byte as a binary value - so a
     * caller reproducing that inspection must be able to reach each byte without re-slicing the
     * string itself.
     */
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

    /**
     * The operator-facing display prefix is an external contract and is reproduced verbatim.
     *
     * <p>An operator already recognises this text from the mainframe console. Its capitalisation,
     * the single space after the colon and the four literal placeholder characters are all part of
     * what they recognise, so the constant is neither reformatted into a format specifier nor
     * trimmed nor otherwise improved.
     */
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

    /**
     * The four statuses this type refuses to carry, each for its own reason.
     *
     * <p>Two of the four rejections are shape checks and are unremarkable. The other two are the
     * whole point of the design: success and end of file are not errors, and refusing them in the
     * constructor is what makes it impossible to smuggle either one through the error channel. Each
     * rejection is asserted in its own test so that a regression names the reason it broke.
     */
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

    /**
     * Anything else that is two characters long is accepted - the type validates shape, never
     * membership.
     *
     * <p>There is no whitelist, and introducing one would be a defect rather than a hardening. A
     * live VSAM, JDBC or object-store layer can surface a status the 1990s source never encountered,
     * and a type that only accepted the nine literals in the census would swallow the diagnostic at
     * exactly the moment it mattered most.
     */
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

    /**
     * The two levels of the legacy status model stay two levels.
     *
     * <p>The fine level is the raw two-byte status, which this type carries; the coarse level is the
     * all-OK / end-of-file / error outcome the layer above derives from it. Nothing here classifies,
     * so there is no {@code isEndOfFile()}, no {@code isNotFound()}, no {@code severity()} and no
     * conversion to a coarse outcome - an absence proved at compile time by the fact that no such
     * call can be written, which is stronger than a reflective check and keeps the low-level code
     * audit clean. The structural evidence is stronger still: because the two non-error statuses are
     * refused by the constructor, no instance can exist that represents success or end of file.
     */
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

    /**
     * The optional context travels with the status, and its absence is silent.
     *
     * <p>Both context values are optional because the legacy diagnostics are: some sites name the
     * dataset and some only the operation. An absent value becomes the empty string rather than the
     * four-character text that a naive string concatenation would produce, and it is omitted from the
     * detail message entirely rather than rendered as an empty label - an operator should never have
     * to read a log line that claims a resource was involved and then names nothing.
     */
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

    /**
     * A lower-level failure is chained without being interpreted either.
     *
     * <p>Where the legacy runtime reported only a status, a Java runtime usually also has a concrete
     * failure to hand. Chaining it keeps that evidence, and chaining it as a plain cause - rather
     * than folding its text into the status - keeps the two pieces of evidence distinguishable in a
     * log.
     */
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

    /**
     * The type's place in the hierarchy, including the one place it deliberately is not.
     *
     * <p>The legacy failure sequence is two steps: emit the raw status, then abend. Merging the two
     * into a single type - by making this exception a kind of abend, or an abend a kind of this -
     * would erase that ordering and invite callers to abend without ever having logged the two bytes.
     * The two types are therefore unrelated by inheritance in both directions, and that is asserted
     * here rather than left to convention.
     */
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

    /**
     * Serialisation identity is explicit, and the carried state survives a round trip.
     *
     * <p>An exception that can cross a job or process boundary needs a stable serialisation identity,
     * so the declared value is pinned here rather than left to a compiler-generated hash that would
     * change with any edit to the class. The stream descriptor is read through {@code java.io}, not
     * through the reflection API: the production tree is required to contain no reflection at all,
     * and while that audit covers production sources only, this file honours the same restraint so
     * that a grep of the module for reflective calls stays clean and unambiguous.
     */
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

    /**
     * Writes an exception to an in-memory object stream and returns the bytes.
     *
     * <p>The wrapping stream is closed by the inner resource block before the buffer is read, so the
     * returned array is complete rather than partially flushed.
     *
     * @param original the exception to serialise
     * @return the serialised form
     * @throws IOException if the in-memory stream rejects the write
     */
    private static byte[] serialise(FileStatusException original) throws IOException {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
                out.writeObject(original);
            }
            return buffer.toByteArray();
        }
    }

    /**
     * Reads an exception back from the bytes produced by {@link #serialise(FileStatusException)}.
     *
     * @param serialised the serialised form
     * @return the restored exception
     * @throws IOException            if the in-memory stream rejects the read
     * @throws ClassNotFoundException if the serialised type cannot be resolved
     */
    private static FileStatusException deserialise(byte[] serialised)
            throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream source = new ByteArrayInputStream(serialised)) {
            try (ObjectInputStream in = new ObjectInputStream(source)) {
                return (FileStatusException) in.readObject();
            }
        }
    }
}
