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

import com.carddemo.domain.enums.UserType;
import com.carddemo.service.SignOnStateService;
import com.carddemo.support.InMemoryCredentialMaster;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Asserts the whole token lifecycle of {@link JwtTokenProvider} - minting, verification, refusal and
 * expiry - and above all that a minted token carries <em>exactly two</em> facts about the signed-on
 * identity and nothing else whatsoever.
 *
 * <p><strong>What the token replaces, and why minimality is the central assertion.</strong> The legacy
 * pseudo-conversation handed a shared communication area from one program to the next, and every online
 * program included it textually. That area is {@code CARDDEMO-COMMAREA}, declared at
 * {@code app/cpy/COCOM01Y.cpy} L19, 160 bytes wide across five groups. It could be trusted because only
 * the transaction manager could produce one; a bearer token can be produced by anyone and is trusted
 * solely because of its signature, and it is additionally logged, forwarded and cached by clients in a way
 * the communication area never was. Two of that area's fields are security facts and are signed here:
 * {@code CDEMO-USER-ID}, {@code PIC X(08)} at L25, as the subject, and {@code CDEMO-USER-TYPE},
 * {@code PIC X(01)} at L26 - whose only declared values are the level-88 condition names
 * {@code CDEMO-USRTYP-ADMIN}, value {@code A} at L27, and {@code CDEMO-USRTYP-USER}, value {@code U} at
 * L28 - as the role claim. Every remaining field is mutable per-request navigation or a business
 * selection, belongs to {@code com.carddemo.api.dto.NavigationContext}, is echoed by the client, and must
 * never appear in a token. {@link ClaimMinimality} holds that line by name, field by field, so that an
 * implementer who later widens the claim set breaks a named assertion rather than a vague one.
 *
 * <p><strong>The claim set is read independently of the class under test.</strong> Asking the provider
 * what it put in a token would let a defect and its own test agree with each other. Every claim assertion
 * below therefore splits the compact token itself, Base64URL-decodes the payload segment and parses it
 * with an independent object mapper, so the expectation is computed from the wire form rather than from
 * the provider's decoder.
 *
 * <p><strong>No credential appears in this file, in any form.</strong> Every piece of signing material is
 * drawn from {@link SecureRandom} at run time and encoded for transport, so nothing here resembles a
 * credential, nothing here is a value any profile ships, and no material is ever written to output or
 * placed in an assertion description - the refusal assertions in {@link UnusableConfiguration} compare
 * inside a boolean so that even a failing run cannot render one. The single shared cleartext credential
 * that the legacy provisioning job stream carries in stream is not referenced here in any spelling,
 * because none of these assertions needs it.
 *
 * <p><strong>Time is supplied, never read.</strong> Every provider below is built on
 * {@link Clock#fixed(Instant, java.time.ZoneId)}, which is what makes an expiry assertion exact and
 * immediate. The fixture instant is deliberately in the past, so every acceptance assertion here would
 * fail outright against an implementation that consulted the platform clock; {@link Expiry} makes that
 * discrimination explicit rather than incidental.
 *
 * <p><strong>Scope.</strong> This class tests the token and nothing around it. The sign-on message
 * catalogue, the upper-case fold applied to keyed input, credential hashing, the route-to-role table and
 * the settings record's own binding and redaction are each owned by their own test class, and are not
 * restated here.
 *
 * <p>The legacy estate is cited above and below by member path, field name, width, condition-name value
 * and line number only; no program, copybook, screen-map or job-stream source text is reproduced.
 */
@DisplayName("Bearer tokens: exactly two signed facts, trusted only when genuinely trustworthy")
class JwtTokenProviderTest {

    /**
     * Issuer these fixtures mint with and require. It names this module rather than an address, which is
     * why every assertion on it reads the raw claim rather than a locator-typed accessor.
     */
    private static final String ISSUER = "carddemo-java";

    /**
     * Lifetime the fixture settings carry. It is a credential lifetime and not a service level, a latency
     * budget or a time-out, and the module documents no such figure anywhere.
     *
     * <p>This is the only span stated in this file. Every instant these tests verify at is derived from
     * {@link JwtProperties#expiration()} on the settings instance actually handed to the provider, so a
     * change here cannot leave an expiry assertion silently measuring the wrong window. It is long enough
     * that an instant one further lifetime beyond expiry is unambiguously outside the tolerance the
     * verifier is left with, which {@link Expiry} depends on.</p>
     */
    private static final Duration FIXTURE_LIFETIME = Duration.ofMinutes(30);

    /**
     * The instant every fixture provider treats as now. Fixed rather than current, and deliberately
     * already past, so that an acceptance assertion could not pass against an implementation reading the
     * platform clock.
     */
    private static final Instant ISSUED_AT = Instant.parse("2026-01-01T12:00:00Z");

    /**
     * Length of the signing material these fixtures derive, in bytes, chosen to clear the floor the fixed
     * signature algorithm imposes. It is a cryptographic length and not a capacity or timing figure.
     */
    private static final int SIGNING_MATERIAL_BYTES = 32;

    /** Source of every piece of signing material below, so that none of it is written into this file. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Index at which the salt-and-hash part of a BCrypt digest begins: four characters of version tag, two
     * of cost factor and one separator.
     *
     * <p>Stated so that {@link RecordFingerprintClaim#carriesNoPartOfTheStoredDigest()} can assert the
     * fingerprint carries no fragment of the <em>secret-bearing</em> part of a digest, rather than only
     * that it does not carry the whole value - the header alone is common to every digest and proves
     * nothing.</p>
     */
    private static final int SALT_AND_HASH_START = 7;

    /** Reads a token's payload segment back from the wire, independently of the class under test. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * An administrative identifier of the seeded shape: eight characters, the fixed width of the user
     * record's key. The seeded identifiers and their types are non-secret metadata of the provisioning
     * job stream; no credential accompanies them here because none of these assertions needs one.
     */
    private static final String ADMINISTRATOR_ID = "ADMIN001";

    /** A standard-user identifier of the same seeded shape and the same fixed width. */
    private static final String STANDARD_USER_ID = "USER0001";

    /**
     * The complete set of claim names a minted token may carry: the two signed facts plus the registered
     * claims the provider states about the token itself.
     *
     * <p>The four registered names are written as literals deliberately. They are the wire contract, they
     * are not the provider's to rename, and spelling them out here keeps this expectation independent of
     * the implementation being checked. The role claim is taken from the published constant so that the
     * minting side, the filter chain and this expectation cannot drift apart on it, and its wire spelling
     * is pinned separately by {@link ClaimMinimality#namesTheRoleClaimOnTheWire()}.</p>
     *
     * <p><strong>{@link JwtTokenProvider#SECURITY_STATE_CLAIM} is enrolled here by name, and the
     * justification belongs in this file rather than only in the class it describes.</strong> It is not a
     * third fact about the signed-on identity and it is not a field of the communication area: it is a
     * fingerprint of the user-security record the other two facts were read from, and it exists because a
     * signed fact stays true to its signature after it has stopped being true about that record. Without
     * it, demoting, deleting or resetting the credential of an operator changed nothing until the token
     * already in that operator's hands expired. It is admitted to this set because it carries no value of
     * the record - {@link RecordFingerprintClaim} asserts that separately, against the identifier, the raw
     * type code and the stored digest - and admitting it by name is what keeps every <em>other</em>
     * widening of the claim set a failure.</p>
     */
    private static final Set<String> PERMITTED_CLAIM_NAMES =
            Set.of("iss", "sub", "iat", "exp", JwtTokenProvider.ROLE_CLAIM,
                    JwtTokenProvider.AUTHORITY_CLAIM, JwtTokenProvider.SECURITY_STATE_CLAIM);

    /**
     * Claim names that would carry a field of the legacy communication area other than the two signed
     * ones, enumerated so that adding any of them fails an assertion that names it.
     *
     * <p>Each of the thirteen fields is listed under the spellings a reasonable implementer would reach
     * for. The routing fields are the general-info group's from- and to-transaction identifiers and
     * program names ({@code CDEMO-FROM-TRANID} {@code PIC X(04)} L21, {@code CDEMO-FROM-PROGRAM}
     * {@code PIC X(08)} L22, {@code CDEMO-TO-TRANID} {@code PIC X(04)} L23, {@code CDEMO-TO-PROGRAM}
     * {@code PIC X(08)} L24), the one-digit re-entry flag {@code CDEMO-PGM-CONTEXT} {@code PIC 9(01)} L29,
     * and the more-info group's {@code CDEMO-LAST-MAP} {@code PIC X(7)} L43 and
     * {@code CDEMO-LAST-MAPSET} {@code PIC X(7)} L44. The remainder are business selections and personal
     * data: {@code CDEMO-CUST-ID} {@code PIC 9(09)} L33 with the three name fields
     * {@code CDEMO-CUST-FNAME}, {@code CDEMO-CUST-MNAME} and {@code CDEMO-CUST-LNAME}, each
     * {@code PIC X(25)}, at L34 to L36; {@code CDEMO-ACCT-ID} {@code PIC 9(11)} L38 and
     * {@code CDEMO-ACCT-STATUS} {@code PIC X(01)} L39; and {@code CDEMO-CARD-NUM} {@code PIC 9(16)}
     * L41.</p>
     */
    private static final List<String> FORBIDDEN_COMMAREA_CLAIM_NAMES = List.of(
            "fromTranId", "from_tran_id", "fromTransactionId", "cdemoFromTranId",
            "fromProgram", "from_program", "fromProgramName", "cdemoFromProgram",
            "toTranId", "to_tran_id", "toTransactionId", "cdemoToTranId",
            "toProgram", "to_program", "toProgramName", "cdemoToProgram",
            "pgmContext", "pgm_context", "programContext", "reenter", "reEnter", "pgmReenter",
            "custId", "cust_id", "customerId", "customer_id",
            "custFname", "cust_fname", "customerFirstName", "firstName", "fname",
            "custMname", "cust_mname", "customerMiddleName", "middleName", "mname",
            "custLname", "cust_lname", "customerLastName", "lastName", "lname",
            "acctId", "acct_id", "accountId", "account_id",
            "acctStatus", "acct_status", "accountStatus",
            "cardNum", "card_num", "cardNumber", "pan",
            "lastMap", "last_map", "lastMapset", "last_mapset", "cdemoLastMap", "cdemoLastMapset");

    /**
     * Claim names that would reintroduce server-driven navigation, enumerated for the same reason.
     *
     * <p>There is no server-side forwarding in this module. The twenty-five program-to-program transfers
     * and the nineteen pseudo-conversational re-arms of the legacy estate all become calls the client
     * makes, and the route it should call next is a declarative constant returned in a response body by
     * {@code com.carddemo.service.NavigationService}. A route carried in a signed credential would put
     * that decision back on the server while making it unalterable for the token's whole life.</p>
     */
    private static final List<String> FORBIDDEN_ROUTING_CLAIM_NAMES = List.of(
            "route", "routes", "nextRoute", "next_route", "nextProgram", "target", "targetProgram",
            "screen", "screenName", "screenId", "mapName", "mapsetName",
            "selection", "selectionId", "selected", "menuOption", "option", "tranId", "transactionId");

    /**
     * Claim names that would carry a protected value, enumerated so that none can appear.
     *
     * <p>A token is readable by whoever holds it, so a credential, a hash of one, a social-security
     * number, a credit score or a card verification value placed in one is disclosed by the act of
     * issuing it. None of these is signed, and the persisted credential field of the user record - which
     * the module stores only as an adaptive hash - is not among them either.</p>
     */
    private static final List<String> FORBIDDEN_PROTECTED_CLAIM_NAMES = List.of(
            "pwd", "pass", "secret", "credential", "credentials", "hash", "digest",
            "ssn", "socialSecurityNumber", "fico", "creditScore", "cvv", "authorities");

    /**
     * Derives signing material long enough for the algorithm the provider fixes.
     *
     * <p>Drawn at run time rather than written down, so this file contains nothing that resembles a
     * credential and nothing any profile ships. The encoded form is transport-safe text whose length
     * comfortably exceeds the byte floor the provider enforces, and the value is never rendered
     * anywhere.</p>
     *
     * @return freshly derived, transport-encoded signing material
     */
    private static String freshSigningMaterial() {
        final byte[] material = new byte[SIGNING_MATERIAL_BYTES];
        RANDOM.nextBytes(material);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    /**
     * Builds fixture settings around given signing material.
     *
     * @param material signing material for these settings
     * @return settings carrying that material, the fixture issuer and the fixture lifetime
     */
    private static JwtProperties propertiesWith(final String material) {
        return new JwtProperties(material, ISSUER, FIXTURE_LIFETIME);
    }

    /**
     * Mints a genuinely signed token over an arbitrary claim set, independently of the class under test.
     *
     * <p><strong>Why this is needed, and why it is not the tampering helper.</strong> The tampering nest
     * rewrites a segment of a minted token, which necessarily breaks the signature - and a broken signature
     * is refused before any claim is judged, so it cannot be used to ask what happens when a claim is
     * <em>legitimately absent</em>. This helper signs whatever claim set it is given with the same material
     * the provider is configured with, so the resulting token is one the provider would accept but for the
     * claim under test. It is the only way to present a validly signed token that omits a registered claim,
     * because the provider's own minting path always writes one.</p>
     *
     * <p>Composed from the same encoder and key types the provider uses, so no dependency is introduced and
     * no signing algorithm is restated: the header names the algorithm the provider pins.</p>
     *
     * @param material signing material to sign with, which must be the material the provider was built on
     *                 for the token to reach the claim checks at all
     * @param claims   populates the claim set, omitting exactly what the test needs omitted
     * @return the compact serialized form
     */
    private static String tokenSignedWith(final String material,
            final Consumer<JwtClaimsSet.Builder> claims) {
        final OctetSequenceKey key = new OctetSequenceKey.Builder(
                material.getBytes(StandardCharsets.UTF_8)).build();
        final JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        final JwtClaimsSet.Builder builder = JwtClaimsSet.builder();
        claims.accept(builder);
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), builder.build())).getTokenValue();
    }

    /**
     * The credential master every provider below reads, and the only mutable fixture state in this file.
     *
     * <p>It is shared rather than per-provider deliberately: the record is what a token is minted from and
     * what a currency check reads, so a fixture in which two providers saw two different records could
     * not express "the record changed after the token was issued" - which is the whole of what
     * {@link RecordFingerprintClaim} and {@link CurrencyOfAVerifiedToken} assert.</p>
     */
    private static final InMemoryCredentialMaster CREDENTIAL_MASTER = new InMemoryCredentialMaster();

    /** Re-seeds the two fixture operators, so no test inherits another's record state. */
    @BeforeEach
    void seedCredentialMaster() {
        CREDENTIAL_MASTER.reset()
                .with(ADMINISTRATOR_ID, UserType.ADMIN.getCode())
                .with(STANDARD_USER_ID, UserType.USER.getCode());
    }

    /**
     * Builds a provider on given settings whose clock is pinned to a chosen instant.
     *
     * <p>The same settings instance can be handed to two providers at two instants, which is how an
     * expiry assertion is made without waiting and without either side reading the platform clock. Every
     * provider reads {@link #CREDENTIAL_MASTER}, so two providers agree about the record exactly as two
     * instances of the running module would.</p>
     *
     * @param properties settings to build from
     * @param at         instant the provider treats as now, for minting and for judging the window
     * @return the provider
     */
    private static JwtTokenProvider providerFor(final JwtProperties properties, final Instant at) {
        return new JwtTokenProvider(properties, Clock.fixed(at, ZoneOffset.UTC), stateService());
    }

    /**
     * Builds the record reader every provider here is given, over the shared credential master.
     *
     * @return a state service reading {@link #CREDENTIAL_MASTER}
     */
    private static SignOnStateService stateService() {
        return new SignOnStateService(CREDENTIAL_MASTER.repository());
    }

    /**
     * Seeds the credential master with an operator, then answers a provider that can mint for it.
     *
     * <p>Minting requires the record, because the fingerprint the token carries is derived from it and a
     * session for an identity that does not exist, or for a role its record does not carry, is refused
     * rather than issued. An assertion about the <em>shape</em> of a token - a fixed-width identifier
     * preserved byte for byte, a numeric-looking one not parsed, a type code carried raw - therefore has
     * to say which record it is minting against. Saying it through this helper keeps each such assertion
     * reading as one statement, and keeps the record and the arguments unable to disagree.</p>
     *
     * @param userId   the identifier to seed and to mint for
     * @param userType the type to seed and to mint
     * @return a provider over the seeded credential master
     */
    private static JwtTokenProvider providerHolding(final String userId, final UserType userType) {
        CREDENTIAL_MASTER.with(userId, userType.getCode());
        return provider();
    }

    /**
     * Builds a provider on given material and issuer, pinned to a chosen instant.
     *
     * @param material signing material
     * @param issuer   issuer to claim and to require
     * @param at       instant the provider treats as now
     * @return the provider
     */
    private static JwtTokenProvider providerAt(final String material, final String issuer,
            final Instant at) {
        return providerFor(new JwtProperties(material, issuer, FIXTURE_LIFETIME), at);
    }

    /**
     * Builds a provider on freshly derived material, at the fixture instant.
     *
     * @return the provider
     */
    private static JwtTokenProvider provider() {
        return providerFor(propertiesWith(freshSigningMaterial()), ISSUED_AT);
    }

    /**
     * Splits a compact token into its three segments.
     *
     * <p>Empty trailing segments are retained, because an unsigned token's third segment is empty and a
     * test that silently lost it would be asserting about a two-segment string instead.</p>
     *
     * @param token a compact token
     * @return its header, payload and signature segments
     */
    private static String[] segmentsOf(final String token) {
        final String[] segments = token.split("\\.", -1);
        assertThat(segments)
                .as("a compact token is a header, a payload and a signature")
                .hasSize(3);
        return segments;
    }

    /**
     * Decodes one Base64URL segment to the text it carries.
     *
     * @param segment a Base64URL segment
     * @return the decoded text
     */
    private static String decodeSegment(final String segment) {
        return new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8);
    }

    /**
     * Encodes text as a Base64URL segment, unpadded as the compact form requires.
     *
     * @param text text to encode
     * @return the encoded segment
     */
    private static String encodeSegment(final String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads a token's claim set straight off the wire, without consulting the class under test.
     *
     * <p>This is the independent oracle every claim assertion in {@link ClaimMinimality} uses. Decoding a
     * token this way defeats nothing: a bearer token is readable by whoever holds it, which is precisely
     * why what it carries is a contract worth asserting.</p>
     *
     * @param token a compact token
     * @return the claim set the token actually carries
     * @throws JsonProcessingException if the payload segment is not the object it must be
     */
    private static Map<String, Object> payloadOf(final String token) throws JsonProcessingException {
        return MAPPER.readValue(decodeSegment(segmentsOf(token)[1]),
                new TypeReference<Map<String, Object>>() {
                });
    }

    /**
     * Asserts that none of the given claim names appears in a token's claim set.
     *
     * @param payload   the claim set read off the wire
     * @param forbidden claim names that must be absent
     * @param reason    why they must be absent, rendered when one of them is present
     */
    private static void assertCarriesNoneOf(final Map<String, Object> payload,
            final List<String> forbidden, final String reason) {
        for (final String name : forbidden) {
            assertThat(payload.containsKey(name))
                    .as("a minted token carries the claim [%s], and it must not: %s", name, reason)
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Minting")
    class Minting {

        @Test
        @DisplayName("refuses an absent identifier rather than minting an unattributed token")
        void refusesAnAbsentIdentifier() {
            final JwtTokenProvider provider = provider();

            assertThatNullPointerException().isThrownBy(() -> provider.issue(null, UserType.USER, UserType.USER.getCode()));
        }

        @Test
        @DisplayName("refuses an absent user type rather than minting a token of unknown entitlement")
        void refusesAnAbsentUserType() {
            final JwtTokenProvider provider = provider();

            assertThatNullPointerException()
                    .isThrownBy(() -> provider.issue(ADMINISTRATOR_ID, null, UserType.ADMIN.getCode()));
        }

        @Test
        @DisplayName("produces the three-segment compact form with a signature actually present")
        void producesASignedCompactForm() {
            final String token = provider().issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode());

            final String[] segments = segmentsOf(token);

            assertThat(segments[0]).as("the header segment states how the token was signed").isNotEmpty();
            assertThat(segments[1]).as("the payload segment carries the claims").isNotEmpty();
            assertThat(segments[2])
                    .as("the signature is the entire reason to trust a token, so it cannot be empty")
                    .isNotEmpty();
        }
    }

    @Nested
    @DisplayName("A token this provider minted, read back")
    class MintedTokenRoundTrip {

        @Test
        @DisplayName("returns the administrative identity and type it was minted for")
        void roundTripsAnAdministrator() {
            final JwtTokenProvider provider = provider();

            final Jwt verified = provider.verify(provider.issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode()))
                    .orElseThrow();

            assertThat(verified.getSubject()).isEqualTo(ADMINISTRATOR_ID);
            assertThat(provider.userTypeOf(verified)).contains(UserType.ADMIN);
        }

        @Test
        @DisplayName("returns the standard identity and type it was minted for")
        void roundTripsAStandardUser() {
            final JwtTokenProvider provider = provider();

            final Jwt verified = provider.verify(provider.issue(STANDARD_USER_ID, UserType.USER, UserType.USER.getCode()))
                    .orElseThrow();

            assertThat(verified.getSubject()).isEqualTo(STANDARD_USER_ID);
            assertThat(provider.userTypeOf(verified)).contains(UserType.USER);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"ADMIN001,A", "ADMIN002,A", "ADMIN003,A", "ADMIN004,A", "ADMIN005,A",
            "USER0001,U", "USER0002,U", "USER0003,U", "USER0004,U", "USER0005,U"})
        @DisplayName("round-trips every identity the provisioning job stream seeds, at its own type")
        void roundTripsEverySeededIdentity(final String seededId, final String typeCode) {
            final UserType seededType = UserType.fromCode(typeCode).orElseThrow();
            final JwtTokenProvider provider = providerHolding(seededId, seededType);

            final Jwt verified = provider.verify(provider.issue(seededId, seededType, seededType.getCode())).orElseThrow();

            assertThat(verified.getSubject()).isEqualTo(seededId);
            assertThat(provider.userTypeOf(verified)).contains(seededType);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(UserType.class)
        @DisplayName("round-trips either user type the estate declares, carrying the legacy code itself")
        void roundTripsEveryDeclaredUserType(final UserType userType) {
            final JwtTokenProvider provider = providerHolding(ADMINISTRATOR_ID, userType);

            final Jwt verified = provider.verify(provider.issue(ADMINISTRATOR_ID, userType, userType.getCode())).orElseThrow();

            assertThat(provider.userTypeOf(verified)).contains(userType);
            assertThat(verified.getClaimAsString(JwtTokenProvider.ROLE_CLAIM))
                    .as("the role claim carries the raw legacy code, not a renamed equivalent")
                    .isEqualTo(userType.getCode());
        }

        @Test
        @DisplayName("preserves a fixed-width identifier byte for byte, trimming nothing")
        void preservesAFixedWidthIdentifier() {
            // Eight characters including trailing blanks, exactly as the fixed-width key is held. Trimming
            // it would stop it matching the record it identifies.
            final String paddedToFullWidth = "USER1   ";
            final JwtTokenProvider provider = providerHolding(paddedToFullWidth, UserType.USER);

            final Jwt verified = provider.verify(provider.issue(paddedToFullWidth, UserType.USER, UserType.USER.getCode()))
                    .orElseThrow();

            assertThat(verified.getSubject()).isEqualTo(paddedToFullWidth);
            assertThat(verified.getSubject()).hasSize(paddedToFullWidth.length());
        }

        @Test
        @DisplayName("preserves a numeric-looking identifier as text, parsing nothing")
        void preservesANumericLookingIdentifier() {
            // Leading zeros survive only if the value is never parsed as a number; a parsed subject would
            // read back as a single digit and would identify nothing.
            final String leadingZeros = "00000001";
            final JwtTokenProvider provider = providerHolding(leadingZeros, UserType.USER);

            final Jwt verified = provider.verify(provider.issue(leadingZeros, UserType.USER, UserType.USER.getCode())).orElseThrow();

            assertThat(verified.getSubject()).isEqualTo(leadingZeros);
        }

        @Test
        @DisplayName("stores an upper-cased identifier unchanged, which is the form sign-on supplies")
        void storesAnUpperCasedIdentifierUnchanged() {
            // Sign-on upper-cases what was keyed in, unconditionally, before using it at all
            // (app/cbl/COSGN00C.cbl L132-L136), so the value reaching this provider is already folded.
            final JwtTokenProvider provider = provider();

            final Jwt verified = provider.verify(provider.issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode()))
                    .orElseThrow();

            assertThat(verified.getSubject()).isEqualTo(ADMINISTRATOR_ID);
            assertThat(verified.getSubject().chars().noneMatch(Character::isLowerCase))
                    .as("an identifier supplied already folded comes back with no lower-case letter")
                    .isTrue();
        }

        @Test
        @DisplayName("does not fold case itself, because the fold belongs to the sign-on path")
        void doesNotFoldCaseItself() {
            // A provider that folded here would duplicate a responsibility that already sits one layer up,
            // and the duplicate could then disagree with it. Whatever it is handed is what it carries.
            final String asSupplied = "admin001";
            final JwtTokenProvider provider = providerHolding(asSupplied, UserType.ADMIN);

            final Jwt verified = provider.verify(provider.issue(asSupplied, UserType.ADMIN, UserType.ADMIN.getCode())).orElseThrow();

            assertThat(verified.getSubject()).isEqualTo(asSupplied);
        }

        @Test
        @DisplayName("resolves a non-administrative type to the standard one without raising, matching "
                + "the unconditional alternative the legacy route split has")
        void resolvesANonAdministrativeTypeWithoutRaising() {
            // The legacy split at app/cbl/COSGN00C.cbl L230-L240 tests the administrator condition once and
            // its alternative is unconditional: there is no second test and no third branch to fail into.
            final JwtTokenProvider provider = provider();
            final Jwt verified = provider.verify(provider.issue(STANDARD_USER_ID, UserType.USER, UserType.USER.getCode()))
                    .orElseThrow();

            assertThatCode(() -> provider.userTypeOf(verified)).doesNotThrowAnyException();
            assertThat(provider.userTypeOf(verified)).contains(UserType.USER);
            assertThat(provider.userTypeOf(verified).map(UserType::isAdmin)).contains(false);
        }

        @Test
        @DisplayName("claims the configured issuer, which is what lets a verifier require it")
        void claimsTheConfiguredIssuer() {
            final JwtTokenProvider provider = provider();

            final Jwt verified = provider.verify(provider.issue(STANDARD_USER_ID, UserType.USER, UserType.USER.getCode()))
                    .orElseThrow();

            // Read as text deliberately: the framework's typed issuer accessor expects a locator, and this
            // module's issuer names a module.
            assertThat(verified.getClaimAsString("iss")).isEqualTo(ISSUER);
        }

        @Test
        @DisplayName("closes exactly one reported lifetime after the instant it was minted at, so the "
                + "figure a client is told cannot disagree with the token it was handed")
        void closesOneReportedLifetimeAfterMinting() {
            final JwtProperties properties = propertiesWith(freshSigningMaterial());
            final JwtTokenProvider provider = providerFor(properties, ISSUED_AT);

            final Jwt verified = provider.verify(provider.issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode()))
                    .orElseThrow();

            assertThat(provider.tokenLifetime()).isEqualTo(properties.expiration());
            assertThat(verified.getIssuedAt()).isEqualTo(ISSUED_AT);
            assertThat(verified.getExpiresAt()).isEqualTo(ISSUED_AT.plus(properties.expiration()));
            assertThat(Duration.between(verified.getIssuedAt(), verified.getExpiresAt()))
                    .isEqualTo(provider.tokenLifetime());
        }
    }

    /**
     * The claim set a minted token carries, read off the wire rather than from the class under test.
     *
     * <p>Every assertion in this group decodes the payload segment itself, so none of them can be
     * satisfied by a provider that is merely self-consistent. Two of the legacy communication area's
     * fields are signed and the other thirteen are not, and both halves of that statement are asserted:
     * the positive half by requiring the claim set to be exactly the permitted names, and the negative
     * half by naming, one at a time, the claims that would carry a field which belongs to the context
     * record the client echoes.</p>
     */
    @Nested
    @DisplayName("The claim set a minted token carries, read independently off the wire")
    class ClaimMinimality {

        @Test
        @DisplayName("is exactly the two signed facts and the registered claims about the token itself")
        void isExactlyTheTwoSignedFactsAndTheRegisteredClaims() throws JsonProcessingException {
            final Map<String, Object> payload =
                    payloadOf(provider().issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode()));

            assertThat(payload.keySet())
                    .as("an unexpected claim is either a disclosure or an unannounced contract change")
                    .containsExactlyInAnyOrderElementsOf(PERMITTED_CLAIM_NAMES);
        }

        @Test
        @DisplayName("carries the signed-on identifier as its subject and nowhere else")
        void carriesTheIdentifierAsItsSubject() throws JsonProcessingException {
            final Map<String, Object> payload =
                    payloadOf(provider().issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode()));

            assertThat(payload).containsEntry("sub", ADMINISTRATOR_ID);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(UserType.class)
        @DisplayName("carries the raw one-character legacy type code as its role claim")
        void carriesTheRawTypeCodeAsItsRoleClaim(final UserType userType) throws JsonProcessingException {
            final Map<String, Object> payload = payloadOf(
                    providerHolding(STANDARD_USER_ID, userType).issue(STANDARD_USER_ID, userType, userType.getCode()));

            assertThat(payload).containsEntry(JwtTokenProvider.ROLE_CLAIM, userType.getCode());
            assertThat(String.valueOf(payload.get(JwtTokenProvider.ROLE_CLAIM)))
                    .as("the code is the estate's own one-character vocabulary")
                    .hasSize(1);
        }

        @Test
        @DisplayName("names the role claim on the wire, which is the part a client actually reads")
        void namesTheRoleClaimOnTheWire() {
            assertThat(JwtTokenProvider.ROLE_CLAIM)
                    .as("renaming this claim silently breaks every holder of an already-issued token")
                    .isEqualTo("role");
        }

        @Test
        @DisplayName("states its window as registered numeric claims taken from the supplied clock")
        void statesItsWindowAsRegisteredNumericClaims() throws JsonProcessingException {
            final JwtProperties properties = propertiesWith(freshSigningMaterial());
            final Map<String, Object> payload = payloadOf(
                    providerFor(properties, ISSUED_AT).issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode()));

            final Object issuedAtClaim = payload.get("iat");
            final Object expiresAtClaim = payload.get("exp");

            assertThat(issuedAtClaim).isInstanceOf(Number.class);
            assertThat(expiresAtClaim).isInstanceOf(Number.class);
            assertThat(((Number) issuedAtClaim).longValue())
                    .as("the issue instant is the one the supplied clock reported")
                    .isEqualTo(ISSUED_AT.getEpochSecond());
            assertThat(((Number) expiresAtClaim).longValue())
                    .as("the window closes one configured lifetime after that instant")
                    .isEqualTo(ISSUED_AT.plus(properties.expiration()).getEpochSecond());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(UserType.class)
        @DisplayName("carries none of the communication-area fields that belong to the context record")
        void carriesNoneOfTheCommareaFields(final UserType userType) throws JsonProcessingException {
            final Map<String, Object> payload = payloadOf(
                    providerHolding(ADMINISTRATOR_ID, userType).issue(ADMINISTRATOR_ID, userType, userType.getCode()));

            assertCarriesNoneOf(payload, FORBIDDEN_COMMAREA_CLAIM_NAMES,
                    "the navigation fields and the customer, account and card selections of the 160-byte "
                            + "communication area belong to the context record the client echoes, never to "
                            + "a signed credential");
        }

        @Test
        @DisplayName("carries no route, target program, screen or selection, because the client drives "
                + "the next call")
        void carriesNoRouteOrSelection() throws JsonProcessingException {
            final Map<String, Object> payload =
                    payloadOf(provider().issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode()));

            assertCarriesNoneOf(payload, FORBIDDEN_ROUTING_CLAIM_NAMES,
                    "there is no server-side forwarding in this module, so a route is a declarative "
                            + "constant in a response body rather than a fact fixed for a token's life");
        }

        @Test
        @DisplayName("carries no protected value of any kind, since whoever holds a token can read it")
        void carriesNoProtectedValue() throws JsonProcessingException {
            final String material = freshSigningMaterial();
            final String token = providerAt(material, ISSUER, ISSUED_AT)
                    .issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode());
            final Map<String, Object> payload = payloadOf(token);

            assertCarriesNoneOf(payload, FORBIDDEN_PROTECTED_CLAIM_NAMES,
                    "a token is readable by whoever holds it, so a protected value placed in one is "
                            + "disclosed by the act of issuing it");
            // Compared inside a boolean so that not even a failing run can render the signing material.
            assertThat(token.contains(material))
                    .as("no part of a token may carry the material its signature was computed with")
                    .isFalse();
        }
    }


    /**
     * Tokens that must not be trusted, and the algorithm safety that decides several of them.
     *
     * <p>Rejection is asserted as the provider expresses it - an empty result - rather than as a message,
     * because the provider deliberately answers identically for a tampered token, a foreign signature, an
     * expired window, a foreign issuer and a malformed string: the caller's next action is the same in
     * every case, and a caller handed the distinction could relay it to whoever presented the token. An
     * empty result also proves the operation is total, since an escaping failure would end the test rather
     * than answer it.</p>
     *
     * <p>The two algorithm assertions are the most consequential in this file. A verifier that took the
     * algorithm from the token it is checking would accept a token that declares it needs no checking at
     * all, which is the single most damaging way to hold a signed credential wrong.</p>
     */
    @Nested
    @DisplayName("A token that may not be trusted")
    class Tampering {

        @Test
        @DisplayName("is refused when its claims were altered under the original signature, which is the "
                + "attack signing exists to defeat")
        void refusesAlteredClaims() throws JsonProcessingException {
            final JwtTokenProvider provider = provider();
            final String token = provider.issue(STANDARD_USER_ID, UserType.USER, UserType.USER.getCode());
            final String[] segments = segmentsOf(token);

            // Promote a standard user to the administrative type and re-attach the original signature.
            final Map<String, Object> forgedClaims = new LinkedHashMap<>(payloadOf(token));
            forgedClaims.put(JwtTokenProvider.ROLE_CLAIM, UserType.ADMIN.getCode());
            final String forgedPayload = encodeSegment(MAPPER.writeValueAsString(forgedClaims));

            assertThat(forgedPayload)
                    .as("the forgery must genuinely differ from the original, or this proves nothing")
                    .isNotEqualTo(segments[1]);
            assertThat(provider.verify(segments[0] + '.' + forgedPayload + '.' + segments[2]))
                    .as("promoting a standard user to the administrative type must not verify")
                    .isEmpty();
        }

        @Test
        @DisplayName("is refused when its signature segment was altered")
        void refusesAnAlteredSignature() {
            final JwtTokenProvider provider = provider();
            final String[] segments = segmentsOf(provider.issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode()));
            final String signature = segments[2];
            final char replacement = signature.charAt(0) == 'A' ? 'B' : 'A';
            final String altered = replacement + signature.substring(1);

            assertThat(altered).as("the alteration must change the signature").isNotEqualTo(signature);
            assertThat(provider.verify(segments[0] + '.' + segments[1] + '.' + altered))
                    .as("a signature that does not match the content it covers must not verify")
                    .isEmpty();
        }

        @Test
        @DisplayName("is refused when signed with different material, so a token minted elsewhere carries "
                + "no authority here")
        void refusesForeignSigningMaterial() {
            final String material = freshSigningMaterial();
            final String otherMaterial = freshSigningMaterial();
            assertThat(material.equals(otherMaterial))
                    .as("the two pieces of material must differ, or this proves nothing")
                    .isFalse();

            final String foreign = providerAt(otherMaterial, ISSUER, ISSUED_AT)
                    .issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode());

            assertThat(providerAt(material, ISSUER, ISSUED_AT).verify(foreign))
                    .as("a perfectly valid signature under the wrong material is not a valid token")
                    .isEmpty();
        }

        @Test
        @DisplayName("is refused when issued by somebody else, even under matching material")
        void refusesAForeignIssuer() {
            final String material = freshSigningMaterial();

            final String elsewhere = providerAt(material, "some-other-service", ISSUED_AT)
                    .issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode());

            assertThat(providerAt(material, ISSUER, ISSUED_AT).verify(elsewhere)).isEmpty();
        }

        @Test
        @DisplayName("is refused when it declares no signature algorithm at all, whether or not it leaves "
                + "an empty signature segment behind")
        void refusesATokenDeclaringNoAlgorithm() {
            final JwtTokenProvider provider = provider();
            final String token = provider.issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode());
            final String[] segments = segmentsOf(token);
            final String unsignedHeader = encodeSegment("{\"alg\":\"none\"}");

            // Control: the payload and signature below are genuine and this token verifies, so the only
            // thing the refusals that follow can be attributed to is the header.
            assertThat(provider.verify(token)).isPresent();
            assertThat(provider.verify(unsignedHeader + '.' + segments[1] + '.'))
                    .as("a token asserting that it needs no signature must never be accepted")
                    .isEmpty();
            assertThat(provider.verify(unsignedHeader + '.' + segments[1]))
                    .as("dropping the signature segment entirely must not help either")
                    .isEmpty();
            assertThat(provider.verify(unsignedHeader + '.' + segments[1] + '.' + segments[2]))
                    .as("nor may a genuine signature excuse a header that disclaims one")
                    .isEmpty();
        }

        @Test
        @DisplayName("is refused when it declares an algorithm other than the one fixed in code, because "
                + "the algorithm is never taken from the token being checked")
        void refusesATokenDeclaringAnotherAlgorithm() {
            final JwtTokenProvider provider = provider();
            final String token = provider.issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode());
            final String[] segments = segmentsOf(token);

            // Control, for the same reason: only the header differs below.
            assertThat(provider.verify(token)).isPresent();

            for (final String otherAlgorithm : List.of("HS384", "HS512", "RS256", "ES256")) {
                final String header = encodeSegment("{\"alg\":\"" + otherAlgorithm + "\"}");

                assertThat(provider.verify(header + '.' + segments[1] + '.' + segments[2]))
                        .as("a token that names [%s] must be refused rather than checked under the "
                                + "algorithm this module fixed", otherAlgorithm)
                        .isEmpty();
            }
        }

        @ParameterizedTest(name = "presented = [{0}]")
        @ValueSource(strings = {"", " ", "\t", "onlyonesegment", "header.payload", "a.b.c.d", "..",
            "!!!.???.***"})
        @DisplayName("is refused when it is not a token this provider could have produced")
        void refusesStructurallyMalformedInput(final String presented) {
            assertThat(provider().verify(presented)).isEmpty();
        }

        @Test
        @DisplayName("is refused when it is a fragment of one - a header alone, a scheme-prefixed value, "
                + "or claims with no header and no signature")
        void refusesFragmentsOfAToken() {
            final JwtTokenProvider provider = provider();
            final String headerAlone = encodeSegment("{\"alg\":\"HS256\"}");
            final String claimsAlone = encodeSegment("{\"sub\":\"" + ADMINISTRATOR_ID + "\"}");

            assertThat(provider.verify(headerAlone))
                    .as("a header on its own carries no claims and no signature")
                    .isEmpty();
            assertThat(provider.verify("Bearer " + headerAlone))
                    .as("the transport scheme is the caller's to strip, not this provider's to tolerate")
                    .isEmpty();
            assertThat(provider.verify('.' + claimsAlone + '.'))
                    .as("claims with neither a header nor a signature are an assertion, not a credential")
                    .isEmpty();
        }

        @Test
        @DisplayName("is refused rather than raising when absent, because an absent credential is an "
                + "ordinary condition and not an error")
        void refusesAnAbsentTokenWithoutRaising() {
            final JwtTokenProvider provider = provider();

            assertThatCode(() -> provider.verify(null)).doesNotThrowAnyException();
            assertThat(provider.verify(null)).isEmpty();
        }

        @Test
        @DisplayName("fails as a total operation, so no low-level failure escapes to the caller")
        void failsTotallyRatherThanRaising() {
            final JwtTokenProvider provider = provider();
            final String emptyObject = encodeSegment("{}");
            final List<String> unusable = List.of("", " ", "onlyonesegment", "header.payload", "a.b.c.d",
                    "..", "!!!.???.***", emptyObject + '.' + emptyObject + '.',
                    '.' + encodeSegment("{\"sub\":\"" + ADMINISTRATOR_ID + "\"}") + '.');

            for (final String presented : unusable) {
                assertThatCode(() -> provider.verify(presented))
                        .as("verification must answer for [%s] rather than raise", presented)
                        .doesNotThrowAnyException();
                assertThat(provider.verify(presented)).isEmpty();
            }
        }
    }


    /**
     * The window a token is usable in, asserted against a clock this class supplies.
     *
     * <p>Every provider here is pinned with {@link Clock#fixed(Instant, java.time.ZoneId)}, which is the
     * only way to assert an expiry immediately and exactly. It is also the proof of a property in its own
     * right: {@link #ISSUED_AT} is already past, so a provider that consulted the platform clock would
     * refuse every token minted below and each acceptance assertion here would fail.
     * {@link #judgesTheWindowAgainstTheSuppliedClock()} makes that discrimination explicit at an instant
     * far enough back that it can never become ambiguous.</p>
     *
     * <p><strong>Where the boundary actually falls, as the production class draws it.</strong> The provider
     * builds the framework's timestamp validator and supplies it with this clock, and deliberately leaves
     * that validator's own tolerance for disagreeing clocks at its default rather than narrowing it. The
     * consequence is asserted here rather than glossed over: a token is accepted throughout its window,
     * accepted at the closing instant, and still accepted marginally beyond it, and it is refused once a
     * further whole lifetime has passed - which is unambiguously outside any tolerance, because
     * {@link #FIXTURE_LIFETIME} is much the longer of the two. Asserting a refusal one second past the
     * closing instant would assert a boundary this module does not draw, and the honest thing is to state
     * the boundary that it does. That tolerance is a verification allowance for clocks that disagree; it is
     * not a latency, time-out or capacity figure, and this module documents no such figure.</p>
     */
    @Nested
    @DisplayName("The window a token is usable in")
    class Expiry {

        @Test
        @DisplayName("is open throughout the configured lifetime")
        void isOpenThroughoutTheLifetime() {
            final JwtProperties properties = propertiesWith(freshSigningMaterial());
            final Duration validity = properties.expiration();
            final String token = providerFor(properties, ISSUED_AT).issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode());

            final JwtTokenProvider midway = providerFor(properties, ISSUED_AT.plus(validity.dividedBy(2)));

            assertThat(midway.verify(token))
                    .as("a token must be usable for the lifetime it was issued with")
                    .isPresent();
            assertThat(midway.verify(token).orElseThrow().getSubject()).isEqualTo(ADMINISTRATOR_ID);
        }

        @Test
        @DisplayName("is still open at the instant it closes")
        void isStillOpenAtTheClosingInstant() {
            final JwtProperties properties = propertiesWith(freshSigningMaterial());
            final String token = providerFor(properties, ISSUED_AT).issue(STANDARD_USER_ID, UserType.USER, UserType.USER.getCode());

            final JwtTokenProvider atClose =
                    providerFor(properties, ISSUED_AT.plus(properties.expiration()));

            assertThat(atClose.verify(token)).isPresent();
        }

        @Test
        @DisplayName("still admits a token marginally beyond that instant, because the verifier keeps the "
                + "tolerance for disagreeing clocks that the production class leaves it")
        void admitsATokenMarginallyBeyondTheClosingInstant() {
            final JwtProperties properties = propertiesWith(freshSigningMaterial());
            final String token = providerFor(properties, ISSUED_AT).issue(STANDARD_USER_ID, UserType.USER, UserType.USER.getCode());

            final JwtTokenProvider justBeyond =
                    providerFor(properties, ISSUED_AT.plus(properties.expiration()).plusSeconds(1));

            assertThat(justBeyond.verify(token))
                    .as("this is the boundary the module implements; a refusal here would be a boundary "
                            + "it does not implement")
                    .isPresent();
        }

        @Test
        @DisplayName("is closed once the lifetime and that tolerance have both elapsed")
        void isClosedOnceTheLifetimeAndToleranceHaveElapsed() {
            final JwtProperties properties = propertiesWith(freshSigningMaterial());
            final Duration validity = properties.expiration();
            final String token = providerFor(properties, ISSUED_AT).issue(STANDARD_USER_ID, UserType.USER, UserType.USER.getCode());

            // One further whole lifetime beyond the closing instant, so the refusal is the expiry itself
            // rather than a borderline reading of it.
            final JwtTokenProvider wellBeyond =
                    providerFor(properties, ISSUED_AT.plus(validity).plus(validity));

            assertThat(wellBeyond.verify(token))
                    .as("an elapsed token must not verify, whatever it is presented to")
                    .isEmpty();
        }

        @Test
        @DisplayName("is CLOSED for a token that declares no expiry at all, because a credential with no "
                + "window would otherwise be usable for as long as the signing key stands")
        void isClosedForATokenThatDeclaresNoExpiry() {
            final String material = freshSigningMaterial();
            final JwtProperties properties = propertiesWith(material);
            // Signed with the configured key and carrying the same claims a legitimate token carries, so
            // the signature, the issuer, the subject and the entitlement all verify: the ONLY difference is
            // the absent expiry. The framework's window validator judges expiry only when the claim is
            // present, so without the presence requirement this token authenticates forever.
            final String noExpiry = tokenSignedWith(material, claims -> claims
                    .issuer(ISSUER)
                    .subject(STANDARD_USER_ID)
                    .issuedAt(ISSUED_AT)
                    .claim(JwtTokenProvider.ROLE_CLAIM, UserType.USER.getCode())
                    .claim(JwtTokenProvider.AUTHORITY_CLAIM, UserType.USER.getCode()));

            assertThat(providerFor(properties, ISSUED_AT).verify(noExpiry))
                    .as("a credential that declares no window must be refused, at the instant it was "
                            + "minted at and at every other instant")
                    .isEmpty();
            assertThat(providerFor(properties, ISSUED_AT.plus(FIXTURE_LIFETIME.multipliedBy(1000)))
                    .verify(noExpiry))
                    .as("and no amount of elapsed time can turn the absence into an expiry")
                    .isEmpty();
        }

        @Test
        @DisplayName("is open for the same claim set once it carries an expiry, so the refusal above is the "
                + "absent claim and not the hand-signed shape of the token")
        void isOpenForTheSameClaimSetOnceItCarriesAnExpiry() {
            final String material = freshSigningMaterial();
            final JwtProperties properties = propertiesWith(material);
            final String withExpiry = tokenSignedWith(material, claims -> claims
                    .issuer(ISSUER)
                    .subject(STANDARD_USER_ID)
                    .issuedAt(ISSUED_AT)
                    .expiresAt(ISSUED_AT.plus(FIXTURE_LIFETIME))
                    .claim(JwtTokenProvider.ROLE_CLAIM, UserType.USER.getCode())
                    .claim(JwtTokenProvider.AUTHORITY_CLAIM, UserType.USER.getCode()));

            assertThat(providerFor(properties, ISSUED_AT).verify(withExpiry))
                    .as("the control: one claim added and the same token verifies")
                    .isPresent();
            assertThat(providerFor(properties, ISSUED_AT).verify(withExpiry).orElseThrow().getSubject())
                    .isEqualTo(STANDARD_USER_ID);
        }

        @Test
        @DisplayName("is closed for a token that declares neither expiry nor not-before, which is the "
                + "shape that has no window of any kind")
        void isClosedForATokenThatDeclaresNeitherBound() {
            final String material = freshSigningMaterial();
            final String unbounded = tokenSignedWith(material, claims -> claims
                    .issuer(ISSUER)
                    .subject(ADMINISTRATOR_ID)
                    .claim(JwtTokenProvider.ROLE_CLAIM, UserType.ADMIN.getCode())
                    .claim(JwtTokenProvider.AUTHORITY_CLAIM, UserType.ADMIN.getCode()));

            assertThat(providerFor(propertiesWith(material), ISSUED_AT).verify(unbounded))
                    .as("neither bound present means nothing about time can refuse it, so the presence "
                            + "requirement is what does")
                    .isEmpty();
        }

        @Test
        @DisplayName("is judged against the clock the provider was given rather than the platform clock")
        void judgesTheWindowAgainstTheSuppliedClock() {
            // Long past by the platform clock: an implementation reading the platform clock would refuse
            // this token outright, and one reading the clock it was given accepts it.
            final Instant longPast = Instant.parse("2000-01-01T00:00:00Z");
            final JwtProperties properties = propertiesWith(freshSigningMaterial());
            final Duration validity = properties.expiration();

            final String token = providerFor(properties, longPast).issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode());

            assertThat(providerFor(properties, longPast).verify(token))
                    .as("the supplied clock decides the window, so a token minted long ago is usable at "
                            + "the instant it was minted at")
                    .isPresent();
            assertThat(providerFor(properties, longPast.plus(validity).plus(validity)).verify(token))
                    .as("and the same supplied clock closes the window once it has moved past it")
                    .isEmpty();
        }
    }

    /**
     * Configuration this provider will not build on, and the discretion it keeps while refusing.
     *
     * <p>These refusals are raised at construction rather than left to the signing library, which would
     * raise them on the first attempt to mint - that is, during a request - and would name an algorithm
     * instead of the configuration key a deployer has to correct. The length floor is also what catches an
     * unresolved configuration placeholder, since placeholder text is present and not blank and so
     * satisfies every presence constraint while being far too short to sign with.</p>
     */
    @Nested
    @DisplayName("Configuration this provider will not build on")
    class UnusableConfiguration {

        @Test
        @DisplayName("is refused when no signing material is configured, naming the key at fault")
        void refusesAbsentSigningMaterial() {
            final JwtProperties absent = new JwtProperties(null, ISSUER, FIXTURE_LIFETIME);
            final Clock clock = Clock.fixed(ISSUED_AT, ZoneOffset.UTC);

            final Throwable thrown = catchThrowable(() -> new JwtTokenProvider(absent, clock, stateService()));

            assertThat(thrown)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(JwtProperties.PREFIX + ".secret");
        }

        @Test
        @DisplayName("is refused when the configured signing material is blank")
        void refusesBlankSigningMaterial() {
            final JwtProperties blank = new JwtProperties("   ", ISSUER, FIXTURE_LIFETIME);
            final Clock clock = Clock.fixed(ISSUED_AT, ZoneOffset.UTC);

            final Throwable thrown = catchThrowable(() -> new JwtTokenProvider(blank, clock, stateService()));

            assertThat(thrown)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(JwtProperties.PREFIX + ".secret");
        }

        @Test
        @DisplayName("is refused when the material is shorter than the fixed algorithm permits, at "
                + "start-up rather than inside the signing library during a request")
        void refusesMaterialShorterThanTheAlgorithmPermits() {
            // Derived rather than written down, then cut short, so this file still contains nothing that
            // looks like a credential.
            final String tooShort = freshSigningMaterial().substring(0, 8);
            final JwtProperties properties = new JwtProperties(tooShort, ISSUER, FIXTURE_LIFETIME);
            final Clock clock = Clock.fixed(ISSUED_AT, ZoneOffset.UTC);

            final Throwable thrown = catchThrowable(() -> new JwtTokenProvider(properties, clock, stateService()));

            assertThat(thrown)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(JwtProperties.PREFIX + ".secret");
        }

        @Test
        @DisplayName("does not quote the configured material in the refusal it raises")
        void doesNotQuoteTheConfiguredMaterial() {
            final String tooShort = freshSigningMaterial().substring(0, 8);
            final JwtProperties properties = new JwtProperties(tooShort, ISSUER, FIXTURE_LIFETIME);
            final Clock clock = Clock.fixed(ISSUED_AT, ZoneOffset.UTC);

            final Throwable thrown = catchThrowable(() -> new JwtTokenProvider(properties, clock, stateService()));
            final String message = String.valueOf(thrown.getMessage());

            // Compared inside a boolean deliberately: a comparison written the other way round would put
            // the configured material into the description that a failing run renders.
            assertThat(message.contains(tooShort))
                    .as("a refusal names the key and the requirement, never the value")
                    .isFalse();
        }

        @Test
        @DisplayName("is refused when no settings are supplied at all")
        void refusesAbsentSettings() {
            final Clock clock = Clock.fixed(ISSUED_AT, ZoneOffset.UTC);

            assertThatNullPointerException().isThrownBy(() -> new JwtTokenProvider(null, clock, stateService()));
        }

        @Test
        @DisplayName("is refused when no clock is supplied, since a window could then be judged against "
                + "nothing")
        void refusesAnAbsentClock() {
            final JwtProperties usable = propertiesWith(freshSigningMaterial());

            assertThatNullPointerException()
                    .isThrownBy(() -> new JwtTokenProvider(usable, null, stateService()));
        }

        @Test
        @DisplayName("is refused when no record reader is supplied, since a token could then be minted "
                + "that nothing is able to revoke")
        void refusesAnAbsentRecordReader() {
            final JwtProperties usable = propertiesWith(freshSigningMaterial());
            final Clock clock = Clock.fixed(ISSUED_AT, ZoneOffset.UTC);

            assertThatNullPointerException()
                    .isThrownBy(() -> new JwtTokenProvider(usable, clock, null));
        }
    }


    /**
     * Reading the user type back out of a verified token.
     *
     * <p>The reader is reached here with claim sets built directly rather than with minted tokens, because
     * a token whose role claim had been edited would fail verification first - correctly - and the reader
     * could never be handed one. Building the claims instead exercises the reader's own tolerance, which is
     * the property that matters: the legacy route split tests the administrator condition once and its
     * alternative is unconditional, so an unrecognised code has no branch to fail into and this reader must
     * not invent one.</p>
     */
    @Nested
    @DisplayName("Reading the user type from a verified token")
    class ReadingTheUserType {

        /**
         * Builds a verified-shaped claim set carrying a chosen authority claim, or none at all.
         *
         * <p>The <em>authority</em> claim is the one the reader consults, and it is a different fact from
         * the role claim beside it: the role claim carries whatever code the record holds, while this one
         * carries the authority that code was resolved to and can only ever be one of the two the estate
         * declares. The reader is therefore exercised on the claim it actually reads.</p>
         *
         * @param authorityClaim value for the authority claim, or {@code null} to omit the claim entirely
         * @return claims carrying that authority and nothing else of interest
         */
        private Jwt claimsCarryingRole(final String authorityClaim) {
            final Jwt.Builder builder = Jwt.withTokenValue("compact-form-not-parsed-by-this-reader")
                    .header("alg", "HS256")
                    .subject(ADMINISTRATOR_ID);
            if (authorityClaim != null) {
                builder.claim(JwtTokenProvider.AUTHORITY_CLAIM, authorityClaim);
            }
            return builder.build();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(UserType.class)
        @DisplayName("yields the user type whose legacy code the claim carries")
        void yieldsTheTypeMatchingTheCode(final UserType userType) {
            assertThat(provider().userTypeOf(claimsCarryingRole(userType.getCode()))).contains(userType);
        }

        @ParameterizedTest(name = "role = [{0}]")
        @ValueSource(strings = {"Z", "a", "u", "ADMIN", "USER", "", " ", "AU", "0"})
        @DisplayName("yields nothing, without raising, for a code the estate does not declare, so an "
                + "unreadable entitlement grants nothing rather than the lesser of the two")
        void yieldsNothingForAnUndeclaredCode(final String undeclared) {
            final JwtTokenProvider provider = provider();
            final Jwt claims = claimsCarryingRole(undeclared);

            assertThatCode(() -> provider.userTypeOf(claims)).doesNotThrowAnyException();
            assertThat(provider.userTypeOf(claims))
                    .as("an undeclared code must grant nothing, not the standard type")
                    .isEmpty();
        }

        @Test
        @DisplayName("yields nothing when the token carries no role claim at all")
        void yieldsNothingWhenTheClaimIsAbsent() {
            final JwtTokenProvider provider = provider();
            final Jwt claims = claimsCarryingRole(null);

            assertThatCode(() -> provider.userTypeOf(claims)).doesNotThrowAnyException();
            assertThat(provider.userTypeOf(claims)).isEmpty();
        }

        @Test
        @DisplayName("refuses an absent token rather than answering for one")
        void refusesAnAbsentToken() {
            final JwtTokenProvider provider = provider();

            assertThatNullPointerException().isThrownBy(() -> provider.userTypeOf(null));
        }
    }

    /**
     * The authority a user type carries.
     *
     * <p>Only the administrative type reaches the administrative authority, mirroring the single condition
     * the legacy route split tests; every other type reaches the standard authority, mirroring its
     * unconditional alternative. Which routes those authorities then open is the filter chain's contract and
     * is asserted with the chain, not here.</p>
     */
    @Nested
    @DisplayName("The authority a user type carries")
    class Authorities {

        @Test
        @DisplayName("is the administrative one for the administrative type, and only for it")
        void isAdministrativeOnlyForTheAdministrativeType() {
            assertThat(JwtTokenProvider.authorityOf(UserType.ADMIN))
                    .isEqualTo(JwtTokenProvider.ADMIN_AUTHORITY);
            assertThat(JwtTokenProvider.authorityOf(UserType.USER))
                    .isNotEqualTo(JwtTokenProvider.ADMIN_AUTHORITY);
        }

        @Test
        @DisplayName("is the standard one for the standard type")
        void isStandardForTheStandardType() {
            assertThat(JwtTokenProvider.authorityOf(UserType.USER))
                    .isEqualTo(JwtTokenProvider.USER_AUTHORITY);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(UserType.class)
        @DisplayName("is defined for every type the estate declares, so the mapping needs no fallback")
        void isDefinedForEveryDeclaredType(final UserType userType) {
            assertThat(JwtTokenProvider.authorityOf(userType))
                    .isNotBlank()
                    .startsWith("ROLE_");
        }

        @Test
        @DisplayName("distinguishes the two types, so one cannot satisfy a rule written for the other")
        void distinguishesTheTwoTypes() {
            assertThat(JwtTokenProvider.ADMIN_AUTHORITY).isNotEqualTo(JwtTokenProvider.USER_AUTHORITY);
        }

        @Test
        @DisplayName("refuses an absent user type rather than defaulting to either authority")
        void refusesAnAbsentUserType() {
            assertThatNullPointerException().isThrownBy(() -> JwtTokenProvider.authorityOf(null));
        }
    }

    /**
     * That the provider keeps nothing between calls.
     *
     * <p>A token is trusted because of its signature and for no other reason, which means there is no
     * register of issued tokens to consult - and the decisive evidence for that is behavioural rather than
     * structural: a second, identically configured provider verifies a token it never minted. A provider
     * holding a register could not do that, and no accessor onto such a register exists or may be added.</p>
     */
    @Nested
    @DisplayName("What the provider keeps between calls")
    class Statelessness {

        @Test
        @DisplayName("mints the same two facts for the same inputs under the same supplied clock")
        void mintsTheSameFactsForTheSameInputs() throws JsonProcessingException {
            final JwtTokenProvider provider = provider();

            final Map<String, Object> first = payloadOf(provider.issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode()));
            final Map<String, Object> second = payloadOf(provider.issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode()));

            assertThat(second.get("sub")).isEqualTo(first.get("sub")).isEqualTo(ADMINISTRATOR_ID);
            assertThat(second.get(JwtTokenProvider.ROLE_CLAIM))
                    .isEqualTo(first.get(JwtTokenProvider.ROLE_CLAIM))
                    .isEqualTo(UserType.ADMIN.getCode());
        }

        @Test
        @DisplayName("verifies a token it never minted, which is only possible because it keeps no "
                + "register of the tokens it issued")
        void verifiesATokenItNeverMinted() {
            final JwtProperties properties = propertiesWith(freshSigningMaterial());
            final String token = providerFor(properties, ISSUED_AT).issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode());

            final JwtTokenProvider neverMintedIt = providerFor(properties, ISSUED_AT);

            assertThat(neverMintedIt.verify(token))
                    .as("trust rests on the signature, not on having seen the token before")
                    .isPresent();
            assertThat(neverMintedIt.verify(token).orElseThrow().getSubject()).isEqualTo(ADMINISTRATOR_ID);
        }

        @Test
        @DisplayName("is reusable, and a later mint leaves an earlier token entirely unaffected")
        void isReusableWithoutStateLeakingBetweenCalls() {
            final JwtTokenProvider provider = provider();

            final String forAdministrator = provider.issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode());
            assertThat(provider.verify(forAdministrator)).isPresent();

            final String forStandardUser = provider.issue(STANDARD_USER_ID, UserType.USER, UserType.USER.getCode());
            final Jwt standardUser = provider.verify(forStandardUser).orElseThrow();
            assertThat(standardUser.getSubject()).isEqualTo(STANDARD_USER_ID);
            assertThat(provider.userTypeOf(standardUser)).contains(UserType.USER);

            final Jwt administrator = provider.verify(forAdministrator).orElseThrow();
            assertThat(administrator.getSubject())
                    .as("the earlier token still reads back as itself after a later mint")
                    .isEqualTo(ADMINISTRATOR_ID);
            assertThat(provider.userTypeOf(administrator)).contains(UserType.ADMIN);
        }
    }

    /**
     * The record fingerprint a minted token carries, read independently off the wire.
     *
     * <p><strong>Why a token needs it at all.</strong> Every legacy terminal turn re-entered a transaction
     * that read the credential master again, so an entitlement could not go stale: there was nothing
     * carried to go stale. A signed claim is the opposite - it stays true to its signature after it has
     * stopped being true about the record it describes - so an administrator who demoted, deleted or reset
     * the credential of an operator changed nothing until the token that operator already held expired.
     * The fingerprint is what makes the record consultable again on the next request.</p>
     *
     * <p>Every assertion here decodes the payload segment itself, so none can be satisfied by a provider
     * that is merely self-consistent, and the negative assertions are the load-bearing ones: the claim must
     * not be the stored credential digest, must not be the type code, and must not be the identifier.</p>
     */
    @Nested
    @DisplayName("The record fingerprint a minted token carries")
    class RecordFingerprintClaim {

        @Test
        @DisplayName("is present, and is the fingerprint the record reader derives for that record")
        void isTheFingerprintTheRecordReaderDerives() throws JsonProcessingException {
            final Map<String, Object> payload =
                    payloadOf(provider().issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode()));

            assertThat(String.valueOf(payload.get(JwtTokenProvider.SECURITY_STATE_CLAIM)))
                    .isEqualTo(stateService().currentStateOf(ADMINISTRATOR_ID)
                            .orElseThrow().fingerprint());
        }

        @Test
        @DisplayName("names the claim on the wire, which is the part every already-issued token depends on")
        void namesTheClaimOnTheWire() {
            assertThat(JwtTokenProvider.SECURITY_STATE_CLAIM)
                    .as("renaming this claim revokes every token already issued")
                    .isEqualTo("authstate");
        }

        @Test
        @DisplayName("carries no part of the stored credential digest, which is the one value that would "
                + "make issuing a token a disclosure")
        void carriesNoPartOfTheStoredDigest() throws JsonProcessingException {
            final String digest = InMemoryCredentialMaster.nextDigest();
            CREDENTIAL_MASTER.with(STANDARD_USER_ID, UserType.USER.getCode(), digest);

            final String token = provider().issue(STANDARD_USER_ID, UserType.USER, UserType.USER.getCode());
            final String fingerprint =
                    String.valueOf(payloadOf(token).get(JwtTokenProvider.SECURITY_STATE_CLAIM));

            // Compared inside booleans deliberately: an assertion written the other way round would put
            // the stored digest into the description a failing run renders.
            assertThat(fingerprint.contains(digest))
                    .as("the fingerprint is a one-way digest OF the stored value, never the value")
                    .isFalse();
            assertThat(token.contains(digest)).isFalse();
            assertThat(fingerprint.contains(digest.substring(SALT_AND_HASH_START))).isFalse();
        }

        @Test
        @DisplayName("carries the identifier in no readable form, even though it is an input to it")
        void carriesTheIdentifierInNoReadableForm() throws JsonProcessingException {
            final String fingerprint = String.valueOf(payloadOf(
                    provider().issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode()))
                    .get(JwtTokenProvider.SECURITY_STATE_CLAIM));

            // The raw type code is deliberately NOT asserted absent: it is a single character, and any
            // 64-character hexadecimal string contains most single hexadecimal characters by chance, so
            // such an assertion would be satisfied or violated at random rather than by the property. What
            // establishes that the type code is not readable from the fingerprint is that the value is a
            // fixed-width digest of the whole canonical image - asserted here - together with
            // doesNotNameItWhenTheRoleClaimDisagreesWithTheRecord, which shows the code is compared against
            // the record rather than recovered from the claim.
            assertThat(fingerprint)
                    .doesNotContain(ADMINISTRATOR_ID)
                    .doesNotContain(ADMINISTRATOR_ID.toLowerCase(Locale.ROOT))
                    .hasSize(SignOnStateService.FINGERPRINT_LENGTH)
                    .matches("[0-9a-f]+");
        }

        @Test
        @DisplayName("differs between two identities, so a fingerprint cannot be replayed against another "
                + "record")
        void differsBetweenTwoIdentities() throws JsonProcessingException {
            final String forAdministrator = String.valueOf(
                    payloadOf(provider().issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode()))
                            .get(JwtTokenProvider.SECURITY_STATE_CLAIM));
            final String forStandardUser = String.valueOf(
                    payloadOf(provider().issue(STANDARD_USER_ID, UserType.USER, UserType.USER.getCode()))
                            .get(JwtTokenProvider.SECURITY_STATE_CLAIM));

            assertThat(forAdministrator).isNotEqualTo(forStandardUser);
        }

        @Test
        @DisplayName("is unchanged by minting twice while the record stands still, because it describes the "
                + "record and not the occasion")
        void isUnchangedWhileTheRecordStandsStill() throws JsonProcessingException {
            final JwtTokenProvider provider = provider();

            final String first = String.valueOf(payloadOf(provider.issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode()))
                    .get(JwtTokenProvider.SECURITY_STATE_CLAIM));
            final String second = String.valueOf(payloadOf(provider.issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode()))
                    .get(JwtTokenProvider.SECURITY_STATE_CLAIM));

            assertThat(second)
                    .as("a per-issue random value could not be recomputed from the record and so could "
                            + "not revoke anything")
                    .isEqualTo(first);
        }

        @Test
        @DisplayName("refuses to mint at all when the record has gone, rather than issuing a session for "
                + "an identity that no longer exists")
        void refusesToMintWhenTheRecordHasGone() {
            final JwtTokenProvider provider = provider();
            CREDENTIAL_MASTER.without(ADMINISTRATOR_ID);

            assertThat(catchThrowable(() -> provider.issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no session is issued");
        }

        @Test
        @DisplayName("refuses to mint a role the record does not carry, so a session can never name an "
                + "entitlement its record denies")
        void refusesToMintARoleTheRecordDoesNotCarry() {
            final JwtTokenProvider provider = provider();

            final Throwable thrown =
                    catchThrowable(() -> provider.issue(STANDARD_USER_ID, UserType.ADMIN, UserType.ADMIN.getCode()));

            assertThat(thrown)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no session is issued");
            assertThat(String.valueOf(thrown.getMessage()))
                    .as("the subject of a credential being minted does not belong in a message")
                    .doesNotContain(STANDARD_USER_ID);
        }

        @Test
        @DisplayName("mints for a stored code the estate never declared, carrying that code as the role "
                + "claim and the standard authority beside it, because the legacy route split admits "
                + "every non-administrator value unconditionally")
        void mintsForAnUndeclaredStoredCode() throws JsonProcessingException {
            // The record holds a code no level-88 declares. The sign-on program tests the administrator
            // condition once and its alternative is unconditional, so this operator signs on and reaches
            // the main menu. Refusing to mint here - which requiring the stored code to equal the resolved
            // authority's code did - turned that successful sign-on into a server failure.
            CREDENTIAL_MASTER.with(STANDARD_USER_ID, "X");
            final JwtTokenProvider provider = provider();

            final String token = provider.issue(STANDARD_USER_ID, UserType.USER, "X");

            final Map<String, Object> payload = payloadOf(token);
            assertThat(payload)
                    .as("the role claim is the record's own code, which is what a currency check compares")
                    .containsEntry(JwtTokenProvider.ROLE_CLAIM, "X");
            assertThat(payload)
                    .as("the authority claim is what the code resolved to, which is what a decision reads")
                    .containsEntry(JwtTokenProvider.AUTHORITY_CLAIM, UserType.USER.getCode());

            final Jwt verified = provider.verify(token).orElseThrow();
            assertThat(provider.userTypeOf(verified))
                    .as("standard authority, never administrative and never nothing")
                    .contains(UserType.USER);
            assertThat(provider.namesCurrentState(verified))
                    .as("the token still describes the record it was minted from")
                    .isTrue();
        }

        @Test
        @DisplayName("refuses to mint when the stored code has changed to another undeclared code, so the "
                + "tolerance of an undeclared code is not a hole in the currency check")
        void refusesToMintWhenTheUndeclaredCodeHasChanged() {
            CREDENTIAL_MASTER.with(STANDARD_USER_ID, "Y");
            final JwtTokenProvider provider = provider();

            assertThat(catchThrowable(() -> provider.issue(STANDARD_USER_ID, UserType.USER, "X")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no session is issued");
        }
    }

    /**
     * Whether a verified token still names the record it was minted from - the question that decides
     * whether an identity is established, as distinct from whether the token is genuine.
     *
     * <p>All four revocation cases are asserted through this one predicate, and each is expressed as the
     * administrative action that causes it: deletion, demotion, promotion and credential reset. The
     * positive control is asserted first and again after an irrelevant change, so the predicate is shown to
     * discriminate rather than merely to refuse.</p>
     */
    @Nested
    @DisplayName("Whether a verified token still names its record")
    class CurrencyOfAVerifiedToken {

        /**
         * Mints and verifies a token for the administrator, leaving the record untouched.
         *
         * @return the verified token
         */
        private Jwt administratorToken() {
            final JwtTokenProvider provider = provider();
            return provider.verify(provider.issue(ADMINISTRATOR_ID, UserType.ADMIN, UserType.ADMIN.getCode())).orElseThrow();
        }

        @Test
        @DisplayName("still names it while nothing has changed, which is the control every refusal below "
                + "is measured against")
        void stillNamesItWhileNothingHasChanged() {
            assertThat(provider().namesCurrentState(administratorToken())).isTrue();
        }

        @Test
        @DisplayName("no longer names it once the record is deleted, so a deleted operator is refused on "
                + "the next request rather than at the end of the token's lifetime")
        void noLongerNamesItOnceTheRecordIsDeleted() {
            final Jwt verified = administratorToken();

            CREDENTIAL_MASTER.without(ADMINISTRATOR_ID);

            assertThat(provider().namesCurrentState(verified)).isFalse();
        }

        @Test
        @DisplayName("no longer names it once the operator is demoted, so an administrative entitlement "
                + "does not outlive the record that granted it")
        void noLongerNamesItOnceTheOperatorIsDemoted() {
            final Jwt verified = administratorToken();

            CREDENTIAL_MASTER.withUserType(ADMINISTRATOR_ID, UserType.USER.getCode());

            assertThat(provider().namesCurrentState(verified)).isFalse();
        }

        @Test
        @DisplayName("no longer names it once the operator is promoted either, because the token describes "
                + "the record and a changed record is a changed description")
        void noLongerNamesItOnceTheOperatorIsPromoted() {
            final JwtTokenProvider provider = provider();
            final Jwt verified =
                    provider.verify(provider.issue(STANDARD_USER_ID, UserType.USER, UserType.USER.getCode())).orElseThrow();

            CREDENTIAL_MASTER.withUserType(STANDARD_USER_ID, UserType.ADMIN.getCode());

            assertThat(provider.namesCurrentState(verified)).isFalse();
        }

        @Test
        @DisplayName("no longer names it once the credential is reset, so setting a credential ends the "
                + "sessions issued against the previous one")
        void noLongerNamesItOnceTheCredentialIsReset() {
            final Jwt verified = administratorToken();

            CREDENTIAL_MASTER.withResetCredential(ADMINISTRATOR_ID);

            assertThat(provider().namesCurrentState(verified)).isFalse();
        }

        @Test
        @DisplayName("still names it after a change that is not a security fact, so correcting a family "
                + "name does not end anybody's session")
        void stillNamesItAfterANonSecurityChange() {
            final Jwt verified = administratorToken();

            CREDENTIAL_MASTER.findById(ADMINISTRATOR_ID).orElseThrow().setSecUsrLname("Corrected");

            assertThat(provider().namesCurrentState(verified))
                    .as("covering the name fields would end a session because somebody fixed a spelling")
                    .isTrue();
        }

        @Test
        @DisplayName("does not name it when the token carries no fingerprint at all, so a token minted "
                + "before this claim existed cannot bypass the check")
        void doesNotNameItWhenTheFingerprintIsAbsent() {
            final Jwt withoutFingerprint = Jwt.withTokenValue("compact-form-not-parsed-here")
                    .header("alg", "HS256")
                    .subject(ADMINISTRATOR_ID)
                    .claim(JwtTokenProvider.ROLE_CLAIM, UserType.ADMIN.getCode())
                    .issuedAt(ISSUED_AT)
                    .expiresAt(ISSUED_AT.plus(FIXTURE_LIFETIME))
                    .build();

            assertThat(provider().namesCurrentState(withoutFingerprint)).isFalse();
        }

        @Test
        @DisplayName("does not name it when the token's role claim disagrees with the record, even though "
                + "the fingerprint would reconcile on its own")
        void doesNotNameItWhenTheRoleClaimDisagreesWithTheRecord() {
            final String currentFingerprint =
                    stateService().currentStateOf(ADMINISTRATOR_ID).orElseThrow().fingerprint();
            // Both type claims are supplied and agree with each other, so the internal-consistency check
            // passes and what is actually exercised is the comparison against the RECORD.
            final Jwt overclaiming = Jwt.withTokenValue("compact-form-not-parsed-here")
                    .header("alg", "HS256")
                    .subject(ADMINISTRATOR_ID)
                    .claim(JwtTokenProvider.ROLE_CLAIM, UserType.USER.getCode())
                    .claim(JwtTokenProvider.AUTHORITY_CLAIM, UserType.USER.getCode())
                    .claim(JwtTokenProvider.SECURITY_STATE_CLAIM, currentFingerprint)
                    .issuedAt(ISSUED_AT)
                    .expiresAt(ISSUED_AT.plus(FIXTURE_LIFETIME))
                    .build();

            assertThat(provider().namesCurrentState(overclaiming))
                    .as("the fingerprint proves what the record said, not what the token claimed; the "
                            + "type-code comparison is what ties the two together")
                    .isFalse();
        }

        @Test
        @DisplayName("does not name it when the token's two type claims disagree with each other, so an "
                + "undeclared stored code can never be paired with the administrative authority")
        void doesNotNameItWhenTheTwoTypeClaimsDisagree() {
            CREDENTIAL_MASTER.with(STANDARD_USER_ID, "X");
            final String currentFingerprint =
                    stateService().currentStateOf(STANDARD_USER_ID).orElseThrow().fingerprint();
            // The stored code is genuine and the fingerprint reconciles, so both of the record-facing
            // requirements would pass. The authority claim is the escalation: an undeclared code resolves
            // to the standard authority under the legacy split and to nothing else.
            final Jwt escalating = Jwt.withTokenValue("compact-form-not-parsed-here")
                    .header("alg", "HS256")
                    .subject(STANDARD_USER_ID)
                    .claim(JwtTokenProvider.ROLE_CLAIM, "X")
                    .claim(JwtTokenProvider.AUTHORITY_CLAIM, UserType.ADMIN.getCode())
                    .claim(JwtTokenProvider.SECURITY_STATE_CLAIM, currentFingerprint)
                    .issuedAt(ISSUED_AT)
                    .expiresAt(ISSUED_AT.plus(FIXTURE_LIFETIME))
                    .build();

            assertThat(provider().namesCurrentState(escalating)).isFalse();
        }

        @Test
        @DisplayName("names it for a stored code the estate never declared, so tolerating such a code does "
                + "not cost the operator a usable session")
        void namesItForAnUndeclaredStoredCode() {
            CREDENTIAL_MASTER.with(STANDARD_USER_ID, "X");
            final JwtTokenProvider provider = provider();
            final Jwt verified =
                    provider.verify(provider.issue(STANDARD_USER_ID, UserType.USER, "X")).orElseThrow();

            assertThat(provider.namesCurrentState(verified)).isTrue();
        }

        @Test
        @DisplayName("does not name it when the record cannot be reached, failing closed rather than "
                + "leaving a session standing while revocation is not working")
        void doesNotNameItWhenTheRecordCannotBeReached() {
            final Jwt verified = administratorToken();

            CREDENTIAL_MASTER.failLookupsWith(new IllegalStateException("credential master unreachable"));

            assertThat(catchThrowable(() -> provider().namesCurrentState(verified)))
                    .as("the failure is absorbed rather than raised, so the chain refuses instead of "
                            + "answering with a server error")
                    .isNull();
            assertThat(provider().namesCurrentState(verified)).isFalse();
        }

        @Test
        @DisplayName("refuses an absent token rather than answering about one")
        void refusesAnAbsentToken() {
            assertThatNullPointerException().isThrownBy(() -> provider().namesCurrentState(null));
        }
    }
}
