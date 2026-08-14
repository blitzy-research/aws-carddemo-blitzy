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

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Specification of which traces this deployment records, and of who does not get to decide it.
 *
 * <h2>The defect this pins down</h2>
 *
 * <p>A trace context arrives on the wire, in a request header, from whoever chose to send it. The
 * framework's own sampler is the one-argument parent-based form, which leaves its remote-parent-sampled arm
 * at always-on - so any caller presenting a {@code traceparent} whose sampled flag is set had every span of
 * that request recorded and exported, whatever the configured ratio said, and the header is read before any
 * credential is examined. In production the configured ratio is one request in ten and the arriving header
 * decided the other nine.
 *
 * <p>That is a cost channel and a fidelity problem at once: an unauthenticated caller could hold the flag on
 * and drive exporter volume, collector storage and log-correlation fan-out at will, and a baseline gathered
 * from a population a caller selected is not a sample of this deployment's traffic.
 *
 * <h2>What each nest establishes</h2>
 *
 * <p>The remote arms are re-decided and the local arms are not, so there are three populations to separate
 * and each has a nest. A <strong>remote</strong> parent that says sampled must be put through the local
 * ratio, which is asserted at both extremes of the ratio so the assertion cannot pass by coincidence. A
 * remote parent that says not-sampled must stay off, because a caller asking for less is entitled to it and
 * inventing traces it did not ask for is the same defect facing the other way. A <strong>local</strong>
 * parent - one of this application's own spans - must be followed exactly, because a span that sampled itself
 * into a trace must not have its children discarded halfway down or the exported trace is a fragment
 * presented as a whole.
 *
 * <h2>Harness</h2>
 *
 * <p>A surefire unit test over the bean method, driven with hand-built trace contexts. No context is booted
 * and no exporter exists: the sampler is declared unconditionally precisely so that the policy can be
 * asserted without standing up a tracing stack.
 */
@DisplayName("Head sampling: the ratio is this deployment's decision, not a caller's")
class TraceSamplingPolicyTest {

    /** A trace identifier that the zero and one ratios settle without ambiguity. */
    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    /** A span identifier for the parent. */
    private static final String SPAN_ID = "b7ad6b7169203331";

    /** The class under test, which reads only the ratio. */
    private final ObservabilityConfig subject = new ObservabilityConfig("carddemo");

    /**
     * Builds a context carrying one parent span.
     *
     * @param  remote  whether the parent arrived from another process
     * @param  sampled whether the parent says it was sampled
     * @return a context a sampler can be asked about
     */
    private static Context parent(final boolean remote, final boolean sampled) {
        final TraceFlags flags = sampled ? TraceFlags.getSampled() : TraceFlags.getDefault();
        final SpanContext spanContext = remote
                ? SpanContext.createFromRemoteParent(TRACE_ID, SPAN_ID, flags, TraceState.getDefault())
                : SpanContext.create(TRACE_ID, SPAN_ID, flags, TraceState.getDefault());
        return Context.root().with(Span.wrap(spanContext));
    }

    /**
     * Asks a sampler built at one ratio for its verdict on one context.
     *
     * @param  probability the configured ratio
     * @param  context     the context to judge
     * @return the decision reached
     */
    private SamplingDecision decisionAt(final double probability, final Context context) {
        final Sampler sampler = this.subject.traceSampler(probability);
        return sampler.shouldSample(context, TRACE_ID, "GET /api/reports/request", SpanKind.SERVER,
                Attributes.empty(), List.of()).getDecision();
    }

    @Nested
    @DisplayName("a remote parent that says sampled is re-decided locally")
    class ARemoteSampledParentIsReDecided {

        @Test
        @DisplayName("is dropped when the local ratio is zero, which the framework's own sampler would "
                + "have recorded - this single case is the finding")
        void isDroppedWhenTheLocalRatioIsZero() {
            assertThat(decisionAt(0.0d, parent(true, true)))
                    .as("the header no longer decides; the ratio does")
                    .isEqualTo(SamplingDecision.DROP);
        }

