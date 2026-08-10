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
package com.carddemo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * Holds the local validation stack to two properties that are only ever one careless line from being
 * lost: every published port is bound to the loopback interface by default, and every image the stack
 * does not build itself is pinned by digest as well as by tag.
 *
 * <h2>Why the binding matters more than the credentials it protects</h2>
 *
 * <p>The stack is deliberately full of throwaway values - a database password readable in the file, an
 * administrator password of {@code admin}, and a token signing secret committed in
 * {@code application-local.yml}. Each is a fixture and is meant to be readable; a developer must be able
 * to bring the stack up and sign on without preparing an environment first.
 *
 * <p>What makes them safe is that nothing outside the machine can reach the services that trust them. A
 * Compose mapping written the obvious way - {@code "5432:5432"} - binds every interface on the host, and
 * on that binding the fixtures stop being fixtures. The database answers any peer that can route to the
 * host. Grafana admits anyone who has read this repository. Most seriously, the application signs bearer
 * tokens with a published secret, so a peer can mint a token carrying the administrator authority and
 * reach the batch-control surface with it - which is authority, not merely information.
 *
 * <p>So the exposure is the control, and it is a control expressed as a default. A default is exactly the
 * kind of thing that is silently lost: adding a service, moving a port, or copying a neighbouring line
 * all reintroduce the wide binding without looking like a security change. This test is what makes that
 * edit fail.
 *
 * <h2>Why the digests matter</h2>
 *
 * <p>A tag is a mutable pointer. An upstream republish of the same tag silently changes what the stack
 * runs, and every gate validated against it was validated against something else. The two Dockerfile
 * bases and the continuous-integration actions are already pinned by digest; the Compose images are the
 * remaining mutable inputs, so they are pinned the same way. The tag stays alongside the digest because
 * it is what a reader recognises, and an upgrade is then a visible two-part diff.
 *
 * <h2>Why this reads the file rather than starting the stack</h2>
 *
 * <p>Both properties are properties of the definition, so the definition is what is checked - no daemon,
 * no image pull and no container. That keeps the guard available in every environment the unit suite runs
 * in, including one with no container runtime at all, which is precisely where a wide binding would
 * otherwise go unnoticed until it reached a machine that had one.
 *
 * <p>Provenance: this guard has no legacy antecedent - the estate carries no test harness of any kind.
 * Checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("The local validation stack is loopback-bound and digest-pinned")
class LocalValidationStackExposureTest {

    /** The stack definition, resolved from the module root the build runs in. */
    private static final Path COMPOSE_FILE = Path.of("docker-compose.yml");

    /** The local profile, which carries the signing secret the loopback binding protects. */
    private static final Path LOCAL_PROFILE = Path.of("src", "main", "resources", "application-local.yml");

    /**
     * The variable the local profile reads its signing secret from.
     *
     * <p>Local-scoped, and named here rather than inline because both the stack definition and the profile
     * are asserted to name the same one. It is deliberately NOT production's {@code CARDDEMO_JWT_SECRET}:
     * this profile once read that variable, so a deployment secret exported on the developer's machine bound
     * into the local stack silently. Placeholder resolution is by exact key, so the separate name is what
     * makes that impossible rather than unlikely.
     */
    private static final String LOCAL_SIGNING_SECRET_VARIABLE = "CARDDEMO_LOCAL_JWT_SECRET";

    /** The loopback address every mapping must default to. */
    private static final String LOOPBACK = "127.0.0.1";

    /**
     * One published-port entry: a quoted list item under a {@code ports:} key.
     *
     * <p>Group one is the whole mapping. Compose accepts {@code host:container},
     * {@code address:host:container} and a bare {@code container}, so the mapping is captured whole and
     * decomposed below rather than being matched shape by shape - a pattern that assumed one shape would
     * simply not see a mapping written in another.
     */
    private static final Pattern PORT_ENTRY = Pattern.compile("^\\s*-\\s*\"([^\"]+)\"", Pattern.MULTILINE);

    /** A service's image reference. */
    private static final Pattern IMAGE_ENTRY =
            Pattern.compile("^\\s*image:\\s*(\\S+)", Pattern.MULTILINE);

    /** A digest suffix in the {@code tag@sha256:<64 hex>} form the Dockerfile bases already use. */
    private static final Pattern DIGEST_SUFFIX = Pattern.compile("@sha256:[0-9a-f]{64}$");

