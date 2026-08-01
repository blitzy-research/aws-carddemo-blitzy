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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.retries.DefaultRetryStrategy;

/**
 * Configures the messaging client that carries the online-to-batch job-submission bridge.
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
 * <p>The reasoning for one write being one attempt is recorded in {@code docs/decision-log.md} DL-095.
 */
@Configuration(proxyBeanMethods = false)
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
