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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Verifies the wiring contract of the application entry point.
 *
 * <h2>What is under test</h2>
 * The startup class named by the packaged artifact as its start class. Every assertion here is made
 * by inspecting the type rather than by starting it, so the whole class is a statement about
 * declarations: which package the type lives in, which annotations it carries, which methods it
 * declares, and whether the build manifest and the type still agree on the name.
 *
 * <h2>Why the context is deliberately not started</h2>
 * Starting the context is what the integration tier does, against real servers it provisions for
 * itself. Doing it here would give a unit test a database dependency and would run schema migration
 * as a side effect of a wiring assertion. The one line that starts the container is therefore left
 * unexecuted by design, and the assertions below constrain its shape instead: that it is reachable
 * as a program entry point, that it is the only method the class declares, and that nothing else on
 * the class can run work.
 *
 * <h2>Why the build manifest is read</h2>
 * The last group compares the start class recorded in the build manifest against the type this test
 * is written about. That pairing is the substance of the defect this class exists to prevent: a
 * manifest can name a start class that no source file defines, and every stage of a build will still
 * report success while the produced artefact refuses to launch. Reading the configured name and
 * resolving it as a class turns that silent mismatch into a failing test, so a rename on either side
 * fails here rather than at a launch attempt.
 *
 * <h2>Why the scan annotations are asserted absent rather than present</h2>
 * The type sits at the root of the package tree, so the scan implied by its single annotation already
 * reaches every layered sub-package beneath it. Declaring a scan by hand would narrow that reach,
 * and a narrowed scan does not fail - it silently stops wiring whichever package was left out. The
 * assertions therefore fix the absence of the narrowing annotations, not the presence of them.
 *
 * <p>Provenance: legacy checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("Application entry point wiring contract")
class CardDemoApplicationTest {

    /** The package the entry point must occupy, restated independently of the type under test. */
    private static final String EXPECTED_PACKAGE = "com.carddemo";

    /** The simple name the entry point must carry, restated independently. */
    private static final String EXPECTED_SIMPLE_NAME = "CardDemoApplication";

    /** The fully qualified name the packaged artefact records as its start class. */
    private static final String EXPECTED_BINARY_NAME = "com.carddemo.CardDemoApplication";

    /**
     * A misspelling of the base package that appears in superseded delivery documentation. It is
     * asserted against explicitly because a build would be perfectly happy with it and only a
     * reader would notice.
     */
    private static final String SUPERSEDED_PACKAGE_MISSPELLING = "com.cardemo";

    /** The name of the sole method the entry point declares. */
    private static final String ENTRY_POINT_METHOD_NAME = "main";

    /** The build manifest, resolved relative to the module directory the test runner works in. */
    private static final String BUILD_MANIFEST = "pom.xml";

    /** Extracts the configured start class from the build manifest. */
    private static final Pattern START_CLASS = Pattern.compile("<mainClass>([^<]+)</mainClass>");

    @Nested
    @DisplayName("Package and identity")
    class PackageAndIdentity {

        @Test
        @DisplayName("occupies the base package exactly")
        void occupiesTheBasePackage() {
            assertThat(CardDemoApplication.class.getPackageName()).isEqualTo(EXPECTED_PACKAGE);
        }

        @Test
        @DisplayName("carries the expected simple name")
        void carriesTheExpectedSimpleName() {
            assertThat(CardDemoApplication.class.getSimpleName()).isEqualTo(EXPECTED_SIMPLE_NAME);
        }

        @Test
        @DisplayName("resolves under the expected fully qualified name")
        void resolvesUnderTheExpectedBinaryName() {
            assertThat(CardDemoApplication.class.getName()).isEqualTo(EXPECTED_BINARY_NAME);
        }

        @Test
        @DisplayName("does not use the superseded package misspelling")
        void doesNotUseTheSupersededPackageMisspelling() {
            assertThat(CardDemoApplication.class.getName())
                    .doesNotContain(SUPERSEDED_PACKAGE_MISSPELLING);
        }

        @Test
        @DisplayName("sits directly in the base package rather than a sub-package")
        void sitsDirectlyInTheBasePackage() {
            String withoutPackage = CardDemoApplication.class.getName()
                    .substring(EXPECTED_PACKAGE.length() + 1);
            assertThat(withoutPackage).isEqualTo(EXPECTED_SIMPLE_NAME).doesNotContain(".");
        }

        @Test
        @DisplayName("is a top-level type")
        void isATopLevelType() {
            assertThat(CardDemoApplication.class.getEnclosingClass()).isNull();
            assertThat(CardDemoApplication.class.isMemberClass()).isFalse();
        }
    }

    @Nested
    @DisplayName("Annotation contract")
    class AnnotationContract {

