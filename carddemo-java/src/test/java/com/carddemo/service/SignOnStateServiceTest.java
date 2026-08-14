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

import com.carddemo.domain.enums.UserType;
import com.carddemo.support.InMemoryCredentialMaster;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Asserts what {@link SignOnStateService} fingerprints, what it deliberately does not, and that its answer
 * changes exactly when an administrative change should end a session.
 *
 * <p><strong>Why the class under test exists.</strong> Every legacy terminal turn re-entered a transaction
 * that read the credential master again, so an entitlement could not go stale: there was nothing carried
 * between turns to go stale. A signed bearer token is the opposite - it stays true to its signature long
 * after it has stopped being true about the record it describes - so demoting, deleting or giving a new
 * credential to an operator changed nothing at all until the token already in that operator's hands
 * expired. The fingerprint this class derives is what lets the record be consulted again.
 *
 * <p><strong>What is asserted, and in which direction.</strong> Three fields are covered - the identifier,
 * the raw one-character type code and the stored credential digest - and two are deliberately not: the
 * given and family names. Both halves matter and both are asserted. Covering too little would leave a
 * demotion or a credential reset with no effect; covering too much would end an operator's session because
 * somebody corrected the spelling of a surname. The negative assertions on disclosure are equally
 * load-bearing: the fingerprint must carry no readable part of the stored digest, because it travels in a
 * token and a token is readable by whoever holds it.
 *
 * <p><strong>No credential value from the estate's provisioning records is used, named or restated here</strong>,
 * and no digest or fingerprint is ever placed in an assertion description - the disclosure assertions
 * compare inside a boolean so that even a failing run cannot render one.
 *
 * <p>The legacy estate is cited by member path, field name and line number only; no program, copybook or
 * job-stream source text is reproduced.
 */
@DisplayName("The fingerprint of a sign-on record: what it covers, and what it must not disclose")
class SignOnStateServiceTest {

    /** An administrative identifier of the seeded shape: eight characters, the record key's fixed width. */
    private static final String ADMINISTRATOR_ID = "ADMIN001";

    /** A standard-user identifier of the same seeded shape and the same fixed width. */
    private static final String STANDARD_USER_ID = "USER0001";

    /**
     * Index at which the salt-and-hash part of a BCrypt digest begins: four characters of version tag, two
     * of cost factor and one separator. Stated so a disclosure assertion can test the secret-bearing part
     * of a digest rather than the header, which is common to every digest and proves nothing.
     */
    private static final int SALT_AND_HASH_START = 7;

    /** The credential master under the service, re-seeded before every test. */
    private final InMemoryCredentialMaster credentialMaster = new InMemoryCredentialMaster();

    /** The service under test. */
    private SignOnStateService service;

    /** Seeds one operator of each declared type and builds the service over them. */
    @BeforeEach
    void setUp() {
        this.credentialMaster.reset()
                .with(ADMINISTRATOR_ID, UserType.ADMIN.getCode())
                .with(STANDARD_USER_ID, UserType.USER.getCode());
        this.service = new SignOnStateService(this.credentialMaster.repository());
    }

    /**
     * Reads the fingerprint of a seeded operator, failing rather than answering empty.
     *
     * @param userId the identifier to read
     * @return that operator's current fingerprint
     */
    private String fingerprintOf(final String userId) {
        return this.service.currentStateOf(userId).orElseThrow().fingerprint();
    }

    @Nested
    @DisplayName("Reading the state of a record")
    class ReadingState {

        @Test
        @DisplayName("answers the raw type code exactly as the record stores it, unjudged and unfolded, "
                + "because the column screens no value and the estate screened none")
        void answersTheRawTypeCode() {
            assertThat(service.currentStateOf(ADMINISTRATOR_ID).orElseThrow().userTypeCode())
                    .isEqualTo(UserType.ADMIN.getCode());
            assertThat(service.currentStateOf(STANDARD_USER_ID).orElseThrow().userTypeCode())
                    .isEqualTo(UserType.USER.getCode());
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"X", "a", "u", "1", " "})
        @DisplayName("answers a type code the estate never declared rather than refusing it, since the "
                + "record may hold one and refusing here would make the record unreadable")
        void answersAnUndeclaredTypeCode(final String undeclared) {
            credentialMaster.withUserType(STANDARD_USER_ID, undeclared);

            assertThat(service.currentStateOf(STANDARD_USER_ID).orElseThrow().userTypeCode())
                    .isEqualTo(undeclared);
        }