        @Test
        @DisplayName("is recorded when the local ratio is one, so the fix is a re-decision and not a "
                + "blanket refusal of anything that arrived from outside")
        void isRecordedWhenTheLocalRatioIsOne() {
            assertThat(decisionAt(1.0d, parent(true, true)))
                    .isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
        }

        @Test
        @DisplayName("reaches the same verdict every time it is asked, because the ratio sampler is "
                + "deterministic on the trace identifier and a trace must not be half exported")
        void reachesTheSameVerdictEveryTime() {
            final Sampler sampler = subject.traceSampler(0.5d);
            final Context context = parent(true, true);

            final SamplingDecision first = sampler.shouldSample(context, TRACE_ID, "first",
                    SpanKind.SERVER, Attributes.empty(), List.of()).getDecision();
            for (int attempt = 0; attempt < 50; attempt++) {
                assertThat(sampler.shouldSample(context, TRACE_ID, "again", SpanKind.INTERNAL,
                        Attributes.empty(), List.of()).getDecision())
                        .as("every span of one trace reaches one verdict")
                        .isEqualTo(first);
            }
        }

        @Test
        @DisplayName("is re-decided for every distinct caller and not just for one, so a population of "
                + "remote traces cannot lift this deployment's rate above the ratio")
        void isReDecidedForEveryDistinctCaller() {
            final Sampler sampler = subject.traceSampler(0.0d);
            final Context context = parent(true, true);

            for (int caller = 0; caller < 50; caller++) {
                // A distinct, well-formed identifier per caller: sixteen bytes of hexadecimal, of which
                // the ratio sampler reads the trailing eight, so these are genuinely different draws.
                final String traceId =
                        String.format(Locale.ROOT, "0af7651916cd43dd%016x", Long.valueOf(caller));

                assertThat(sampler.shouldSample(context, traceId, "inbound", SpanKind.SERVER,
                        Attributes.empty(), List.of()).getDecision())
                        .as("caller %d arrived with a sampled header; the ratio still decides", caller)
                        .isEqualTo(SamplingDecision.DROP);
            }
        }
    }

    @Nested
    @DisplayName("a remote parent that says not sampled stays off")
    class ARemoteUnsampledParentStaysOff {

        @Test
        @DisplayName("is dropped even at a local ratio of one, because a caller asking for less is asking "
                + "for something it is entitled to")
        void isDroppedEvenAtRatioOne() {
            assertThat(decisionAt(1.0d, parent(true, false)))
                    .as("inventing traces a caller did not ask for is the same defect facing the other way")
                    .isEqualTo(SamplingDecision.DROP);
        }
    }

    @Nested
    @DisplayName("a local parent is followed exactly, so one trace is never exported as a fragment")
    class ALocalParentIsFollowed {

        @Test
        @DisplayName("a local sampled parent is recorded even at a ratio of zero, because its children "
                + "belong to a trace that already exists")
        void aLocalSampledParentIsRecorded() {
            assertThat(decisionAt(0.0d, parent(false, true)))
                    .as("discarding children halfway down would export a fragment as a whole trace")
                    .isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
        }

        @Test
        @DisplayName("a local unsampled parent stays dropped even at a ratio of one, for the same reason "
                + "read the other way")
        void aLocalUnsampledParentStaysDropped() {
            assertThat(decisionAt(1.0d, parent(false, false)))
                    .isEqualTo(SamplingDecision.DROP);
        }
    }

    @Nested
    @DisplayName("a trace this deployment starts itself is decided by the ratio alone")
    class ARootTraceUsesTheRatio {

        @Test
        @DisplayName("is dropped at a ratio of zero and recorded at a ratio of one, so the figure the "
                + "profile states is the figure that applies")
        void followsTheConfiguredRatio() {
            assertThat(decisionAt(0.0d, Context.root())).isEqualTo(SamplingDecision.DROP);
            assertThat(decisionAt(1.0d, Context.root())).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
        }

        @Test
        @DisplayName("describes itself, so an operator reading the exported configuration can see which "
                + "policy is in force")
        void describesItself() {
            assertThat(subject.traceSampler(0.1d).getDescription())
                    .as("the description names the parent-based composition and its arms")
                    .containsIgnoringCase("parent");
        }
    }
}
