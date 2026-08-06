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
package com.carddemo.support;

import com.carddemo.domain.UserSecurity;
import com.carddemo.repository.UserSecurityRepository;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * An in-memory credential master, for the assertions that need the authoritative user-security record
 * to exist, to change, to disappear, or to be unreachable - within one test method and without a
 * database.
 *
 * <h2>Why a hand-written fixture and not a mock</h2>
 *
 * <p>What this stands in for is consulted <em>during</em> a request, by the security boundary, to decide
 * whether a session already issued still describes the record it was minted from. The assertions that
 * matter therefore change the record between two requests and read the second request's answer, which is
 * a sequence of states rather than a single stubbed return. Expressing that as recorded interactions
 * would put the fixture's own scripting between the test and the behaviour; a real map does not, and it
 * also means a lookup that the code under test performs twice genuinely reads the same value twice.
 *
 * <p>It is also what lets a test prove the boundary fails <em>closed</em>: {@link #failLookupsWith} makes
 * the lookup raise, which is the only way to observe what the boundary does when the record cannot be
 * reached at all.
 *
 * <h2>What it implements, and what it deliberately refuses</h2>
 *
 * <p>The repository it stands in for declares exactly six operations. The three this fixture implements -
 * the identifier lookup, the save and the delete - are the whole of what the security boundary and the
 * revocation paths use. The three projection finders that serve the administrative list screen are
 * refused rather than approximated: a fixture that returned a plausible page of projections would invite
 * a screen test to be written against it, and a screen paging contract asserted against a hand-rolled
 * page is asserted against the fixture rather than against the query.
 *
 * <h2>Credentials in this fixture</h2>
 *
 * <p>No credential value from the estate's provisioning records is used, named or restated here. Every
 * digest is produced from a freshly generated throwaway value.
 *
 * <p>Two constraints pull against each other, and the resolution is worth stating. The entity refuses any
 * stored credential below a structural cost floor of its own - it is the guard that stops a cleartext or
 * trivially cheap value reaching the column, and this fixture must not be the reason that guard is
 * loosened - so a fixture digest cannot be cheaper than that floor. But hashing at that floor takes
 * appreciable time, and a fixture that hashed on every seeding call would add minutes to a suite whose
 * security slices re-seed before every test method. So a small pool of distinct digests is produced once,
 * at the floor, and handed out in rotation. Every fixture record therefore carries a genuine digest that
 * the entity accepts, no two records carry the same one, and the cost is paid once per run rather than
 * once per test.
 *
 * <p>The rotation is deliberately not a reuse: {@link #withResetCredential(String)} guarantees the
 * replacement differs from what the record held, because a reset that happened to reinstate the same
 * digest would leave the fingerprint under test unchanged and the assertion depending on it would pass
 * for the wrong reason.
 *
 * <p>Nothing here asserts or depends on hashing strength: the assertions that read a cost factor read it
 * from the encoder that produced the digest, not from this fixture.
 *
 * <p>This fixture is not thread-safe and is not meant to be: the suite runs sequentially, and a fixture
 * that synchronised would hide a test that shared it by accident.
 *
 * <p>Provenance: this support type has no legacy antecedent - the legacy estate carries no test harness
 * of any kind. Checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
public final class InMemoryCredentialMaster {

    /**
     * Cost factor for fixture digests.
     *
     * <p>It is the entity's own structural floor for a stored credential, which is the cheapest value the
     * entity will accept and therefore the cheapest a fixture may use. It is deliberately <em>not</em> the
     * module's production hashing strength: nothing this fixture serves asserts a cost factor, and paying
     * the production cost here would slow every test that seeds a record for no gain. The floor is
     * restated rather than read because the entity keeps it private - and rightly so, since publishing it
     * would invite a caller to write digests exactly at it.
     */
    private static final int FIXTURE_DIGEST_COST = 10;

    /** Produces the pool of fixture digests, once. */
    private static final BCryptPasswordEncoder FIXTURE_ENCODER =
            new BCryptPasswordEncoder(FIXTURE_DIGEST_COST);

    /** Prefix marking a generated fixture value as a fixture wherever one is ever printed. */
    private static final String FIXTURE_VALUE_PREFIX = "TEST-CREDENTIAL-";

    /** Repository view supplied to production collaborators while this fixture retains mutation helpers. */
    private final UserSecurityRepository repositoryView =
            (UserSecurityRepository) Proxy.newProxyInstance(
                    UserSecurityRepository.class.getClassLoader(),
                    new Class<?>[] {UserSecurityRepository.class},
                    this::invokeRepository);

    /**
     * @return the repository interface view backed by this fixture
     */
    public UserSecurityRepository repository() {
        return this.repositoryView;
    }

    private Object invokeRepository(final Object proxy, final Method method, final Object[] arguments) {
        final Object[] args = arguments == null ? new Object[0] : arguments;
        return switch (method.getName()) {
            case "findById" -> findById((String) args[0]);
            case "save" -> save((UserSecurity) args[0]);
            case "deleteById" -> {
                deleteById((String) args[0]);
                yield null;
            }
            // The repository is a closed interface, so the three writes and reads above are the whole
            // surface a production collaborator can reach through this view. The browse operations are
            // deliberately absent rather than refused: no fixture user of this class lists identities,
            // and the default arm below names the operation if one ever starts to.
            case "toString" -> "InMemoryCredentialMaster.repository";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(
                    "InMemoryCredentialMaster does not implement repository method "
                            + method.getName());
        };
    }

    /**
     * How many distinct digests the pool holds.
     *
     * <p>Comfortably more than any single test needs - the security slices seed two records and reset at
     * most one credential - so a rotation never has to hand back a value a record in the same test still
     * holds.
     */
    private static final int DIGEST_POOL_SIZE = 8;

    /**
     * Distinct digests, produced once for the whole run.
     *
     * <p>Each carries its own random salt, so no two are equal even though they were produced the same
     * way - which is the property that makes replacing one an observable change to anything derived from
     * the record.
     */
    private static final List<String> DIGEST_POOL = buildDigestPool();

    /** Rotation cursor over {@link #DIGEST_POOL}; the suite is sequential, so a plain counter suffices. */
    private static int digestCursor;

    /** Records by identifier, in insertion order so that iteration is deterministic. */
    private final Map<String, UserSecurity> records = new LinkedHashMap<>();

    /** Failure the lookup raises instead of answering, or {@code null} when it answers normally. */
    private RuntimeException lookupFailure;

    /**
     * Builds the pool of fixture digests.
     *
     * @return distinct, structurally valid BCrypt digests of freshly generated throwaway values
     */
    private static List<String> buildDigestPool() {
        final List<String> pool = new ArrayList<>(DIGEST_POOL_SIZE);
        for (int index = 0; index < DIGEST_POOL_SIZE; index++) {
            pool.add(FIXTURE_ENCODER.encode(FIXTURE_VALUE_PREFIX + index + '-' + System.nanoTime()));
        }
        return List.copyOf(pool);
    }

    /**
     * Hands out the next fixture digest in rotation.
     *
     * <p>Successive calls return different values, which is what a caller seeding two records or resetting
     * a credential depends on. The value is drawn from a pool produced once rather than hashed on demand,
     * for the reason this class's documentation gives.
     *
     * @return a structurally valid BCrypt digest
     */
    public static String nextDigest() {
        final String digest = DIGEST_POOL.get(Math.floorMod(digestCursor, DIGEST_POOL_SIZE));
        digestCursor++;
        return digest;
    }

    /**
     * Seeds one operator, generating its credential digest.
     *
     * @param userId   the identifier to key it by
     * @param typeCode the raw one-character role code, stored exactly as given and unjudged, because
     *                 the column screens no value and the estate screened none
     * @return this fixture, so seeding reads as one statement
     */
    public InMemoryCredentialMaster with(final String userId, final String typeCode) {
        return with(userId, typeCode, nextDigest());
    }

    /**
     * Seeds one operator with a stated credential digest.
     *
     * @param userId   the identifier to key it by
     * @param typeCode the raw one-character role code
     * @param digest   the stored credential digest; must be structurally a digest, which the entity
     *                 itself enforces
     * @return this fixture
     */
    public InMemoryCredentialMaster with(final String userId, final String typeCode,
            final String digest) {
        this.records.put(userId,
                new UserSecurity(userId, "Test", "Operator", digest, typeCode));
        return this;
    }

    /**
     * Replaces the stored credential of a seeded operator with a new digest, as an administrative
     * credential reset does.
     *
     * @param userId the identifier whose credential is reset
     * @return this fixture
     * @throws IllegalStateException if no record carries that identifier, which would make the
     *                               assertion that followed meaningless
     */
    public InMemoryCredentialMaster withResetCredential(final String userId) {
        final UserSecurity record = required(userId);
        String replacement = nextDigest();
        if (replacement.equals(record.credentialDigest())) {
            // A reset that reinstated the same digest would leave everything derived from the record
            // unchanged, and the assertion depending on the reset would pass for the wrong reason.
            replacement = nextDigest();
        }
        record.replaceCredentialDigest(replacement);
        return this;
    }

    /**
     * Changes the raw role code of a seeded operator, as an administrative demotion or promotion does.
     *
     * @param userId   the identifier whose role changes
     * @param typeCode the new raw one-character role code
     * @return this fixture
     * @throws IllegalStateException if no record carries that identifier
     */
    public InMemoryCredentialMaster withUserType(final String userId, final String typeCode) {
        required(userId).setSecUsrType(typeCode);
        return this;
    }

    /**
     * Removes a seeded operator, as an administrative deletion does.
     *
     * @param userId the identifier to remove
     * @return this fixture
     */
    public InMemoryCredentialMaster without(final String userId) {
        this.records.remove(userId);
        return this;
    }

    /**
     * Makes every subsequent lookup raise instead of answering, so that a caller's behaviour when the
     * record cannot be reached can be observed.
     *
     * @param failure the failure to raise; must not be {@code null}
     * @return this fixture
     */
    public InMemoryCredentialMaster failLookupsWith(final RuntimeException failure) {
        this.lookupFailure = Objects.requireNonNull(failure, "failure must not be null");
        return this;
    }

    /**
     * Empties the fixture and clears any lookup failure, so one test cannot inherit another's records.
     *
     * @return this fixture
     */
    public InMemoryCredentialMaster reset() {
        this.records.clear();
        this.lookupFailure = null;
        return this;
    }

    /**
     * Reports how many records the fixture holds, for an assertion that a seeding step took effect.
     *
     * @return the record count
     */
    public int size() {
        return this.records.size();
    }

    public Optional<UserSecurity> findById(final String secUsrId) {
        if (this.lookupFailure != null) {
            throw this.lookupFailure;
        }
        return Optional.ofNullable(this.records.get(secUsrId));
    }

    public UserSecurity save(final UserSecurity identity) {
        this.records.put(identity.getSecUsrId(), identity);
        return identity;
    }

    public void deleteById(final String secUsrId) {
        this.records.remove(secUsrId);
    }

    /**
     * Reads a seeded record or fails loudly.
     *
     * @param userId the identifier to read
     * @return the record
     * @throws IllegalStateException when the fixture does not hold it
     */
    private UserSecurity required(final String userId) {
        final UserSecurity record = this.records.get(userId);
        if (record == null) {
            throw new IllegalStateException(
                    "the fixture holds no record to change for identifier " + userId);
        }
        return record;
    }

    /**
     * Builds the refusal the three projection finders raise.
     *
     * @return the refusal, for the caller to throw
     */
    private static UnsupportedOperationException unsupportedProjection() {
        return new UnsupportedOperationException(
                "InMemoryCredentialMaster serves the identifier lookup, the save and the delete only; "
                        + "the administrative list screen's paging contract must be asserted against the "
                        + "real query rather than against a hand-rolled page");
    }
}