        @Test
        @DisplayName("answers a fingerprint of the published length in lower-case hexadecimal")
        void answersAFingerprintOfThePublishedShape() {
            assertThat(fingerprintOf(ADMINISTRATOR_ID))
                    .hasSize(SignOnStateService.FINGERPRINT_LENGTH)
                    .matches("[0-9a-f]+");
        }

        @Test
        @DisplayName("answers nothing when no record carries the identifier, rather than raising, because "
                + "an absent record is an ordinary condition on this path")
        void answersNothingForAnAbsentRecord() {
            credentialMaster.without(ADMINISTRATOR_ID);

            assertThat(service.currentStateOf(ADMINISTRATOR_ID)).isEmpty();
        }

        @Test
        @DisplayName("does not absorb a failure to reach the record, because a session must not be issued "
                + "while the record cannot be read")
        void doesNotAbsorbAFailureToReachTheRecord() {
            credentialMaster.failLookupsWith(new IllegalStateException("credential master unreachable"));

            assertThat(catchThrowable(() -> service.currentStateOf(ADMINISTRATOR_ID)))
                    .as("issuing a session against a record nobody could read is the outcome this refuses")
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("refuses an absent identifier rather than reading a record for nothing")
        void refusesAnAbsentIdentifier() {
            assertThatNullPointerException().isThrownBy(() -> service.currentStateOf(null));
        }

        @Test
        @DisplayName("refuses an absent credential master, so no instance can exist that answers about "
                + "nothing")
        void refusesAnAbsentCredentialMaster() {
            assertThatNullPointerException().isThrownBy(() -> new SignOnStateService(null));
        }
    }

    @Nested
    @DisplayName("What the fingerprint covers")
    class Coverage {

        @Test
        @DisplayName("is stable while the record stands still, since it describes the record and not the "
                + "occasion of reading it")
        void isStableWhileTheRecordStandsStill() {
            assertThat(fingerprintOf(ADMINISTRATOR_ID))
                    .as("a value that changed per read could not be compared against one issued earlier")
                    .isEqualTo(fingerprintOf(ADMINISTRATOR_ID));
        }

        @Test
        @DisplayName("changes when the raw type code changes, which is what makes a demotion take effect")
        void changesWhenTheTypeCodeChanges() {
            final String before = fingerprintOf(ADMINISTRATOR_ID);

            credentialMaster.withUserType(ADMINISTRATOR_ID, UserType.USER.getCode());

            assertThat(fingerprintOf(ADMINISTRATOR_ID)).isNotEqualTo(before);
        }

        @Test
        @DisplayName("changes when the stored credential changes, which is what makes setting a credential "
                + "end the sessions issued against the previous one")
        void changesWhenTheStoredCredentialChanges() {
            final String before = fingerprintOf(STANDARD_USER_ID);

            credentialMaster.withResetCredential(STANDARD_USER_ID);

            assertThat(fingerprintOf(STANDARD_USER_ID)).isNotEqualTo(before);
        }

        @Test
        @DisplayName("differs between two identities holding the same type, so a fingerprint cannot be "
                + "replayed against another record")
        void differsBetweenTwoIdentitiesOfOneType() {
            final String second = "USER0002";
            credentialMaster.with(second, UserType.USER.getCode());

            assertThat(fingerprintOf(STANDARD_USER_ID)).isNotEqualTo(fingerprintOf(second));
        }

