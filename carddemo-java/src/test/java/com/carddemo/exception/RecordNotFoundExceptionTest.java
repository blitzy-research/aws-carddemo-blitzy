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
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RecordNotFoundException}.
 *
 * <p>A plain JUnit 5 test: no application context, no container, no mock. The
 * subject is a value-carrying exception, so everything it promises can be
 * proved by construction, accessor round-tripping, message inspection and one
 * serialisation round-trip.</p>
 *
 * <p><strong>What the legacy estate actually does with status 23</strong></p>
 *
 * <p>The record-not-found status is the two-character value {@code "23"}. It
 * occurs at <strong>six</strong> sites across three batch programs, and the
 * single most important fact about those six is that they do <em>not</em> agree
 * on whether a miss is a failure. Three continue and three abend, so this
 * exception type is deliberately <em>not</em> the universal answer to a
 * not-found.</p>
 *
 * <p><em>Three sites treat the miss as a non-error and carry on.</em></p>
 *
 * <ol>
 *   <li>{@code CBACT04C} paragraph {@code 1200-GET-INTEREST-RATE}, lines 415 to
 *       440. Its invalid-key handler emits a missing-record diagnostic and a
 *       retry-with-default diagnostic, and then line 422 folds {@code "23"} in
 *       <em>with</em> {@code "00"} as the non-error outcome, giving the coarse
 *       result 0. Only afterwards does line 436 test for {@code "23"}
 *       specifically, substitute the default group key and perform the
 *       default-rate lookup.</li>
 *   <li>The same program's paragraph {@code 1200-A-GET-DEFAULT-INT-RATE},
 *       beginning at line 443, accepts <strong>only</strong> {@code "00"}: a
 *       second consecutive miss becomes the coarse error value 12, produces an
 *       error diagnostic and abends. The lookup is therefore retried
 *       <strong>exactly once</strong> and <strong>there is no third
 *       fallback</strong>.</li>
 *   <li>{@code CBTRN02C} paragraph {@code 2700-UPDATE-TCATBAL}, lines 470 to
 *       501. A create flag is initialised to no, the invalid-key handler emits a
 *       not-found-and-creating diagnostic and raises the flag to yes, line 481
 *       folds {@code "23"} in with {@code "00"} as the non-error outcome, and
 *       the flag then selects create-the-row over update-the-row. A missing
 *       category-balance row is <strong>not an error and not a reject</strong>:
 *       the row is <strong>created</strong>.</li>
 * </ol>
 *
 * <p><em>Three sites treat the miss as terminal.</em> {@code CBTRN03C}
 * paragraphs {@code 1500-A-LOOKUP-XREF} (line 484), {@code
 * 1500-B-LOOKUP-TRANTYPE} (line 494) and {@code 1500-C-LOOKUP-TRANCATG} (line
 * 504) each assign 23 to the display status - at lines 488, 498 and 508
 * respectively - format it, and then abend. Note that this program assigns the
 * value as an unquoted numeric literal while the two programs above compare the
 * quoted two-character literal, which is one reason the Java constant is
 * declared as text: both renderings normalise onto a single value.</p>
 *
 * <p><strong>The coarse outcome</strong></p>
 *
 * <p>Every batch program normalises a raw two-byte file status into a coarse
 * outcome before it branches: {@code "00"} becomes 0, {@code "10"} becomes 16
 * and anything else becomes 12, which is the error arm. The canonical
 * expression of that cascade is {@code CBACT01C} lines 94 to 103, immediately
 * followed by the all-clear test at line 104. The coarse variable is load
 * bearing across the whole estate - 223 source lines in the program tree
 * reference it - which is why the target keeps a raw status and a tri-state
 * outcome rather than collapsing both into one enumeration.</p>
 *
 * <p><strong>The boundary this test file exists to pin down</strong></p>
 *
 * <p>{@code RecordNotFoundException} is raised <strong>only where the legacy
 * code treats a miss as a genuine failure</strong>. Where the legacy code folds
 * {@code "23"} in with {@code "00"} and continues - the disclosure-group default
 * fallback and the category-balance create-on-missing path - the Java service
 * returns an empty {@link Optional} or raises a create flag and throws
 * <strong>nothing at all</strong>. The fallback and create-on-missing
 * <em>logic</em> is not tested here; it belongs to the interest-calculation and
 * transaction-posting service tests. This file records the boundary so that no
 * downstream service throws where the source continued.</p>
 *
 * <p><strong>Credential handling</strong></p>
 *
 * <p>A not-found on the user-security record carries the user identifier and
 * nothing else. The legacy user record holds an eight-character cleartext
 * credential, and no credential value may ever be passed as a key, appear in a
 * message, or appear anywhere in this file. The test below proves the message is
 * composed from the supplied context alone.</p>
 *
 * <p>Provenance: the legacy estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Program names,
 * paragraph names, line numbers, field widths and status codes are cited as
 * metadata; no COBOL source text is reproduced in this module.</p>
 */
