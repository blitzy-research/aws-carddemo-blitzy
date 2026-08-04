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

import com.carddemo.config.AwsConfig;
import com.carddemo.config.AwsProperties;
import com.carddemo.exception.JobSubmissionException;
import com.carddemo.support.AbstractLocalStackIT;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import io.awspring.cloud.sqs.operations.SqsOperations;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder;
import software.amazon.awssdk.services.sqs.model.ListQueuesRequest;

/**
 * Verifies the two runtime properties of the job-submission bridge that no unit test can establish.
 *
 * <h2>Why these need a running service and a real transport</h2>
 *
 * <p>Both properties are behaviours of the messaging client rather than of this module's own code, so
 * both are invisible to a test that substitutes a double for the client. The first is what happens
 * when the destination queue cannot be resolved, which is decided inside the publishing template. The
 * second is how many times a refused request reaches the wire, which is decided inside the client's
 * retry strategy. A double would answer whatever the test told it to and would prove nothing about
 * either.
 *
 * <h2>Absent queues are refused rather than created</h2>
 *
 * <p>The publishing template's own default is to create a queue whose name it cannot resolve. That
 * default is comfortable and wrong here. The legacy queue was defined to exist before first use, so
 * provisioning is the environment's job and never the application's; and a name that is well formed
 * but wrong - a typo, or a value carried over from another environment - would be created on the spot,
 * the submission would report complete, and the cards would sit in a queue that nothing consumes. The
 * failure would be silent and would look like success.
 *
 * <p>The shipped configuration therefore fixes the strategy to refusal, and the first group below
 * shows what that produces: a well-formed name for a queue that does not exist fails the submission
 * without raising to the caller, and no queue comes into being.
 *
 * <h2>One card write is one attempt</h2>
 *
 * <p>The legacy program writes one card with one queue write and inspects that write's response
 * immediately. The client's standard retry strategy instead treats a refused transport as transient
 * and reissues, so one logical write becomes several on the wire.
 *
 * <p>The second group counts attempts directly, by pointing the client at a socket this test owns and
 * closing every connection it accepts. Each attempt is one accepted connection, so the count is the
 * attempt count. The two clients differ only in whether the module's customizer was applied, which is
 * what makes the comparison evidence of cause rather than of coincidence: the uncustomized client is
 * asserted to make more than one attempt, and the customized client exactly one.
 *
 * <p>The uncustomized assertion is deliberately "more than one" rather than an exact figure. The
 * client's default attempt count is the library's to choose and may change; what must not change is
 * that this module does not inherit it.
 */
@DisplayName("job-submission bridge: absent queues are refused, and one write is one attempt")
class JobSubmissionQueueBridgeIT extends AbstractLocalStackIT {

    /** The canonical message group, exactly as the shipped configuration declares it. */
    private static final String MESSAGE_GROUP_ID = "carddemo-job-submission";

    /** Card count the legacy paragraph transmits, sentinel included. */
    private static final int CARD_COUNT = 17;

    /** Reporting-period start, of the shape the report screen collects. */
    private static final String START_DATE = "2022-01-01";

    /** Reporting-period end, of the shape the report screen collects. */
    private static final String END_DATE = "2022-07-06";

    /** The attempt count the legacy queue write made: the write, and nothing after it. */
    private static final int LEGACY_ATTEMPTS_PER_WRITE = 1;

    /** Seconds allowed for a submission against a socket that refuses every connection. */
    private static final int REFUSED_TRANSPORT_TIMEOUT_SECONDS = 60;

    /** The socket standing in for an unreachable queue endpoint; closed after each test. */
    private ConnectionCountingEndpoint endpoint;

    @AfterEach
    void closeEndpoint() throws IOException {
        if (endpoint != null) {
            endpoint.close();
            endpoint = null;
        }
    }

    @Nested
    @DisplayName("a queue that does not exist")
    class AQueueThatDoesNotExist {

        @Test
        @DisplayName("is refused rather than created, so a wrong-but-valid name cannot look like a "
                + "successful submission")
        void isRefusedRatherThanCreated() {
            final String absentButValidName = "absent-" + UUID.randomUUID() + FIFO_SUFFIX;
            final JobSubmissionService service = new JobSubmissionService(
                    refusingTemplate(), absentButValidName, MESSAGE_GROUP_ID);

            final JobSubmissionService.SubmissionResult result =
                    service.submitTransactionReportJob(submissionId(), START_DATE, END_DATE);

            assertThat(result.cardsRequested()).isEqualTo(CARD_COUNT);
            assertThat(result.cardsPublished())
                    .as("the name is well formed and the queue is absent, which is precisely the "
                            + "case the template's own default would resolve by creating it")
                    .isZero();
            assertThat(result.failed()).isTrue();
            assertThat(result.complete()).isFalse();
            assertThat(result.failureMessage())
                    .as("the operator sees the frozen literal, exactly as the legacy screen did")
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);