        @Test
        @DisplayName("does not change when a name changes, so correcting a spelling does not end anybody's "
                + "session")
        void doesNotChangeWhenANameChanges() {
            final String before = fingerprintOf(STANDARD_USER_ID);

            credentialMaster.findById(STANDARD_USER_ID).orElseThrow().setSecUsrFname("Corrected");
            credentialMaster.findById(STANDARD_USER_ID).orElseThrow().setSecUsrLname("Also-Corrected");

            assertThat(fingerprintOf(STANDARD_USER_ID))
                    .as("the given and family names are not security facts and are deliberately uncovered")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("distinguishes a split of the same characters, because each field is length-prefixed "
                + "in the canonical image")
        void distinguishesASplitOfTheSameCharacters() {
            // Two records whose identifier and type code between them use the same characters in the same
            // order. Without a length prefix per field, the two canonical images would be identical.
            final String longerIdentifier = "USER0001U";
            credentialMaster.with(longerIdentifier, "");
            final String sharedDigest = credentialMaster.findById(longerIdentifier)
                    .orElseThrow().credentialDigest();
            credentialMaster.with(STANDARD_USER_ID, "U", sharedDigest);

            assertThat(fingerprintOf(longerIdentifier)).isNotEqualTo(fingerprintOf(STANDARD_USER_ID));
        }

        @Test
        @DisplayName("changes at every successive credential reset, so the value tracks the record rather "
                + "than settling after the first change")
        void changesAtEverySuccessiveReset() {
            // Three resets rather than a longer run: the fixture hands out distinct digests from a pool of
            // finite size, so a run long enough to exhaust it would be asserting the pool's size rather
            // than the service's behaviour. Three is well inside it and is enough to show the value keeps
            // moving after the first change rather than only once.
            final int resets = 3;
            final Set<String> observed = new HashSet<>();
            String previous = fingerprintOf(STANDARD_USER_ID);
            observed.add(previous);

            for (int reset = 0; reset < resets; reset++) {
                credentialMaster.withResetCredential(STANDARD_USER_ID);
                final String current = fingerprintOf(STANDARD_USER_ID);

                assertThat(current)
                        .as("each reset must change the value, not only the first")
                        .isNotEqualTo(previous);
                observed.add(current);
                previous = current;
            }

            assertThat(observed).hasSize(resets + 1);
        }
    }

    @Nested
    @DisplayName("What the fingerprint must not disclose")
    class Disclosure {

        @Test
        @DisplayName("carries no part of the stored credential digest, which is the one value that would "
                + "make issuing a token a disclosure")
        void carriesNoPartOfTheStoredDigest() {
            final String digest = InMemoryCredentialMaster.nextDigest();
            credentialMaster.with(STANDARD_USER_ID, UserType.USER.getCode(), digest);

            final String fingerprint = fingerprintOf(STANDARD_USER_ID);

            // Compared inside booleans deliberately: an assertion written the other way round would put
            // the stored digest into the description a failing run renders.
            assertThat(fingerprint.contains(digest)).isFalse();
            assertThat(fingerprint.contains(digest.substring(SALT_AND_HASH_START))).isFalse();
            assertThat(digest.contains(fingerprint)).isFalse();
        }

        @Test
        @DisplayName("carries the identifier in no readable form, even though it is an input")
        void carriesTheIdentifierInNoReadableForm() {
            assertThat(fingerprintOf(ADMINISTRATOR_ID))
                    .doesNotContain(ADMINISTRATOR_ID)
                    .doesNotContain(ADMINISTRATOR_ID.toLowerCase(Locale.ROOT));
        }

