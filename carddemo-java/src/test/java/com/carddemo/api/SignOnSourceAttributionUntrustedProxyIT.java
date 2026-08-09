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
 * A caller reaching the boundary directly cannot choose its own abuse-governor allowance.
 *
 * <h2>The vulnerability this closes, stated as the caller would exploit it</h2>
 *
 * <p>The governor's second subject is the caller address, and it is the only subject that catches an
 * enumeration sweep. Production previously selected a forwarded-header strategy that rewrites the request's
 * address from {@code X-Forwarded-For} <strong>whatever the peer</strong>. Transport security terminates in
 * this process, so a client can reach it directly - and a direct client that rotates one header value per
 * attempt presents each attempt as a new source. Every identifier tried once, every attempt a fresh source:
 * neither allowance ever accumulates, and the sweep runs unbounded while both protections look configured.
 *
 * <h2>What this specification does about it</h2>
 *
 * <p>It configures the container's trusted-proxy-aware strategy with an allow-list the peer does
 * <strong>not</strong> match - the boundary is reached over loopback and the list names a private address
 * that is not it - which is the shape of a deployment exposed directly. Four submissions are then made, each
 * naming a different forwarded client and each carrying a different unknown identifier, so that
 * <em>every</em> identity allowance stays at a single failure and only the source allowance can decide the
 * outcome. The allowance is three. If forwarded addresses were honoured, the fourth submission would be the
 * first of a brand-new source and would answer the not-found text; because they are ignored, all four share
 * the peer's allowance and the fourth is refused without any credential being read.
 *
 * <p>The companion specification proves the other half - that a peer the deployment <em>does</em> name is
 * still able to distinguish its clients - so this one cannot be satisfied by disabling the feature outright.
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
            // An allow-list the peer does not match. Loopback is deliberately absent: this is a
            // deployment reached directly, where a forwarded header is a claim by the client itself.
            "server.tomcat.remoteip.internal-proxies="
                    + SignOnSourceAttributionUntrustedProxyIT.AN_ADDRESS_THAT_IS_NOT_THE_PEER,
            AbstractSignOnSourceAttributionIT.ALLOWANCE_PROPERTY})
@DisplayName("a forwarded header from an untrusted peer cannot choose the sign-on source allowance")
class SignOnSourceAttributionUntrustedProxyIT extends AbstractSignOnSourceAttributionIT {

    /**
     * The one address this deployment would trust a forwarded header from, chosen so that it is not the
     * peer these requests actually arrive from.
     *
     * <p>A private-range literal rather than a pattern, so the allow-list is unambiguous, and it is
     * matched against the peer address rather than against anything a caller sends.
     */
    static final String AN_ADDRESS_THAT_IS_NOT_THE_PEER = "10\\.99\\.99\\.99";

    /**
     * Forwarded client addresses a sweeping caller would rotate through, all in the documentation ranges
     * reserved for examples so that none can be mistaken for a real host.
     */
    private static final String[] CLAIMED_CLIENTS = {
        "203.0.113.10", "203.0.113.11", "198.51.100.12", "192.0.2.13"};

    /** Identifiers no credential record carries, one per attempt, so no identity allowance accumulates. */
    private static final String[] UNKNOWN_IDENTIFIERS = {
        "ITSRCU01", "ITSRCU02", "ITSRCU03", "ITSRCU04", "ITSRCU05"};

    /** Creates the specification. */
    SignOnSourceAttributionUntrustedProxyIT() {
        super();
    }

    /**
     * The whole property in one method, because the governor's state is process-wide and its clock is
     * pinned: once a peer's allowance is spent it stays spent for the life of the context, so a second
     * method would begin where this one ends rather than from a fresh allowance. Splitting it would make
     * the second method's outcome depend on the first having run, which is a worse defect than length.
     *
     * @throws IOException if a reply cannot be parsed
     */
    @Test
    @DisplayName("attempts from one peer share one allowance however they claim to be forwarded: three "
            + "spend it, and neither a fresh forwarded client nor an absent header escapes it")
    void rotatingTheForwardedClientDoesNotBuyAFreshAllowance() throws IOException {
        assertConfiguredAllowance();

        // The first three spend the peer's allowance. Each answers the not-found text, which is the proof
        // that the allowance was still open and a credential read actually happened.
        for (int attempt = 0; attempt < ALLOWANCE; attempt++) {
            assertThat(signOnMessage(UNKNOWN_IDENTIFIERS[attempt], CLAIMED_CLIENTS[attempt]))
                    .as("attempt %d of %d claims forwarded client %s and identifier %s; while the "
                            + "allowance is open an unknown identifier answers the not-found text",
                            attempt + 1, ALLOWANCE, CLAIMED_CLIENTS[attempt],
                            UNKNOWN_IDENTIFIERS[attempt])
                    .isEqualTo(USER_NOT_FOUND_MESSAGE);
        }

        // The fourth is the assertion the vulnerability turned on. It is a new identifier and a new
        // claimed client, so nothing about it has been seen before EXCEPT the peer it came from.
        assertThat(signOnMessage(UNKNOWN_IDENTIFIERS[ALLOWANCE], CLAIMED_CLIENTS[ALLOWANCE]))
                .as("the fourth attempt claims a fourth forwarded client (%s) and a fourth unknown "
                        + "identifier (%s), so the only subject it shares with the first three is the "
                        + "peer address. Answering the not-found text here would mean the forwarded "
                        + "header chose the allowance, which is the bypass: a sweeping caller would "
                        + "rotate that header and never be refused",
                        CLAIMED_CLIENTS[ALLOWANCE], UNKNOWN_IDENTIFIERS[ALLOWANCE])
                .isEqualTo(UNABLE_TO_VERIFY_MESSAGE);

        // And the header's absence is not an escape either. A caller that cannot buy an allowance by
        // changing the value must not be able to buy one by omitting it, which would be the same bypass
        // with one fewer header.
        assertThat(signOnMessage(UNKNOWN_IDENTIFIERS[ALLOWANCE + 1], null))
                .as("a fifth attempt, fifth unknown identifier, no forwarded header at all. The subject "
                        + "is the peer either way, so this attempt finds the same allowance spent")
                .isEqualTo(UNABLE_TO_VERIFY_MESSAGE);
    }
}
