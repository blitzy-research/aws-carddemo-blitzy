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
package com.carddemo.config;

import io.awspring.cloud.autoconfigure.sqs.SqsAsyncClientCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.retries.DefaultRetryStrategy;

/**
 * Registers the settings of the online-to-batch job-submission bridge and configures the messaging
 * client that carries it.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>The legacy estate's entire online-to-batch bridge is a single transient-data queue write. The
 * reporting program emits its job stream one eighty-character card at a time, and each card is
 * written with one {@code WRITEQ TD} whose response it inspects immediately. There is no retry loop
 * around that write, no second attempt on a refused write, and no backoff: the queue is defined
 * ignore-on-error, so a refused write is recorded and the emitting loop stops.
 *
 * <p>The messaging client this module publishes through does not behave that way by default. Its
 * standard retry strategy treats a refused transport as transient and reissues the request, so one
 * logical card write can become several attempts on the wire. That is a behavioural difference rather
 * than a tuning preference, and it is observable in two ways that matter. A queue that accepted a
 * card on a later attempt would turn a failure the legacy program reports into a success it never
 * had, changing which cards reach the queue and therefore whether the submitted job stream is
 * complete. And a retried publish that eventually succeeds would deliver the card after the
 * publisher had already been told the write failed, which the append-ordered contract cannot absorb.
 *
 * <p>This class removes the retry so that one card write is one attempt, matching the legacy write
 * exactly.
 *
 * <h2>How the strategy reaches the client</h2>
 *
 * <p>The client itself is auto-configured, and it stays that way: nothing here builds a client,
 * resolves a region, resolves an endpoint or supplies a credential. The bean below is a customizer,
 * which the messaging auto-configuration collects and applies to the client builder after it has
 * finished configuring it. Two consequences follow from that ordering and both are relied upon here.
 * The retry strategy set by the customizer is applied last, so it is the one the built client uses.
 * And because the auto-configuration has already installed its own override configuration by then,
 * the customizer must extend that configuration rather than replace it.
 *
 * <p>That is why the body reads the builder's current override configuration and rebuilds from it.
 * The convenience form that accepts a consumer of a fresh configuration builder would look
 * equivalent and would not be: it constructs a new configuration from nothing and then replaces the
 * existing one wholesale, discarding the client-identification option the messaging library sets for
 * its own telemetry. Copying the current configuration and adding one setting to it preserves
 * everything the library established and changes only the retry behaviour.
 *
 * <h2>Scope of the change</h2>
 *
 * <p>This module has exactly one queue interaction - it publishes job-submission cards and consumes
 * nothing - so the client this customizer configures is the publisher's client and no other traffic
 * is affected by it. A separately named client and template dedicated to the publisher would isolate
 * it more literally while changing nothing observable, and would oblige the publisher to select its
 * collaborator by name rather than by type. With a single consumer there is nothing to select
 * between, so the one client is configured instead.
 *
 * <p>No timeout, no attempt count, no backoff interval, no pool size and no queue capacity is set
 * here. Removing the retry is the absence of a figure rather than the choice of one, which is what
 * makes it a parity statement rather than a tuning decision. The queue's remaining behavioural
 * attributes belong elsewhere and are unaffected: the fixed record width and the non-fatal failure
 * handling are enforced by the publisher, and the append ordering is carried by the first-in-
 * first-out queue and its single message group.
 *
 * <h2>Why this class also owns the settings type</h2>
 *
 * <p>{@link AwsProperties} is self-annotated only. It carries no stereotype annotation and nothing
 * scans for it, so it becomes a bean exactly where a configuration class enables it. This class is
 * that place, and it is the only one: two registrations of one settings type are two bean
 * definitions of it, and the second is discovered as a context failure rather than as a duplicate.
 * Placing the registration here keeps the binding beside the messaging configuration that the
 * bucket, queue, message-group and topic names describe, and it mirrors how the security
 * configuration owns its own signing settings type.
 *
 * <p>Nothing here reads a value out of the bound settings. The publisher resolves the queue and
 * message-group names it needs directly from the property keys, because the messaging layer must not
 * depend on this package. Registration exists so that the {@code carddemo.aws} tree is bound and
 * validated once during context startup, which is what turns a missing or malformed setting into a
 * startup failure instead of a failure on the first publish.
 *
 * <p>The reasoning for one write being one attempt is recorded in {@code docs/decision-log.md} DL-095.
 *
 * <h2>The settings this class owns</h2>
 *
 * <p>{@link AwsProperties} is <strong>self-annotated only</strong>: it carries no stereotype and
 * nothing scans for it, so it becomes a bean exactly where a configuration class enables it. This
 * class is that single owner, and the enabling annotation above is the whole of the registration.
 * One owner is deliberate rather than incidental - two registrations of one settings type are two
 * bean definitions of it, and the second is discovered as a context failure rather than as a
 * duplicate - which is why no other configuration class enables it and why none may.
 *
 * <p><strong>Registration is what makes the settings' validation a start-up gate rather than a
 * decoration.</strong> Unregistered, the type binds nowhere, its presence constraints are never
 * evaluated, and its refusal of a queue name that does not carry the first-in-first-out suffix is
 * never reached - so the single easiest misconfiguration in this namespace would be discovered at
 * the first submission, by which point an operator sees a failed report request instead of a failed
 * deployment. Registered here, a missing bucket, queue, message group, topic or key prefix, and a
 * well-formed queue name that is merely not a first-in-first-out name, all stop the application
 * before it serves a request. The publisher reads the same key paths from the same environment, so
 * the values the settings validated are necessarily the values it publishes with.
 *
 * <p>The publisher itself does <strong>not</strong> receive this object. The layering forbids it:
 * a service may depend on the repository, domain, utility and exception layers, and this
 * configuration layer is not among them, so {@code com.carddemo.service.JobSubmissionService} binds
 * the two queue key paths it needs directly and names them through its own constants. The two sets
 * of constants are held to each other by a test that fails the build if they ever diverge, so the
 * arrangement is a checked invariant rather than a convention.
 *
 * <p><strong>What is deliberately not in this namespace.</strong> The region, the endpoint
 * redirection and the credentials are the messaging, object-store and notification clients' own
 * configuration, under {@code spring.cloud.aws}, because that is the namespace the integration's
 * auto-configuration reads when it builds those clients. Declaring them a second time under this
 * module's own prefix would create two sources of truth for one setting, and for the endpoint it
 * would be actively dangerous: an absent endpoint override does not fail closed, it resolves the
 * region's real public endpoint. The region is required of a production deployment, with no
 * fallback, by {@link ProductionConfigurationValidator}, which also requires the job-submission
 * queue - so both are start-up-validated, each in the namespace that consumes it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AwsProperties.class)
public class AwsConfig {

    /**
     * Configures the job-submission client to make exactly one attempt per publish.
     *
     * <p>The customizer copies the override configuration the messaging auto-configuration has
     * already applied to the builder and adds the no-retry strategy to that copy, so the client
     * retains its library-supplied identification and gains single-attempt behaviour. Replacing the
     * configuration instead of extending it would silently drop the former.
     *
     * @return a customizer that installs the no-retry strategy on the queue client builder; never
     *         {@code null}
     */
    @Bean
    public SqsAsyncClientCustomizer singleAttemptSqsClientCustomizer() {
        return builder -> builder.overrideConfiguration(builder.overrideConfiguration()
                .toBuilder()
                .retryStrategy(DefaultRetryStrategy.doNotRetry())
                .build());
    }
}