@DisplayName("RecordNotFoundException")
class RecordNotFoundExceptionTest {

    /**
     * The four-character rendering that must never reach a diagnostic. It is
     * used exclusively in negative assertions: an accessor must never equal it
     * and a message must never contain it. A normalised absent value is the
     * empty string.
     */
    private static final String ABSENT_VALUE_RENDERING = "null";

    /** Account record type name. */
    private static final String ACCOUNT_RECORD_TYPE = "ACCOUNT";

    /**
     * An account identifier at its legacy width of eleven characters, with
     * leading zeros. The legacy key is a character substring of the record
     * image, never an integer, so the zeros are significant.
     */
    private static final String ACCOUNT_KEY = "00000000011";

    /** Legacy data-definition name of the account resource. */
    private static final String ACCOUNT_RESOURCE = "ACCTDAT";

    /** Card record type name. */
    private static final String CARD_RECORD_TYPE = "CARD";

    /**
     * A sixteen-character card number stand-in. Every digit is a nine so the
     * value is obviously synthetic and cannot be mistaken for a real account
     * number.
     */
    private static final String SYNTHETIC_CARD_KEY = "9999999999999999";

    /** Legacy data-definition name of the card resource. */
    private static final String CARD_RESOURCE = "CARDDAT";

    /** Disclosure-group record type name. */
    private static final String DISCLOSURE_GROUP_RECORD_TYPE = "DISCLOSURE_GROUP";

    /**
     * A disclosure-group composite key at its full sixteen-character width: a
     * group code padded to ten characters, a two-character transaction type and
     * a four-character transaction category. The embedded padding is part of the
     * key and must survive untouched.
     */
    private static final String DISCLOSURE_GROUP_KEY = "ZEROAPR   010005";

    /** Legacy data-definition name of the disclosure-group resource. */
    private static final String DISCLOSURE_GROUP_RESOURCE = "DISCGRP";

    /**
     * A group code alone, padded to the full ten-character width of its field.
     * Used to prove that trailing padding is never trimmed.
     */
    private static final String PADDED_GROUP_CODE = "A         ";

    /** Transaction-category-balance record type name. */
    private static final String CATEGORY_BALANCE_RECORD_TYPE = "TRANSACTION_CATEGORY_BALANCE";

    /**
     * A category-balance composite key at its full seventeen-character width: an
     * eleven-character account identifier, a two-character transaction type and
     * a four-character transaction category.
     */
    private static final String CATEGORY_BALANCE_KEY = "00000000011010005";

    /** Legacy data-definition name of the category-balance resource. */
    private static final String CATEGORY_BALANCE_RESOURCE = "TCATBALF";

    /** Card cross-reference record type name. */
    private static final String CARD_XREF_RECORD_TYPE = "CARD_XREF";

    /** Legacy data-definition name of the cross-reference resource. */
    private static final String CARD_XREF_RESOURCE = "CARDXREF";

    /** Transaction-type record type name. */
    private static final String TRANSACTION_TYPE_RECORD_TYPE = "TRANSACTION_TYPE";

    /** A transaction-type code at its legacy width of two characters. */
    private static final String TRANSACTION_TYPE_KEY = "01";

    /** Legacy data-definition name of the transaction-type resource. */
    private static final String TRANSACTION_TYPE_RESOURCE = "TRANTYPE";

    /** Transaction-category record type name. */
    private static final String TRANSACTION_CATEGORY_RECORD_TYPE = "TRANSACTION_CATEGORY";

    /**
     * A transaction-category composite key: a two-character transaction type
     * followed by a four-character category code.
     */
    private static final String TRANSACTION_CATEGORY_KEY = "010005";

    /** Legacy data-definition name of the transaction-category resource. */
    private static final String TRANSACTION_CATEGORY_RESOURCE = "TRANCATG";

    /** User-security record type name. */
    private static final String USER_SECURITY_RECORD_TYPE = "USER_SECURITY";

    /**
     * A neutral, obviously synthetic user identifier at the legacy identifier
     * width of eight characters. It is an identifier and nothing else; a
     * credential value is never a valid key.
     */
    private static final String USER_SECURITY_IDENTIFIER = "TESTUSR1";

    /** Legacy data-definition name of the user-security resource. */
    private static final String USER_SECURITY_RESOURCE = "USRSEC";

    /** Diagnostic text for a chained cause. Deliberately terse and factual. */
    private static final String CAUSE_DETAIL = "indexed read failed";

    @Nested
    @DisplayName("the published record-not-found status constant")
    class StatusConstant {

        @Test
        @DisplayName("is the two-character text 23, at the legacy two-byte status width")
        void statusConstantIsTheTwoByteRecordNotFoundCode() {
            assertThat(RecordNotFoundException.STATUS_RECORD_NOT_FOUND)
                    .isNotNull()
                    .isEqualTo("23")
                    .hasSize(2);
        }

