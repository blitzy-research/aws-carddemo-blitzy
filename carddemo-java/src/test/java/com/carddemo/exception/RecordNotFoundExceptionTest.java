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
 * <p>The published status constant is the two-character text {@code 23} at the legacy two-byte status
 * width, and it stays text: as a number it would lose its leading digit and stop matching the status
 * the legacy programs compare. The no-argument constructor exists so the repository layer can use a
 * method reference as its not-found supplier.
 *
 * <p>Absent context is normalised to the empty string and never rendered as a null-looking word, and
 * the rendered form carries no business identifier - this exception reaches a diagnostic, so a key in
 * its text would reach a log.
 */
@DisplayName("RecordNotFoundException")
class RecordNotFoundExceptionTest {
    private static final String ABSENT_VALUE_RENDERING = "null";

    private static final String KEY_PLACEHOLDER = "***REDACTED***";

    private static final String ACCOUNT_RECORD_TYPE = "ACCOUNT";

    private static final String ACCOUNT_KEY = "00000000011";

    private static final String ACCOUNT_RESOURCE = "ACCTDAT";

    private static final String CARD_RECORD_TYPE = "CARD";

    private static final String SYNTHETIC_CARD_KEY = "9999999999999999";

    private static final String CARD_RESOURCE = "CARDDAT";

    private static final String DISCLOSURE_GROUP_RECORD_TYPE = "DISCLOSURE_GROUP";

    private static final String DISCLOSURE_GROUP_KEY = "ZEROAPR   010005";

    private static final String DISCLOSURE_GROUP_RESOURCE = "DISCGRP";

    private static final String PADDED_GROUP_CODE = "A         ";

    private static final String CATEGORY_BALANCE_RECORD_TYPE = "TRANSACTION_CATEGORY_BALANCE";

    private static final String CATEGORY_BALANCE_KEY = "00000000011010005";

    private static final String CATEGORY_BALANCE_RESOURCE = "TCATBALF";

    private static final String CARD_XREF_RECORD_TYPE = "CARD_XREF";

    private static final String CARD_XREF_RESOURCE = "CARDXREF";

    private static final String TRANSACTION_TYPE_RECORD_TYPE = "TRANSACTION_TYPE";

    private static final String TRANSACTION_TYPE_KEY = "01";

    private static final String TRANSACTION_TYPE_RESOURCE = "TRANTYPE";

    private static final String TRANSACTION_CATEGORY_RECORD_TYPE = "TRANSACTION_CATEGORY";

    private static final String TRANSACTION_CATEGORY_KEY = "010005";

    private static final String TRANSACTION_CATEGORY_RESOURCE = "TRANCATG";

    private static final String USER_SECURITY_RECORD_TYPE = "USER_SECURITY";

    private static final String USER_SECURITY_IDENTIFIER = "TESTUSR1";

    private static final String USER_SECURITY_RESOURCE = "USRSEC";

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

