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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.carddemo.util.AwsResourcePolicyRules.PolicyPosture;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds the resource-policy rule to the two properties it exists to decide, in both directions.
 *
 * <h2>Why both directions are asserted, and why the negative direction is the larger half</h2>
 *
 * <p>A rule that refuses everything would satisfy every hostile case here and would refuse every
 * correctly-provisioned account, so the sound cases are as load-bearing as the refusals: the shape the
 * local bootstrap hook writes must be accepted, and so must the shape the notification service attaches
 * to a topic by itself, which names every principal and is confined by a condition. The refusals then
 * cover each way a document can fail - absent, unreadable, open, or silent about transport - and each way
 * an <em>almost</em>-sound document can fail, which is where a structural rule is most likely to be
 * fooled: a denial that covers one action rather than every action, one principal rather than every
 * principal, or the wrong condition operator.
 *
 * <h2>Every document here is written out by hand</h2>
 *
 * <p>None is read from the bootstrap hook or from the production verifier, so nothing in this class is
 * asserted against itself. The agreement between the hook's documents and this rule is established
 * separately, where the hook's own output is judged by this very rule after being read back out of a
 * running emulator.
 *
 * <p>No account, role, bucket, queue or topic named below exists. The identifiers are synthetic and
 * authorise nothing.
 */
@DisplayName("AwsResourcePolicyRules: an attached policy opens the resource to nobody and refuses "
        + "plain transport")
class AwsResourcePolicyRulesTest {

    /** The object store's action namespace. */
    private static final String OBJECT_STORE = "s3";

    /** The queue service's action namespace. */
    private static final String QUEUE = "sqs";

    /** The notification service's action namespace. */
    private static final String TOPIC = "sns";

    /** A synthetic account, for a condition that confines a grant. */
    private static final String ACCOUNT = "000000000000";

    /** A synthetic role, for a statement that names one principal rather than every principal. */
    private static final String ROLE = "arn:aws:iam::000000000000:role/synthetic";

    /**
     * The statement that satisfies the transport rule, parameterised by service.
     *
     * @param  namespace the service's action namespace
     * @return a deny statement covering every action of that service over plain transport
     */
    private static String transportDenial(final String namespace) {
        return String.format(Locale.ROOT, """
                {"Sid":"DenyInsecureTransport","Effect":"Deny","Principal":"*",\
                "Action":"%s:*","Resource":"*",\
                "Condition":{"Bool":{"aws:SecureTransport":"false"}}}\
                """, namespace);
    }