        @Test
        @DisplayName("is not the stored digest under another name, and cannot verify a credential - only "
                + "the digest service can")
        void isNotTheStoredDigestUnderAnotherName() {
            final String fingerprint = fingerprintOf(STANDARD_USER_ID);
            final String storedDigest = credentialMaster.findById(STANDARD_USER_ID)
                    .orElseThrow().credentialDigest();

            assertThat(fingerprint.equals(storedDigest)).isFalse();
            assertThat(new CredentialDigestService().isDigest(fingerprint))
                    .as("a fingerprint is not digest-shaped, so it can never be mistaken for a stored "
                            + "credential by the persistence guard")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Whether a presented pair still names the record")
    class StillNames {

        @Test
        @DisplayName("says yes for the record's own type code and fingerprint")
        void saysYesForTheRecordsOwnPair() {
            assertThat(service.stillNames(ADMINISTRATOR_ID, UserType.ADMIN.getCode(),
                    fingerprintOf(ADMINISTRATOR_ID))).isTrue();
        }

        @Test
        @DisplayName("says no once the record is deleted")
        void saysNoOnceTheRecordIsDeleted() {
            final String fingerprint = fingerprintOf(ADMINISTRATOR_ID);

            credentialMaster.without(ADMINISTRATOR_ID);

            assertThat(service.stillNames(ADMINISTRATOR_ID, UserType.ADMIN.getCode(), fingerprint))
                    .isFalse();
        }

        @Test
        @DisplayName("says no once the type code changes, even when the type code presented is the one the "
                + "record now holds")
        void saysNoOnceTheTypeCodeChanges() {
            final String fingerprint = fingerprintOf(ADMINISTRATOR_ID);

            credentialMaster.withUserType(ADMINISTRATOR_ID, UserType.USER.getCode());

            assertThat(service.stillNames(ADMINISTRATOR_ID, UserType.ADMIN.getCode(), fingerprint))
                    .as("the fingerprint no longer describes the record")
                    .isFalse();
            assertThat(service.stillNames(ADMINISTRATOR_ID, UserType.USER.getCode(), fingerprint))
                    .as("and presenting the new code does not rescue the stale fingerprint")
                    .isFalse();
        }

        @Test
        @DisplayName("says no once the credential is set")
        void saysNoOnceTheCredentialIsSet() {
            final String fingerprint = fingerprintOf(STANDARD_USER_ID);

            credentialMaster.withResetCredential(STANDARD_USER_ID);

            assertThat(service.stillNames(STANDARD_USER_ID, UserType.USER.getCode(), fingerprint))
                    .isFalse();
        }

        @Test
        @DisplayName("says no when the type code presented disagrees with the record, even though the "
                + "fingerprint reconciles - which is what stops a session naming an entitlement its record "
                + "denies")
        void saysNoWhenTheTypeCodeDisagreesWithTheRecord() {
            assertThat(service.stillNames(ADMINISTRATOR_ID, UserType.USER.getCode(),
                    fingerprintOf(ADMINISTRATOR_ID)))
                    .as("the fingerprint proves what the record said, not what the caller claimed")
                    .isFalse();
        }

        @Test
        @DisplayName("says no for another record's fingerprint, so a fingerprint cannot be replayed")
        void saysNoForAnotherRecordsFingerprint() {
            assertThat(service.stillNames(ADMINISTRATOR_ID, UserType.ADMIN.getCode(),
                    fingerprintOf(STANDARD_USER_ID))).isFalse();
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"", "   ", "not-a-fingerprint", "0123456789abcdef"})
        @DisplayName("says no for a fingerprint that is absent, blank or simply wrong, without raising")
        void saysNoForAnUnusableFingerprint(final String unusable) {
            assertThat(service.stillNames(ADMINISTRATOR_ID, UserType.ADMIN.getCode(), unusable)).isFalse();
        }

        @Test
        @DisplayName("says no rather than raising when any argument is absent, because an unusable session "
                + "is an ordinary condition on this path and not an error")
        void saysNoRatherThanRaisingForAbsentArguments() {
            final String fingerprint = fingerprintOf(ADMINISTRATOR_ID);

            assertThat(service.stillNames(null, UserType.ADMIN.getCode(), fingerprint)).isFalse();
            assertThat(service.stillNames(ADMINISTRATOR_ID, null, fingerprint)).isFalse();
            assertThat(service.stillNames(ADMINISTRATOR_ID, UserType.ADMIN.getCode(), null)).isFalse();
        }

        @Test
        @DisplayName("says no, and absorbs the failure, when the record cannot be reached - failing closed "
                + "rather than leaving a session standing while revocation is not working")
        void saysNoAndAbsorbsAFailureToReachTheRecord() {
            final String fingerprint = fingerprintOf(ADMINISTRATOR_ID);
            credentialMaster.failLookupsWith(new IllegalStateException("credential master unreachable"));

            assertThat(catchThrowable(() -> service.stillNames(ADMINISTRATOR_ID,
                    UserType.ADMIN.getCode(), fingerprint)))
                    .as("the caller is a request filter; a raised failure there answers with a server "
                            + "error instead of a refusal")
                    .isNull();
            assertThat(service.stillNames(ADMINISTRATOR_ID, UserType.ADMIN.getCode(), fingerprint))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("The published contract")
    class PublishedContract {

        @Test
        @DisplayName("names the version marker, so changing what is covered is a deliberate revocation of "
                + "every session rather than a silent change of meaning")
        void namesTheVersionMarker() {
            assertThat(SignOnStateService.FINGERPRINT_SCHEME).isEqualTo("carddemo-signon-state-v1");
        }

        @Test
        @DisplayName("names the digest algorithm and the length that follows from it")
        void namesTheDigestAlgorithmAndLength() {
            assertThat(SignOnStateService.DIGEST_ALGORITHM).isEqualTo("SHA-256");
            assertThat(SignOnStateService.FINGERPRINT_LENGTH).isEqualTo(64);
        }
    }
}
