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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the local container stack's host boundary, immutable image inputs and least-privilege runtime
 * settings.
 *
 * <p>The stack is operational configuration rather than Java code, so a compiler cannot protect it.
 * These assertions parse the delivered Compose document and independently read the Dockerfile. They
 * deliberately check resolved values rather than comments: removing a capability drop or changing a
 * port bind must fail the build even if the surrounding explanation remains unchanged.</p>
 */
@DisplayName("container runtime contract: loopback, immutable inputs and least privilege")
final class ContainerHardeningContractTest {

    private static final Path COMPOSE_PATH = Path.of("docker-compose.yml");
    private static final Path DOCKERFILE_PATH = Path.of("Dockerfile");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /**
     * Every shipped configuration document, including both copies of the test profile.
     *
     * <p>The test profile exists twice - packaged on the main classpath and again on the test classpath,
     * where it takes precedence during a suite run - so a rule applied to one copy and not the other is
     * a rule that holds for whichever copy the reader happened to open. Both are listed.</p>
     */
    private static final List<Path> PROFILE_DOCUMENTS = List.of(
            Path.of("src/main/resources/application.yml"),
            Path.of("src/main/resources/application-local.yml"),
            Path.of("src/main/resources/application-test.yml"),
            Path.of("src/main/resources/application-prod.yml"),
            Path.of("src/test/resources/application-test.yml"));

    private static final List<String> SERVICES =
            List.of("postgres", "localstack", "jaeger", "app", "prometheus", "grafana");

    private static final Map<String, String> IMMUTABLE_IMAGES = Map.of(
            "postgres",
            "postgres:16.14-bookworm@sha256:"
                    + "92620daddcd947f8d5ab5ba66e848702fe443d87fed30c4cea8e389fd78dfc55",
            "localstack",
            "localstack/localstack:4.14.0@sha256:"
                    + "3ebc37595918b8accb852f8048fef2aff047d465167edd655528065b07bc364a",
            "jaeger",
            "jaegertracing/all-in-one:1.71.0@sha256:"
                    + "beb31282a9c5d0d10cb78dd168945dab9887acebb42fcc0bd738b08c36b68bc0",
            "prometheus",
            "prom/prometheus:v3.5.0@sha256:"
                    + "63805ebb8d2b3920190daf1cb14a60871b16fd38bed42b857a3182bc621f4996",
            "grafana",
            "grafana/grafana:11.6.6@sha256:"
                    + "f3b7b0bf02f79eca049a9463424f19fc600f70cabbc0d0e4946f810c5d165830");

    private static final Map<String, String> EXPLICIT_USERS = Map.of(
            "localstack", "1000:1000",
            "jaeger", "10001:10001",
            "app", "10001:10001",
            "prometheus", "65534:65534",
            "grafana", "472:0");

    private static JsonNode compose() throws IOException {
        assertThat(COMPOSE_PATH).isRegularFile();
        return YAML.readTree(COMPOSE_PATH.toFile());
    }

    private static JsonNode service(final JsonNode root, final String name) {
        final JsonNode service = root.path("services").path(name);
        assertThat(service.isObject()).as("Compose service %s must exist", name).isTrue();
        return service;
    }