        @Test
        @DisplayName("carries the Spring Boot application annotation")
        void carriesTheSpringBootApplicationAnnotation() {
            assertThat(CardDemoApplication.class.getDeclaredAnnotation(SpringBootApplication.class))
                    .isNotNull();
        }

        @Test
        @DisplayName("carries exactly one annotation")
        void carriesExactlyOneAnnotation() {
            assertThat(CardDemoApplication.class.getDeclaredAnnotations())
                    .extracting(annotation -> annotation.annotationType().getName())
                    .containsExactly(SpringBootApplication.class.getName());
        }

        @Test
        @DisplayName("does not narrow the component scan by package name")
        void doesNotNarrowTheComponentScanByPackageName() {
            assertThat(springBootApplication().scanBasePackages()).isEmpty();
        }

        @Test
        @DisplayName("does not narrow the component scan by marker class")
        void doesNotNarrowTheComponentScanByMarkerClass() {
            assertThat(springBootApplication().scanBasePackageClasses()).isEmpty();
        }

        @Test
        @DisplayName("excludes no autoconfiguration")
        void excludesNoAutoconfiguration() {
            assertThat(springBootApplication().exclude()).isEmpty();
            assertThat(springBootApplication().excludeName()).isEmpty();
        }

        @Test
        @DisplayName("leaves configuration class proxying at its default")
        void leavesConfigurationProxyingAtItsDefault() {
            assertThat(springBootApplication().proxyBeanMethods()).isTrue();
        }

        @Test
        @DisplayName("declares no explicit component scan")
        void declaresNoExplicitComponentScan() {
            assertThat(CardDemoApplication.class.getDeclaredAnnotation(ComponentScan.class)).isNull();
        }

        @Test
        @DisplayName("declares no explicit entity scan")
        void declaresNoExplicitEntityScan() {
            assertThat(CardDemoApplication.class.getDeclaredAnnotation(EntityScan.class)).isNull();
        }

        @Test
        @DisplayName("declares no explicit repository scan")
        void declaresNoExplicitRepositoryScan() {
            assertThat(CardDemoApplication.class.getDeclaredAnnotation(EnableJpaRepositories.class))
                    .isNull();
        }

