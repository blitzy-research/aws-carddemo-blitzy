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
package com.carddemo.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Holds the one shared statement of what a staged dataset name may be to its boundaries.
 *
 * <h2>What is actually at stake</h2>
 *
 * <p>Five batch job configurations resolve a configured dataset name against a staging root and then
 * create, write, truncate or delete the resolved file. The rule under test is the only thing that keeps
 * the result inside that root, and the two ways out are both silent: resolving an absolute value against
 * a directory yields the absolute value, and resolving a value carrying parent references walks upward.
 * Neither produces an error at resolution time, so a configuration that did either would run, and would
 * operate on a file nobody intended.
 *
 * <p>Each assertion below therefore fixes the exact line between "refused when the configuration is
 * bound" and "operating outside the staging root for the life of the deployment". The containment
 * assertions do the arithmetic explicitly rather than trusting the rule's own reasoning, so a rule that
 * admitted an escaping value would fail on the escape rather than on the wording.
 *
 * <p>A pure unit test: no Spring context, no file system access, no container.
 *
 * @since 1.0.0
 */
@DisplayName("StagedResourceNames :: a dataset name is one element and cannot leave the staging root")
class StagedResourceNamesTest {

    /** A staging root standing in for a configured one, absolute as a real deployment's would be. */
    private static final String STAGING_ROOT = "/var/carddemo/staging";

    /** The configuration key named in every diagnostic. */
    private static final String KEY = "dalytranDataset";

    @Nested
    @DisplayName("A legitimate dataset name")
    class ALegitimateName {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"AWS.M2.CARDDEMO.DALYTRAN.PS", "dalytran.txt", "DALYREJS",
            "AWS.M2.CARDDEMO.TRANREPT.G0001V00", "a"})
        @DisplayName("is admitted unchanged, because the legacy names this replaces are single elements "
                + "and the rule must refuse nothing a faithful configuration supplies")
        void isAdmittedUnchanged(final String name) {
            assertThat(StagedResourceNames.requireSimpleName(name, KEY)).isSameAs(name);
        }

        @Test
        @DisplayName("resolves inside the staging root, which is the property the whole rule exists for")
        void resolvesInsideTheStagingRoot() {
            final String name =
                    StagedResourceNames.requireSimpleName("AWS.M2.CARDDEMO.DALYTRAN.PS", KEY);

            // Compared as text rather than through the path assertion that reads the file system: the
            // property under test is where the name resolves to, not whether anything exists there.
            assertThat(Path.of(STAGING_ROOT).resolve(name).normalize().toString())
                    .startsWith(Path.of(STAGING_ROOT).toString());
        }

        @Test
        @DisplayName("is neither trimmed nor folded, because a name that needs correcting is a "
                + "configuration defect and correcting it silently would use a resource nobody "
                + "configured")
        void isNeitherTrimmedNorFolded() {
            assertThat(StagedResourceNames.requireSimpleName(" Padded.PS ", KEY))
                    .isEqualTo(" Padded.PS ");
        }
    }

    @Nested
    @DisplayName("A value that would escape the staging root")
    class AnEscapingValue {

        @Test
        @DisplayName("is refused when absolute, because resolving an absolute value against a directory "
                + "yields the absolute value and so leaves the directory entirely")
        void isRefusedWhenAbsolute() {
            // The arithmetic first, so the assertion rests on what resolution does rather than on the
            // rule's description of it.
            assertThat(Path.of(STAGING_ROOT).resolve("/etc/passwd"))
                    .isEqualTo(Path.of("/etc/passwd"));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> StagedResourceNames.requireSimpleName("/etc/passwd", KEY))
                    .withMessageContaining(KEY);
        }

        @Test
        @DisplayName("is refused when it walks upward, because a parent reference resolves above the root")
        void isRefusedWhenItWalksUpward() {
            assertThat(Path.of(STAGING_ROOT).resolve("../../etc/passwd").normalize())
                    .isEqualTo(Path.of("/var/etc/passwd"));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> StagedResourceNames.requireSimpleName("../../etc/passwd", KEY))
                    .withMessageContaining(KEY);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"sub/dalytran.PS", "sub\\dalytran.PS", "/absolute.PS", "..",
            "../sibling.PS", "dir/", "nested/deep/name.PS", "\\\\server\\share\\name.PS"})
        @DisplayName("is refused for carrying a path separator of either convention, so the rule is the "
                + "same rule wherever the module runs")
        void isRefusedForCarryingASeparator(final String value) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> StagedResourceNames.requireSimpleName(value, KEY));
        }

        @Test
        @DisplayName("is refused when it is the current-directory reference, which resolves to the root "
                + "itself rather than to a dataset")
        void isRefusedWhenItIsTheCurrentDirectoryReference() {
            assertThat(Path.of(STAGING_ROOT).resolve(".").normalize())
                    .isEqualTo(Path.of(STAGING_ROOT));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> StagedResourceNames.requireSimpleName(".", KEY));
        }
    }

    @Nested
    @DisplayName("An absent or empty value")
    class AnAbsentValue {

        @Test
        @DisplayName("is refused when null, naming the key so the defect is actionable without reading "
                + "the rule")
        void isRefusedWhenNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> StagedResourceNames.requireSimpleName(null, KEY))
                    .withMessageContaining(KEY);
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"", " ", "\t", "   "})
        @DisplayName("is refused when blank, because a blank value resolves to the staging directory "
                + "itself rather than to a dataset")
        void isRefusedWhenBlank(final String value) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> StagedResourceNames.requireSimpleName(value, KEY))
                    .withMessageContaining(KEY);
        }

        @Test
        @DisplayName("refuses an absent key, because a diagnostic that could not name the key would not "
                + "be actionable")
        void refusesAnAbsentKey() {
            assertThatNullPointerException()
                    .isThrownBy(() -> StagedResourceNames.requireSimpleName("name.PS", null));
        }
    }

    @Nested
    @DisplayName("The holder itself")
    class TheHolderItself {

        @Test
        @DisplayName("is never instantiated, so the rule has exactly one form and no state")
        void isNeverInstantiated() throws Exception {
            final Constructor<StagedResourceNames> constructor =
                    StagedResourceNames.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("publishes the two separators and the two directory references it refuses, so a "
                + "reader and a caller see the same vocabulary")
        void publishesTheVocabularyItRefuses() {
            assertThat(StagedResourceNames.POSIX_SEPARATOR).isEqualTo('/');
            assertThat(StagedResourceNames.WINDOWS_SEPARATOR).isEqualTo('\\');
            assertThat(StagedResourceNames.CURRENT_DIRECTORY).isEqualTo(".");
            assertThat(StagedResourceNames.PARENT_DIRECTORY).isEqualTo("..");
        }
    }
}