    private static List<String> textValues(final JsonNode array) {
        assertThat(array.isArray()).isTrue();
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    @Test
    @DisplayName("every published port is bound to the loopback interface")
    void everyPublishedPortIsLoopbackOnly() throws IOException {
        final JsonNode root = compose();
        int publishedPorts = 0;
        for (final String serviceName : SERVICES) {
            final JsonNode ports = service(root, serviceName).path("ports");
            if (!ports.isArray()) {
                continue;
            }
            for (final JsonNode port : ports) {
                publishedPorts++;
                assertThat(port.asText())
                        .as("%s must not publish a port on every host interface", serviceName)
                        .matches("\\$\\{[A-Z_]+_BIND_ADDRESS:-127\\.0\\.0\\.1}:.*");
            }
        }
        assertThat(publishedPorts).isEqualTo(8);
    }

    @Test
    @DisplayName("every third-party service image is pinned by tag and digest")
    void everyExternalImageIsImmutable() throws IOException {
        final JsonNode root = compose();
        IMMUTABLE_IMAGES.forEach((serviceName, expectedImage) ->
                assertThat(service(root, serviceName).path("image").asText())
                        .isEqualTo(expectedImage));
        assertThat(service(root, "app").path("build").isObject()).isTrue();
    }

    @Test
    @DisplayName("every service has an immutable root, no privilege escalation and no capabilities")
    void everyServiceHasTheSharedHardeningFloor() throws IOException {
        final JsonNode root = compose();
        for (final String serviceName : SERVICES) {
            final JsonNode candidate = service(root, serviceName);
            assertThat(candidate.path("read_only").asBoolean()).as(serviceName).isTrue();
            assertThat(textValues(candidate.path("security_opt")))
                    .as(serviceName)
                    .containsExactly("no-new-privileges:true");
            assertThat(textValues(candidate.path("cap_drop")))
                    .as(serviceName)
                    .containsExactly("ALL");
            assertThat(textValues(candidate.path("tmpfs")))
                    .as("%s must name every writable root-filesystem exception", serviceName)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("every image that supports direct non-root startup has an explicit runtime identity")
    void supportedServicesHaveExplicitUsers() throws IOException {
        final JsonNode root = compose();
        EXPLICIT_USERS.forEach((serviceName, expectedUser) ->
                assertThat(service(root, serviceName).path("user").asText())
                        .isEqualTo(expectedUser));

        final JsonNode postgres = service(root, "postgres");
        assertThat(postgres.has("user"))
                .as("the official entrypoint must initialise and chown a new named volume before it "
                        + "drops to uid 999")
                .isFalse();
        assertThat(textValues(postgres.path("cap_add")))
                .containsExactly("CHOWN", "DAC_OVERRIDE", "FOWNER", "SETGID", "SETUID");
    }

    @Test
    @DisplayName("Prometheus exposes no unauthenticated configuration-reload mutation")
    void prometheusLifecycleMutationIsDisabled() throws IOException {
        assertThat(textValues(service(compose(), "prometheus").path("command")))
                .doesNotContain("--web.enable-lifecycle");
    }

    @Test
    @DisplayName("only dedicated emulator variables may provide local AWS credentials")
    void emulatorCredentialsCannotInheritAmbientAwsVariables() throws IOException {
        final JsonNode root = compose();
        final String accessReference =
                "${LOCALSTACK_ACCESS_KEY_ID:-localstack-placeholder-not-a-real-key}";
        final String secretReference =
                "${LOCALSTACK_SECRET_ACCESS_KEY:-localstack-placeholder-not-a-real-secret}";

        for (final String serviceName : List.of("localstack", "app")) {
            final JsonNode environment = service(root, serviceName).path("environment");
            assertThat(environment.path("AWS_ACCESS_KEY_ID").asText()).isEqualTo(accessReference);
            assertThat(environment.path("AWS_SECRET_ACCESS_KEY").asText()).isEqualTo(secretReference);
        }

        final String raw = Files.readString(COMPOSE_PATH, StandardCharsets.UTF_8);
        assertThat(raw)
                .doesNotContain("${AWS_ACCESS_KEY_ID")
                .doesNotContain("${AWS_SECRET_ACCESS_KEY");
    }

    @Test
    @DisplayName("no shipped profile may resolve a credential from the ambient AWS_* variables either")
    void noProfileResolvesCredentialsFromAmbientAwsVariables() throws IOException {
        // The rule above held for the Compose document and for nothing else, which is how two profiles
        // came to read AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY while claiming in the same breath to
        // keep the SDK's default credential chain out of the picture. The rule is the same wherever a
        // credential is resolved, so it is enforced across every profile document rather than only where
        // it was first written. A reference is what matters, not a mention: the assertion looks for the
        // interpolation "${AWS_ACCESS_KEY_ID", so a comment naming the variable to explain why it is not
        // read - which both profiles now carry - is left alone.
        for (final Path profile : PROFILE_DOCUMENTS) {
            assertThat(profile).isRegularFile();
            assertThat(Files.readString(profile, StandardCharsets.UTF_8))
                    .as("%s must resolve emulator credentials only from LOCALSTACK_* variables", profile)
                    .doesNotContain("${AWS_ACCESS_KEY_ID")
                    .doesNotContain("${AWS_SECRET_ACCESS_KEY")
                    .doesNotContain("${AWS_SESSION_TOKEN");
        }
    }

    @Test
    @DisplayName("the slow-statement diagnostic cannot print the values it was bound with")
    void theDatabaseDiagnosticCannotLogBindValues() throws IOException {
        // Suppressing bind values takes an explicit setting because the server's default is to log them
        // in full: log_parameter_max_length defaults to -1. Every statement this application issues is
        // prepared and bound through the extended protocol, so each logged slow statement carried a
        // "DETAIL: parameters: $1 = ..." line naming a social security number, a card number, a card
        // verification code, a balance, or a credential digest - into a container log that is collected
        // and retained by whatever reads the Docker log driver.
        final List<String> command = textValues(service(compose(), "postgres").path("command"));

        assertThat(command)
                .as("the diagnostic itself must remain, or the setting below would be protecting nothing")
                .contains("log_min_duration_statement=2000")
                .as("zero disables parameter logging; -1, the default, logs every value in full")
                .contains("log_parameter_max_length=0")
                .as("the error path must give the same answer, so one half cannot read as both")
                .contains("log_parameter_max_length_on_error=0");
        assertThat(command)
                .as("no setting may re-enable parameter logging at any width")
                .noneMatch(argument -> argument.startsWith("log_parameter_max_length=")
                        && !"log_parameter_max_length=0".equals(argument))
                .as("statement logging must stay off: it would log every statement, not only slow ones")
                .noneMatch(argument -> argument.startsWith("log_statement=")
                        && !"log_statement=none".equals(argument));
    }

    @Test
    @DisplayName("the application receives five seconds beyond Spring's graceful-shutdown budget")
    void applicationStopBudgetExceedsTheFrameworkBudget() throws IOException {
        assertThat(service(compose(), "app").path("stop_grace_period").asText())
                .isEqualTo("35s");
    }

    /**
     * The all-zero revision the Dockerfile refuses by name, and Compose's default for an unsupplied one.
     *
     * <p>Restated here rather than imported, so this expectation cannot be satisfied by the same literal
     * it is checking.
     */
    private static final String ALL_ZERO_REVISION_SENTINEL =
            "0000000000000000000000000000000000000000";

    @Test
    @DisplayName("Compose defaults both identity arguments so a teardown needs no exports, and the "
            + "default revision is the sentinel the image build refuses")
    void composeDefaultsBuildIdentityWithoutWeakeningTheGuard() throws IOException {
        final JsonNode arguments = service(compose(), "app").path("build").path("args");

        // Neither may be a required-variable reference. Compose interpolates the whole file for EVERY
        // subcommand, so a required-but-unset variable makes `config`, `ps`, `logs` and `down` fail -
        // and a teardown cannot need the build provenance of the image it is removing.
        assertThat(arguments.path("APP_VERSION").asText())
                .as("a required-variable reference here breaks every read-only subcommand")
                .doesNotStartWith("${APP_VERSION:?")
                .isEqualTo("${APP_VERSION:-" + mavenProjectVersion() + "}");
        assertThat(arguments.path("SOURCE_REVISION").asText())
                .as("the default must be the sentinel the Dockerfile refuses, so an unlabelled image "
                        + "still cannot be built - the refusal simply moves to the build, where it can "
                        + "say what a correct value looks like")
                .doesNotStartWith("${SOURCE_REVISION:?")
                .isEqualTo("${SOURCE_REVISION:-" + ALL_ZERO_REVISION_SENTINEL + "}");
        assertThat(arguments.path("SOURCE_DATE_EPOCH").asText())
                .isEqualTo("${SOURCE_DATE_EPOCH:-1658188800}");

        // And the guard the compose reference used to express is still enforced, in the one place a
        // build can check it.
        final String dockerfile = Files.readString(DOCKERFILE_PATH, StandardCharsets.UTF_8);
        assertThat(dockerfile)
                .as("the image build refuses the sentinel by name")
                .contains("[ \"$SOURCE_REVISION\" != '" + ALL_ZERO_REVISION_SENTINEL + "' ]")
                .as("and refuses any revision that is not forty characters")
                .contains("[ \"${#SOURCE_REVISION}\" -eq 40 ]")
                .as("and refuses a version outside the Maven grammar")
                .contains("FATAL: APP_VERSION must be the exact Maven project version");
    }

    /**
     * Reads this module's own Maven version out of its build file.
     *
     * <p>Read rather than restated, because the assertion above is precisely that the Compose default
     * and the Maven version do not drift apart; a literal here would let both move together silently.
     *
     * @return the project version
     * @throws IOException if the build file cannot be read
     */
    private static String mavenProjectVersion() throws IOException {
        final String build = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        final java.util.regex.Matcher coordinate = java.util.regex.Pattern.compile(
                "<artifactId>carddemo-java</artifactId>\\s*<version>([^<]+)</version>")
                .matcher(build);
        assertThat(coordinate.find())
                .as("pom.xml must declare this module's own version beside its artefact identifier")
                .isTrue();
        return coordinate.group(1);
    }

    @Test
    @DisplayName("the Dockerfile verifies build arguments against the packaged build identity")
    void dockerfileVerifiesAndPublishesBuildIdentity() throws IOException {
        assertThat(DOCKERFILE_PATH).isRegularFile();
        final String dockerfile = Files.readString(DOCKERFILE_PATH, StandardCharsets.UTF_8);

        assertThat(dockerfile)
                .contains("ARG APP_VERSION")
                .contains("ARG SOURCE_REVISION")
                .contains("ARG SOURCE_DATE_EPOCH=1658188800")
                .doesNotContain("ARG APP_VERSION=")
                .doesNotContain("ARG SOURCE_REVISION=")
                .contains("actual_version=\"$(sed -n 's/^build.version=//p' \"$info\")\"")
                .contains("actual_revision=\"$(sed -n "
                        + "'s/^build.source-revision=//p' \"$info\")\"")
                .contains("expected_time=\"$(date -u -d \"@$SOURCE_DATE_EPOCH\" "
                        + "'+%Y-%m-%dT%H:%M:%SZ')\"")
                .contains("org.opencontainers.image.version=\"${APP_VERSION}\"")
                .contains("org.opencontainers.image.revision=\"${SOURCE_REVISION}\"")
                .contains("com.carddemo.legacy-estate.release="
                        + "\"CardDemo_v1.0-15-g27d6c6f-68 (2022-07-19)\"");
    }

    @Test
    @DisplayName("the image probe reads the liveness group, over the transport the server is using, "
            + "through an interpreter that actually has the feature it uses")
    void theImageProbeIsCorrectInAllThreeRespects() throws IOException {
        // Three separate defects have lived in this one instruction, and none of them was observable from
        // the outside as a probe defect - each presented as a healthy application reported unhealthy.
        //
        // THE INTERPRETER. /dev/tcp is a bash feature and is absent from dash. A revision that named
        // /bin/sh produced "cannot create /dev/tcp/127.0.0.1/8080: Directory nonexistent" on every single
        // probe: a container observed in this workspace had a failing streak of 9,453 while the
        // application inside it was serving correctly. Measured in the pinned runtime base image:
        // `/bin/sh -c 'exec 3<>/dev/tcp/127.0.0.1/8080'` reports Directory nonexistent, while
        // `/usr/bin/bash -c` of the same line reports Connection refused - which is the feature working
        // and nothing listening. /bin/sh is the more conventional-looking choice, which is exactly why
        // this is asserted rather than left to the next person's judgement.
        //
        // THE TRANSPORT. The probe used to send plaintext unconditionally while the transport is a profile
        // decision, so a TLS-enabled process was reported unhealthy. It now switches on
        // SERVER_SSL_ENABLED - the same relaxed-binding form Spring Boot itself binds to
        // server.ssl.enabled, so the server and its probe read ONE switch and cannot drift - and speaks
        // TLS through openssl, which the pinned runtime image already ships, so no package and no CVE
        // surface is added.
        //
        // THE GROUP. Asking for the aggregate made the probe's own duration a function of four external
        // services under a five-second timeout, and let successive probes overlap. The liveness group
        // consults only this process's state, so it is bounded by construction.
        assertThat(DOCKERFILE_PATH).isRegularFile();
        final String dockerfile = Files.readString(DOCKERFILE_PATH, StandardCharsets.UTF_8);
        final String probe = dockerfile.lines()
                .dropWhile(line -> !line.startsWith("HEALTHCHECK"))
                .takeWhile(line -> !line.isBlank())
                .reduce("", (first, second) -> first + "\n" + second);

        assertThat(probe).as("the HEALTHCHECK instruction must be present").contains("HEALTHCHECK");
        assertThat(probe)
                .as("bash, because the probe uses /dev/tcp and dash does not have it")
                .contains("\"/usr/bin/bash\"")
                .as("naming sh here would fail every probe while the application served correctly")
                .doesNotContain("\"/bin/sh\"")
                .as("the liveness group, so the probe makes no external call")
                .contains("/actuator/health/liveness")
                .as("and never the aggregate, whose duration depends on other services")
                .doesNotContain("GET /actuator/health HTTP")
                .as("the transport is read rather than assumed, from the switch the server reads")
                .contains("SERVER_SSL_ENABLED")
                .as("and the TLS branch speaks TLS, using the tool the runtime image already ships")
                .contains("openssl s_client")
                .as("a 200 status line is the pass condition on both branches")
                .contains("^HTTP/1\\\\.[01] 200");
    }
}