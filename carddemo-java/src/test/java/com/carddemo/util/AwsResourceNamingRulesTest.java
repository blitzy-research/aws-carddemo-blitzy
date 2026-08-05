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

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds each non-queue AWS configuration grammar to its service boundary.
 *
 * <p>The settings record also exercises these rules through Spring's configuration binder. These
 * tests deliberately sit one layer lower: every public rule is called directly, so a binder default,
 * conversion or annotation cannot hide a defect in the grammar itself. In particular, the accepted
 * boundary values prove that the rules do not reject deployable names, while the hostile values prove
 * that an impossible resource fails at start-up rather than after a batch execution has produced an
 * artifact or reached its terminal notification.
 *
 * <p>The assertions also hold the diagnostic contract recorded in {@code docs/decision-log.md}
 * DL-041: a refusal names the property that an operator must repair and never repeats the configured
 * value. The endpoint parser receives extra scrutiny because the JDK's syntax exception normally
 * quotes its input; the production rule intentionally detaches that cause.
 */
@DisplayName("AwsResourceNamingRules: fail-fast grammar for non-queue AWS settings")
class AwsResourceNamingRulesTest {

    /** Configuration key for the region shared by all three clients. */
    private static final String REGION_PROPERTY = "carddemo.aws.region";

    /** Configuration key for the object-store staging bucket. */
    private static final String BUCKET_PROPERTY = "carddemo.aws.s3.batch-staging-bucket";

    /** Configuration key for the terminal-notification topic. */
    private static final String TOPIC_PROPERTY = "carddemo.aws.sns.job-notification-topic";

    /** Configuration key for the emulator endpoint override. */
    private static final String ENDPOINT_PROPERTY = "carddemo.aws.endpoint-override";

    @Nested
    @DisplayName("the region rule")
    class TheRegionRule {

        @ParameterizedTest(name = "[{0}] is region-shaped")
        @ValueSource(strings = {
            "us-east-1",
            "eu-west-2",
            "ap-southeast-3",
            "us-gov-west-1",
            "cn-north-1",
            "il-central-1",
            "us-iso-east-1",
            "eusc-de-east-1",
            "us-east-12"})
        @DisplayName("accepts published region shapes without freezing a list of current regions")
        void everyPublishedShapeIsAccepted(final String region) {
            assertThat(AwsResourceNamingRules.requireRegion(region, REGION_PROPERTY))
                    .as("the configured spelling is returned unchanged")
                    .isSameAs(region);
        }

        @Test
        @DisplayName("accepts exactly the documented length ceiling and refuses one character more")
        void theLengthBoundaryIsExact() {
            final String atLimit = "us-" + "a".repeat(59) + "-1";
            final String overLimit = "us-" + "a".repeat(60) + "-1";

            assertThat(atLimit).hasSize(AwsResourceNamingRules.REGION_MAX_LENGTH);
            assertThat(AwsResourceNamingRules.requireRegion(atLimit, REGION_PROPERTY))
                    .isSameAs(atLimit);

            assertThat(overLimit).hasSize(AwsResourceNamingRules.REGION_MAX_LENGTH + 1);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireRegion(overLimit, REGION_PROPERTY))
                    .withMessageContaining(REGION_PROPERTY)
                    .withMessageContaining(
                            String.valueOf(AwsResourceNamingRules.REGION_MAX_LENGTH));
        }