            assertThat(thrown.getMessage())
                    .isEqualTo("RecordNotFound[recordType=, key=" + KEY_PLACEHOLDER + "]");
        }
    }

    @Nested
    @DisplayName("the context-carrying constructors")
    class ContextCarryingConstructors {
        @Test
        @DisplayName("(recordType, key) round-trips both values through the accessors, leaves the resource "
                + "name empty, chains no cause, and keeps the key out of the message")
        void twoArgumentConstructorRoundTripsRecordTypeAndKey() {
            RecordNotFoundException thrown = new RecordNotFoundException(ACCOUNT_RECORD_TYPE, ACCOUNT_KEY);

            assertThat(thrown.recordType()).isEqualTo(ACCOUNT_RECORD_TYPE);
            assertThat(thrown.key()).isEqualTo(ACCOUNT_KEY);
            assertThat(thrown.resourceName()).isNotNull().isEmpty();
            assertThat(thrown).hasNoCause();
            assertThat(thrown.getMessage())
                    .contains(ACCOUNT_RECORD_TYPE)
                    .contains(KEY_PLACEHOLDER)
                    .doesNotContain(ACCOUNT_KEY);
        }

        @Test
        @DisplayName("(recordType, key, resourceName) round-trips all three values through the accessors, "
                + "chains no cause, and keeps the key out of the message")
        void threeArgumentConstructorRoundTripsTheResourceNameToo() {
            RecordNotFoundException thrown =
                    new RecordNotFoundException(CARD_RECORD_TYPE, SYNTHETIC_CARD_KEY, CARD_RESOURCE);

            assertThat(thrown.recordType()).isEqualTo(CARD_RECORD_TYPE);
            assertThat(thrown.key()).isEqualTo(SYNTHETIC_CARD_KEY);
            assertThat(thrown.resourceName()).isEqualTo(CARD_RESOURCE);
            assertThat(thrown).hasNoCause();

            assertThat(thrown.getMessage())
                    .contains(CARD_RECORD_TYPE, CARD_RESOURCE)
                    .contains(KEY_PLACEHOLDER)
                    .doesNotContain(SYNTHETIC_CARD_KEY);
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
            assertThat(thrown.getMessage()).doesNotContain(ABSENT_VALUE_RENDERING).doesNotContain(ACCOUNT_KEY);
        }

        @Test
        @DisplayName("(recordType, key) normalises an absent key while the record type survives")
        void twoArgumentConstructorNormalisesAnAbsentKey() {
            RecordNotFoundException thrown = new RecordNotFoundException(ACCOUNT_RECORD_TYPE, null);

            assertThat(thrown.recordType()).isEqualTo(ACCOUNT_RECORD_TYPE);
            assertThat(thrown.key()).isNotNull().isNotEqualTo(ABSENT_VALUE_RENDERING).isEmpty();
            assertThat(thrown.getMessage()).doesNotContain(ABSENT_VALUE_RENDERING).contains(KEY_PLACEHOLDER);
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

            assertThat(absentRecordType.getMessage())
                    .doesNotContain(ABSENT_VALUE_RENDERING)
                    .doesNotContain(DISCLOSURE_GROUP_KEY);
            assertThat(absentKey.getMessage()).doesNotContain(ABSENT_VALUE_RENDERING);
            assertThat(absentResource.getMessage())
                    .doesNotContain(ABSENT_VALUE_RENDERING)
                    .doesNotContain(DISCLOSURE_GROUP_KEY);

            assertThat(absentRecordType.getMessage()).contains(KEY_PLACEHOLDER);
            assertThat(absentKey.getMessage()).contains(KEY_PLACEHOLDER);
            assertThat(absentResource.getMessage()).contains(KEY_PLACEHOLDER);
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
        @DisplayName("names the record type and the resource that were searched, and carries the placeholder "
                + "where the key would be")
        void namesTheRecordTypeAndTheResourceButNotTheKey() {
            RecordNotFoundException thrown = new RecordNotFoundException(
                    CATEGORY_BALANCE_RECORD_TYPE, CATEGORY_BALANCE_KEY, CATEGORY_BALANCE_RESOURCE);

            assertThat(thrown.getMessage()).isEqualTo("RecordNotFound[recordType=" + CATEGORY_BALANCE_RECORD_TYPE
                    + ", key=" + KEY_PLACEHOLDER
                    + ", resourceName=" + CATEGORY_BALANCE_RESOURCE + "]");
            assertThat(thrown.getMessage()).doesNotContain(CATEGORY_BALANCE_KEY);

            assertThat(thrown.key()).isEqualTo(CATEGORY_BALANCE_KEY);
        }

        @Test
        @DisplayName("names the resource only when a resource was supplied, and withholds the key either way")
        void namesTheResourceOnlyWhenOneWasSupplied() {
            RecordNotFoundException withResource =
                    new RecordNotFoundException(ACCOUNT_RECORD_TYPE, ACCOUNT_KEY, ACCOUNT_RESOURCE);
            RecordNotFoundException withoutResource =
                    new RecordNotFoundException(ACCOUNT_RECORD_TYPE, ACCOUNT_KEY);

            assertThat(withResource.getMessage()).contains(ACCOUNT_RESOURCE).doesNotContain(ACCOUNT_KEY);
            assertThat(withoutResource.getMessage())
                    .contains(ACCOUNT_RECORD_TYPE)
                    .contains(KEY_PLACEHOLDER)
                    .doesNotContain(ACCOUNT_RESOURCE)
                    .doesNotContain(ACCOUNT_KEY);
        }

        @Test
        @DisplayName("withholds every key the estate can produce, whatever its width or shape")
        void withholdsEveryKeyShapeTheEstateCanProduce() {
            record Case(String recordType, String key, String resourceName) { }
            Case[] cases = {
                new Case(ACCOUNT_RECORD_TYPE, ACCOUNT_KEY, ACCOUNT_RESOURCE),
                new Case(CARD_RECORD_TYPE, SYNTHETIC_CARD_KEY, CARD_RESOURCE),
                new Case(DISCLOSURE_GROUP_RECORD_TYPE, DISCLOSURE_GROUP_KEY, DISCLOSURE_GROUP_RESOURCE),
                new Case(CATEGORY_BALANCE_RECORD_TYPE, CATEGORY_BALANCE_KEY, CATEGORY_BALANCE_RESOURCE),
                new Case(TRANSACTION_TYPE_RECORD_TYPE, TRANSACTION_TYPE_KEY, TRANSACTION_TYPE_RESOURCE),
                new Case(TRANSACTION_CATEGORY_RECORD_TYPE, TRANSACTION_CATEGORY_KEY,
                        TRANSACTION_CATEGORY_RESOURCE),
                new Case(USER_SECURITY_RECORD_TYPE, USER_SECURITY_IDENTIFIER, USER_SECURITY_RESOURCE),
                new Case(DISCLOSURE_GROUP_RECORD_TYPE, PADDED_GROUP_CODE, DISCLOSURE_GROUP_RESOURCE),
                new Case(CARD_XREF_RECORD_TYPE, SYNTHETIC_CARD_KEY, CARD_XREF_RESOURCE),
            };

            for (Case scenario : cases) {
                RecordNotFoundException thrown =
                        new RecordNotFoundException(scenario.recordType(), scenario.key(),
                                scenario.resourceName());

                assertThat(thrown.getMessage())
                        .as("the searched key must not reach the message for %s", scenario.recordType())
                        .doesNotContain(scenario.key())
                        .contains(KEY_PLACEHOLDER);
                assertThat(thrown.key())
                        .as("the accessor must still return the key verbatim for %s", scenario.recordType())
                        .isEqualTo(scenario.key());
            }
        }

        @Test
        @DisplayName("carries a fixed placeholder, so neither the value nor the length of the key is disclosed")
        void thePlaceholderIsFixedAndDisclosesNothingAboutTheKey() {
            RecordNotFoundException shortKey =
                    new RecordNotFoundException(TRANSACTION_TYPE_RECORD_TYPE, TRANSACTION_TYPE_KEY);
            RecordNotFoundException longKey =
                    new RecordNotFoundException(TRANSACTION_TYPE_RECORD_TYPE, CATEGORY_BALANCE_KEY);
            RecordNotFoundException emptyKey =
                    new RecordNotFoundException(TRANSACTION_TYPE_RECORD_TYPE, "");

            assertThat(shortKey.getMessage()).isEqualTo(longKey.getMessage()).isEqualTo(emptyKey.getMessage());
            assertThat(shortKey.getMessage()).containsOnlyOnce(KEY_PLACEHOLDER);
        }

        @Test
        @DisplayName("carries no operator advice and no remediation wording")
        void carriesNoOperatorAdviceAndNoRemediationWording() {
            RecordNotFoundException thrown = new RecordNotFoundException(
                    CARD_XREF_RECORD_TYPE, SYNTHETIC_CARD_KEY, CARD_XREF_RESOURCE);

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

            assertThat(thrown.key()).isEqualTo(DISCLOSURE_GROUP_KEY).hasSize(16);
            assertThat(thrown.getMessage()).doesNotContain(DISCLOSURE_GROUP_KEY);
        }

        @Test
        @DisplayName("a seventeen-character composite key round-trips byte-identically as well")
        void longerCompositeKeyRoundTripsByteIdentically() {
            RecordNotFoundException thrown = new RecordNotFoundException(
                    CATEGORY_BALANCE_RECORD_TYPE, CATEGORY_BALANCE_KEY, CATEGORY_BALANCE_RESOURCE);

            assertThat(thrown.key()).isEqualTo(CATEGORY_BALANCE_KEY).hasSize(17);
            assertThat(thrown.getMessage()).doesNotContain(CATEGORY_BALANCE_KEY);
        }

        @Test
        @DisplayName("leading zeros in a numeric-looking key survive, because a legacy key is a character "
                + "substring of the record image and never an integer")
        void leadingZerosArePreserved() {
            RecordNotFoundException thrown =
                    new RecordNotFoundException(ACCOUNT_RECORD_TYPE, ACCOUNT_KEY, ACCOUNT_RESOURCE);

            assertThat(thrown.key()).isEqualTo(ACCOUNT_KEY).hasSize(11).startsWith("0");
            assertThat(thrown.getMessage()).doesNotContain(ACCOUNT_KEY);
        }

        @Test
        @DisplayName("trailing padding in a key is never trimmed, because fixed-width padding is significant")
        void trailingPaddingIsNeverTrimmed() {
            RecordNotFoundException thrown = new RecordNotFoundException(
                    DISCLOSURE_GROUP_RECORD_TYPE, PADDED_GROUP_CODE, DISCLOSURE_GROUP_RESOURCE);

            assertThat(thrown.key()).isEqualTo(PADDED_GROUP_CODE).hasSize(10).endsWith(" ");
            assertThat(thrown.getMessage()).doesNotContain(PADDED_GROUP_CODE);
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
        @DisplayName("carries the eight-character user identifier on its accessor alone, never in the message")
        void carriesTheUserIdentifierOnly() {
            RecordNotFoundException thrown = new RecordNotFoundException(
                    USER_SECURITY_RECORD_TYPE, USER_SECURITY_IDENTIFIER, USER_SECURITY_RESOURCE);

            assertThat(thrown.key()).isEqualTo(USER_SECURITY_IDENTIFIER).hasSize(8);
            assertThat(thrown.getMessage()).doesNotContain(USER_SECURITY_IDENTIFIER);

            String composedWithoutTheKey = "RecordNotFound[recordType=" + USER_SECURITY_RECORD_TYPE
                    + ", key=" + KEY_PLACEHOLDER
                    + ", resourceName=" + USER_SECURITY_RESOURCE + "]";
            assertThat(thrown.getMessage()).isEqualTo(composedWithoutTheKey);
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
            assertThat(restored.getCause()).isInstanceOf(IOException.class).hasMessage(CAUSE_DETAIL);

            assertThat(restored.getMessage())
                    .isEqualTo(original.getMessage())
                    .doesNotContain(CATEGORY_BALANCE_KEY)
                    .contains(KEY_PLACEHOLDER);
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
            assertThat(restored.getMessage())
                    .isEqualTo(original.getMessage())
                    .doesNotContain(ABSENT_VALUE_RENDERING)
                    .contains(KEY_PLACEHOLDER);
            assertThat(restored).hasNoCause();
        }
    }
}