        @Test
        @DisplayName("enables no scheduling, so nothing fires on a timer")
        void enablesNoScheduling() {
            assertThat(CardDemoApplication.class.getDeclaredAnnotation(EnableScheduling.class))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("Type shape")
    class TypeShape {

        @Test
        @DisplayName("is public, so the launcher can reach it")
        void isPublic() {
            assertThat(Modifier.isPublic(CardDemoApplication.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("is not final, so the container can proxy it")
        void isNotFinal() {
            assertThat(Modifier.isFinal(CardDemoApplication.class.getModifiers())).isFalse();
        }

        @Test
        @DisplayName("is a concrete class rather than an interface or abstract type")
        void isConcrete() {
            assertThat(CardDemoApplication.class.isInterface()).isFalse();
            assertThat(Modifier.isAbstract(CardDemoApplication.class.getModifiers())).isFalse();
        }

        @Test
        @DisplayName("exposes a single public no-argument constructor")
        void exposesASinglePublicNoArgumentConstructor() {
            Constructor<?>[] constructors = CardDemoApplication.class.getDeclaredConstructors();
            assertThat(constructors).hasSize(1);
            assertThat(constructors[0].getParameterCount()).isZero();
            assertThat(Modifier.isPublic(constructors[0].getModifiers())).isTrue();
        }

        @Test
        @DisplayName("is instantiable, because a private constructor would break the container")
        void isInstantiable() throws ReflectiveOperationException {
            assertThat(CardDemoApplication.class.getDeclaredConstructor().newInstance())
                    .isInstanceOf(CardDemoApplication.class);
        }

        @Test
        @DisplayName("implements no startup runner interface")
        void implementsNoStartupRunnerInterface() {
            assertThat(CommandLineRunner.class.isAssignableFrom(CardDemoApplication.class)).isFalse();
            assertThat(ApplicationRunner.class.isAssignableFrom(CardDemoApplication.class)).isFalse();
        }

        @Test
        @DisplayName("extends nothing but the root type, so it inherits no behaviour")
        void extendsNothingButTheRootType() {
            assertThat(CardDemoApplication.class.getSuperclass()).isEqualTo(Object.class);
            assertThat(CardDemoApplication.class.getInterfaces()).isEmpty();
        }

        @Test
        @DisplayName("declares no field, so it holds no state")
        void declaresNoField() {
            assertThat(CardDemoApplication.class.getDeclaredFields())
                    .filteredOn(field -> !field.isSynthetic())
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Entry point method")
    class EntryPointMethod {

        @Test
        @DisplayName("declares the entry point method and nothing else")
        void declaresTheEntryPointMethodAndNothingElse() {
            assertThat(declaredMethods())
                    .extracting(Method::getName)
                    .containsExactly(ENTRY_POINT_METHOD_NAME);
        }

        @Test
        @DisplayName("is a launchable program entry point")
        void isALaunchableProgramEntryPoint() throws NoSuchMethodException {
            Method entryPoint =
                    CardDemoApplication.class.getDeclaredMethod(ENTRY_POINT_METHOD_NAME, String[].class);
            assertThat(Modifier.isPublic(entryPoint.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(entryPoint.getModifiers())).isTrue();
            assertThat(entryPoint.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("accepts the command line as its only parameter")
        void acceptsTheCommandLineAsItsOnlyParameter() throws NoSuchMethodException {
            Method entryPoint =
                    CardDemoApplication.class.getDeclaredMethod(ENTRY_POINT_METHOD_NAME, String[].class);
            assertThat(entryPoint.getParameterTypes()).containsExactly(String[].class);
        }

        @Test
        @DisplayName("declares no checked exception, so a launcher needs no handling")
        void declaresNoCheckedException() throws NoSuchMethodException {
            Method entryPoint =
                    CardDemoApplication.class.getDeclaredMethod(ENTRY_POINT_METHOD_NAME, String[].class);
            assertThat(entryPoint.getExceptionTypes()).isEmpty();
        }

        @Test
        @DisplayName("retains its parameter name, which the container relies on elsewhere")
        void retainsItsParameterName() throws NoSuchMethodException {
            Method entryPoint =
                    CardDemoApplication.class.getDeclaredMethod(ENTRY_POINT_METHOD_NAME, String[].class);
            assertThat(entryPoint.getParameters()[0].isNamePresent()).isTrue();
        }
    }

    @Nested
    @DisplayName("Packaged artefact contract")
    class PackagedArtefactContract {

        @Test
        @DisplayName("the build manifest is readable from the module directory")
        void theBuildManifestIsReadable() {
            assertThat(Path.of(BUILD_MANIFEST)).isRegularFile();
        }

        @Test
        @DisplayName("the build manifest names exactly one start class")
        void theBuildManifestNamesExactlyOneStartClass() throws IOException {
            assertThat(configuredStartClasses()).hasSize(1);
        }

        @Test
        @DisplayName("the configured start class is the type under test")
        void theConfiguredStartClassIsTheTypeUnderTest() throws IOException {
            assertThat(configuredStartClasses()).containsExactly(CardDemoApplication.class.getName());
        }

        @Test
        @DisplayName("the configured start class resolves to a loadable type")
        void theConfiguredStartClassResolvesToALoadableType() throws Exception {
            String configured = configuredStartClasses().get(0);
            assertThat(Class.forName(configured)).isEqualTo(CardDemoApplication.class);
        }

        @Test
        @DisplayName("the configured start class declares a launchable entry point")
        void theConfiguredStartClassDeclaresALaunchableEntryPoint() throws Exception {
            Method entryPoint = Class.forName(configuredStartClasses().get(0))
                    .getDeclaredMethod(ENTRY_POINT_METHOD_NAME, String[].class);
            assertThat(Modifier.isPublic(entryPoint.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(entryPoint.getModifiers())).isTrue();
        }
    }

    /**
     * Reads the single annotation the entry point carries.
     *
     * @return the Spring Boot application annotation declared on the entry point
     */
    private static SpringBootApplication springBootApplication() {
        return CardDemoApplication.class.getDeclaredAnnotation(SpringBootApplication.class);
    }

    /**
     * Lists the methods the entry point declares, with coverage instrumentation filtered out.
     *
     * <p>Instrumentation adds a synthetic method to every class it rewrites, so an unfiltered
     * enumeration never matches the source and would fail only when coverage is being measured.</p>
     *
     * @return the declared methods that exist in the source
     */
    private static List<Method> declaredMethods() {
        List<Method> methods = new ArrayList<>();
        for (Method method : CardDemoApplication.class.getDeclaredMethods()) {
            if (!method.isSynthetic()) {
                methods.add(method);
            }
        }
        return methods;
    }

    /**
     * Extracts every start class the build manifest configures.
     *
     * @return the configured start class names, in the order they appear
     * @throws IOException if the build manifest cannot be read
     */
    private static List<String> configuredStartClasses() throws IOException {
        String manifest = Files.readString(Path.of(BUILD_MANIFEST), StandardCharsets.UTF_8);
        List<String> configured = new ArrayList<>();
        Matcher matcher = START_CLASS.matcher(manifest);
        while (matcher.find()) {
            configured.add(matcher.group(1).trim());
        }
        return configured;
    }
}