        @ParameterizedTest(name = "[{0}] is refused")
        @ValueSource(strings = {
            "",
            "US-EAST-1",
            "us_east_1",
            "useast1",
            "us-east",
            "us-east-x",
            "us-east-1a",
            "us-east-123",
            "u-east-1",
            "united-east-1",
            "us--1",
            "us-east-\u0661"})
        @DisplayName("refuses a value outside the lower-case hyphenated shape")
        void aMalformedRegionIsRefused(final String region) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireRegion(region, REGION_PROPERTY))
                    .withMessageContaining(REGION_PROPERTY)
                    .withMessageContaining("region name");
        }

        @Test
        @DisplayName("refuses a missing value and names the property that is missing")
        void aNullRegionIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireRegion(null, REGION_PROPERTY))
                    .withMessageContaining(REGION_PROPERTY)
                    .withMessageContaining("region");
        }

        @Test
        @DisplayName("does not repeat a chosen malformed value in its diagnostic")
        void aRegionRefusalDoesNotEchoTheValue() {
            final String chosenText = "CHOSEN-REGION-TEXT";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireRegion(chosenText, REGION_PROPERTY))
                    .satisfies(refused -> {
                        assertThat(refused.getMessage()).contains(REGION_PROPERTY);
                        assertThat(refused.getMessage()).doesNotContain(chosenText);
                    });
        }
    }

    @Nested
    @DisplayName("the object-store bucket rule")
    class TheBucketRule {

        @ParameterizedTest(name = "[{0}] is a legal bucket name")
        @ValueSource(strings = {
            "abc",
            "carddemo-batch-staging",
            "batch.staging.example",
            "a1b",
            "0-starts-with-a-digit",
            "ends-with-9"})
        @DisplayName("accepts lower-case names composed from the service's permitted characters")
        void everyPermittedShapeIsAccepted(final String bucket) {
            assertThat(AwsResourceNamingRules.requireBucketName(bucket, BUCKET_PROPERTY))
                    .isSameAs(bucket);
        }

        @Test
        @DisplayName("accepts both inclusive length boundaries and refuses either side")
        void theLengthBoundariesAreExact() {
            final String minimum = "abc";
            final String maximum = "a" + "b".repeat(61) + "c";

            assertThat(minimum).hasSize(AwsResourceNamingRules.BUCKET_MIN_LENGTH);
            assertThat(maximum).hasSize(AwsResourceNamingRules.BUCKET_MAX_LENGTH);
            assertThatNoException().isThrownBy(() ->
                    AwsResourceNamingRules.requireBucketName(minimum, BUCKET_PROPERTY));
            assertThatNoException().isThrownBy(() ->
                    AwsResourceNamingRules.requireBucketName(maximum, BUCKET_PROPERTY));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireBucketName("ab", BUCKET_PROPERTY))
                    .withMessageContaining(BUCKET_PROPERTY)
                    .withMessageContaining(
                            String.valueOf(AwsResourceNamingRules.BUCKET_MIN_LENGTH));

            final String overMaximum = "a" + "b".repeat(62) + "c";
            assertThat(overMaximum).hasSize(AwsResourceNamingRules.BUCKET_MAX_LENGTH + 1);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireBucketName(
                                    overMaximum, BUCKET_PROPERTY))
                    .withMessageContaining(BUCKET_PROPERTY)
                    .withMessageContaining(
                            String.valueOf(AwsResourceNamingRules.BUCKET_MAX_LENGTH));
        }

        @ParameterizedTest(name = "[{0}] contains a forbidden character")
        @ValueSource(strings = {
            "Upper-case",
            "bucket_name",
            "bucket/name",
            "bucket:name",
            "bucket name",
            "bucket+name",
            "bucket\u00e9"})
        @DisplayName("refuses any character outside lower-case ASCII, digits, hyphens and dots")
        void aForbiddenCharacterIsRefused(final String bucket) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireBucketName(bucket, BUCKET_PROPERTY))
                    .withMessageContaining(BUCKET_PROPERTY)
                    .withMessageContaining("position");
        }

        @ParameterizedTest(name = "[{0}] has an illegal edge or dot sequence")
        @ValueSource(strings = {
            "-bucket",
            ".bucket",
            "bucket-",
            "bucket.",
            "bucket..name"})
        @DisplayName("refuses an illegal edge or two adjacent dots")
        void anIllegalEdgeOrDotSequenceIsRefused(final String bucket) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireBucketName(bucket, BUCKET_PROPERTY))
                    .withMessageContaining(BUCKET_PROPERTY);
        }

        @Test
        @DisplayName("refuses a dotted-quad address even though every character is otherwise legal")
        void aDottedQuadAddressIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AwsResourceNamingRules.requireBucketName(
                            "192.168.5.4", BUCKET_PROPERTY))
                    .withMessageContaining(BUCKET_PROPERTY)
                    .withMessageContaining("dotted-quad");
        }

        @ParameterizedTest(name = "[{0}] carries a reserved prefix")
        @ValueSource(strings = {
            "xn--reserved",
            "sthree-reserved",
            "amzn-s3-demo-reserved"})
        @DisplayName("refuses every service-reserved prefix")
        void aReservedPrefixIsRefused(final String bucket) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireBucketName(bucket, BUCKET_PROPERTY))
                    .withMessageContaining(BUCKET_PROPERTY)
                    .withMessageContaining("reserved prefix");
        }

        @ParameterizedTest(name = "[{0}] carries a reserved suffix")
        @ValueSource(strings = {
            "reserved-s3alias",
            "reserved--ol-s3",
            "reserved.mrap",
            "reserved--x-s3",
            "reserved--table-s3"})
        @DisplayName("refuses every service-reserved suffix")
        void aReservedSuffixIsRefused(final String bucket) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireBucketName(bucket, BUCKET_PROPERTY))
                    .withMessageContaining(BUCKET_PROPERTY)
                    .withMessageContaining("reserved suffix");
        }

        @Test
        @DisplayName("refuses a missing bucket and does not repeat a chosen malformed name")
        void aMissingOrMalformedBucketIsReportedWithoutEchoingIt() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireBucketName(null, BUCKET_PROPERTY))
                    .withMessageContaining(BUCKET_PROPERTY);

            final String chosenText = "chosen_bucket_value";
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireBucketName(
                                    chosenText, BUCKET_PROPERTY))
                    .satisfies(refused -> {
                        assertThat(refused.getMessage()).contains(BUCKET_PROPERTY);
                        assertThat(refused.getMessage()).doesNotContain(chosenText);
                    });
        }
    }

    @Nested
    @DisplayName("the notification-topic rule")
    class TheTopicRule {

        @ParameterizedTest(name = "[{0}] is a legal bare topic name")
        @ValueSource(strings = {
            "carddemo-job-notifications",
            "CardDemo_Job_Notifications",
            "topic",
            "TOPIC-9",
            "a"})
        @DisplayName("accepts each permitted character in a bare standard-topic name")
        void aLegalBareNameIsAccepted(final String topic) {
            assertThat(AwsResourceNamingRules.requireTopicDestination(topic, TOPIC_PROPERTY))
                    .isSameAs(topic);
        }

        @ParameterizedTest(name = "partition = {0}")
        @ValueSource(strings = {"aws", "aws-cn", "aws-us-gov"})
        @DisplayName("accepts a topic ARN in every recognised partition and returns it unchanged")
        void aLegalArnIsAccepted(final String partition) {
            final String topic = "arn:" + partition
                    + ":sns:us-east-1:000000000000:carddemo-job-notifications";

            assertThat(AwsResourceNamingRules.requireTopicDestination(topic, TOPIC_PROPERTY))
                    .isSameAs(topic);
        }

        @Test
        @DisplayName("accepts a name at the exact ceiling and refuses one character more")
        void theNameLengthBoundaryIsExact() {
            final String atLimit = "t".repeat(AwsResourceNamingRules.TOPIC_NAME_MAX_LENGTH);
            final String overLimit = "t" + atLimit;

            assertThat(AwsResourceNamingRules.requireTopicDestination(atLimit, TOPIC_PROPERTY))
                    .isSameAs(atLimit);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireTopicDestination(
                                    overLimit, TOPIC_PROPERTY))
                    .withMessageContaining(TOPIC_PROPERTY)
                    .withMessageContaining(
                            String.valueOf(AwsResourceNamingRules.TOPIC_NAME_MAX_LENGTH));
        }

        @ParameterizedTest(name = "[{0}] is not a standard-topic name")
        @ValueSource(strings = {
            "",
            "topic.with.dot",
            "topic with space",
            "topic/slash",
            "topic:colon",
            "topic+plus",
            "topic\u00e9"})
        @DisplayName("refuses an empty name or a character unavailable to a standard topic")
        void anIllegalBareNameIsRefused(final String topic) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireTopicDestination(topic, TOPIC_PROPERTY))
                    .withMessageContaining(TOPIC_PROPERTY);
        }

        @ParameterizedTest(name = "ARN = {0}")
        @ValueSource(strings = {
            "arn:aws:sns:us-east-1:000000000000",
            "arn:aws:sns:us-east-1:000000000000:topic:extra",
            "arn:aws-fictional:sns:us-east-1:000000000000:topic",
            "arn:aws:sqs:us-east-1:000000000000:topic",
            "arn:aws:sns:US-EAST-1:000000000000:topic",
            "arn:aws:sns:us-east-1:00000000000:topic",
            "arn:aws:sns:us-east-1:00000000000a:topic",
            "arn:aws:sns:us-east-1:000000000000:",
            "arn:aws:sns:us-east-1:000000000000:topic.with.dot"})
        @DisplayName("refuses an ARN with a malformed envelope or resource name")
        void aMalformedArnIsRefused(final String topic) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireTopicDestination(topic, TOPIC_PROPERTY))
                    .withMessageContaining(TOPIC_PROPERTY);
        }

        @Test
        @DisplayName("holds a topic name inside an ARN to the same 256-character ceiling")
        void theArnResourceNameHasTheSameLengthCeiling() {
            final String oversizedName =
                    "t".repeat(AwsResourceNamingRules.TOPIC_NAME_MAX_LENGTH + 1);
            final String topic = "arn:aws:sns:us-east-1:000000000000:" + oversizedName;

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireTopicDestination(topic, TOPIC_PROPERTY))
                    .withMessageContaining(TOPIC_PROPERTY)
                    .withMessageContaining(
                            String.valueOf(AwsResourceNamingRules.TOPIC_NAME_MAX_LENGTH));
        }

        @Test
        @DisplayName("refuses a missing topic and never repeats a malformed destination")
        void aMissingOrMalformedTopicIsReportedWithoutEchoingIt() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireTopicDestination(null, TOPIC_PROPERTY))
                    .withMessageContaining(TOPIC_PROPERTY);

            final String chosenText =
                    "arn:aws-fictional:sns:us-east-1:000000000000:chosen-topic";
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireTopicDestination(
                                    chosenText, TOPIC_PROPERTY))
                    .satisfies(refused -> {
                        assertThat(refused.getMessage()).contains(TOPIC_PROPERTY);
                        assertThat(refused.getMessage()).doesNotContain(chosenText);
                    });
        }
    }

    @Nested
    @DisplayName("the endpoint-override rule")
    class TheEndpointRule {

        @ParameterizedTest(name = "[{0}] is a legal base address")
        @ValueSource(strings = {
            "http://localhost:4566",
            "https://example.test",
            "HTTP://LOCALHOST:4566",
            "https://127.0.0.1:65535/base",
            "http://[::1]:4566",
            "https://example.test/path/prefix"})
        @DisplayName("accepts plain and secure base addresses with a host and optional path")
        void aLegalBaseAddressIsAccepted(final String endpoint) {
            assertThat(AwsResourceNamingRules.requireEndpointOverride(
                    endpoint, ENDPOINT_PROPERTY))
                    .isEqualTo(URI.create(endpoint));
        }

        @Test
        @DisplayName("trims endpoint padding before parsing because the value is an address, not a name")
        void endpointPaddingIsIgnored() {
            assertThat(AwsResourceNamingRules.requireEndpointOverride(
                    "  http://localhost:4566/base  ", ENDPOINT_PROPERTY))
                    .isEqualTo(URI.create("http://localhost:4566/base"));
        }

        @Test
        @DisplayName("accepts exactly the documented length ceiling and refuses one character more")
        void theLengthBoundaryIsExact() {
            final String prefix = "https://example.test/";
            final String atLimit = prefix + "a".repeat(
                    AwsResourceNamingRules.ENDPOINT_OVERRIDE_MAX_LENGTH - prefix.length());
            final String overLimit = atLimit + "a";

            assertThat(atLimit)
                    .hasSize(AwsResourceNamingRules.ENDPOINT_OVERRIDE_MAX_LENGTH);
            assertThatNoException().isThrownBy(() ->
                    AwsResourceNamingRules.requireEndpointOverride(
                            atLimit, ENDPOINT_PROPERTY));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireEndpointOverride(
                                    overLimit, ENDPOINT_PROPERTY))
                    .withMessageContaining(ENDPOINT_PROPERTY)
                    .withMessageContaining(
                            String.valueOf(AwsResourceNamingRules.ENDPOINT_OVERRIDE_MAX_LENGTH));
        }

        @ParameterizedTest(name = "[{0}] uses an unsupported or absent transport")
        @ValueSource(strings = {
            "ftp://example.test",
            "file:///tmp/localstack",
            "s3://bucket/key",
            "jar:https://example.test/archive.jar",
            "gopher://example.test",
            "//example.test/path",
            "relative/path"})
        @DisplayName("refuses any address outside the two transports the clients speak")
        void anUnsupportedTransportIsRefused(final String endpoint) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireEndpointOverride(
                                    endpoint, ENDPOINT_PROPERTY))
                    .withMessageContaining(ENDPOINT_PROPERTY)
                    .withMessageContaining("http");
        }

        @ParameterizedTest(name = "[{0}] carries no host")
        @ValueSource(strings = {
            "http:/path",
            "https:///path",
            "http:opaque",
            "https://:4566"})
        @DisplayName("refuses a transport address that carries no host")
        void anAddressWithoutAHostIsRefused(final String endpoint) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireEndpointOverride(
                                    endpoint, ENDPOINT_PROPERTY))
                    .withMessageContaining(ENDPOINT_PROPERTY)
                    .withMessageContaining("host");
        }

        @Test
        @DisplayName("refuses embedded credentials without publishing either credential")
        void embeddedCredentialsAreRefusedWithoutEcho() {
            final String endpoint = "https://chosen-user:chosen-password@example.test/base";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireEndpointOverride(
                                    endpoint, ENDPOINT_PROPERTY))
                    .satisfies(refused -> {
                        assertThat(refused.getMessage()).contains(ENDPOINT_PROPERTY);
                        assertThat(refused.getMessage()).contains("credentials");
                        assertThat(refused.getMessage()).doesNotContain("chosen-user");
                        assertThat(refused.getMessage()).doesNotContain("chosen-password");
                    });
        }

        @ParameterizedTest(name = "[{0}] is a request rather than a base address")
        @ValueSource(strings = {
            "https://example.test/base?chosen=query",
            "https://example.test/base#chosen-fragment"})
        @DisplayName("refuses a query or fragment because an endpoint override must be a base address")
        void aRequestRatherThanABaseAddressIsRefused(final String endpoint) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireEndpointOverride(
                                    endpoint, ENDPOINT_PROPERTY))
                    .withMessageContaining(ENDPOINT_PROPERTY)
                    .withMessageContaining("base address");
        }

        @Test
        @DisplayName("refuses an out-of-range port before a client builder sees it")
        void anOutOfRangePortIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AwsResourceNamingRules.requireEndpointOverride(
                            "http://localhost:65536", ENDPOINT_PROPERTY))
                    .withMessageContaining(ENDPOINT_PROPERTY)
                    .withMessageContaining("port");
        }

        @Test
        @DisplayName("refuses malformed syntax without attaching the JDK exception that quotes input")
        void malformedSyntaxIsRefusedWithoutEchoOrCause() {
            final String chosenText = "chosen malformed host";
            final String endpoint = "https://" + chosenText + "/base";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireEndpointOverride(
                                    endpoint, ENDPOINT_PROPERTY))
                    .satisfies(refused -> {
                        assertThat(refused.getMessage()).contains(ENDPOINT_PROPERTY);
                        assertThat(refused.getMessage()).doesNotContain(chosenText);
                        assertThat(refused.getCause()).isNull();
                    });
        }

        @Test
        @DisplayName("refuses a missing endpoint and names the missing property")
        void aNullEndpointIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            AwsResourceNamingRules.requireEndpointOverride(
                                    null, ENDPOINT_PROPERTY))
                    .withMessageContaining(ENDPOINT_PROPERTY)
                    .withMessageContaining("endpoint override");
        }
    }
}