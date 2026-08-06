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
package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumSet;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.stereotype.Component;

import com.carddemo.config.FlywayConfig;
import com.carddemo.util.SensitiveFieldCodec;

/**
 * Verifies the sealing pass without a database, so that the decisions it takes per row are asserted
 * one at a time rather than only in aggregate.
 *
 * <h2>Why a mocked connection rather than a real one here</h2>
 *
 * <p>The companion integration test proves the pass against a real migrated schema, which is the
 * evidence that matters. What that test cannot do cheaply is present a row in every combination the
 * pass distinguishes - a cleartext value, an absent value, a blank value and an already-sealed value,
 * in both columns - and prove that the write it performs is scoped to exactly the values that needed
 * it. Driving the pass over a scripted result set does that, and it also pins the two properties that
 * matter most and are the easiest to lose in a later edit: that a second run writes nothing, and that
 * a failure diagnostic names no value.
 *
 * <p>The pass itself, and the reason it is a lifecycle callback rather than a fifth versioned
 * migration, are recorded as decision {@code DL-110} in {@code docs/decision-log.md}. The idempotence
 * asserted here is the property that entry relies on: the encryption service refuses to protect an
 * already-protected value, so a pass that did not skip a sealed row would fail the second start of a
 * local stack rather than merely repeat work.
 */
@DisplayName("Seeded identity sealing: what each row does, and what a failure says")
class SeededIdentifierSealingCallbackTest {

    /** A Base64 key decoding to exactly thirty-two bytes. It is a test constant and protects nothing. */
    private static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    /** A fixture government-issued identifier, twenty characters as the legacy record holds it. */
    private static final String CLEARTEXT_GOVERNMENT_IDENTIFIER = "00000000000049368437";

    /** A fixture national identifier, nine digits as the legacy record holds it. */
    private static final String CLEARTEXT_NATIONAL_IDENTIFIER = "999887777";

    /** The seeded customer key of the first fixture row. */
    private static final String FIRST_CUSTOMER_KEY = "000000001";

    /** The seeded customer key of the second fixture row. */
    private static final String SECOND_CUSTOMER_KEY = "000000002";

    /** Parameter position of the national identifier in the update, mirroring the callback. */
    private static final int NATIONAL_PARAMETER = 1;

    /** Parameter position of the government-issued identifier in the update. */
    private static final int GOVERNMENT_PARAMETER = 2;

    /** Parameter position of the customer key in the update. */
    private static final int KEY_PARAMETER = 3;

    /** The encryption service the callback seals through; the only producer of an envelope. */
    private SensitiveFieldEncryptionService encryption;

    /** The callback under test. */
    private SeededIdentifierSealingCallback callback;

    @BeforeEach
    void createCallback() {
        this.encryption = new SensitiveFieldEncryptionService(TEST_KEY);
        this.callback = new SeededIdentifierSealingCallback(this.encryption);
    }

    /**
     * Scripts a connection whose customer table holds the supplied rows.
     *
     * @param connection the mocked connection to script
     * @param update     the mocked update statement it returns
     * @param keys       the customer keys, in read order
     * @param nationals  the national-identifier values, in read order
     * @param governments the government-identifier values, in read order
     * @throws SQLException never; declared because the mocked methods declare it
     */
    private static void scriptRows(final Connection connection, final PreparedStatement update,
            final String[] keys, final String[] nationals, final String[] governments)
            throws SQLException {
        scriptRead(connection, keys, nationals, governments);
        when(connection.prepareStatement(anyString())).thenReturn(update);
    }

    /**
     * Wraps a connection in the minimal migration context the callback reads.
     *
     * @param connection the connection the context supplies
     * @return a context answering that connection and nothing else
     */
    private static org.flywaydb.core.api.callback.Context contextOver(final Connection connection) {
        final org.flywaydb.core.api.callback.Context context =
                mock(org.flywaydb.core.api.callback.Context.class);
        when(context.getConnection()).thenReturn(connection);
        return context;
    }

