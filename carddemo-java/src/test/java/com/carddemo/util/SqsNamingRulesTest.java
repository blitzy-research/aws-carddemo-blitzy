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
package com.carddemo.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds the one shared statement of the queue naming contract to its boundaries.
 *
 * <h2>What is actually at stake</h2>
 *
 * <p>The publisher that uses these rules is deliberately non-fatal on a failed send, because the
 * legacy contract it reproduces ignores a queue-write error. So an invalid queue name or message group
 * does not announce itself at run time: every send fails into the tolerated path, and a submission
 * reports complete having queued nothing. These rules exist to convert that into a start-up refusal,
 * which means the value of each assertion below is that it fixes the exact line between "refused
 * loudly at start-up" and "silently broken for the life of the deployment".
 *
 * <p>The second thing at stake is agreement. The emulator bootstrap script enforces the same two
 * limits over the same character set in shell, and previously the two disagreed - the script bounded
 * and restricted both values while the publisher checked only for printable text and the suffix. The
 * limits are asserted here as constants so that a change to either side has to come through this
 * class, and {@code LocalStackBootstrapContractTest} separately asserts the script's own numbers
 * against these same constants.
 *
 * <h2>Why the three destination forms are exercised individually</h2>
 *
 * <p>A deployment may configure a bare queue name, a queue URL or a queue ARN, and the messaging
 * template resolves all three. That is why the character rule cannot be applied to the whole
 * configured value: a URL carries a scheme, slashes and dots and an ARN carries colons, none of which
 * may appear in a queue name. Each form therefore has its envelope checked and its queue name
 * extracted, and both halves of that need proving - that a legitimate envelope is accepted, and that
 * the name inside it is still held to the rule.
 *
 * <p>No legacy source text appears here.
 */
@DisplayName("SqsNamingRules: the one shared statement of the queue naming contract")
class SqsNamingRulesTest {

    /** The queue property key, quoted exactly as configuration declares it. */
    private static final String QUEUE_PROPERTY = "carddemo.aws.sqs.job-queue";

    /** The message-group property key, quoted exactly as configuration declares it. */
    private static final String GROUP_PROPERTY = "carddemo.aws.sqs.message-group-id";

    /** The mandated queue name, which every accepted form below carries. */
    private static final String CANONICAL_QUEUE = "JOBS.fifo";

    /** The mandated message group. */
    private static final String CANONICAL_GROUP = "carddemo-job-submission";

    @Nested
    @DisplayName("the three destination forms a deployment may configure")
    class TheAcceptedForms {

        @ParameterizedTest(name = "[{0}] is accepted")
        @ValueSource(strings = {
            "JOBS.fifo",
            "https://sqs.us-east-1.amazonaws.com/000000000000/JOBS.fifo",
            "http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/JOBS.fifo",
            "arn:aws:sqs:us-east-1:000000000000:JOBS.fifo"})
        @DisplayName("every form the messaging template resolves is accepted, and returned unchanged")
        void everyResolvableFormIsAccepted(String destination) {
            assertThat(SqsNamingRules.requireQueueDestination(destination, QUEUE_PROPERTY))
                    .as("the CONFIGURED value is returned, not the extracted name: the template is "
                            + "handed exactly what the deployment wrote")
                    .isSameAs(destination);
        }

        @Test
        @DisplayName("the emulator URL form is accepted with its port, because that is the form the "
                + "local and test profiles actually resolve")
        void theEmulatorUrlFormWithAPortIsAccepted() {
            assertThatNoException().isThrownBy(() -> SqsNamingRules.requireQueueDestination(
                    "http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/"
                            + CANONICAL_QUEUE,
                    QUEUE_PROPERTY));
        }
    }

    @Nested
    @DisplayName("the queue-name rule, which is the rule the bootstrap script also applies")
    class TheQueueNameRule {

        @Test
        @DisplayName("a name of exactly the maximum length is accepted, and one character more is not")
        void theLengthBoundaryIsExact() {
            final String suffix = SqsNamingRules.FIFO_SUFFIX;
            final String atLimit = "q".repeat(SqsNamingRules.QUEUE_NAME_MAX_LENGTH - suffix.length())
                    + suffix;
            assertThat(atLimit).hasSize(SqsNamingRules.QUEUE_NAME_MAX_LENGTH);
            assertThatNoException()
                    .as("the ceiling is inclusive: a name of exactly the maximum length is legal")
                    .isThrownBy(() -> SqsNamingRules.requireQueueDestination(atLimit, QUEUE_PROPERTY));

            final String overLimit = "q" + atLimit;
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("and one character beyond it is not, which is the check that was missing "
                            + "entirely before: a name of any length ending in the suffix used to pass")
                    .isThrownBy(() ->
                            SqsNamingRules.requireQueueDestination(overLimit, QUEUE_PROPERTY))
                    .withMessageContaining(QUEUE_PROPERTY)
                    .withMessageContaining(String.valueOf(overLimit.length()))
                    .withMessageContaining(String.valueOf(SqsNamingRules.QUEUE_NAME_MAX_LENGTH));
        }