        @Test
        @DisplayName("keeps its leading digit and carries no padding, because a status is text and not a number")
        void statusConstantIsTextAndIsNeitherPaddedNorTrimmed() {
            String status = RecordNotFoundException.STATUS_RECORD_NOT_FOUND;

            // A two-byte status is compared character by character in the legacy
            // programs. Holding it as text is what lets the quoted comparison in
            // the interest and posting programs and the unquoted numeric
            // assignment in the reporting program normalise onto one value, and
            // it is what keeps a leading zero - as in the all-clear status -
            // from being lost.
            assertThat(status).startsWith("2").endsWith("3").isNotBlank();
            assertThat(status).isEqualTo(status.strip());
        }
    }

    @Nested
    @DisplayName("the no-argument constructor")
    class NoArgumentConstructor {

        @Test
        @DisplayName("constructs without throwing")
        void constructsWithoutThrowing() {
            assertThatCode(RecordNotFoundException::new).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("is usable as the supplier method reference the repository layer relies on, "
                + "as in findById(id).orElseThrow(RecordNotFoundException::new)")
        void isUsableAsASupplierMethodReference() {
            // This assertion is a compile-time proof as much as a runtime one:
            // if the no-argument constructor were removed, the method reference
            // below would stop compiling, which is a stronger signal than any
            // runtime check. The type witness is explicit so that no inference
            // diagnostic can fire under a warnings-as-errors build.
            assertThatThrownBy(() -> Optional.<String>empty().orElseThrow(RecordNotFoundException::new))
                    .isInstanceOf(RecordNotFoundException.class)
                    .isInstanceOf(RuntimeException.class)
                    .hasNoCause();
        }

        @Test
        @DisplayName("normalises every absent context value to the empty string and never renders it as text")
        void normalisesEveryAbsentContextValue() {
            RecordNotFoundException thrown = new RecordNotFoundException();

            assertThat(thrown.recordType()).isNotNull().isNotEqualTo(ABSENT_VALUE_RENDERING).isEmpty();
            assertThat(thrown.key()).isNotNull().isNotEqualTo(ABSENT_VALUE_RENDERING).isEmpty();
            assertThat(thrown.resourceName()).isNotNull().isNotEqualTo(ABSENT_VALUE_RENDERING).isEmpty();
            assertThat(thrown.getMessage()).isNotNull().doesNotContain(ABSENT_VALUE_RENDERING);
            assertThat(thrown).hasNoCause();
        }
    }

    @Nested
    @DisplayName("the context-carrying constructors")
    class ContextCarryingConstructors {

        @Test
        @DisplayName("(recordType, key) round-trips both values, leaves the resource name empty and chains no cause")
        void twoArgumentConstructorRoundTripsRecordTypeAndKey() {
            RecordNotFoundException thrown = new RecordNotFoundException(ACCOUNT_RECORD_TYPE, ACCOUNT_KEY);

            assertThat(thrown.recordType()).isEqualTo(ACCOUNT_RECORD_TYPE);
            assertThat(thrown.key()).isEqualTo(ACCOUNT_KEY);
            assertThat(thrown.resourceName()).isNotNull().isEmpty();
            assertThat(thrown).hasNoCause();
            assertThat(thrown.getMessage()).contains(ACCOUNT_RECORD_TYPE, ACCOUNT_KEY);
        }

        @Test
        @DisplayName("(recordType, key, resourceName) round-trips all three values and chains no cause")
        void threeArgumentConstructorRoundTripsTheResourceNameToo() {
            RecordNotFoundException thrown =
                    new RecordNotFoundException(CARD_RECORD_TYPE, SYNTHETIC_CARD_KEY, CARD_RESOURCE);

            assertThat(thrown.recordType()).isEqualTo(CARD_RECORD_TYPE);
            assertThat(thrown.key()).isEqualTo(SYNTHETIC_CARD_KEY);
            assertThat(thrown.resourceName()).isEqualTo(CARD_RESOURCE);
            assertThat(thrown).hasNoCause();
            assertThat(thrown.getMessage()).contains(CARD_RECORD_TYPE, SYNTHETIC_CARD_KEY, CARD_RESOURCE);
        }