            assertThat(queueExists(absentButValidName))
                    .as("provisioning belongs to the environment; the legacy queue existed before "
                            + "first use and the application never created it")
                    .isFalse();
        }

        @Test
        @DisplayName("does not raise to the caller, because the queue definition ignores write errors")
        void doesNotRaiseToTheCaller() {
            final JobSubmissionService service = new JobSubmissionService(refusingTemplate(),
                    "absent-" + UUID.randomUUID() + FIFO_SUFFIX, MESSAGE_GROUP_ID);

            assertThat(service.submitTransactionReportJob(submissionId(), START_DATE, END_DATE))
                    .as("a refusal is reported through the result; raising would abort a request "
                            + "the legacy transaction completed")
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("a refused transport")
    class ARefusedTransport {

        @Test
        @Timeout(REFUSED_TRANSPORT_TIMEOUT_SECONDS)
        @DisplayName("is attempted exactly once, matching the single legacy queue write")
        void isAttemptedExactlyOnce() throws IOException {
            endpoint = new ConnectionCountingEndpoint();
            final JobSubmissionService service = new JobSubmissionService(
                    SqsTemplate.newSyncTemplate(clientAgainstEndpoint(true)),
                    "unreachable" + FIFO_SUFFIX, MESSAGE_GROUP_ID);

            final JobSubmissionService.SubmissionResult result =
                    service.submitTransactionReportJob(submissionId(), START_DATE, END_DATE);

            assertThat(endpoint.attemptsReachingTheWire())
                    .as("one refused write reached the wire once; a reissue would have delivered a "
                            + "card after the publisher was already told the write failed")
                    .isEqualTo(LEGACY_ATTEMPTS_PER_WRITE);
            assertThat(result.failed())
                    .as("one attempt, and one non-fatal result from it")
                    .isTrue();
            assertThat(result.cardsPublished()).isZero();
        }

        @Test
        @Timeout(REFUSED_TRANSPORT_TIMEOUT_SECONDS)
        @DisplayName("would be reissued without the module's customizer, which is what the customizer "
                + "exists to prevent")
        void wouldBeReissuedWithoutTheCustomizer() throws IOException {
            endpoint = new ConnectionCountingEndpoint();
            final JobSubmissionService service = new JobSubmissionService(
                    SqsTemplate.newSyncTemplate(clientAgainstEndpoint(false)),
                    "unreachable" + FIFO_SUFFIX, MESSAGE_GROUP_ID);

            service.submitTransactionReportJob(submissionId(), START_DATE, END_DATE);

            assertThat(endpoint.attemptsReachingTheWire())
                    .as("the client's own default reissues a refused request, so this module must "
                            + "not inherit it; the exact figure is the library's to choose")
                    .isGreaterThan(LEGACY_ATTEMPTS_PER_WRITE);
        }
    }

    /**
     * Builds a publishing template that refuses an unresolvable queue instead of creating one.
     *
     * <p>This mirrors what the shipped configuration produces. The auto-configuration forwards the
     * declared strategy onto the template it builds; a template constructed here carries the library
     * default instead, so the strategy is stated explicitly rather than inherited. That the shipped
     * configuration declares it, and that a profile overlay does not displace it, is asserted
     * separately over the configuration documents themselves.
     *
     * @return a template bound to the emulator that refuses an absent queue
     */
    private static SqsOperations refusingTemplate() {
        return SqsTemplate.builder()
                .sqsAsyncClient(sqsAsyncClient())
                .configure(options ->
                        options.queueNotFoundStrategy(QueueNotFoundStrategy.FAIL))
                .buildSyncTemplate();
    }

    /**
     * Builds a client pointed at this test's own socket, optionally customized by the module.
     *
     * @param applyModuleCustomizer whether to apply the module's single-attempt customizer
     * @return a client whose every request reaches the counting socket
     */
    private SqsAsyncClient clientAgainstEndpoint(final boolean applyModuleCustomizer) {
        final SqsAsyncClientBuilder builder = SqsAsyncClient.builder()
                .region(Region.of(emulatorRegion()))
                .endpointOverride(URI.create("http://127.0.0.1:" + endpoint.port()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")));
        if (applyModuleCustomizer) {
            new AwsConfig(settingsWithoutRedirection()).singleAttemptSqsClientCustomizer()
                    .customize(builder);
        }
        return builder.build();
    }

    /**
     * Builds the module settings the customizer reads, deliberately carrying no endpoint redirection.
     *
     * <p>The customizer applies the configured region unconditionally and a redirection only when one
     * is configured. Withholding the redirection is what leaves the counting socket above as the
     * client's endpoint, which is the whole point of this fixture: a redirection supplied here would
     * aim the client at the emulator instead and the attempts this test counts would never arrive. The
     * region is the emulator's own, so applying it changes nothing the builder above did not already
     * say.</p>
     *
     * @return settings naming this emulator's region, with no endpoint redirection
     */
    private static AwsProperties settingsWithoutRedirection() {
        return new AwsProperties(
                emulatorRegion(),
                null,
                new AwsProperties.S3("carddemo-batch-staging"),
                new AwsProperties.Sqs("carddemo-jobs" + FIFO_SUFFIX, MESSAGE_GROUP_ID),
                new AwsProperties.Sns("carddemo-job-notifications"));
    }

    /**
     * Reports whether a queue of the given name exists on the emulator.
     *
     * @param queueName the queue name to look for
     * @return {@code true} when the emulator holds a queue of that name
     */
    private static boolean queueExists(final String queueName) {
        return !sqsAsyncClient()
                .listQueues(ListQueuesRequest.builder().queueNamePrefix(queueName).build())
                .join()
                .queueUrls()
                .isEmpty();
    }

    /**
     * Produces a submission identity unique to one invocation, so no assertion depends on another.
     *
     * @return a fresh submission identity
     */
    private static String submissionId() {
        return "queue-bridge-" + UUID.randomUUID();
    }

    /**
     * A socket that answers every request with a retryable server error and counts the requests.
     *
     * <h2>Why it answers rather than simply closing</h2>
     *
     * <p>An earlier form of this class closed each connection as soon as it accepted it, and counted
     * accepted connections. That measured the wrong thing. A connection closed before any response
     * is a transport fault, and the asynchronous client's connection pool reacts to a transport fault
     * on its own account - re-establishing connections independently of the retry strategy - so the
     * connection count came out an order of magnitude above the request count and told us nothing
     * about how many times the request was issued.
     *
     * <p>Answering with a well-formed {@code 500} instead gives a clean measurement. The status is a
     * server error, which the client's default strategy classifies as retryable, so the count is
     * exactly the number of times the strategy chose to issue the request: one when retries are
     * removed, more than one when they are not. One request served is one attempt.
     *
     * <p>The request is read before the response is written, because a server that answers without
     * draining what was sent to it can reset the connection and reintroduce the transport fault this
     * class exists to avoid.
     *
     * <p>The accept loop runs on a daemon thread so a test failure cannot keep the build alive, and
     * it exits when the socket is closed.
     */
    private static final class ConnectionCountingEndpoint implements AutoCloseable {

        /** A retryable server error, answered to every request, with no body and no keep-alive. */
        private static final String SERVER_ERROR_RESPONSE = "HTTP/1.1 500 Internal Server Error\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n"
                + "\r\n";

        /** Largest request this endpoint will drain before answering. */
        private static final int REQUEST_DRAIN_LIMIT = 64 * 1024;

        /** The listening socket, bound to a port the operating system chooses. */
        private final ServerSocket serverSocket;

        /** Requests answered so far, one per attempt that reached the wire. */
        private final AtomicInteger requestsServed = new AtomicInteger();

        /**
         * Binds the socket and starts accepting.
         *
         * @throws IOException if the socket cannot be bound
         */
        ConnectionCountingEndpoint() throws IOException {
            this.serverSocket = new ServerSocket(0);
            final Thread acceptor = new Thread(this::serveUntilClosed, "carddemo-erroring-endpoint");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        /** Answers every request with a retryable server error until the socket is closed. */
        private void serveUntilClosed() {
            while (!this.serverSocket.isClosed()) {
                try (Socket connection = this.serverSocket.accept()) {
                    drainRequest(connection);
                    // Counted once the request has been read, so the count is requests and not
                    // connections: the pool may open more connections than the strategy issues
                    // requests.
                    this.requestsServed.incrementAndGet();
                    connection.getOutputStream()
                            .write(SERVER_ERROR_RESPONSE.getBytes(StandardCharsets.US_ASCII));
                    connection.getOutputStream().flush();
                } catch (final IOException closedOrAbandoned) {
                    if (this.serverSocket.isClosed()) {
                        return;
                    }
                }
            }
        }

        /**
         * Reads the request head, so the answer is written to a drained connection.
         *
         * @param connection the accepted connection
         * @throws IOException if the connection fails while being read
         */
        private static void drainRequest(final Socket connection) throws IOException {
            final java.io.InputStream request = connection.getInputStream();
            int read = 0;
            int consecutiveTerminators = 0;
            while (read < REQUEST_DRAIN_LIMIT && consecutiveTerminators < 2) {
                final int next = request.read();
                if (next < 0) {
                    return;
                }
                read++;
                if (next == '\n') {
                    consecutiveTerminators++;
                } else if (next != '\r') {
                    consecutiveTerminators = 0;
                }
            }
        }

        /**
         * The port the socket is listening on.
         *
         * @return the bound port
         */
        int port() {
            return this.serverSocket.getLocalPort();
        }

        /**
         * The number of attempts that reached the wire.
         *
         * @return the number of requests answered
         */
        int attemptsReachingTheWire() {
            return this.requestsServed.get();
        }

        @Override
        public void close() throws IOException {
            this.serverSocket.close();
        }
    }
}