        @ParameterizedTest(name = "the illegal character in [{0}] is refused")
        @ValueSource(strings = {
            "queue with spaces.fifo", "queue/slash.fifo", "queue:colon.fifo", "queue+plus.fifo",
            "queue%percent.fifo", "queue*star.fifo", "queue(paren).fifo", "queue'quote.fifo",
            "queue\"quote.fifo", "queue\\backslash.fifo", "queue|pipe.fifo", "queue$dollar.fifo",
            "queue@at.fifo", "queue#hash.fifo", "queue,comma.fifo", "queue;semi.fifo"})
        @DisplayName("a character outside letters, digits, dot, hyphen and underscore is refused")
        void anIllegalCharacterIsRefused(String candidate) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("every one of these ends in the suffix and is printable, so all of them used "
                            + "to construct a publisher that would then fail every single send")
                    .isThrownBy(() ->
                            SqsNamingRules.requireQueueDestination(candidate, QUEUE_PROPERTY))
                    .withMessageContaining(QUEUE_PROPERTY)
                    .withMessageContaining("zero-based position");
        }

        @ParameterizedTest(name = "the name [{0}] is accepted")
        @ValueSource(strings = {
            "a.fifo", "A.fifo", "0.fifo", "under_score.fifo", "hy-phen.fifo", "dot.ted.fifo",
            "MiXeD-Case_9.fifo"})
        @DisplayName("every character the queue service does permit is permitted here")
        void everyPermittedCharacterIsAccepted(String candidate) {
            assertThatNoException().isThrownBy(() ->
                    SqsNamingRules.requireQueueDestination(candidate, QUEUE_PROPERTY));
        }