    /**
     * Scripts the read half only, for the assertions that drive one pass directly rather than through
     * {@code handle}.
     *
     * @param keys        the customer keys, in read order
     * @param nationals   the national-identifier values, in read order
     * @param governments the government-identifier values, in read order
     * @param connection  the mocked connection to script
     * @throws SQLException never; declared because the mocked methods declare it
     */
    private static void scriptRead(final Connection connection, final String[] keys,
            final String[] nationals, final String[] governments) throws SQLException {
        final Statement select = mock(Statement.class);
        final ResultSet rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(select);
        when(select.executeQuery(anyString())).thenReturn(rows);

        final Boolean[] remaining = new Boolean[keys.length];
        for (int index = 0; index < keys.length; index++) {
            remaining[index] = Boolean.TRUE;
        }
        when(rows.next()).thenReturn(Boolean.TRUE, appendFalse(remaining));
        when(rows.getString("cust_id")).thenReturn(keys[0], tail(keys));
        when(rows.getString("cust_ssn")).thenReturn(nationals[0], tail(nationals));
        when(rows.getString("govt_issued_id")).thenReturn(governments[0], tail(governments));
    }

    /**
     * Returns the supplied flags with a terminating {@code false} appended, dropping the first, which
     * the caller has already supplied as the first stubbed answer.
     *
     * @param flags one flag per row
     * @return the remaining answers for the row cursor
     */
    private static Boolean[] appendFalse(final Boolean[] flags) {
        final Boolean[] remaining = new Boolean[flags.length];
        for (int index = 0; index < flags.length - 1; index++) {
            remaining[index] = Boolean.TRUE;
        }
        remaining[flags.length - 1] = Boolean.FALSE;
        return remaining;
    }

    /**
     * Returns every element of the supplied values except the first, which the caller supplies as the
     * first stubbed answer.
     *
     * @param values the values, in read order
     * @return the remaining values, possibly empty
     */
    private static String[] tail(final String[] values) {
        final String[] remaining = new String[Math.max(values.length - 1, 0)];
        System.arraycopy(values, 1, remaining, 0, remaining.length);
        return remaining;
    }

    @Nested
    @DisplayName("the lifecycle contract")
    class TheLifecycleContract {

        @Test
        @DisplayName("only the after-migrate event is acted on, so the pass runs once per migration "
                + "and after the last script")
        void onlyAfterMigrateIsActedOn() {
            assertThat(callback.supports(Event.AFTER_MIGRATE, null)).isTrue();
            for (final Event other : EnumSet.complementOf(EnumSet.of(Event.AFTER_MIGRATE))) {
                assertThat(callback.supports(other, null))
                        .as("event %s must not trigger the pass", other)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the pass elects a transaction of its OWN, which makes it all-or-nothing within "
                + "itself and is not atomicity with the seeds it inspects")
        void thePassRunsInTransactionOfItsOwn() {
            assertThat(callback.canHandleInTransaction(Event.AFTER_MIGRATE, null))
                    .as("returning true is what keeps the pass from leaving half the rows converted. "
                            + "It does NOT make the pass atomic with the seed migration, and no return "
                            + "value here could: the after-migrate event is raised after the "
                            + "migration's own transaction has committed. The class documentation used "
                            + "to claim otherwise, and this assertion's name is deliberately the "
                            + "narrower claim so the two cannot drift apart again")
                    .isTrue();
        }

        @Test
        @DisplayName("the callback names itself, so the migration log identifies what ran")
        void theCallbackNamesItself() {
            assertThat(callback.getCallbackName())
                    .isEqualTo(SeededIdentifierSealingCallback.CALLBACK_NAME)
                    .isEqualTo("seeded customer identity sealing");
        }

        @Test
        @DisplayName("the callback cannot be built without the one encryption service, because it "
                + "must not carry a second way to produce an envelope")
        void theCallbackCannotBeBuiltWithoutTheEncryptionService() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new SeededIdentifierSealingCallback(null))
                    .withMessage("encryption must not be null");
        }