    /**
     * Wraps statements in a policy document.
     *
     * @param  statements the statements, already rendered and comma-separated by the caller
     * @return the document
     */
    private static String policy(final String statements) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}";
    }

    @Nested
    @DisplayName("a sound document")
    class ASoundDocument {

        @Test
        @DisplayName("is the one the local bootstrap writes: a single denial of every action of the "
                + "service over plain transport")
        void isASingleTransportDenial() {
            assertThat(AwsResourcePolicyRules.postureOf(policy(transportDenial(OBJECT_STORE)),
                    OBJECT_STORE))
                    .isEqualTo(PolicyPosture.SOUND);
            assertThat(AwsResourcePolicyRules.postureOf(policy(transportDenial(QUEUE)), QUEUE))
                    .isEqualTo(PolicyPosture.SOUND);
            assertThat(AwsResourcePolicyRules.postureOf(policy(transportDenial(TOPIC)), TOPIC))
                    .isEqualTo(PolicyPosture.SOUND);
        }

        @Test
        @DisplayName("may carry the notification service's own default grant alongside it, because a "
                + "grant confined by a condition is not an open grant")
        void mayCarryAConditionedGrantAlongsideTheDenial() {
            // This is the shape a topic has after the service creates it and a transport denial is
            // added: every principal is named, and a condition on the owning account is what confines
            // it. A rule that read "Principal *" as open would refuse every correctly-provisioned
            // topic, which is the failure mode that matters most here because it would be discovered
            // only by the deployment that could not start.
            final String defaultGrant = String.format(Locale.ROOT, """
                    {"Sid":"OwnerStatement","Effect":"Allow","Principal":{"AWS":"*"},\
                    "Action":["sns:Publish","sns:GetTopicAttributes"],"Resource":"*",\
                    "Condition":{"StringEquals":{"AWS:SourceOwner":"%s"}}}\
                    """, ACCOUNT);

            assertThat(AwsResourcePolicyRules.postureOf(
                    policy(defaultGrant + "," + transportDenial(TOPIC)), TOPIC))
                    .isEqualTo(PolicyPosture.SOUND);
        }

        @Test
        @DisplayName("may deny every action of every service rather than of one, because the wider "
                + "denial covers the narrower requirement")
        void mayDenyEveryActionOfEveryService() {
            final String denial = """
                    {"Effect":"Deny","Principal":"*","Action":"*","Resource":"*",\
                    "Condition":{"Bool":{"aws:SecureTransport":"false"}}}\
                    """;

            assertThat(AwsResourcePolicyRules.postureOf(policy(denial), OBJECT_STORE))
                    .isEqualTo(PolicyPosture.SOUND);
        }

        @Test
        @DisplayName("may use the operator that also fires when the transport key is absent, which is "
                + "the stricter of the two")
        void mayUseTheIfExistsOperator() {
            final String denial = """
                    {"Effect":"Deny","Principal":"*","Action":"s3:*","Resource":"*",\
                    "Condition":{"BoolIfExists":{"aws:SecureTransport":"false"}}}\
                    """;

            assertThat(AwsResourcePolicyRules.postureOf(policy(denial), OBJECT_STORE))
                    .isEqualTo(PolicyPosture.SOUND);
        }

        @Test
        @DisplayName("may write its element names and its keyword values in any case, because the "
                + "services compare them that way and a document written so takes effect there")
        void mayWriteItsElementNamesInAnyCase() {
            final String denial = """
                    {"effect":"DENY","principal":"*","action":"S3:*","resource":"*",\
                    "condition":{"bool":{"AWS:SECURETRANSPORT":"FALSE"}}}\
                    """;

            assertThat(AwsResourcePolicyRules.postureOf(policy(denial), OBJECT_STORE))
                    .isEqualTo(PolicyPosture.SOUND);
        }

        @Test
        @DisplayName("may declare its single statement as an object rather than as a one-element "
                + "array, because the services accept that form")
        void mayDeclareOneStatementAsAnObject() {
            final String document = "{\"Version\":\"2012-10-17\",\"Statement\":"
                    + transportDenial(QUEUE) + "}";

            assertThat(AwsResourcePolicyRules.postureOf(document, QUEUE))
                    .isEqualTo(PolicyPosture.SOUND);
        }

        @Test
        @DisplayName("may list the actions it denies as an array, so long as the service's wildcard is "
                + "among them")
        void mayListItsDeniedActionsAsAnArray() {
            final String denial = """
                    {"Effect":"Deny","Principal":"*","Action":["sqs:SendMessage","sqs:*"],\
                    "Resource":"*","Condition":{"Bool":{"aws:SecureTransport":"false"}}}\
                    """;

            assertThat(AwsResourcePolicyRules.postureOf(policy(denial), QUEUE))
                    .isEqualTo(PolicyPosture.SOUND);
        }

        @Test
        @DisplayName("is judged against the namespace the caller declares, folded to lower case, so a "
                + "caller that shouts the service name is not refused for it")
        void isJudgedAgainstTheDeclaredNamespaceFolded() {
            assertThat(AwsResourcePolicyRules.postureOf(policy(transportDenial(OBJECT_STORE)), " S3 "))
                    .isEqualTo(PolicyPosture.SOUND);
        }
    }

    @Nested
    @DisplayName("a document that is not there")
    class ADocumentThatIsNotThere {

        @Test
        @DisplayName("reads as absent when the service reported none")
        void readsAsAbsentWhenTheServiceReportedNone() {
            assertThat(AwsResourcePolicyRules.postureOf(null, QUEUE))
                    .isEqualTo(PolicyPosture.ABSENT);
        }

        @ParameterizedTest(name = "[{0}] reads as absent rather than as unreadable")
        @ValueSource(strings = {"", " ", "\t", "\n"})
        @DisplayName("reads as absent when it holds nothing, because an empty attribute and a missing "
                + "one are the same provisioning omission")
        void readsAsAbsentWhenItHoldsNothing(final String document) {
            assertThat(AwsResourcePolicyRules.postureOf(document, QUEUE))
                    .isEqualTo(PolicyPosture.ABSENT);
        }
    }

    @Nested
    @DisplayName("a document this rule will not draw a conclusion from")
    class ADocumentThisRuleWillNotConclude {

        @Test
        @DisplayName("is unreadable when it is not JSON at all")
        void isUnreadableWhenItIsNotJson() {
            assertThat(AwsResourcePolicyRules.postureOf("Effect: Deny", OBJECT_STORE))
                    .isEqualTo(PolicyPosture.UNREADABLE);
        }

        @ParameterizedTest(name = "[{0}] is not an object, so it is unreadable")
        @ValueSource(strings = {"[]", "\"a policy\"", "17", "true", "null"})
        @DisplayName("is unreadable when its root is anything but an object")
        void isUnreadableWhenItsRootIsNotAnObject(final String document) {
            assertThat(AwsResourcePolicyRules.postureOf(document, OBJECT_STORE))
                    .isEqualTo(PolicyPosture.UNREADABLE);
        }

        @Test
        @DisplayName("is unreadable when it declares no statement, which is a document that permits "
                + "nothing and requires nothing")
        void isUnreadableWhenItDeclaresNoStatement() {
            assertThat(AwsResourcePolicyRules.postureOf("{\"Version\":\"2012-10-17\"}", OBJECT_STORE))
                    .isEqualTo(PolicyPosture.UNREADABLE);
        }

        @Test
        @DisplayName("is unreadable when its statement list is empty")
        void isUnreadableWhenItsStatementListIsEmpty() {
            assertThat(AwsResourcePolicyRules.postureOf(policy(""), OBJECT_STORE))
                    .isEqualTo(PolicyPosture.UNREADABLE);
        }

        @Test
        @DisplayName("is unreadable when its statement list holds anything that is not a statement, "
                + "and the sound statement beside it does not rescue it")
        void isUnreadableWhenAStatementIsNotAnObject() {
            // Fail-closed: a document this rule cannot read completely is one it must not pass, even
            // when the part it can read would satisfy both rules on its own.
            assertThat(AwsResourcePolicyRules.postureOf(
                    policy("\"not a statement\"," + transportDenial(OBJECT_STORE)), OBJECT_STORE))
                    .isEqualTo(PolicyPosture.UNREADABLE);
        }

        @Test
        @DisplayName("is unreadable when its statement element is neither an object nor an array")
        void isUnreadableWhenTheStatementElementIsAScalar() {
            assertThat(AwsResourcePolicyRules.postureOf(
                    "{\"Version\":\"2012-10-17\",\"Statement\":\"everything\"}", OBJECT_STORE))
                    .isEqualTo(PolicyPosture.UNREADABLE);
        }

        @Test
        @DisplayName("is unreadable when it is longer than the ceiling the services themselves place "
                + "on a policy, so one malformed document cannot decide how much work this costs")
        void isUnreadableWhenItExceedsTheServiceCeiling() {
            final String padding = "x".repeat(AwsResourcePolicyRules.MAX_POLICY_DOCUMENT_LENGTH);
            final String oversized = policy(transportDenial(OBJECT_STORE)
                    + ",{\"Sid\":\"" + padding + "\"}");

            assertThat(oversized.length())
                    .isGreaterThan(AwsResourcePolicyRules.MAX_POLICY_DOCUMENT_LENGTH);
            assertThat(AwsResourcePolicyRules.postureOf(oversized, OBJECT_STORE))
                    .isEqualTo(PolicyPosture.UNREADABLE);
        }
    }

    @Nested
    @DisplayName("a document that opens the resource")
    class ADocumentThatOpensTheResource {

        @Test
        @DisplayName("is refused when it allows the bare wildcard principal with no condition")
        void isRefusedForABareWildcardPrincipal() {
            final String grant = """
                    {"Effect":"Allow","Principal":"*","Action":"s3:GetObject","Resource":"*"}\
                    """;

            assertThat(AwsResourcePolicyRules.postureOf(policy(grant), OBJECT_STORE))
                    .isEqualTo(PolicyPosture.PUBLICLY_GRANTED);
        }

        @Test
        @DisplayName("is refused when the wildcard is qualified, because a qualifier names a kind of "
                + "principal rather than a principal")
        void isRefusedForAQualifiedWildcardPrincipal() {
            final String grant = """
                    {"Effect":"Allow","Principal":{"AWS":"*"},"Action":"sqs:SendMessage",\
                    "Resource":"*"}\
                    """;

            assertThat(AwsResourcePolicyRules.postureOf(policy(grant), QUEUE))
                    .isEqualTo(PolicyPosture.PUBLICLY_GRANTED);
        }

        @Test
        @DisplayName("is refused when the wildcard hides in a list beside a named principal, which is "
                + "the form that reads as a restriction")
        void isRefusedForAWildcardInAListOfPrincipals() {
            final String grant = String.format(Locale.ROOT, """
                    {"Effect":"Allow","Principal":{"AWS":["%s","*"]},"Action":"sns:Publish",\
                    "Resource":"*"}\
                    """, ROLE);

            assertThat(AwsResourcePolicyRules.postureOf(policy(grant), TOPIC))
                    .isEqualTo(PolicyPosture.PUBLICLY_GRANTED);
        }

        @Test
        @DisplayName("is refused when it allows every principal except a named few, because that is a "
                + "grant to every other principal")
        void isRefusedForAnAllowCarryingNotPrincipal() {
            final String grant = String.format(Locale.ROOT, """
                    {"Effect":"Allow","NotPrincipal":{"AWS":"%s"},"Action":"s3:*","Resource":"*"}\
                    """, ROLE);

            assertThat(AwsResourcePolicyRules.postureOf(policy(grant), OBJECT_STORE))
                    .isEqualTo(PolicyPosture.PUBLICLY_GRANTED);
        }

        @Test
        @DisplayName("is refused when its condition element is present but empty, because an empty "
                + "condition confines nothing")
        void isRefusedForAnEmptyConditionElement() {
            final String grant = """
                    {"Effect":"Allow","Principal":"*","Action":"s3:*","Resource":"*",\
                    "Condition":{}}\
                    """;

            assertThat(AwsResourcePolicyRules.postureOf(policy(grant), OBJECT_STORE))
                    .isEqualTo(PolicyPosture.PUBLICLY_GRANTED);
        }

        @Test
        @DisplayName("is refused for the open grant even when a sound transport denial sits beside it, "
                + "because the graver finding is the one reported")
        void isRefusedForTheOpenGrantEvenBesideASoundDenial() {
            final String grant = """
                    {"Effect":"Allow","Principal":"*","Action":"s3:GetObject","Resource":"*"}\
                    """;

            assertThat(AwsResourcePolicyRules.postureOf(
                    policy(transportDenial(OBJECT_STORE) + "," + grant), OBJECT_STORE))
                    .isEqualTo(PolicyPosture.PUBLICLY_GRANTED);
        }

        @Test
        @DisplayName("is not refused for a grant to a named principal, which is what every deployment "
                + "that works at all carries")
        void isNotRefusedForAGrantToANamedPrincipal() {
            final String grant = String.format(Locale.ROOT, """
                    {"Effect":"Allow","Principal":{"AWS":"%s"},"Action":"s3:*","Resource":"*"}\
                    """, ROLE);

            assertThat(AwsResourcePolicyRules.postureOf(
                    policy(grant + "," + transportDenial(OBJECT_STORE)), OBJECT_STORE))
                    .isEqualTo(PolicyPosture.SOUND);
        }
    }

    @Nested
    @DisplayName("a document that permits plain transport")
    class ADocumentThatPermitsPlainTransport {

        @Test
        @DisplayName("is refused when it carries no denial at all")
        void isRefusedWhenItCarriesNoDenial() {
            final String grant = String.format(Locale.ROOT, """
                    {"Effect":"Allow","Principal":{"AWS":"%s"},"Action":"s3:*","Resource":"*"}\
                    """, ROLE);

            assertThat(AwsResourcePolicyRules.postureOf(policy(grant), OBJECT_STORE))
                    .isEqualTo(PolicyPosture.INSECURE_TRANSPORT_PERMITTED);
        }

        @Test
        @DisplayName("is refused when the denial covers one action rather than every action, which is "
                + "the near miss a structural rule is most likely to be fooled by")
        void isRefusedWhenTheDenialCoversOneAction() {
            final String denial = """
                    {"Effect":"Deny","Principal":"*","Action":"s3:GetObject","Resource":"*",\
                    "Condition":{"Bool":{"aws:SecureTransport":"false"}}}\
                    """;

            assertThat(AwsResourcePolicyRules.postureOf(policy(denial), OBJECT_STORE))
                    .isEqualTo(PolicyPosture.INSECURE_TRANSPORT_PERMITTED);
        }

        @Test
        @DisplayName("is refused when the denial names one principal rather than every principal")
        void isRefusedWhenTheDenialNamesOnePrincipal() {
            final String denial = String.format(Locale.ROOT, """
                    {"Effect":"Deny","Principal":{"AWS":"%s"},"Action":"s3:*","Resource":"*",\
                    "Condition":{"Bool":{"aws:SecureTransport":"false"}}}\
                    """, ROLE);

            assertThat(AwsResourcePolicyRules.postureOf(policy(denial), OBJECT_STORE))
                    .isEqualTo(PolicyPosture.INSECURE_TRANSPORT_PERMITTED);
        }

        @Test
        @DisplayName("is refused when the denial covers another service's actions, so a policy copied "
                + "between resources does not satisfy the rule for both")
        void isRefusedWhenTheDenialCoversAnotherService() {
            assertThat(AwsResourcePolicyRules.postureOf(policy(transportDenial(QUEUE)), OBJECT_STORE))
                    .isEqualTo(PolicyPosture.INSECURE_TRANSPORT_PERMITTED);
        }

        @Test
        @DisplayName("is refused when the denial fires on secure transport instead, which denies the "
                + "traffic this deployment actually sends")
        void isRefusedWhenTheDenialFiresOnSecureTransport() {
            final String denial = """
                    {"Effect":"Deny","Principal":"*","Action":"s3:*","Resource":"*",\
                    "Condition":{"Bool":{"aws:SecureTransport":"true"}}}\
                    """;

            assertThat(AwsResourcePolicyRules.postureOf(policy(denial), OBJECT_STORE))
                    .isEqualTo(PolicyPosture.INSECURE_TRANSPORT_PERMITTED);
        }

        @Test
        @DisplayName("is refused when the denial tests the transport key with an operator that does "
                + "not compare a boolean")
        void isRefusedWhenTheDenialUsesAnotherOperator() {
            final String denial = """
                    {"Effect":"Deny","Principal":"*","Action":"s3:*","Resource":"*",\
                    "Condition":{"StringEquals":{"aws:SecureTransport":"false"}}}\
                    """;

            assertThat(AwsResourcePolicyRules.postureOf(policy(denial), OBJECT_STORE))
                    .isEqualTo(PolicyPosture.INSECURE_TRANSPORT_PERMITTED);
        }

        @Test
        @DisplayName("is refused when the denial carries no condition, so it denies everything to "
                + "everybody and would have been noticed - but not by this rule")
        void isRefusedWhenTheDenialCarriesNoCondition() {
            final String denial = """
                    {"Effect":"Deny","Principal":"*","Action":"s3:*","Resource":"*"}\
                    """;

            assertThat(AwsResourcePolicyRules.postureOf(policy(denial), OBJECT_STORE))
                    .isEqualTo(PolicyPosture.INSECURE_TRANSPORT_PERMITTED);
        }

        @Test
        @DisplayName("is refused when the denial's effect is a word the policy language does not "
                + "define, because an unrecognised effect takes no effect")
        void isRefusedWhenTheEffectIsUnrecognised() {
            final String denial = """
                    {"Effect":"Refuse","Principal":"*","Action":"s3:*","Resource":"*",\
                    "Condition":{"Bool":{"aws:SecureTransport":"false"}}}\
                    """;

            assertThat(AwsResourcePolicyRules.postureOf(policy(denial), OBJECT_STORE))
                    .isEqualTo(PolicyPosture.INSECURE_TRANSPORT_PERMITTED);
        }

        @Test
        @DisplayName("is refused when the transport value is a boolean literal rather than the string "
                + "the condition language uses, which is fail-closed rather than lenient")
        void isRefusedWhenTheTransportValueIsABooleanLiteral() {
            final String denial = """
                    {"Effect":"Deny","Principal":"*","Action":"s3:*","Resource":"*",\
                    "Condition":{"Bool":{"aws:SecureTransport":false}}}\
                    """;

            assertThat(AwsResourcePolicyRules.postureOf(policy(denial), OBJECT_STORE))
                    .isEqualTo(PolicyPosture.INSECURE_TRANSPORT_PERMITTED);
        }
    }

    @Nested
    @DisplayName("the declared service namespace")
    class TheDeclaredServiceNamespace {

        @Test
        @DisplayName("must be supplied, because the denial has to cover every action of one named "
                + "service")
        void mustBeSupplied() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> AwsResourcePolicyRules.postureOf(
                            policy(transportDenial(OBJECT_STORE)), null));
        }

        @ParameterizedTest(name = "[{0}] is not a service namespace")
        @ValueSource(strings = {"", " ", "s3:", "s3 storage", "s3*", "s_3"})
        @DisplayName("must hold only lower-case letters and digits, so nothing can be smuggled into "
                + "the wildcard the denial is matched against")
        void mustHoldOnlyLettersAndDigits(final String namespace) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AwsResourcePolicyRules.postureOf(
                            policy(transportDenial(OBJECT_STORE)), namespace));
        }

        @Test
        @DisplayName("is refused before the document is read, so a malformed namespace is reported as "
                + "itself rather than as an unreadable policy")
        void isRefusedBeforeTheDocumentIsRead() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AwsResourcePolicyRules.postureOf("not json at all", "s3!"));
        }
    }

    @Nested
    @DisplayName("what this rule publishes")
    class WhatThisRulePublishes {

        @Test
        @DisplayName("is a verdict and nothing else: no verdict name carries a principal, an action, "
                + "an account or any part of the document")
        void isAVerdictAndNothingElse() {
            // The caller refuses a start-up with whatever it is handed, and a start-up refusal is
            // rendered into the deployment log. The whole of what can travel from here is one of five
            // authored constants, and this is the assertion that says so.
            final String openDocument = policy("""
                    {"Sid":"AnythingSecret","Effect":"Allow","Principal":"*","Action":"s3:*",\
                    "Resource":"arn:aws:s3:::a-bucket-nobody-should-learn-about/*"}\
                    """);

            final PolicyPosture posture =
                    AwsResourcePolicyRules.postureOf(openDocument, OBJECT_STORE);

            assertThat(posture).isEqualTo(PolicyPosture.PUBLICLY_GRANTED);
            assertThat(posture.name())
                    .doesNotContain("a-bucket-nobody-should-learn-about")
                    .doesNotContain("AnythingSecret");
        }

        @Test
        @DisplayName("names the transport condition key once, so a refusal and the rule that produced "
                + "it cannot spell it differently")
        void namesTheTransportConditionKeyOnce() {
            assertThat(AwsResourcePolicyRules.SECURE_TRANSPORT_CONDITION_KEY)
                    .isEqualTo("aws:SecureTransport");
        }
    }
}
