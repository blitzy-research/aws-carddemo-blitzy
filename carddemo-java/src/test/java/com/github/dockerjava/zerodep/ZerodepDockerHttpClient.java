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
package com.github.dockerjava.zerodep;

import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.transport.SSLConfig;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Binary-compatibility adapter from Testcontainers 1.21.x's fixed transport class name to the
 * non-shaded Docker Java HttpClient 5 transport.
 *
 * <p>Testcontainers 1.21.4 directly constructs
 * {@code com.github.dockerjava.zerodep.ZerodepDockerHttpClient.Builder}; it does not discover a
 * transport implementation. The corresponding zerodep artifact embeds an unmanaged HTTP Core copy
 * that carries two HIGH findings. This test-scope adapter preserves the constructor surface
 * Testcontainers calls while delegating every operation to {@link ApacheDockerHttpClient}, whose
 * ordinary dependencies remain visible to Maven dependency management and dependency-check.</p>
 *
 * <p>The class intentionally occupies the binary name Testcontainers hardcodes. It implements no
 * Docker protocol itself, retains no request state and is never packaged into the application jar.</p>
 */
public final class ZerodepDockerHttpClient implements DockerHttpClient {

    private final ApacheDockerHttpClient delegate;

    private ZerodepDockerHttpClient(final ApacheDockerHttpClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public Response execute(final Request request) {
        return this.delegate.execute(request);
    }

    @Override
    public void close() throws IOException {
        this.delegate.close();
    }

    /**
     * Builder matching the binary API Testcontainers calls and forwarding every option unchanged.
     */
    public static final class Builder {

        private final ApacheDockerHttpClient.Builder delegate =
                new ApacheDockerHttpClient.Builder();

        /** Creates a builder. */
        public Builder() {
        }

        /**
         * Sets the Docker daemon address.
         *
         * @param dockerHost daemon URI
         * @return this builder
         */
        public Builder dockerHost(final URI dockerHost) {
            this.delegate.dockerHost(dockerHost);
            return this;
        }

        /**
         * Sets transport-layer security configuration.
         *
         * @param sslConfig TLS configuration, or {@code null} for a local unencrypted socket
         * @return this builder
         */
        public Builder sslConfig(final SSLConfig sslConfig) {
            this.delegate.sslConfig(sslConfig);
            return this;
        }

        /**
         * Sets the maximum connection count.
         *
         * @param maxConnections positive connection count
         * @return this builder
         */
        public Builder maxConnections(final int maxConnections) {
            this.delegate.maxConnections(maxConnections);
            return this;
        }

        /**
         * Sets the connection-establishment timeout.
         *
         * @param connectionTimeout timeout
         * @return this builder
         */
        public Builder connectionTimeout(final Duration connectionTimeout) {
            this.delegate.connectionTimeout(connectionTimeout);
            return this;
        }

        /**
         * Sets the response timeout.
         *
         * @param responseTimeout timeout
         * @return this builder
         */
        public Builder responseTimeout(final Duration responseTimeout) {
            this.delegate.responseTimeout(responseTimeout);
            return this;
        }

        /**
         * Builds the compatibility wrapper over the managed transport.
         *
         * @return a Docker HTTP client
         */
        public ZerodepDockerHttpClient build() {
            return new ZerodepDockerHttpClient(this.delegate.build());
        }
    }
}