        @Test
        @DisplayName("a name that is only the suffix is refused, because it names no queue")
        void theBareSuffixIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> SqsNamingRules.requireQueueDestination(
                            SqsNamingRules.FIFO_SUFFIX, QUEUE_PROPERTY))
                    .withMessageContaining(QUEUE_PROPERTY)
                    .withMessageContaining("names no queue");
        }

        @ParameterizedTest(name = "the non-ordered name [{0}] is refused")
        // Five distinct shapes, each unsuffixed for a different reason: the bare prescribed stem, the
        // suffix in the wrong case, a truncated suffix, a suffix with more after it, and a name of an
        // altogether different shape. The bare stem matters most - it is the legacy transient-data
        // queue name exactly as the operator message carries it, and it must still be refused here,
        // because a standard queue cannot honour the append order the bridge contract requires.
        @ValueSource(strings = {"JOBS", "JOBS.FIFO", "JOBS.fif",
            "JOBS.fifo2", "submission-queue"})
        @DisplayName("a name that does not end in the suffix is refused, because ordering is "
                + "contractual and a standard queue cannot honour it")
        void aNameWithoutTheSuffixIsRefused(String candidate) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            SqsNamingRules.requireQueueDestination(candidate, QUEUE_PROPERTY))
                    .withMessageContaining(SqsNamingRules.FIFO_SUFFIX);
        }

        @Test
        @DisplayName("an empty name is refused rather than reaching the character scan")
        void anEmptyNameIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> SqsNamingRules.requireQueueDestination("", QUEUE_PROPERTY))
                    .withMessageContaining(QUEUE_PROPERTY)
                    .withMessageContaining("empty");
        }
    }

    @Nested
    @DisplayName("the envelope of a queue ARN")
    class TheArnEnvelope {

        @Test
        @DisplayName("an ARN with the wrong number of segments is refused, naming both counts")
        void theSegmentCountIsChecked() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> SqsNamingRules.requireQueueDestination(
                            "arn:aws:sqs:us-east-1:" + CANONICAL_QUEUE, QUEUE_PROPERTY))
                    .withMessageContaining(QUEUE_PROPERTY)
                    .withMessageContaining("colon-separated segments");
        }

        @Test
        @DisplayName("an ARN naming a different service is refused, so a topic ARN cannot be "
                + "configured as a queue")
        void theServiceSegmentIsChecked() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("this is a realistic mistake rather than a contrived one: the module also "
                            + "configures a notification topic, whose ARN differs only here")
                    .isThrownBy(() -> SqsNamingRules.requireQueueDestination(
                            "arn:aws:sns:us-east-1:000000000000:" + CANONICAL_QUEUE, QUEUE_PROPERTY))
                    .withMessageContaining("service segment");
        }

        @Test
        @DisplayName("the queue name inside an otherwise well-formed ARN is still held to the rule")
        void theNameInsideAnArnIsStillChecked() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the envelope being valid must not excuse the name: this is the whole reason "
                            + "the name is extracted rather than the value being scanned as one string")
                    .isThrownBy(() -> SqsNamingRules.requireQueueDestination(
                            "arn:aws:sqs:us-east-1:000000000000:not a queue name.fifo",
                            QUEUE_PROPERTY))
                    .withMessageContaining("zero-based position");
        }
    }

    @Nested
    @DisplayName("the envelope of a queue URL")
    class TheUrlEnvelope {

        @Test
        @DisplayName("a URL with no path is refused, because nothing in it names a queue")
        void aUrlWithoutAPathIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> SqsNamingRules.requireQueueDestination(
                            "https://sqs.us-east-1.amazonaws.com", QUEUE_PROPERTY))
                    .withMessageContaining(QUEUE_PROPERTY)
                    .withMessageContaining("no such segment");
        }

        @Test
        @DisplayName("a URL ending in a separator is refused, because its last segment is empty")
        void aUrlEndingInASeparatorIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> SqsNamingRules.requireQueueDestination(
                            "https://sqs.us-east-1.amazonaws.com/000000000000/", QUEUE_PROPERTY))
                    .withMessageContaining(QUEUE_PROPERTY);
        }

        @Test
        @DisplayName("a URL whose only separator is its last character is refused as pathless")
        void aUrlWhoseOnlySeparatorIsItsLastCharacterIsRefused() {
            // The authority and the path are one character apart here, so the first path separator is
            // also the final character of the value. That is a distinct arm of the path check from the
            // no-separator-at-all case above and from a trailing separator after a real path segment,
            // and it is a plausible misconfiguration: an endpoint pasted with its trailing slash and
            // no queue appended. It must be reported as carrying no queue segment rather than
            // producing an empty queue name further down.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> SqsNamingRules.requireQueueDestination(
                            "http://localhost:4566/", QUEUE_PROPERTY))
                    .withMessageContaining(QUEUE_PROPERTY)
                    .withMessageContaining("no such segment");
        }

        @Test
        @DisplayName("the queue name inside an otherwise well-formed URL is still held to the rule")
        void theNameInsideAUrlIsStillChecked() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> SqsNamingRules.requireQueueDestination(
                            "https://sqs.us-east-1.amazonaws.com/000000000000/JOBS",
                            QUEUE_PROPERTY))
                    .withMessageContaining(SqsNamingRules.FIFO_SUFFIX);
        }

        @Test
        @DisplayName("a destination longer than the overall ceiling is refused before it is parsed")
        void anAbsurdlyLongDestinationIsRefusedBeforeParsing() {
            final String absurd = "https://sqs.us-east-1.amazonaws.com/"
                    + "0".repeat(SqsNamingRules.QUEUE_DESTINATION_MAX_LENGTH) + "/" + CANONICAL_QUEUE;
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> SqsNamingRules.requireQueueDestination(absurd, QUEUE_PROPERTY))
                    .withMessageContaining(
                            String.valueOf(SqsNamingRules.QUEUE_DESTINATION_MAX_LENGTH));
        }
    }

    @Nested
    @DisplayName("the message-group rule")
    class TheMessageGroupRule {

        @Test
        @DisplayName("the mandated group is accepted and returned unchanged")
        void theMandatedGroupIsAccepted() {
            assertThat(SqsNamingRules.requireMessageGroupId(CANONICAL_GROUP, GROUP_PROPERTY))
                    .isSameAs(CANONICAL_GROUP);
        }

        @Test
        @DisplayName("a group of exactly the maximum length is accepted, and one character more is not")
        void theLengthBoundaryIsExact() {
            final String atLimit = "g".repeat(SqsNamingRules.MESSAGE_GROUP_ID_MAX_LENGTH);
            assertThatNoException()
                    .isThrownBy(() -> SqsNamingRules.requireMessageGroupId(atLimit, GROUP_PROPERTY));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the group used to be checked for printable text only, so a group of any "
                            + "length passed start-up and then failed every send")
                    .isThrownBy(() ->
                            SqsNamingRules.requireMessageGroupId("g" + atLimit, GROUP_PROPERTY))
                    .withMessageContaining(GROUP_PROPERTY)
                    .withMessageContaining(
                            String.valueOf(SqsNamingRules.MESSAGE_GROUP_ID_MAX_LENGTH));
        }

        @ParameterizedTest(name = "the illegal group [{0}] is refused")
        @ValueSource(strings = {"group with spaces", "group/slash", "group:colon", "group%percent",
            "group*star", "group@at"})
        @DisplayName("a character outside the permitted set is refused")
        void anIllegalCharacterIsRefused(String candidate) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            SqsNamingRules.requireMessageGroupId(candidate, GROUP_PROPERTY))
                    .withMessageContaining(GROUP_PROPERTY)
                    .withMessageContaining("zero-based position");
        }

        @Test
        @DisplayName("an empty group is refused")
        void anEmptyGroupIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> SqsNamingRules.requireMessageGroupId("", GROUP_PROPERTY))
                    .withMessageContaining("empty");
        }

        @Test
        @DisplayName("the group ceiling is deliberately higher than the queue-name ceiling, because "
                + "the queue service sets them differently")
        void theTwoCeilingsAreNotTheSameNumber() {
            assertThat(SqsNamingRules.MESSAGE_GROUP_ID_MAX_LENGTH)
                    .as("collapsing them to one number would either reject a legal group or admit an "
                            + "illegal queue name, so the asymmetry is asserted rather than assumed")
                    .isGreaterThan(SqsNamingRules.QUEUE_NAME_MAX_LENGTH);
        }
    }

    @Nested
    @DisplayName("what a rejection is allowed to say - decision DL-041")
    class WhatARejectionMaySay {

        /** A marker that must never be echoed back, standing in for an operator-supplied value. */
        private static final String HOSTILE_MARKER = "SECRET-VALUE-DO-NOT-ECHO";

        @Test
        @DisplayName("no rejection repeats the configured value, in any of the four failure modes")
        void noRejectionRepeatsTheConfiguredValue() {
            final String[] rejected = {
                HOSTILE_MARKER + " with a space.fifo",
                HOSTILE_MARKER.repeat(10) + ".fifo",
                HOSTILE_MARKER,
                "arn:aws:sns:us-east-1:000000000000:" + HOSTILE_MARKER + ".fifo"};
            for (final String candidate : rejected) {
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .isThrownBy(() ->
                                SqsNamingRules.requireQueueDestination(candidate, QUEUE_PROPERTY))
                        .satisfies(refusal -> assertThat(refusal.getMessage())
                                .as("the diagnostic must name the property key and this module's own "
                                        + "limits, and never the value an operator supplied")
                                .contains(QUEUE_PROPERTY)
                                .doesNotContain(HOSTILE_MARKER));
            }
        }

        @Test
        @DisplayName("no rejection carries a line terminator, so it cannot forge a log record")
        void noRejectionCarriesALineTerminator() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> SqsNamingRules.requireQueueDestination(
                            "bad name.fifo", QUEUE_PROPERTY))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .doesNotContain("\r")
                            .doesNotContain("\n"));
        }
    }

    @Nested
    @DisplayName("the holder itself")
    class TheHolderItself {

        @Test
        @DisplayName("is not instantiable, because it holds no state beyond its constants")
        void theHolderIsNotInstantiable() throws NoSuchMethodException {
            final Constructor<SqsNamingRules> constructor =
                    SqsNamingRules.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("states the permitted character set as a predicate, so the shell and the Java "
                + "side can be compared against one definition")
        void thePermittedCharacterSetIsStatedOnce() {
            for (char character = 0; character < 128; character++) {
                final boolean expected = Character.isLetterOrDigit(character)
                        && character < 128
                        || character == '.' || character == '-' || character == '_';
                assertThat(SqsNamingRules.isPermittedNameCharacter(character))
                        .as("code point %d", (int) character)
                        .isEqualTo(expected);
            }
        }
    }

    /**
     * The stricter grammar a production deployment's destination is held to.
     *
     * <p>The permissive rule above answers "is this a well-formed destination", which is the right
     * question for a developer's machine and for the suite, where the destination legitimately
     * addresses an emulator over plain transport. It is the wrong question for a deployment: a
     * correctly formed destination can name any host at all, and the value under test here would then
     * receive the eighty-column job-control cards of every batch submission.
     *
     * <p>Every fixture is synthetic. No real account identifier, host or credential appears below.
     */
    @Nested
    @DisplayName("the production destination rule, which is about identity rather than shape")
    class TheProductionDestinationRule {

        /** The region a fixture deployment declares, and the one a destination must agree with. */
        private static final String REGION = "eu-west-2";

        /** A synthetic twelve-digit account identifier. */
        private static final String ACCOUNT = "000000000000";

        /** The canonical queue name. */
        private static final String QUEUE = "JOBS.fifo";

        /** The key every diagnostic names. */
        private static final String KEY = "carddemo.aws.sqs.job-submission-queue";

        @Test
        @DisplayName("a bare queue name is accepted and returned unchanged, and it is the preferred "
                + "form because a name carries no destination for anything to redirect")
        void aBareNameIsAcceptedUnchanged() {
            assertThat(SqsNamingRules.requireProductionQueueDestination(QUEUE, KEY, REGION))
                    .isEqualTo(QUEUE);
        }

        @ParameterizedTest(name = "destination = {0}")
        @ValueSource(strings = {
            "http://sqs.eu-west-2.amazonaws.com/000000000000/JOBS.fifo",
            "http://attacker.internal/JOBS.fifo",
            "http://localhost:4566/000000000000/JOBS.fifo"})
        @DisplayName("plain transport is refused whatever host it names, because a job-control card on "
                + "an unencrypted connection is readable and rewritable in flight")
        void plainTransportIsRefused(final String destination) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> SqsNamingRules
                            .requireProductionQueueDestination(destination, KEY, REGION))
                    .withMessageContaining(KEY);
        }

        @Test
        @DisplayName("a secure queue URL on a queue-service endpoint host for this region is accepted")
        void aGenuineUrlIsAccepted() {
            final String url = "https://sqs." + REGION + ".amazonaws.com/" + ACCOUNT + "/" + QUEUE;

            assertThat(SqsNamingRules.requireProductionQueueDestination(url, KEY, REGION))
                    .isEqualTo(url);
        }

        @ParameterizedTest(name = "host = {0}")
        @ValueSource(strings = {
            "attacker.invalid",
            "sqs.eu-west-2.amazonaws.com.attacker.invalid",
            "attacker.invalid.sqs.eu-west-2.amazonaws.com",
            "sqs.eu-west-2.amazonaws.com:8443",
            "sqs.eu-west-2.amazonaws.com@attacker.invalid",
            "sqs.us-east-1.amazonaws.com",
            "attacker.amazonaws.com",
            "sqs..amazonaws.com"})
        @DisplayName("a host that is not exactly a queue-service endpoint for this region is refused, "
                + "so a value cannot pass by containing a familiar substring")
        void aHostThatIsNotTheEndpointIsRefused(final String host) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> SqsNamingRules.requireProductionQueueDestination(
                            "https://" + host + "/" + ACCOUNT + "/" + QUEUE, KEY, REGION));
        }

        @Test
        @DisplayName("the validated-cryptography endpoint is accepted, because refusing it would push "
                + "a deployment towards the ordinary one")
        void theFipsEndpointIsAccepted() {
            assertThatNoException().isThrownBy(() -> SqsNamingRules.requireProductionQueueDestination(
                    "https://sqs-fips." + REGION + ".amazonaws.com/" + ACCOUNT + "/" + QUEUE,
                    KEY, REGION));
        }

        @Test
        @DisplayName("the China partition host is accepted, so the rule is not silently limited to one "
                + "partition")
        void theChinaPartitionHostIsAccepted() {
            assertThatNoException().isThrownBy(() -> SqsNamingRules.requireProductionQueueDestination(
                    "https://sqs.cn-north-1.amazonaws.com.cn/" + ACCOUNT + "/" + QUEUE,
                    KEY, "cn-north-1"));
        }

        @ParameterizedTest(name = "path = {0}")
        @ValueSource(strings = {"/JOBS.fifo", "/000000000000", "/000000000000/x/y",
            "/000000000000/JOBS.fifo/"})
        @DisplayName("a path that is not exactly an account segment and a queue segment is refused")
        void aPathThatIsNotAccountAndQueueIsRefused(final String path) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> SqsNamingRules.requireProductionQueueDestination(
                            "https://sqs." + REGION + ".amazonaws.com" + path, KEY, REGION));
        }

        @Test
        @DisplayName("a URL with no path at all names an endpoint rather than a queue and is refused")
        void aUrlWithNoPathIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> SqsNamingRules.requireProductionQueueDestination(
                            "https://sqs." + REGION + ".amazonaws.com", KEY, REGION));
        }

        @Test
        @DisplayName("a queue ARN naming a recognised partition, the queue service, this region and a "
                + "twelve-digit account is accepted")
        void aGenuineArnIsAccepted() {
            final String arn = "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":" + QUEUE;

            assertThat(SqsNamingRules.requireProductionQueueDestination(arn, KEY, REGION))
                    .isEqualTo(arn);
        }

        @ParameterizedTest(name = "partition = {0}")
        @ValueSource(strings = {"aws", "aws-cn", "aws-us-gov"})
        @DisplayName("all three recognised partitions are accepted")
        void everyRecognisedPartitionIsAccepted(final String partition) {
            assertThatNoException().isThrownBy(() -> SqsNamingRules.requireProductionQueueDestination(
                    "arn:" + partition + ":sqs:" + REGION + ":" + ACCOUNT + ":" + QUEUE, KEY, REGION));
        }

        @ParameterizedTest(name = "arn = {0}")
        @ValueSource(strings = {
            "arn:aws-fictional:sqs:eu-west-2:000000000000:JOBS.fifo",
            "arn:aws:sns:eu-west-2:000000000000:JOBS.fifo",
            "arn:aws:sqs:us-east-1:000000000000:JOBS.fifo",
            "arn:aws:sqs:eu-west-2:00000000000:JOBS.fifo",
            "arn:aws:sqs:eu-west-2:00000000000a:JOBS.fifo",
            "arn:aws:sqs:eu-west-2:000000000000:JOBS",
            "arn:aws:sqs:eu-west-2:000000000000"})
        @DisplayName("an ARN whose partition, service, region, account or queue name this deployment "
                + "cannot have meant is refused")
        void aMistrustedArnIsRefused(final String arn) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            SqsNamingRules.requireProductionQueueDestination(arn, KEY, REGION));
        }

        @ParameterizedTest(name = "region = [{0}]")
        @ValueSource(strings = {"", "   "})
        @DisplayName("a destination cannot be checked without a region, and that is reported as the "
                + "missing region rather than passed over as acceptable")
        void aMissingRegionIsReported(final String blankRegion) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> SqsNamingRules.requireProductionQueueDestination(
                            "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":" + QUEUE, KEY, blankRegion))
                    .withMessageContaining("region");
        }

        @Test
        @DisplayName("a null region is reported the same way, so the check can never be skipped by "
                + "supplying nothing to compare against")
        void aNullRegionIsReported() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> SqsNamingRules.requireProductionQueueDestination(
                            "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":" + QUEUE, KEY, null))
                    .withMessageContaining("region");
        }

        @Test
        @DisplayName("the region comparison ignores case, because a region is not case-significant and "
                + "refusing EU-WEST-2 would be a rule about spelling")
        void theRegionComparisonIgnoresCase() {
            assertThatNoException().isThrownBy(() -> SqsNamingRules.requireProductionQueueDestination(
                    "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":" + QUEUE, KEY,
                    REGION.toUpperCase(java.util.Locale.ROOT)));
        }

        @ParameterizedTest(name = "destination = {0}")
        @ValueSource(strings = {"ftp://sqs.eu-west-2.amazonaws.com/000000000000/JOBS.fifo",
            "//sqs.eu-west-2.amazonaws.com/000000000000/JOBS.fifo",
            "sqs:JOBS.fifo"})
        @DisplayName("a value that is none of the three forms is refused rather than falling through "
                + "to the bare-name rule")
        void anUnrecognisedFormIsRefused(final String destination) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> SqsNamingRules
                            .requireProductionQueueDestination(destination, KEY, REGION));
        }

        @Test
        @DisplayName("a refusal names the key and never repeats the configured value, so composing a "
                + "destination cannot place chosen text in a diagnostic")
        void aRefusalNamesTheKeyAndNotTheValue() {
            final String chosenText = "chosen-log-line.invalid";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> SqsNamingRules.requireProductionQueueDestination(
                            "https://" + chosenText + "/" + ACCOUNT + "/" + QUEUE, KEY, REGION))
                    .satisfies(refused -> {
                        assertThat(refused.getMessage()).contains(KEY);
                        assertThat(refused.getMessage()).doesNotContain(chosenText);
                    });
        }
    }
}