        @Test
        @DisplayName("the callback registers ITSELF from this package rather than being published by "
                + "a configuration class, which is what keeps configuration from depending on service")
        void theCallbackRegistersItselfFromThisPackage() {
            final MergedAnnotations annotations = MergedAnnotations.from(
                    SeededIdentifierSealingCallback.class, SearchStrategy.TYPE_HIERARCHY);

            assertThat(annotations.isPresent(Component.class))
                    .as("Boot's Flyway auto-configuration collects every Callback bean through an "
                            + "ObjectProvider, whatever package declares it, so the component can be "
                            + "discovered here instead of being handed out by a @Bean method in "
                            + "com.carddemo.config - which would make the configuration package "
                            + "import this one and invert the layering")
                    .isTrue();
            assertThat(SeededIdentifierSealingCallback.class.getPackageName())
                    .as("it collaborates with the encryption service, so it belongs beside it")
                    .isEqualTo("com.carddemo.service");
        }

        @Test
        @DisplayName("the callback is restricted to exactly the two profiles that seed a row, because "
                + "production has no seeded identifier to seal")
        void theCallbackIsRestrictedToTheSeedingProfiles() {
            final Profile profile = SeededIdentifierSealingCallback.class
                    .getAnnotation(Profile.class);

            assertThat(profile)
                    .as("an unrestricted component would register for a production migration too, "
                            + "where the two seed scripts are held out by the version ceiling and "
                            + "there is consequently nothing for the pass to convert")
                    .isNotNull();
            assertThat(profile.value())
                    .containsExactlyInAnyOrder(FlywayConfig.LOCAL_PROFILE, FlywayConfig.TEST_PROFILE)
                    .doesNotContain(FlywayConfig.PRODUCTION_PROFILE);
        }
    }

    @Nested
    @DisplayName("what each row does")
    class WhatEachRowDoes {

        @Test
        @DisplayName("a cleartext government-issued identifier is sealed, bound to its own column, "
                + "and the row is addressed by its key")
        void aCleartextGovernmentIdentifierIsSealed() throws SQLException {
            final Connection connection = mock(Connection.class);
            final PreparedStatement update = mock(PreparedStatement.class);
            scriptRows(connection, update,
                    new String[] {FIRST_CUSTOMER_KEY},
                    new String[] {null},
                    new String[] {CLEARTEXT_GOVERNMENT_IDENTIFIER});

            assertThat(callback.seal(connection)).isEqualTo(1);

            final ArgumentCaptor<String> sealed = ArgumentCaptor.forClass(String.class);
            verify(update).setString(eq(GOVERNMENT_PARAMETER), sealed.capture());
            verify(update).setString(NATIONAL_PARAMETER, null);
            verify(update).setString(KEY_PARAMETER, FIRST_CUSTOMER_KEY);
            verify(update).executeBatch();

            assertThat(sealed.getValue()).startsWith(SensitiveFieldCodec.ENVELOPE_PREFIX);
            assertThat(encryption.reveal(
                    SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                    sealed.getValue()))
                    .as("the envelope must open under this column's binding and no other")
                    .isEqualTo(CLEARTEXT_GOVERNMENT_IDENTIFIER);
        }

        @Test
        @DisplayName("a cleartext national identifier is sealed too, under its own binding, so the "
                + "invariant covers both protected columns rather than one")
        void aCleartextNationalIdentifierIsSealedUnderItsOwnBinding() throws SQLException {
            final Connection connection = mock(Connection.class);
            final PreparedStatement update = mock(PreparedStatement.class);
            scriptRows(connection, update,
                    new String[] {FIRST_CUSTOMER_KEY},
                    new String[] {CLEARTEXT_NATIONAL_IDENTIFIER},
                    new String[] {CLEARTEXT_GOVERNMENT_IDENTIFIER});

            assertThat(callback.seal(connection))
                    .as("two values were sealed on one row")
                    .isEqualTo(2);

            final ArgumentCaptor<String> national = ArgumentCaptor.forClass(String.class);
            verify(update).setString(eq(NATIONAL_PARAMETER), national.capture());
            assertThat(encryption.reveal(
                    SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD, national.getValue()))
                    .isEqualTo(CLEARTEXT_NATIONAL_IDENTIFIER);
        }

        @Test
        @DisplayName("a PARTIALLY sealed row converts only the unsealed column and carries the sealed "
                + "one through byte for byte")
        void aPartiallySealedRowConvertsOnlyTheUnsealedColumn() throws SQLException {
            final String alreadySealed = encryption.protect(
                    SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                    CLEARTEXT_GOVERNMENT_IDENTIFIER);
            final Connection connection = mock(Connection.class);
            final PreparedStatement update = mock(PreparedStatement.class);
            scriptRows(connection, update,
                    new String[] {FIRST_CUSTOMER_KEY},
                    new String[] {CLEARTEXT_NATIONAL_IDENTIFIER},
                    new String[] {alreadySealed});

            assertThat(callback.seal(connection))
                    .as("one of the row's two values needed converting, so the count is one and not "
                            + "two: a row is not all-or-nothing, each column is decided on its own")
                    .isEqualTo(1);

            final ArgumentCaptor<String> government = ArgumentCaptor.forClass(String.class);
            verify(update).setString(eq(GOVERNMENT_PARAMETER), government.capture());
            assertThat(government.getValue())
                    .as("the column that was already sealed must be written back UNCHANGED. Re-sealing "
                            + "it would wrap an envelope inside an envelope, and the encryption service "
                            + "refuses that outright, so a partially converted row would otherwise turn "
                            + "the next start-up into a failure")
                    .isEqualTo(alreadySealed);
        }

        @Test
        @DisplayName("a row already carrying envelopes in both columns is not written at all, which "
                + "is what makes a restart convert nothing")
        void anAlreadySealedRowIsNotWritten() throws SQLException {
            final Connection connection = mock(Connection.class);
            final PreparedStatement update = mock(PreparedStatement.class);
            scriptRows(connection, update,
                    new String[] {FIRST_CUSTOMER_KEY},
                    new String[] {encryption.protect(
                            SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD,
                            CLEARTEXT_NATIONAL_IDENTIFIER)},
                    new String[] {encryption.protect(
                            SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                            CLEARTEXT_GOVERNMENT_IDENTIFIER)});

            assertThat(callback.seal(connection)).isZero();

            verify(connection, never()).prepareStatement(anyString());
            verify(update, never()).executeBatch();
        }

        @Test
        @DisplayName("an absent or blank value is left exactly as it is, because the reference seed "
                + "leaves the national identifier unseeded on purpose")
        void anAbsentOrBlankValueIsLeftAlone() throws SQLException {
            final Connection connection = mock(Connection.class);
            final PreparedStatement update = mock(PreparedStatement.class);
            scriptRows(connection, update,
                    new String[] {FIRST_CUSTOMER_KEY},
                    new String[] {"   "},
                    new String[] {encryption.protect(
                            SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                            CLEARTEXT_GOVERNMENT_IDENTIFIER)});

            assertThat(callback.seal(connection)).isZero();
            verify(connection, never()).prepareStatement(anyString());
        }

        @Test
        @DisplayName("a mixed table converts only the rows that need it, and batches them as one "
                + "write")
        void aMixedTableConvertsOnlyTheRowsThatNeedIt() throws SQLException {
            final Connection connection = mock(Connection.class);
            final PreparedStatement update = mock(PreparedStatement.class);
            scriptRows(connection, update,
                    new String[] {FIRST_CUSTOMER_KEY, SECOND_CUSTOMER_KEY},
                    new String[] {null, null},
                    new String[] {CLEARTEXT_GOVERNMENT_IDENTIFIER,
                        encryption.protect(
                                SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                                CLEARTEXT_GOVERNMENT_IDENTIFIER)});

            assertThat(callback.seal(connection)).isEqualTo(1);

            verify(update, times(1)).setString(KEY_PARAMETER, FIRST_CUSTOMER_KEY);
            verify(update, never()).setString(KEY_PARAMETER, SECOND_CUSTOMER_KEY);
            verify(update, times(1)).addBatch();
            verify(update, times(1)).executeBatch();
        }

        @Test
        @DisplayName("an empty table is a complete pass rather than an error, so a migration that "
                + "seeded nothing still succeeds")
        void anEmptyTableIsACompletePass() throws SQLException {
            final Connection connection = mock(Connection.class);
            final Statement select = mock(Statement.class);
            final ResultSet rows = mock(ResultSet.class);
            when(connection.createStatement()).thenReturn(select);
            when(select.executeQuery(anyString())).thenReturn(rows);
            when(rows.next()).thenReturn(Boolean.FALSE);

            assertThat(callback.seal(connection)).isZero();
            verify(connection, never()).prepareStatement(anyString());
        }
    }

    @Nested
    @DisplayName("the key-and-binding invariant - a stored value must OPEN UNDER ITS COLUMN, not merely "
            + "look like an envelope")
    class TheKeyInvariant {

        /**
         * A second Base64 key, decoding to thirty-two bytes and different from {@link #TEST_KEY}. It
         * stands in for an overridden or rotated key: the state the seed-bearing profiles now prevent
         * by declaring their key as a bare literal, and that this invariant refuses however else it
         * arises.
         */
        private static final String FOREIGN_KEY = "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8=";

        @Test
        @DisplayName("an envelope sealed under a FOREIGN key is refused, and the envelope-marker check "
                + "is proved blind to it in the same test")
        void anEnvelopeSealedUnderFOREIGNKeyIsRefused() throws SQLException {
            final String foreignEnvelope = new SensitiveFieldEncryptionService(FOREIGN_KEY)
                    .protect(SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                            CLEARTEXT_GOVERNMENT_IDENTIFIER);
            assertThat(encryption.isProtected(foreignEnvelope))
                    .as("THE PRECONDITION THAT MAKES THIS INVARIANT NECESSARY. The shape check answers "
                            + "true for a value this process cannot read, so sealing alone would pass "
                            + "the row along and nothing would object until something decrypted it")
                    .isTrue();

            final Connection connection = mock(Connection.class);
            scriptRead(connection, new String[] {FIRST_CUSTOMER_KEY}, new String[] {null},
                    new String[] {foreignEnvelope});

            assertThatExceptionOfType(FlywayException.class)
                    .isThrownBy(() -> callback.verifyEveryStoredValueOpens(connection))
                    .withMessageContaining("govt_issued_id")
                    .withMessageContaining(
                            SensitiveFieldEncryptionService.FIELD_ENCRYPTION_KEY_PROPERTY);
        }

        @Test
        @DisplayName("the refusal names the column and the property, and never the value, the cleartext, "
                + "the key or the row - decision DL-041")
        void theRefusalNamesNeitherValueNorKey() throws SQLException {
            final String foreignEnvelope = new SensitiveFieldEncryptionService(FOREIGN_KEY)
                    .protect(SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                            CLEARTEXT_GOVERNMENT_IDENTIFIER);
            final Connection connection = mock(Connection.class);
            scriptRead(connection, new String[] {FIRST_CUSTOMER_KEY}, new String[] {null},
                    new String[] {foreignEnvelope});

            assertThatExceptionOfType(FlywayException.class)
                    .isThrownBy(() -> callback.verifyEveryStoredValueOpens(connection))
                    .satisfies(refusal -> {
                        final StringWriter trace = new StringWriter();
                        refusal.printStackTrace(new PrintWriter(trace));
                        assertThat(trace.toString())
                                .as("the whole chain is inspected, not only the top message: a cause "
                                        + "carrying the value would publish it just as surely")
                                .doesNotContain(CLEARTEXT_GOVERNMENT_IDENTIFIER)
                                .doesNotContain(foreignEnvelope)
                                .doesNotContain(FOREIGN_KEY)
                                .doesNotContain(TEST_KEY);
                        assertThat(refusal.getMessage())
                                .doesNotContain(FIRST_CUSTOMER_KEY)
                                .doesNotContain("\r")
                                .doesNotContain("\n");
                    });
        }

        @Test
        @DisplayName("an UNBOUND envelope is refused, because every reader of the column would refuse "
                + "it too - which is the defect this pass now catches at start-up")
        void anUnboundEnvelopeIsRefused() throws SQLException {
            final String unbound = encryption.protect(CLEARTEXT_GOVERNMENT_IDENTIFIER);
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("PINS WHY THE PASS HAS TO READ THE BINDING. This is the form an earlier "
                            + "revision of V3__seed_reference_data.sql delivered, and the field-bound "
                            + "reveal every reader of the column uses refuses it - so a seed carrying "
                            + "it starts cleanly and then fails every request that reads a customer")
                    .isThrownBy(() -> encryption.reveal(
                            SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                            unbound))
                    .withMessageContaining("field binding");

            final Connection connection = mock(Connection.class);
            scriptRead(connection, new String[] {FIRST_CUSTOMER_KEY}, new String[] {null},
                    new String[] {unbound});

            assertThatExceptionOfType(FlywayException.class)
                    .as("and this pass now refuses it for the same reason, naming the column and the "
                            + "binding it failed to carry, so the failure is a start-up failure rather "
                            + "than a 500 on the account view screen")
                    .isThrownBy(() -> callback.verifyEveryStoredValueOpens(connection))
                    .withMessageContaining("govt_issued_id")
                    .withMessageContaining(
                            SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD);
        }

        @Test
        @DisplayName("an envelope written for the OTHER column is refused, so a value lifted from one "
                + "protected column into the other cannot pass as native")
        void anEnvelopeBoundToTheOtherColumnIsRefused() throws SQLException {
            final String misbound = encryption.protect(
                    SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD,
                    CLEARTEXT_GOVERNMENT_IDENTIFIER);
            final Connection connection = mock(Connection.class);
            scriptRead(connection, new String[] {FIRST_CUSTOMER_KEY}, new String[] {null},
                    new String[] {misbound});

            assertThatExceptionOfType(FlywayException.class)
                    .as("it decrypts under this key, so a key-only check would accept it; the binding "
                            + "is what tells the two columns apart, and this pass asks exactly what "
                            + "the service asks when the value is read into the domain")
                    .isThrownBy(() -> callback.verifyEveryStoredValueOpens(connection))
                    .withMessageContaining("govt_issued_id");
        }

        @Test
        @DisplayName("every present column-bound value is counted, in either column, while an absent "
                + "one is not")
        void everyValueUnderTheConfiguredKeyOpens() throws SQLException {
            final Connection connection = mock(Connection.class);
            scriptRead(connection,
                    new String[] {FIRST_CUSTOMER_KEY, SECOND_CUSTOMER_KEY},
                    new String[] {
                        encryption.protect(SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD,
                                CLEARTEXT_NATIONAL_IDENTIFIER),
                        null},
                    new String[] {
                        encryption.protect(
                                SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                                CLEARTEXT_GOVERNMENT_IDENTIFIER),
                        encryption.protect(
                                SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                                CLEARTEXT_GOVERNMENT_IDENTIFIER)});

            assertThat(callback.verifyEveryStoredValueOpens(connection))
                    .as("three values are present across the two rows - two bound government "
                            + "identifiers and one bound national identifier - and the absent national "
                            + "identifier of the second row is not a value and is not counted")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("handle runs BOTH passes: it converts what needs converting and then opens what is "
                + "stored, so neither invariant depends on the other being invoked separately")
        void handleRunsBothPasses() throws SQLException {
            final Connection connection = mock(Connection.class);
            final PreparedStatement update = mock(PreparedStatement.class);
            scriptRows(connection, update,
                    new String[] {FIRST_CUSTOMER_KEY},
                    new String[] {CLEARTEXT_NATIONAL_IDENTIFIER},
                    new String[] {CLEARTEXT_GOVERNMENT_IDENTIFIER});

            callback.handle(Event.AFTER_MIGRATE, contextOver(connection));

            verify(update).executeBatch();
            // Two reads on the one connection: the seal pass reads to decide what to convert, and the
            // key pass reads back to open what is stored. A handle that ran only the first would leave
            // the key invariant unenforced wherever it matters most, since handle is the only entry
            // point the migration tool calls.
            verify(connection, times(2)).createStatement();
        }

        @Test
        @DisplayName("an absent or blank value is not a value: it is neither opened nor counted, which "
                + "is the same rule the seal pass applies")
        void anAbsentOrBlankValueIsNotAValue() throws SQLException {
            final Connection connection = mock(Connection.class);
            scriptRead(connection, new String[] {FIRST_CUSTOMER_KEY}, new String[] {null},
                    new String[] {"   "});

            assertThat(callback.verifyEveryStoredValueOpens(connection))
                    .as("a blank would not open, but the seal pass leaves a blank alone, so refusing "
                            + "one here would make the two passes contradict each other")
                    .isZero();
        }

        @Test
        @DisplayName("an empty table is a complete pass, so a database migrated before any row exists "
                + "is not refused")
        void anEmptyTableOpensNothingAndIsAccepted() throws SQLException {
            final Connection connection = mock(Connection.class);
            final Statement select = mock(Statement.class);
            final ResultSet rows = mock(ResultSet.class);
            when(connection.createStatement()).thenReturn(select);
            when(select.executeQuery(anyString())).thenReturn(rows);
            when(rows.next()).thenReturn(Boolean.FALSE);

            assertThat(callback.verifyEveryStoredValueOpens(connection)).isZero();
        }

        @Test
        @DisplayName("a cleartext value is sealed by the first pass and then opens under the second, so "
                + "the two passes agree about what a converted row looks like")
        void aSealedCleartextValueThenOpens() throws SQLException {
            final Connection connection = mock(Connection.class);
            final PreparedStatement update = mock(PreparedStatement.class);
            scriptRows(connection, update, new String[] {FIRST_CUSTOMER_KEY},
                    new String[] {CLEARTEXT_NATIONAL_IDENTIFIER},
                    new String[] {CLEARTEXT_GOVERNMENT_IDENTIFIER});

            assertThat(callback.seal(connection))
                    .as("both cleartext values are converted")
                    .isEqualTo(2);

            final ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
            verify(update, times(1)).setString(eq(GOVERNMENT_PARAMETER), written.capture());
            final Connection reread = mock(Connection.class);
            scriptRead(reread, new String[] {FIRST_CUSTOMER_KEY}, new String[] {null},
                    new String[] {written.getValue()});

            assertThat(callback.verifyEveryStoredValueOpens(reread))
                    .as("what the seal pass wrote must be exactly what the key invariant accepts; if "
                            + "the two disagreed, a genuine conversion would fail its own start-up")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("a failure fails the migration and names no value - decision DL-041")
    class AFailureNamesNoValue {

        @Test
        @DisplayName("a read failure fails the migration rather than reporting success over rows "
                + "still holding cleartext")
        void aReadFailureFailsTheMigration() throws SQLException {
            final Connection connection = mock(Connection.class);
            when(connection.createStatement())
                    .thenThrow(new SQLException("relation \"customer\" does not exist"));

            assertThatExceptionOfType(FlywayException.class)
                    .isThrownBy(() -> callback.handle(Event.AFTER_MIGRATE, contextOver(connection)))
                    .withMessageContaining("cust_ssn")
                    .withMessageContaining("govt_issued_id")
                    .withMessageContaining("failed rather than")
                    .satisfies(failure -> assertThat(failure.getCause())
                            .as("the underlying cause is carried rather than discarded")
                            .isInstanceOf(SQLException.class));
        }

        @Test
        @DisplayName("the failure diagnostic names the columns and never a customer key or a value")
        void theFailureDiagnosticNamesNoValue() throws SQLException {
            final Connection connection = mock(Connection.class);
            final PreparedStatement update = mock(PreparedStatement.class);
            scriptRows(connection, update,
                    new String[] {FIRST_CUSTOMER_KEY},
                    new String[] {CLEARTEXT_NATIONAL_IDENTIFIER},
                    new String[] {CLEARTEXT_GOVERNMENT_IDENTIFIER});
            when(update.executeBatch()).thenThrow(new SQLException("write refused"));

            assertThatExceptionOfType(FlywayException.class)
                    .isThrownBy(() -> callback.handle(Event.AFTER_MIGRATE, contextOver(connection)))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage())
                                .doesNotContain(CLEARTEXT_GOVERNMENT_IDENTIFIER)
                                .doesNotContain(CLEARTEXT_NATIONAL_IDENTIFIER)
                                .doesNotContain(FIRST_CUSTOMER_KEY)
                                .doesNotContain("\r")
                                .doesNotContain("\n");
                    });
        }

        @Test
        @DisplayName("a successful handle over a converted table completes without writing")
        void aSuccessfulHandleOverAConvertedTableCompletes() throws SQLException {
            final Connection connection = mock(Connection.class);
            final Statement select = mock(Statement.class);
            final ResultSet rows = mock(ResultSet.class);
            when(connection.createStatement()).thenReturn(select);
            when(select.executeQuery(anyString())).thenReturn(rows);
            when(rows.next()).thenReturn(Boolean.FALSE);

            callback.handle(Event.AFTER_MIGRATE, contextOver(connection));

            verify(connection, never()).prepareStatement(anyString());
        }
    }
}
