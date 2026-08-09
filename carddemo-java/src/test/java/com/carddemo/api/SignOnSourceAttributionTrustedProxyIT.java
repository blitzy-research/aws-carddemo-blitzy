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
package com.carddemo.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Two genuinely different clients behind a named proxy keep two separate abuse-governor allowances.
 *
 * <h2>Why this half has to be proved as well</h2>
 *
 * <p>The companion specification requires a forwarded header from an <em>untrusted</em> peer to be ignored.
 * That requirement alone could be satisfied by withdrawing forwarded-header support altogether - and doing
 * so would break the deployment the production profile describes. Where transport security terminates at a
 * balancer, every request arrives from the balancer's address; ignoring its forwarded headers would collapse
 * every client into one source subject, so one abusive client would spend the allowance for everyone behind
 * it. That is an availability defect introduced while fixing a security one, and this specification is what
 * fails if it is.
 *
 * <p>The allow-list here therefore names the peer these requests actually arrive from, which is the shape of
 * a deployment behind a proxy the operator has named. Attempts claiming two different forwarded clients must
 * then be counted separately, and an attempt claiming a client whose allowance is already spent must still be
 * refused - the allowance follows the client, which is the whole point of honouring the header from a source
 * entitled to set it.
 *
 * <p>Every identifier is one no credential record carries and each is used once, so every identity allowance
 * stays at a single failure and the source subject is the only thing that can change the answer.
 *
 * @since 1.0.0
 */
@SpringBootTest(classes = AbstractSignOnSourceAttributionIT.SourceAttributionContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.batch.job.enabled=false",
            "spring.main.banner-mode=off",
            "management.endpoint.health.validate-group-membership=false",
            "management.tracing.enabled=false",
            // The boundary is reached over the loopback interface of the process that opened it, so
            // there is no wire for a session to be observed on. Nothing else is relaxed.
            "carddemo.security.require-https=false",
            // The mechanism under test, exactly as the production document declares it.
            "server.forward-headers-strategy=native",
            // An allow-list that DOES name the peer, which is what a deployment behind a balancer
            // configures. The production default names loopback for exactly this shape.
            "server.tomcat.remoteip.internal-proxies="
                    + SignOnSourceAttributionTrustedProxyIT.THE_PEER,
            AbstractSignOnSourceAttributionIT.ALLOWANCE_PROPERTY})
@DisplayName("behind a named proxy, two forwarded clients keep two sign-on source allowances")
class SignOnSourceAttributionTrustedProxyIT extends AbstractSignOnSourceAttributionIT {

    /**
     * The peer every request here arrives from, named as a trusted proxy.
     *
     * <p>Both loopback forms are named because a host may present either, and the value is a Java regular
     * expression matched against the peer address.
     */
    static final String THE_PEER = "127\\.0\\.0\\.1|0:0:0:0:0:0:0:1|::1";

    /** The first forwarded client, whose allowance the opening attempts spend. */
    private static final String FIRST_CLIENT = "203.0.113.20";

    /** A second, genuinely different forwarded client, which must have an allowance of its own. */
    private static final String SECOND_CLIENT = "198.51.100.21";

    /** Identifiers no credential record carries, one per attempt. */
    private static final String[] UNKNOWN_IDENTIFIERS = {
        "ITSRCT01", "ITSRCT02", "ITSRCT03", "ITSRCT04", "ITSRCT05"};

    /** Creates the specification. */
    SignOnSourceAttributionTrustedProxyIT() {
        super();
    }

    /**
     * One method for the same reason the companion specification uses one: the governor's state is
     * process-wide and its clock is pinned, so a spent allowance stays spent and a second method would
     * inherit this one's end state instead of starting from a fresh allowance.
     *
     * @throws IOException if a reply cannot be parsed
     */
    @Test
    @DisplayName("the first client's allowance is spent by three attempts, the second client's is "
            + "untouched, and a further attempt for the first client is still refused")
    void twoForwardedClientsBehindOneTrustedPeerAreCountedSeparately() throws IOException {
        assertConfiguredAllowance();

        // Spend the first client's allowance. Each attempt answers the not-found text, so each really did
        // reach a credential read rather than being refused ahead of one.
        for (int attempt = 0; attempt < ALLOWANCE; attempt++) {
            assertThat(signOnMessage(UNKNOWN_IDENTIFIERS[attempt], FIRST_CLIENT))
                    .as("attempt %d of %d for forwarded client %s", attempt + 1, ALLOWANCE,
                            FIRST_CLIENT)
                    .isEqualTo(USER_NOT_FOUND_MESSAGE);
        }

        // The separation assertion. Same peer, same allowance size, different client.
        assertThat(signOnMessage(UNKNOWN_IDENTIFIERS[ALLOWANCE], SECOND_CLIENT))
                .as("forwarded client %s has spent nothing, so its first attempt must be served. A "
                        + "refusal here would mean the two clients share one allowance, which is what "
                        + "ignoring the header from a TRUSTED peer would cause: one abusive client would "
                        + "lock out everyone behind the balancer", SECOND_CLIENT)
                .isEqualTo(USER_NOT_FOUND_MESSAGE);

        // And the allowance genuinely follows the client rather than being disabled: the first client is
        // still refused, on a fifth identifier it has never presented.
        assertThat(signOnMessage(UNKNOWN_IDENTIFIERS[ALLOWANCE + 1], FIRST_CLIENT))
                .as("forwarded client %s spent its allowance above, so a further attempt from it is "
                        + "refused without a credential read even though the identifier is new. This is "
                        + "what makes the case above a separation rather than an absence of counting",
                        FIRST_CLIENT)
                .isEqualTo(UNABLE_TO_VERIFY_MESSAGE);
    }
}