    /**
     * The one image reference that is not pinned, and cannot be.
     *
     * <p>The application image is built from this module's own {@code Dockerfile} inside this same stack,
     * so its digest does not exist until the build that produces it has run. Enrolled by exact text, so
     * that a <em>different</em> unpinned image still fails; its inputs are pinned instead - both
     * Dockerfile bases by digest, every dependency by exact version in {@code pom.xml}.
     */
    private static final String LOCALLY_BUILT_IMAGE = "${COMPOSE_PROJECT_NAME:-carddemo}-app:local";

    /**
     * The services whose ports must be published, so a service losing its mapping altogether is not
     * mistaken for compliance by a rule that only inspects the mappings it finds.
     */
    private static final Set<String> SERVICES_PUBLISHING_A_PORT =
            Set.of("postgres", "localstack", "jaeger", "app", "prometheus", "grafana");

    /** How many published mappings the stack has: one each, except the collector's three. */
    private static final int EXPECTED_MAPPING_COUNT = 8;

    /**
     * Reads a repository file.
     *
     * @param file the file to read
     * @return its text
     */
    private static String read(final Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("the stack definition could not be read: " + file, unreadable);
        }
    }

    /**
     * Splits the definition into its service blocks.
     *
     * <p>The scan is bounded to the {@code services:} section before any block is cut. That bound is not
     * a refinement: the trailing {@code volumes:} and {@code networks:} sections declare their own
     * two-space-indented keys, so an unbounded scan reports {@code postgres-data} and {@code default} as
     * services - which is what the first draft of this reader did, and what its own completeness check
     * caught. Working per service is what lets a finding name the service that carries it, and what lets
     * that completeness check notice a service which has stopped publishing anything at all.
     *
     * @param definition the whole file text
     * @return service name to block text, in file order
     */
    private static Map<String, String> serviceBlocks(final String definition) {
        final String body = definition.substring(definition.indexOf("\nservices:") + 1);
        final Matcher topLevel = Pattern.compile("^[a-z][\\w-]*:\\s*$", Pattern.MULTILINE).matcher(body);
        final int servicesEnd = topLevel.find(1) ? topLevel.start() : body.length();
        final String section = body.substring(0, servicesEnd);

        final List<String> names = new ArrayList<>();
        final List<Integer> starts = new ArrayList<>();
        final Matcher header =
                Pattern.compile("^ {2}([a-z][\\w-]*):\\s*$", Pattern.MULTILINE).matcher(section);
        while (header.find()) {
            names.add(header.group(1));
            starts.add(header.end());
        }

        final Map<String, String> blocks = new LinkedHashMap<>();
        for (int index = 0; index < starts.size(); index++) {
            final int end = index + 1 < starts.size() ? starts.get(index + 1) : section.length();
            blocks.put(names.get(index), section.substring(starts.get(index), end));
        }
        return blocks;
    }

    /**
     * Extracts the published-port mappings from one service block.
     *
     * <p>Only the {@code ports:} list is read. A health check writing {@code http://127.0.0.1:9090} and a
     * volume mapping both look superficially similar to a port entry, and neither is one.
     *
     * @param block the service block text
     * @return the mapping strings, in file order
     */
    private static List<String> publishedMappings(final String block) {
        final int portsAt = block.indexOf("\n    ports:");
        if (portsAt < 0) {
            return List.of();
        }
        final String afterPorts = block.substring(portsAt + "\n    ports:".length());
        final int nextKey = nextSiblingKeyOffset(afterPorts);
        final List<String> mappings = new ArrayList<>();
        final Matcher entry = PORT_ENTRY.matcher(afterPorts.substring(0, nextKey));
        while (entry.find()) {
            mappings.add(entry.group(1));
        }
        return mappings;
    }

    /**
     * Finds where the list under a service key ends.
     *
     * @param text the text following the key
     * @return the offset of the next four-space-indented sibling key, or the text length
     */
    private static int nextSiblingKeyOffset(final String text) {
        final Matcher sibling = Pattern.compile("^ {4}[a-z][\\w_-]*:", Pattern.MULTILINE).matcher(text);
        return sibling.find() ? sibling.start() : text.length();
    }

    /**
     * Splits a mapping at the colons that separate its elements, ignoring those inside an interpolation.
     *
     * <p>This is the whole subtlety of reading these mappings. Compose's default syntax {@code ${NAME:-v}}
     * contains a colon, so splitting a mapping on every colon tears each interpolation in half and lands
     * the element boundary in the middle of a variable name. Depth is tracked across {@code ${} and
     * {@code &#125;} so only the separators are seen.
     *
     * @param mapping the mapping string
     * @return its elements, in order
     */
    private static List<String> mappingElements(final String mapping) {
        final List<String> elements = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int index = 0; index < mapping.length(); index++) {
            final char character = mapping.charAt(index);
            if (character == '$' && index + 1 < mapping.length() && mapping.charAt(index + 1) == '{') {
                depth++;
                current.append("${");
                index++;
            } else if (character == '}' && depth > 0) {
                depth--;
                current.append(character);
            } else if (character == ':' && depth == 0) {
                elements.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        elements.add(current.toString());
        return elements;
    }

    /**
     * Reports the host address a mapping binds.
     *
     * <p>Compose reads the last element as the container port and the one before it as the host port;
     * anything earlier is the host address. A two-element mapping therefore names no address and binds
     * every interface, which is the case this whole test exists to prevent. Taking the address as
     * everything before the final two elements - rather than as the text before the first colon - is also
     * what keeps a bracketed IPv6 address readable.
     *
     * @param mapping the mapping string
     * @return the host address, or empty when the mapping names none
     */
    private static String hostAddressOf(final String mapping) {
        final List<String> elements = mappingElements(mapping);
        return elements.size() < 3 ? "" : String.join(":", elements.subList(0, elements.size() - 2));
    }

    @Nested
    @DisplayName("the reader itself is sound, so a misread cannot pass as compliance")
    class TheReaderIsSound {

        @Test
        @DisplayName("a mapping with no host address reports none, which is the case being prevented")
        void aMappingWithNoHostAddressReportsNone() {
            assertAll(
                    () -> assertThat(hostAddressOf("5432:5432")).isEmpty(),
                    () -> assertThat(hostAddressOf("${POSTGRES_PORT:-5432}:5432")).isEmpty());
        }

        @Test
        @DisplayName("and the split ignores the colon inside a default, which is where a naive reader "
                + "puts the element boundary")
        void andTheSplitIgnoresTheColonInsideADefault() {
            // Written after the first draft of hostAddressOf split on every colon and reported the host
            // address of a compliant mapping as "${POSTGRES_BIND_ADDRESS:-127.0.0.1}:${POSTGRES_PORT".
            // That draft would have failed every mapping in the file for the wrong reason, and - worse in
            // a guard - a mapping written WITHOUT an address still reported a non-empty address, so the
            // loopback rule would have passed a wide binding.
            assertAll(
                    () -> assertThat(mappingElements("${POSTGRES_PORT:-5432}:5432"))
                            .containsExactly("${POSTGRES_PORT:-5432}", "5432"),
                    () -> assertThat(mappingElements(
                            "${A_BIND_ADDRESS:-127.0.0.1}:${A_PORT:-1}:1"))
                            .containsExactly("${A_BIND_ADDRESS:-127.0.0.1}", "${A_PORT:-1}", "1"));
        }

        @Test
        @DisplayName("a mapping that names an address reports it, defaulted or literal")
        void aMappingThatNamesAnAddressReportsIt() {
            assertAll(
                    () -> assertThat(hostAddressOf("127.0.0.1:5432:5432")).isEqualTo(LOOPBACK),
                    () -> assertThat(hostAddressOf("${POSTGRES_BIND_ADDRESS:-127.0.0.1}:${POSTGRES_PORT"
                            + ":-5432}:5432")).isEqualTo("${POSTGRES_BIND_ADDRESS:-127.0.0.1}"),
                    () -> assertThat(hostAddressOf("0.0.0.0:5432:5432")).isEqualTo("0.0.0.0"));
        }

        @Test
        @DisplayName("the digest rule accepts the Dockerfile's own pinning form and rejects a bare tag")
        void theDigestRuleAcceptsThePinningForm() {
            assertAll(
                    () -> assertThat(DIGEST_SUFFIX.matcher("postgres:16.14-bookworm@sha256:"
                            + "92620daddcd947f8d5ab5ba66e848702fe443d87fed30c4cea8e389fd78dfc55").find())
                            .isTrue(),
                    () -> assertThat(DIGEST_SUFFIX.matcher("postgres:16.14-bookworm").find()).isFalse(),
                    () -> assertThat(DIGEST_SUFFIX.matcher("postgres@sha256:abc").find()).isFalse());
        }

        @Test
        @DisplayName("every service is found, and every one that must publish a port does")
        void everyServiceIsFound() {
            final Map<String, String> blocks = serviceBlocks(read(COMPOSE_FILE));

            assertThat(blocks.keySet())
                    .as("a service the reader cannot see is a service this test does not govern")
                    .containsExactlyInAnyOrderElementsOf(SERVICES_PUBLISHING_A_PORT);
            assertThat(blocks).allSatisfy((service, block) -> assertThat(publishedMappings(block))
                    .as("service %s must publish at least one port", service)
                    .isNotEmpty());
        }

        @Test
        @DisplayName("and the trailing volume and network declarations are not read as services")
        void andTrailingDeclarationsAreNotReadAsServices() {
            // Both sections declare two-space-indented keys of their own, so an unbounded scan reported
            // postgres-data, prometheus-data, grafana-data and default as services. None publishes a port,
            // so the loopback rule stayed silent about them - but the completeness check above did not,
            // which is why that check is written as an exact set rather than as a containment.
            assertThat(read(COMPOSE_FILE))
                    .contains("\nvolumes:\n")
                    .contains("\nnetworks:\n");
            assertThat(serviceBlocks(read(COMPOSE_FILE)).keySet())
                    .doesNotContain("postgres-data", "prometheus-data", "grafana-data", "default");
        }

        @Test
        @DisplayName("a health check that names the loopback address is not read as a port mapping")
        void aHealthCheckIsNotReadAsAPortMapping() {
            // Three services health-check themselves over http://127.0.0.1:<port>. Those lines would
            // satisfy the loopback rule accidentally while telling it nothing, so the reader must not
            // see them at all. The count is what proves it: eight mappings, not eleven.
            final long mappings = serviceBlocks(read(COMPOSE_FILE)).values().stream()
                    .mapToLong(block -> publishedMappings(block).size())
                    .sum();

            assertThat(read(COMPOSE_FILE)).contains("http://127.0.0.1:9090/-/healthy");
            assertThat(mappings).isEqualTo(EXPECTED_MAPPING_COUNT);
        }
    }

    @Nested
    @DisplayName("every published port is bound to the loopback interface by default")
    class EveryPublishedPortIsLoopbackBound {

        @Test
        @DisplayName("so an unqualified bring-up reaches no interface but this machine's own")
        void soAnUnqualifiedBringUpReachesNoOtherInterface() {
            final List<String> findings = new ArrayList<>();
            serviceBlocks(read(COMPOSE_FILE)).forEach((service, block) ->
                    publishedMappings(block).forEach(mapping -> {
                        final String address = hostAddressOf(mapping);
                        if (!address.contains(LOOPBACK)) {
                            findings.add(service + " publishes [" + mapping + "] on "
                                    + (address.isEmpty() ? "every interface" : address));
                        }
                    }));

            assertThat(findings)
                    .as("write the mapping as \"${<SERVICE>_BIND_ADDRESS:-127.0.0.1}:${<SERVICE>_PORT"
                            + ":-<port>}:<port>\"; the committed signing secret, database password and "
                            + "dashboard password are all safe only while nothing off this machine can "
                            + "reach the service that trusts them")
                    .isEmpty();
        }

        @Test
        @DisplayName("and each address is an override with loopback as its default, so widening is a "
                + "deliberate act rather than an edit to this file")
        void andEachAddressIsAnOverrideDefaultingToLoopback() {
            final List<String> findings = new ArrayList<>();
            serviceBlocks(read(COMPOSE_FILE)).forEach((service, block) ->
                    publishedMappings(block).forEach(mapping -> {
                        final String address = hostAddressOf(mapping);
                        if (!address.matches("\\$\\{[A-Z][A-Z0-9_]*_BIND_ADDRESS:-" + Pattern.quote(LOOPBACK)
                                + "}")) {
                            findings.add(service + " binds [" + address + "] rather than an override");
                        }
                    }));

            assertThat(findings)
                    .as("a hard-coded loopback address is safe but unusable: a colleague inspecting a "
                            + "reproduction would have to edit this file, and an edited file is how a wide "
                            + "binding gets committed")
                    .isEmpty();
        }

        @Test
        @DisplayName("and no shipped file or runbook carries a recipe that publishes this stack on a "
                + "routable address")
        void andNoRunbookCarriesANonLoopbackBindRecipe() {
            // WHAT THIS REPLACES, AND WHY THE REPLACEMENT IS A DIFFERENT KIND OF ASSERTION. The previous
            // version of this test asserted that the Compose file MENTIONED CARDDEMO_JWT_SECRET,
            // POSTGRES_PASSWORD and GRAFANA_ADMIN_PASSWORD, on the reasoning that a widening procedure
            // must name the credentials it obliges an operator to replace. It passed while the procedure
            // it was guarding did not work: the app service's environment block forwards NEITHER
            // CARDDEMO_JWT_SECRET NOR CARDDEMO_MANAGEMENT_TOKEN into the container, so an operator who
            // exported a generated signing secret changed nothing about the running application, which
            // went on minting and accepting tokens under the committed literal. A test that reads prose
            // cannot catch that. This one reads what the stack DOES.
            //
            // The posture is now that there is no supported non-loopback bind at all - a tunnel or the
            // production profile is the answer - so the assertion is the absence of the recipe. The
            // pattern below matches any *_BIND_ADDRESS assignment to something that is not a loopback
            // address, in this file and in all three runbooks that documented one.
            final Pattern wideBindRecipe =
                    Pattern.compile("[A-Z][A-Z0-9_]*_BIND_ADDRESS\\s*=\\s*(?!127\\.)\\S+");
            final List<Path> shipped = List.of(COMPOSE_FILE, LOCAL_PROFILE,
                    Path.of("README.md"),
                    Path.of("..", "README.md"),
                    Path.of("..", "docs", "onboarding-guide.md"));

            final List<String> findings = new ArrayList<>();
            for (final Path file : shipped) {
                final Matcher recipe = wideBindRecipe.matcher(read(file));
                while (recipe.find()) {
                    findings.add(file + " carries [" + recipe.group() + "]");
                }
            }

            assertThat(findings)
                    .as("a documented widening is an instruction, and this stack has nothing to widen "
                            + "into safety: it answers over cleartext, accepts ten seeded identities "
                            + "whose password is published, and stands beside four services that "
                            + "authenticate nobody. Direct a remote reader at `ssh -L` or the prod "
                            + "profile instead")
                    .isEmpty();
        }

        @Test
        @DisplayName("and the two supported ways to reach it from elsewhere are the ones named, so the "
                + "removal reads as a redirection rather than as an omission")
        void andTheSupportedRemoteAccessRouteIsNamed() {
            assertAll(
                    () -> assertThat(read(COMPOSE_FILE))
                            .as("the encrypted, authenticated forward keeps the listener loopback-bound")
                            .contains("ssh -L 8080:127.0.0.1:8080"),
                    () -> assertThat(read(COMPOSE_FILE))
                            .as("and a service that must answer other hosts is a production deployment")
                            .contains("`prod` profile"),
                    () -> assertThat(read(LOCAL_PROFILE))
                            .as("the profile carrying the fixture credentials says the same, beside the "
                                    + "credential rather than in a distant section")
                            .contains("ssh -L 8080:127.0.0.1:8080"));
        }

        @Test
        @DisplayName("and the app container's own bind is wide INSIDE the namespace and loopback on the "
                + "host, which is the pair the published mapping needs")
        void andTheContainerBindIsWideInsideAndLoopbackOutside() {
            // Both halves are asserted together because each alone is a defect. Without the profile
            // default, `spring-boot:run` and `java -jar` bind every interface of the developer's machine
            // while carrying a committed signing secret. Without the container override, Docker's
            // published port forwards to the container's own interface and finds nothing listening
            // there, so the stack comes up healthy and answers nobody.
            assertThat(read(LOCAL_PROFILE))
                    .as("a host-run process must default to loopback")
                    .contains("address: ${SERVER_ADDRESS:127.0.0.1}");

            final String appService = serviceBlocks(read(COMPOSE_FILE)).get("app");
            assertThat(appService)
                    .as("and the container must widen it, because a published port does not reach a "
                            + "container's loopback")
                    .contains("SERVER_ADDRESS: 0.0.0.0");
            assertThat(publishedMappings(appService))
                    .as("while the host side of the mapping stays loopback-bound")
                    .allSatisfy(mapping -> assertThat(hostAddressOf(mapping)).contains(LOOPBACK));
        }

        @Test
        @DisplayName("and the app service forwards no application credential, so no file may claim that "
                + "supplying one changes what the container trusts")
        void andTheAppServiceForwardsNoApplicationCredential() {
            // This is the fact the removed widening recipe got wrong, pinned so that it cannot be
            // asserted in prose again without being made true first. If a future revision decides the
            // container SHOULD receive an operator-supplied signing secret, this test is where that
            // decision is registered - and the runbook may then say so.
            final String appService = serviceBlocks(read(COMPOSE_FILE)).get("app");

            assertThat(appService)
                    .as("the local signing secret and operator credential come from "
                            + "application-local.yml, which is what makes them inspectable fixtures "
                            + "rather than values a stack passes around")
                    .doesNotContain("CARDDEMO_JWT_SECRET")
                    .doesNotContain("CARDDEMO_MANAGEMENT_TOKEN");
        }

        @Test
        @DisplayName("and the profile's loopback-only premise is no longer merely asserted")
        void andTheProfilesLoopbackPremiseIsNoLongerMerelyAsserted() {
            // application-local.yml relaxes the required transport security on the grounds that the only
            // situation it describes is a process addressing containers over loopback. That premise was
            // false while the stack published every interface. The profile must now cite the binding that
            // makes it true, so a reader can check the claim instead of taking it.
            assertThat(read(LOCAL_PROFILE))
                    .contains("require-https: false")
                    .contains("LocalValidationStackExposureTest");
        }
    }

    @Nested
    @DisplayName("every image the stack does not build is pinned by digest")
    class EveryExternalImageIsDigestPinned {

        @Test
        @DisplayName("so an upstream republish of a tag cannot change what the gates were validated "
                + "against without appearing here as a diff")
        void soAnUpstreamRepublishCannotChangeWhatRuns() {
            final List<String> findings = new ArrayList<>();
            final Matcher image = IMAGE_ENTRY.matcher(read(COMPOSE_FILE));
            while (image.find()) {
                final String reference = image.group(1);
                if (LOCALLY_BUILT_IMAGE.equals(reference) || DIGEST_SUFFIX.matcher(reference).find()) {
                    continue;
                }
                findings.add("unpinned image [" + reference + "]");
            }

            assertThat(findings)
                    .as("append the digest from `docker image inspect <tag> --format "
                            + "'{{index .RepoDigests 0}}'`, keeping the tag so the reference stays legible")
                    .isEmpty();
        }

        @Test
        @DisplayName("and the tag is retained beside each digest, so an upgrade is a legible two-part diff")
        void andTheTagIsRetainedBesideEachDigest() {
            final List<String> external = new ArrayList<>();
            final Matcher image = IMAGE_ENTRY.matcher(read(COMPOSE_FILE));
            while (image.find()) {
                if (!LOCALLY_BUILT_IMAGE.equals(image.group(1))) {
                    external.add(image.group(1));
                }
            }

            // The digest is asserted before the prefix is taken, deliberately. Slicing at indexOf('@')
            // first would throw on an unpinned reference, and a guard that reports an exception instead of
            // the reason is a guard whose failure has to be debugged rather than read.
            assertThat(external)
                    .as("the five services this stack does not build")
                    .hasSize(5)
                    .allSatisfy(reference -> {
                        assertThat(reference)
                                .as("every external reference must carry a digest")
                                .contains("@sha256:");
                        assertThat(reference.substring(0, reference.indexOf('@')))
                                .as("a digest alone names no version a reader recognises")
                                .contains(":");
                    });
        }

        @Test
        @DisplayName("and the module's own image is the only unpinned reference, for a reason that holds "
                + "by construction")
        void andTheModulesOwnImageIsTheOnlyUnpinnedReference() {
            final String definition = read(COMPOSE_FILE);

            assertAll(
                    () -> assertThat(definition).contains("image: " + LOCALLY_BUILT_IMAGE),
                    () -> assertThat(definition)
                            .as("its inputs are pinned instead, and that substitution is stated here")
                            .contains("built from ./Dockerfile"),
                    () -> assertThat(read(Path.of("Dockerfile")))
                            .as("both build stages must themselves be digest-pinned, or the substitution "
                                    + "is empty")
                            .containsPattern("FROM eclipse-temurin:[^\\s]+@sha256:[0-9a-f]{64} AS build")
                            .containsPattern("FROM eclipse-temurin:[^\\s]+@sha256:[0-9a-f]{64} AS "
                                    + "runtime"));
        }
    }
}
