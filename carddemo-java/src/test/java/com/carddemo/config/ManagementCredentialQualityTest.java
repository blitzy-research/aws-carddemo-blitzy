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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Specification of the quality a production management credential must have.
 *
 * <h2>Why presence is not enough</h2>
 *
 * <p>One shared machine credential reaches every metrics endpoint, the exposition scrape and every non-probe
 * management path of this deployment, and a requirement that it merely not be blank would admit a
 * single character as a valid production credential. It is also long-lived by construction, because it
 * is presented on every scrape, and it is compared without any limit on attempts - so a weak value is
 * not merely weak but weak indefinitely and quietly.
 *
 * <h2>The three rules, and what each is for</h2>
 *
 * <p>A length floor of {@value SecurityConfig#MANAGEMENT_TOKEN_MINIMUM_BYTES} bytes - the same floor this
 * module already applies to its signing material - is what makes guessing arithmetic rather than an attack.
 * A floor on distinct characters refuses a value of the required length built from a couple of repeated
 * symbols, whose search space is those symbols rather than its length. And a short list of forbidden words
 * refuses the credential that was left as the example, which is the failure that actually happens.
 *
 * <p>Each case below varies exactly one property of an otherwise acceptable credential, so no case can pass
 * for a reason other than the rule it is named for. Two further cases carry the properties the refusal itself
 * must have: it must name every broken rule, and it must never repeat the configured value - a live
 * credential for the surface that publishes this deployment's metrics must not be written into the log of the
 * deployment that rejected it, where it would outlive the correction.
 *
 * <h2>What is deliberately not attempted</h2>
 *
 * <p>No entropy measurement and no claim of randomness. Randomness is not a property of a string, and a
 * check that pretended otherwise would refuse generated credentials while passing crafted ones. The final
 * group states that boundary as a property: a value that is obviously not generated is refused, and a value
 * that could be is accepted, and nothing here distinguishes further.
 *
 * <h2>Harness</h2>
 *
 * <p>A surefire unit test over the static check, driven with a mock environment. No context is built,
 * because the check is a bean-factory post-processor that runs before any singleton exists.
 *
 * <p>See {@code docs/decision-log.md} entry DL-312.
 *
 * @since 1.0.0
 */
@DisplayName("the production profile's management credential quality")
class ManagementCredentialQualityTest {

    /** Thirty-two bytes of mixed characters: the shape a deployment would generate, and the base below. */
    private static final String ACCEPTABLE = "7Qf2ZmXk9Lv3Rb8TpWn5Yc1Hd6Js4Gu0";

    /**
     * Builds an environment carrying one management credential.
     *
     * @param  configured the credential to configure, or {@code null} to configure none
     * @return the environment
     */
    private static MockEnvironment withCredential(final String configured) {
        final MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(ProductionConfigurationValidator.PRODUCTION_PROFILE);
        if (configured != null) {
            environment.setProperty(SecurityConfig.MANAGEMENT_TOKEN_PROPERTY, configured);
        }
        return environment;
    }

    /**
     * Asserts that one credential is refused, and that the refusal says nothing it should not.
     *
     * @param configured the credential to offer
     * @param because    the rule it breaks, as the refusal words it
     */
    private static void assertRefused(final String configured, final String because) {
        assertThatExceptionOfType(IllegalStateException.class)
                .as("refused a credential of length %d", configured.length())
                .isThrownBy(() -> ProductionConfigurationValidator
                        .validateManagementCredentialQuality(withCredential(configured)))
                .withMessageContaining(SecurityConfig.MANAGEMENT_TOKEN_PROPERTY)
                .withMessageContaining(because)
                .withMessageNotContaining(configured);
    }

    /**
     * Asserts that a credential carrying a typed word is refused for carrying one, <em>without</em> the
     * refusal saying which.
     *
     * <p>The distinction is the whole point of this helper. The matched word is a substring of a live
     * credential, so a refusal that named it published part of the value into the log of the deployment
     * that rejected it - and it narrowed a guess at the rest, because a reader then knows one run of the
     * credential exactly. The rule is what a deployer needs; the finding is what a log reader gains.
     * Recorded as {@code DL-348}.</p>
     *
     * @param configured  the credential to offer
     * @param typedWord   the word it carries, which must not appear anywhere in the refusal
     */
    private static void assertRefusedWithoutNamingTheWord(final String configured,
            final String typedWord) {
        assertThatExceptionOfType(IllegalStateException.class)
                .as("refused a credential carrying a typed word")
                .isThrownBy(() -> ProductionConfigurationValidator
                        .validateManagementCredentialQuality(withCredential(configured)))
                .withMessageContaining(SecurityConfig.MANAGEMENT_TOKEN_PROPERTY)
                .withMessageContaining("must not contain any of the")
                .withMessageNotContaining(configured)
                .withMessageNotContaining(typedWord)
                .withMessageNotContaining(typedWord.toUpperCase(Locale.ROOT));
    }

    @Nested
    @DisplayName("a credential that could have been generated is accepted")
    class WhatIsAccepted {

        @Test
        @DisplayName("thirty-two bytes of mixed characters is accepted")
        void theAcceptableCredentialIsAccepted() {
            assertThatCode(() -> ProductionConfigurationValidator
                    .validateManagementCredentialQuality(withCredential(ACCEPTABLE)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a longer generated credential is accepted, because the rule is a floor and not a "
                + "length")
        void aLongerCredentialIsAccepted() {
            assertThatCode(() -> ProductionConfigurationValidator
                    .validateManagementCredentialQuality(withCredential(ACCEPTABLE + ACCEPTABLE)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an absent credential is not reported here, because the required-settings sweep "
                + "already reports it and one missing variable must produce one message")
        void anAbsentCredentialIsSomebodyElsesReport() {
            assertThatCode(() -> ProductionConfigurationValidator
                    .validateManagementCredentialQuality(withCredential(null)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a blank credential is not reported here either: blank is the fail-closed state the "
                + "filter already treats as no identity at all")
        void aBlankCredentialIsSomebodyElsesReport() {
            assertThatCode(() -> ProductionConfigurationValidator
                    .validateManagementCredentialQuality(withCredential("   ")))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("each of the three rules refuses on its own")
    class EachRuleRefuses {

        @Test
        @DisplayName("a credential one byte below the floor is refused, so the floor is the floor")
        void aCredentialBelowTheFloorIsRefused() {
            final String tooShort = ACCEPTABLE.substring(0,
                    SecurityConfig.MANAGEMENT_TOKEN_MINIMUM_BYTES - 1);

            assertRefused(tooShort, "at least " + SecurityConfig.MANAGEMENT_TOKEN_MINIMUM_BYTES + " bytes");
        }

        @Test
        @DisplayName("a single character is refused, which was a valid production credential before this "
                + "rule existed")
        void aSingleCharacterIsRefused() {
            // A punctuation mark rather than a letter, deliberately: the shared assertion also proves the
            // refusal does not repeat the configured value, and no single letter can carry that assertion -
            // the refusal's own prose contains every letter somewhere. This character appears nowhere in it.
            assertRefused("~", "bytes");
        }

        @Test
        @DisplayName("length made of whitespace does not count, because the filter strips before comparing "
                + "and a credential of mostly spaces is not the length it appears to be")
        void whitespaceDoesNotCountTowardsTheFloor() {
            // Deliberately over the floor as written and under it once stripped, which is the only shape
            // that can tell the two measurements apart: 33 bytes of text, 23 bytes of credential. The
            // consumer strips before comparing, so 23 bytes is what an attacker would have to guess.
            final String padded = "     Zq7Wm2Bx9Kd4Rt6Yn1Pv5Hs     ";

            assertThat(padded.length())
                    .as("as written it clears the floor, so only the stripping can refuse it")
                    .isGreaterThan(SecurityConfig.MANAGEMENT_TOKEN_MINIMUM_BYTES);
            assertThat(padded.strip().length())
                    .isLessThan(SecurityConfig.MANAGEMENT_TOKEN_MINIMUM_BYTES);
            assertRefused(padded, "bytes");
        }

        @Test
        @DisplayName("a credential of the required length built from too few distinct characters is "
                + "refused, because its search space is those characters and not its length")
        void aRepetitiveCredentialIsRefused() {
            final String repetitive = "ababababababababababababababababab";

            assertThat(repetitive.length())
                    .as("long enough to pass the floor, so only the distinctness rule can refuse it")
                    .isGreaterThan(SecurityConfig.MANAGEMENT_TOKEN_MINIMUM_BYTES);
            assertRefused(repetitive, "distinct characters");
        }

        @Test
        @DisplayName("a credential containing a word that only appears in typed values is refused, even "
                + "when it satisfies both numeric rules")
        void aTypedCredentialIsRefused() {
            final String typed = "Xk9-changeme-Rb8TpWn5Yc1Hd6Js4Gu0";

            assertThat(typed.length())
                    .isGreaterThanOrEqualTo(SecurityConfig.MANAGEMENT_TOKEN_MINIMUM_BYTES);
            assertThat(typed.chars().distinct().count())
                    .as("distinct enough to pass, so only the forbidden-word rule can refuse it")
                    .isGreaterThanOrEqualTo(
                            ProductionConfigurationValidator
                                    .MANAGEMENT_TOKEN_MINIMUM_DISTINCT_CHARACTERS);
            assertRefusedWithoutNamingTheWord(typed, "changeme");
        }

        @Test
        @DisplayName("the forbidden words are matched without regard to case, because a deployer who "
                + "capitalised the example did not change it")
        void forbiddenWordsAreMatchedWithoutCase() {
            assertRefusedWithoutNamingTheWord("Xk9-PLACEHOLDER-Rb8TpWn5Yc1Hd6Js4", "placeholder");
        }

        @Test
        @DisplayName("the module's own name is forbidden, because a credential naming the application it "
                + "protects is a credential somebody typed")
        void theApplicationNameIsForbidden() {
            // Asserted through the rule rather than through the word, and deliberately not through
            // assertRefusedWithoutNamingTheWord: the property key is `carddemo.security.management.token`,
            // so this one word appears in every refusal this check composes and its presence would say
            // nothing either way. What proves the finding is withheld in general is
            // theRefusalIsTheSameWhicheverWordWasFound below, which compares two whole messages.
            assertRefused("carddemo-Xk9-Rb8TpWn5Yc1Hd6Js4Gu0z", "must not contain any of the");
        }

        @Test
        @DisplayName("the refusal is the same message whichever word was found, so it cannot be read "
                + "backwards to the word - which is the disclosure, stated as an invariant")
        void theRefusalIsTheSameWhicheverWordWasFound() {
            // The strongest available statement of the property, and stronger than asserting that one
            // word is absent: two credentials that differ only in which refused word they carry must
            // produce byte-identical refusals. If any part of the message were derived from the finding -
            // the word, its position, its length - these two would differ.
            final String carryingChangeme = "Xk9-changeme-Rb8TpWn5Yc1Hd6Js4Gu0";
            final String carryingPlaceholder = "Xk9-placeholder-Rb8TpWn5Yc1Hd6J4";

            assertThat(carryingChangeme.length())
                    .isGreaterThanOrEqualTo(SecurityConfig.MANAGEMENT_TOKEN_MINIMUM_BYTES);
            assertThat(carryingPlaceholder.length())
                    .isGreaterThanOrEqualTo(SecurityConfig.MANAGEMENT_TOKEN_MINIMUM_BYTES);
            assertThat(carryingChangeme.chars().distinct().count())
                    .as("both must break the word rule and nothing else, or the comparison below would "
                            + "be between two different fault sets")
                    .isGreaterThanOrEqualTo(
                            ProductionConfigurationValidator
                                    .MANAGEMENT_TOKEN_MINIMUM_DISTINCT_CHARACTERS);
            assertThat(carryingPlaceholder.chars().distinct().count())
                    .isGreaterThanOrEqualTo(
                            ProductionConfigurationValidator
                                    .MANAGEMENT_TOKEN_MINIMUM_DISTINCT_CHARACTERS);

            assertThat(refusalFor(carryingChangeme))
                    .as("no part of the refusal may be derived from which word was found")
                    .isEqualTo(refusalFor(carryingPlaceholder));
        }

        /**
         * Offers one credential and returns the refusal it produces.
         *
         * @param  configured the credential to offer
         * @return the refusal message
         */
        private String refusalFor(final String configured) {
            return assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateManagementCredentialQuality(withCredential(configured)))
                    .actual()
                    .getMessage();
        }
    }

    @Nested
    @DisplayName("what the refusal itself has to do")
    class WhatTheRefusalDoes {

        @Test
        @DisplayName("names every broken rule rather than the first, because a deployer fixing one at a "
                + "time learns about them one start-up at a time")
        void namesEveryBrokenRule() {
            // The credential offered breaks all three rules: it is shorter than the byte floor, it holds
            // seven distinct characters where sixteen are required, and it is one of the refused words
            // // outright. "secret" is deliberately not the value offered, because an assertion requiring the
            // // refusal to quote the matched word back is the disclosure this suite forbids. "changeme" is
            // // offered instead because, unlike "secret" and "token", it appears nowhere in the refusal's
            // own prose, so its absence is a statement about the finding rather than about the wording.
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateManagementCredentialQuality(withCredential("changeme")))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .contains("bytes")
                            .contains("distinct characters")
                            .contains("must not contain any of the")
                            .contains("3 rule(s)")
                            .doesNotContain("changeme"));
        }

        @Test
        @DisplayName("never repeats the configured value, because this is a live credential for the "
                + "surface that publishes this deployment's metrics")
        void neverRepeatsTheConfiguredValue() {
            final String live = "a-real-looking-management-credential-0123456789";

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateManagementCredentialQuality(withCredential(live)))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .doesNotContain(live)
                            .doesNotContain("a-real-looking"));
        }

        @Test
        @DisplayName("names the profile it refused and cites the decision, so a deployer reading only the "
                + "message can find both the rule and the reasoning")
        void namesTheProfileAndTheDecision() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateManagementCredentialQuality(withCredential("x")))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .contains(ProductionConfigurationValidator.PRODUCTION_PROFILE)
                            .contains("DL-312")
                            .contains("application-prod.yml"));
        }

        @Test
        @DisplayName("says how to produce an acceptable value, because a refusal a deployer cannot act on "
                + "is a refusal they will work around")
        void saysHowToProduceAnAcceptableValue() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateManagementCredentialQuality(withCredential("x")))
                    .withMessageContaining("32 bytes from a cryptographic source");
        }
    }

    @Nested
    @DisplayName("the boundary of what this check claims")
    class TheBoundaryOfTheClaim {

        @Test
        @DisplayName("the floor is stated on the class that consumes the credential, not restated here, so "
                + "the rule and its use cannot drift apart")
        void theFloorHasOneHome() {
            assertThat(SecurityConfig.MANAGEMENT_TOKEN_MINIMUM_BYTES)
                    .as("the same floor this module applies to its signing material")
                    .isEqualTo(32);
        }

        @Test
        @DisplayName("the distinctness floor is cleared by thirty-two characters drawn from a wide "
                + "alphabet, which is the shape the profile's guidance names")
        void theDistinctnessFloorIsClearedByTheGeneratorTheGuidanceNames() {
            assertThat(ProductionConfigurationValidator.MANAGEMENT_TOKEN_MINIMUM_DISTINCT_CHARACTERS)
                    .isEqualTo(16);
            assertThat(ACCEPTABLE.chars().distinct().count())
                    .as("thirty-two characters over a base64-width alphabet clear this floor, which is "
                            + "what makes the rule safe to apply to the generator the profile names; a "
                            + "narrow alphabet does not, and the profile says so rather than leaving a "
                            + "deployer to find out by being refused")
                    .isGreaterThan(ProductionConfigurationValidator
                            .MANAGEMENT_TOKEN_MINIMUM_DISTINCT_CHARACTERS);
        }

        @Test
        @DisplayName("a value that merely looks unusual is accepted: this check refuses what is obviously "
                + "not generated and claims nothing beyond that")
        void anUnusualButPlausibleValueIsAccepted() {
            assertThatCode(() -> ProductionConfigurationValidator
                    .validateManagementCredentialQuality(
                            withCredential("Zz9!Qw8@Er7#Ty6$Ui5%Op4^As3&Df2*")))
                    .doesNotThrowAnyException();
        }
    }
}