        @Test
        @DisplayName("(recordType, key, resourceName, cause) chains the supplied cause by identity")
        void canonicalConstructorChainsTheSuppliedCause() {
            IOException cause = new IOException(CAUSE_DETAIL);

            RecordNotFoundException thrown = new RecordNotFoundException(
                    CATEGORY_BALANCE_RECORD_TYPE, CATEGORY_BALANCE_KEY, CATEGORY_BALANCE_RESOURCE, cause);

            assertThat(thrown.recordType()).isEqualTo(CATEGORY_BALANCE_RECORD_TYPE);
            assertThat(thrown.key()).isEqualTo(CATEGORY_BALANCE_KEY);
            assertThat(thrown.resourceName()).isEqualTo(CATEGORY_BALANCE_RESOURCE);
            assertThat(thrown.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("(recordType, key, resourceName, cause) accepts an absent cause and stays uncaused")
        void canonicalConstructorAcceptsAnAbsentCause() {
            RecordNotFoundException thrown = new RecordNotFoundException(
                    TRANSACTION_TYPE_RECORD_TYPE, TRANSACTION_TYPE_KEY, TRANSACTION_TYPE_RESOURCE, null);

            assertThat(thrown.recordType()).isEqualTo(TRANSACTION_TYPE_RECORD_TYPE);
            assertThat(thrown.key()).isEqualTo(TRANSACTION_TYPE_KEY);
            assertThat(thrown.resourceName()).isEqualTo(TRANSACTION_TYPE_RESOURCE);
            assertThat(thrown).hasNoCause();
        }
    }

    @Nested
    @DisplayName("absent-value normalisation")
    class AbsentValueNormalisation {

        @Test
        @DisplayName("(recordType, key) normalises an absent record type while the key survives")
        void twoArgumentConstructorNormalisesAnAbsentRecordType() {
            RecordNotFoundException thrown = new RecordNotFoundException(null, ACCOUNT_KEY);

            assertThat(thrown.recordType()).isNotNull().isNotEqualTo(ABSENT_VALUE_RENDERING).isEmpty();
            assertThat(thrown.key()).isEqualTo(ACCOUNT_KEY);
            assertThat(thrown.getMessage()).doesNotContain(ABSENT_VALUE_RENDERING);
        }

        @Test
        @DisplayName("(recordType, key) normalises an absent key while the record type survives")
        void twoArgumentConstructorNormalisesAnAbsentKey() {
            RecordNotFoundException thrown = new RecordNotFoundException(ACCOUNT_RECORD_TYPE, null);

            assertThat(thrown.recordType()).isEqualTo(ACCOUNT_RECORD_TYPE);
            assertThat(thrown.key()).isNotNull().isNotEqualTo(ABSENT_VALUE_RENDERING).isEmpty();
            assertThat(thrown.getMessage()).doesNotContain(ABSENT_VALUE_RENDERING);
        }

        @Test
        @DisplayName("(recordType, key) normalises both positions when both are absent")
        void twoArgumentConstructorNormalisesBothPositions() {
            RecordNotFoundException thrown = new RecordNotFoundException(null, null);

            assertThat(thrown.recordType()).isNotNull().isNotEqualTo(ABSENT_VALUE_RENDERING).isEmpty();
            assertThat(thrown.key()).isNotNull().isNotEqualTo(ABSENT_VALUE_RENDERING).isEmpty();
            assertThat(thrown.resourceName()).isNotNull().isEmpty();
            assertThat(thrown.getMessage()).doesNotContain(ABSENT_VALUE_RENDERING);
        }

        @Test
        @DisplayName("(recordType, key, resourceName) normalises each position individually")
        void threeArgumentConstructorNormalisesEachPositionIndividually() {
            RecordNotFoundException absentRecordType =
                    new RecordNotFoundException(null, DISCLOSURE_GROUP_KEY, DISCLOSURE_GROUP_RESOURCE);
            RecordNotFoundException absentKey =
                    new RecordNotFoundException(DISCLOSURE_GROUP_RECORD_TYPE, null, DISCLOSURE_GROUP_RESOURCE);
            RecordNotFoundException absentResource =
                    new RecordNotFoundException(DISCLOSURE_GROUP_RECORD_TYPE, DISCLOSURE_GROUP_KEY, null);

            assertThat(absentRecordType.recordType()).isNotNull().isNotEqualTo(ABSENT_VALUE_RENDERING).isEmpty();
            assertThat(absentRecordType.key()).isEqualTo(DISCLOSURE_GROUP_KEY);
            assertThat(absentRecordType.resourceName()).isEqualTo(DISCLOSURE_GROUP_RESOURCE);

            assertThat(absentKey.recordType()).isEqualTo(DISCLOSURE_GROUP_RECORD_TYPE);
            assertThat(absentKey.key()).isNotNull().isNotEqualTo(ABSENT_VALUE_RENDERING).isEmpty();
            assertThat(absentKey.resourceName()).isEqualTo(DISCLOSURE_GROUP_RESOURCE);

            assertThat(absentResource.recordType()).isEqualTo(DISCLOSURE_GROUP_RECORD_TYPE);
            assertThat(absentResource.key()).isEqualTo(DISCLOSURE_GROUP_KEY);
            assertThat(absentResource.resourceName()).isNotNull().isNotEqualTo(ABSENT_VALUE_RENDERING).isEmpty();

            assertThat(absentRecordType.getMessage()).doesNotContain(ABSENT_VALUE_RENDERING);
            assertThat(absentKey.getMessage()).doesNotContain(ABSENT_VALUE_RENDERING);
            assertThat(absentResource.getMessage()).doesNotContain(ABSENT_VALUE_RENDERING);
        }

        @Test
        @DisplayName("(recordType, key, resourceName) normalises all three positions when all three are absent")
        void threeArgumentConstructorNormalisesAllThreePositions() {
            RecordNotFoundException thrown = new RecordNotFoundException(null, null, null);

            assertThat(thrown.recordType()).isNotNull().isNotEqualTo(ABSENT_VALUE_RENDERING).isEmpty();
            assertThat(thrown.key()).isNotNull().isNotEqualTo(ABSENT_VALUE_RENDERING).isEmpty();
            assertThat(thrown.resourceName()).isNotNull().isNotEqualTo(ABSENT_VALUE_RENDERING).isEmpty();
            assertThat(thrown.getMessage()).doesNotContain(ABSENT_VALUE_RENDERING);
        }

        @Test
        @DisplayName("(recordType, key, resourceName, cause) normalises every position when everything is absent")
        void canonicalConstructorNormalisesEveryPosition() {
            RecordNotFoundException thrown = new RecordNotFoundException(null, null, null, null);

            assertThat(thrown.recordType()).isNotNull().isNotEqualTo(ABSENT_VALUE_RENDERING).isEmpty();
            assertThat(thrown.key()).isNotNull().isNotEqualTo(ABSENT_VALUE_RENDERING).isEmpty();
            assertThat(thrown.resourceName()).isNotNull().isNotEqualTo(ABSENT_VALUE_RENDERING).isEmpty();
            assertThat(thrown.getMessage()).isNotNull().doesNotContain(ABSENT_VALUE_RENDERING);
            assertThat(thrown).hasNoCause();
        }

        @Test
        @DisplayName("a cause is chained even when every context value is absent")
        void canonicalConstructorChainsACauseWithoutAnyContext() {
            IOException cause = new IOException(CAUSE_DETAIL);

            RecordNotFoundException thrown = new RecordNotFoundException(null, null, null, cause);

            assertThat(thrown.getCause()).isSameAs(cause);
            assertThat(thrown.getMessage()).doesNotContain(ABSENT_VALUE_RENDERING);
        }
    }

    @Nested
    @DisplayName("the detail message")
    class DetailMessage {

        @Test
        @DisplayName("names the record type and the key that were searched for")
        void namesTheRecordTypeAndTheKey() {
            RecordNotFoundException thrown = new RecordNotFoundException(
                    CATEGORY_BALANCE_RECORD_TYPE, CATEGORY_BALANCE_KEY, CATEGORY_BALANCE_RESOURCE);

            // Containment rather than equality: production owns the phrasing,
            // this test owns the content that must be present in it.
            assertThat(thrown.getMessage())
                    .contains(CATEGORY_BALANCE_RECORD_TYPE)
                    .contains(CATEGORY_BALANCE_KEY)
                    .contains(CATEGORY_BALANCE_RESOURCE);
        }

        @Test
        @DisplayName("names the resource only when a resource was supplied")
        void namesTheResourceOnlyWhenOneWasSupplied() {
            RecordNotFoundException withResource =
                    new RecordNotFoundException(ACCOUNT_RECORD_TYPE, ACCOUNT_KEY, ACCOUNT_RESOURCE);
            RecordNotFoundException withoutResource =
                    new RecordNotFoundException(ACCOUNT_RECORD_TYPE, ACCOUNT_KEY);

            assertThat(withResource.getMessage()).contains(ACCOUNT_RESOURCE);
            assertThat(withoutResource.getMessage())
                    .contains(ACCOUNT_RECORD_TYPE, ACCOUNT_KEY)
                    .doesNotContain(ACCOUNT_RESOURCE);
        }

        @Test
        @DisplayName("carries no operator advice and no remediation wording")
        void carriesNoOperatorAdviceAndNoRemediationWording() {
            RecordNotFoundException thrown = new RecordNotFoundException(
                    CARD_XREF_RECORD_TYPE, SYNTHETIC_CARD_KEY, CARD_XREF_RESOURCE);

            // The legacy diagnostics are terse and factual: each names the
            // condition and, on a terminal path, the raw status. None advises an
            // operator what to do next. Inventing advice here would be feature
            // expansion, so the message must stay free of it.
            assertThat(thrown.getMessage())
                    .doesNotContainIgnoringCase("please", "try again", "contact", "retry");
        }

        @Test
        @DisplayName("makes no claim that the row will be created or that a fallback will be attempted, "
                + "because both are caller behaviours and not properties of this exception")
        void makesNoClaimAboutCreationOrFallback() {
            RecordNotFoundException disclosureGroupMiss = new RecordNotFoundException(
                    DISCLOSURE_GROUP_RECORD_TYPE, DISCLOSURE_GROUP_KEY, DISCLOSURE_GROUP_RESOURCE);
            RecordNotFoundException categoryBalanceMiss = new RecordNotFoundException(
                    CATEGORY_BALANCE_RECORD_TYPE, CATEGORY_BALANCE_KEY, CATEGORY_BALANCE_RESOURCE);

            // These two are exactly the sites at which the legacy code folds the
            // not-found status in with the all-clear status and carries on: one
            // substitutes a default group and retries once, the other creates the
            // missing row. Neither continuation is promised by the exception,
            // because neither is the exception's to promise.
            assertThat(disclosureGroupMiss.getMessage())
                    .doesNotContainIgnoringCase("creat", "fallback", "substitut", "will be");
            assertThat(categoryBalanceMiss.getMessage())
                    .doesNotContainIgnoringCase("creat", "fallback", "substitut", "will be");
        }

        @Test
        @DisplayName("renders identically for identical context, so a diagnostic is stable across call sites")
        void rendersIdenticallyForIdenticalContext() {
            RecordNotFoundException first =
                    new RecordNotFoundException(ACCOUNT_RECORD_TYPE, ACCOUNT_KEY, ACCOUNT_RESOURCE);
            RecordNotFoundException second =
                    new RecordNotFoundException(ACCOUNT_RECORD_TYPE, ACCOUNT_KEY, ACCOUNT_RESOURCE);

            assertThat(first.getMessage()).isEqualTo(second.getMessage());
            assertThat(first).isNotSameAs(second);
        }
    }

    @Nested
    @DisplayName("keys are opaque character strings")
    class OpaqueKeys {

        @Test
        @DisplayName("a sixteen-character composite key round-trips byte-identically: not parsed, not split, not re-ordered")
        void compositeKeyRoundTripsByteIdentically() {
            RecordNotFoundException thrown = new RecordNotFoundException(
                    DISCLOSURE_GROUP_RECORD_TYPE, DISCLOSURE_GROUP_KEY, DISCLOSURE_GROUP_RESOURCE);

            // Three of the eleven legacy record layouts carry composite keys, so
            // a composite key is the ordinary case rather than an edge case.
            assertThat(thrown.key()).isEqualTo(DISCLOSURE_GROUP_KEY).hasSize(16);
            assertThat(thrown.getMessage()).contains(DISCLOSURE_GROUP_KEY);
        }

        @Test
        @DisplayName("a seventeen-character composite key round-trips byte-identically as well")
        void longerCompositeKeyRoundTripsByteIdentically() {
            RecordNotFoundException thrown = new RecordNotFoundException(
                    CATEGORY_BALANCE_RECORD_TYPE, CATEGORY_BALANCE_KEY, CATEGORY_BALANCE_RESOURCE);

            assertThat(thrown.key()).isEqualTo(CATEGORY_BALANCE_KEY).hasSize(17);
            assertThat(thrown.getMessage()).contains(CATEGORY_BALANCE_KEY);
        }

        @Test
        @DisplayName("leading zeros in a numeric-looking key survive, because a legacy key is a character "
                + "substring of the record image and never an integer")
        void leadingZerosArePreserved() {
            RecordNotFoundException thrown =
                    new RecordNotFoundException(ACCOUNT_RECORD_TYPE, ACCOUNT_KEY, ACCOUNT_RESOURCE);

            assertThat(thrown.key()).isEqualTo(ACCOUNT_KEY).hasSize(11).startsWith("0");
            assertThat(thrown.getMessage()).contains(ACCOUNT_KEY);
        }

        @Test
        @DisplayName("trailing padding in a key is never trimmed, because fixed-width padding is significant")
        void trailingPaddingIsNeverTrimmed() {
            RecordNotFoundException thrown = new RecordNotFoundException(
                    DISCLOSURE_GROUP_RECORD_TYPE, PADDED_GROUP_CODE, DISCLOSURE_GROUP_RESOURCE);

            assertThat(thrown.key()).isEqualTo(PADDED_GROUP_CODE).hasSize(10).endsWith(" ");
        }

        @Test
        @DisplayName("the record type is carried verbatim and is never reformatted")
        void recordTypeIsCarriedVerbatim() {
            RecordNotFoundException thrown = new RecordNotFoundException(
                    CATEGORY_BALANCE_RECORD_TYPE, CATEGORY_BALANCE_KEY, CATEGORY_BALANCE_RESOURCE);

            assertThat(thrown.recordType()).isEqualTo(CATEGORY_BALANCE_RECORD_TYPE);
            assertThat(thrown.resourceName()).isEqualTo(CATEGORY_BALANCE_RESOURCE);
        }
    }

    @Nested
    @DisplayName("a user-security miss")
    class UserSecurityMiss {

        @Test
        @DisplayName("carries the eight-character user identifier and nothing else")
        void carriesTheUserIdentifierOnly() {
            RecordNotFoundException thrown = new RecordNotFoundException(
                    USER_SECURITY_RECORD_TYPE, USER_SECURITY_IDENTIFIER, USER_SECURITY_RESOURCE);

            assertThat(thrown.key()).isEqualTo(USER_SECURITY_IDENTIFIER).hasSize(8);
            assertThat(thrown.getMessage()).contains(USER_SECURITY_IDENTIFIER);

            // Exact equality against a message composed solely from the three
            // supplied context values is the strongest available proof that the
            // diagnostic carries nothing beyond them. The legacy user record
            // holds an eight-character cleartext credential in a field adjacent
            // to the identifier; a credential is never a valid key, so it can
            // never reach this message.
            String composedFromSuppliedContextOnly = "RecordNotFound[recordType=" + USER_SECURITY_RECORD_TYPE
                    + ", key=" + USER_SECURITY_IDENTIFIER
                    + ", resourceName=" + USER_SECURITY_RESOURCE + "]";
            assertThat(thrown.getMessage()).isEqualTo(composedFromSuppliedContextOnly);
            assertThat(thrown.getMessage())
                    .doesNotContainIgnoringCase("secret", "credential", "pwd", "token", "hash");
        }
    }

    @Nested
    @DisplayName("the non-error boundary")
    class NonErrorBoundary {

        @Test
        @DisplayName("is unchecked, so a caller that must continue can catch it and proceed: the two legacy "
                + "sites that fold status 23 in with 00 carry on rather than unwinding the job")
        void isUncheckedSoACallerCanCatchItAndContinue() {
            RecordNotFoundException probe = new RecordNotFoundException();
            assertThat(probe).isInstanceOf(RuntimeException.class);

            String recoveredKey = "";
            try {
                throw new RecordNotFoundException(
                        CATEGORY_BALANCE_RECORD_TYPE, CATEGORY_BALANCE_KEY, CATEGORY_BALANCE_RESOURCE);
            } catch (RecordNotFoundException caught) {
                // This is the shape of the create-on-missing path: the caller
                // absorbs the miss and goes on to insert the row. In the target
                // the repository finder returns an empty Optional on this path
                // and the exception is never constructed at all.
                recoveredKey = caught.key();
            }

            assertThat(recoveredKey).isEqualTo(CATEGORY_BALANCE_KEY);
        }

        @Test
        @DisplayName("carries no retry budget and no fallback state: the disclosure-group lookup is retried "
                + "exactly once by its calling service and there is no third fallback")
        void carriesNoRetryBudgetAndNoFallbackState() {
            RecordNotFoundException thrown = new RecordNotFoundException(
                    DISCLOSURE_GROUP_RECORD_TYPE, DISCLOSURE_GROUP_KEY, DISCLOSURE_GROUP_RESOURCE);

            // The whole public surface is the three context accessors, so nothing
            // here can be mistaken for a retry counter, and the message says
            // nothing about attempts. Modelling the retry here would invite a
            // second fallback layer that the source does not have: the default
            // lookup accepts only the all-clear status and abends otherwise.
            assertThat(thrown.recordType()).isEqualTo(DISCLOSURE_GROUP_RECORD_TYPE);
            assertThat(thrown.key()).isEqualTo(DISCLOSURE_GROUP_KEY);
            assertThat(thrown.resourceName()).isEqualTo(DISCLOSURE_GROUP_RESOURCE);
            assertThat(thrown.getMessage()).doesNotContainIgnoringCase("attempt", "again", "once", "second");
        }

        @Test
        @DisplayName("identifies the resource searched at each of the three terminal sites, where the legacy "
                + "assigns status 23 and then abends")
        void identifiesTheResourceSearchedAtEachTerminalSite() {
            RecordNotFoundException crossReferenceMiss = new RecordNotFoundException(
                    CARD_XREF_RECORD_TYPE, SYNTHETIC_CARD_KEY, CARD_XREF_RESOURCE);
            RecordNotFoundException transactionTypeMiss = new RecordNotFoundException(
                    TRANSACTION_TYPE_RECORD_TYPE, TRANSACTION_TYPE_KEY, TRANSACTION_TYPE_RESOURCE);
            RecordNotFoundException transactionCategoryMiss = new RecordNotFoundException(
                    TRANSACTION_CATEGORY_RECORD_TYPE, TRANSACTION_CATEGORY_KEY, TRANSACTION_CATEGORY_RESOURCE);

            assertThat(crossReferenceMiss.resourceName()).isEqualTo(CARD_XREF_RESOURCE);
            assertThat(transactionTypeMiss.resourceName()).isEqualTo(TRANSACTION_TYPE_RESOURCE);
            assertThat(transactionCategoryMiss.resourceName()).isEqualTo(TRANSACTION_CATEGORY_RESOURCE);

            assertThat(crossReferenceMiss.key()).isEqualTo(SYNTHETIC_CARD_KEY);
            assertThat(transactionTypeMiss.key()).isEqualTo(TRANSACTION_TYPE_KEY).hasSize(2);
            assertThat(transactionCategoryMiss.key()).isEqualTo(TRANSACTION_CATEGORY_KEY).hasSize(6);

            // The exception neither logs nor abends. In the legacy flow the
            // diagnostic and the raw status are displayed first and the abend
            // follows as a separate step, so the caller keeps that ordering.
            assertThat(crossReferenceMiss).isNotInstanceOf(AbendException.class);
        }
    }

    @Nested
    @DisplayName("type identity")
    class TypeIdentity {

        @Test
        @DisplayName("extends RuntimeException directly")
        void extendsRuntimeExceptionDirectly() {
            assertThat(RecordNotFoundException.class.getSuperclass()).isEqualTo(RuntimeException.class);
            assertThat(new RecordNotFoundException()).isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("is neither a FileStatusException, because at three of its six legacy sites the miss is "
                + "not a file error at all, nor an AbendException, because the abend is a separate later step")
        void isNeitherAFileStatusExceptionNorAnAbendException() {
            // Plain Class API on class literals: no reflective member lookup is
            // performed here, and the module's production reflection budget of
            // zero is untouched because this is a test source.
            assertThat(FileStatusException.class.isAssignableFrom(RecordNotFoundException.class)).isFalse();
            assertThat(AbendException.class.isAssignableFrom(RecordNotFoundException.class)).isFalse();

            assertThat(new RecordNotFoundException())
                    .isNotInstanceOf(FileStatusException.class)
                    .isNotInstanceOf(AbendException.class);
        }
    }

    @Nested
    @DisplayName("serialisation")
    class Serialisation {

        @Test
        @DisplayName("declares its serial version identifier explicitly as 1 rather than relying on a computed hash")
        void serialVersionIdentifierIsDeclaredExplicitly() {
            ObjectStreamClass descriptor = ObjectStreamClass.lookup(RecordNotFoundException.class);

            assertThat(descriptor).isNotNull();
            assertThat(descriptor.getSerialVersionUID()).isEqualTo(1L);
        }

        @Test
        @DisplayName("survives a serialisation round-trip with its context, its message and its cause intact")
        void survivesASerialisationRoundTrip() throws IOException, ClassNotFoundException {
            IOException cause = new IOException(CAUSE_DETAIL);
            RecordNotFoundException original = new RecordNotFoundException(
                    CATEGORY_BALANCE_RECORD_TYPE, CATEGORY_BALANCE_KEY, CATEGORY_BALANCE_RESOURCE, cause);

            byte[] encoded;
            try (ByteArrayOutputStream sink = new ByteArrayOutputStream();
                 ObjectOutputStream encoder = new ObjectOutputStream(sink)) {
                encoder.writeObject(original);
                encoder.flush();
                encoded = sink.toByteArray();
            }

            RecordNotFoundException restored;
            try (ByteArrayInputStream source = new ByteArrayInputStream(encoded);
                 ObjectInputStream decoder = new ObjectInputStream(source)) {
                restored = (RecordNotFoundException) decoder.readObject();
            }

            assertThat(restored).isNotSameAs(original);
            assertThat(restored.recordType()).isEqualTo(CATEGORY_BALANCE_RECORD_TYPE);
            assertThat(restored.key()).isEqualTo(CATEGORY_BALANCE_KEY);
            assertThat(restored.resourceName()).isEqualTo(CATEGORY_BALANCE_RESOURCE);
            assertThat(restored.getMessage()).isEqualTo(original.getMessage());
            assertThat(restored.getCause()).isInstanceOf(IOException.class).hasMessage(CAUSE_DETAIL);
        }

        @Test
        @DisplayName("survives a serialisation round-trip when it carries no context at all")
        void survivesASerialisationRoundTripWithoutContext() throws IOException, ClassNotFoundException {
            RecordNotFoundException original = new RecordNotFoundException();

            byte[] encoded;
            try (ByteArrayOutputStream sink = new ByteArrayOutputStream();
                 ObjectOutputStream encoder = new ObjectOutputStream(sink)) {
                encoder.writeObject(original);
                encoder.flush();
                encoded = sink.toByteArray();
            }

            RecordNotFoundException restored;
            try (ByteArrayInputStream source = new ByteArrayInputStream(encoded);
                 ObjectInputStream decoder = new ObjectInputStream(source)) {
                restored = (RecordNotFoundException) decoder.readObject();
            }

            assertThat(restored.recordType()).isNotNull().isEmpty();
            assertThat(restored.key()).isNotNull().isEmpty();
            assertThat(restored.resourceName()).isNotNull().isEmpty();
            assertThat(restored.getMessage()).isEqualTo(original.getMessage()).doesNotContain(ABSENT_VALUE_RENDERING);
            assertThat(restored).hasNoCause();
        }
    }
}
