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
package com.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * Sign-on identity and role source - the Java translation of the legacy {@code SEC-USER-DATA}
 * record declared in copybook {@code CSUSR01Y}, whose fields sum to exactly 80 bytes.
 *
 * <p><strong>Verified 80-byte layout.</strong> Five fields become columns, at zero-based offset and
 * width: the 8-byte identifier at 0, the 20-byte given name at 8, the 20-byte family name at 28, the
 * 8-byte credential at 48 and the 1-byte role code at 56. Mapped bytes end at offset 57, where a
 * 23-byte trailing filler begins. That filler is <em>named</em> in the source rather than being a
 * bare {@code FILLER}, which changes nothing about persistence - it is padding reconstructed on
 * output from the declared record width, there is no filler attribute and no filler column, and the
 * only code that addresses those bytes is the fixed-width mapper in the utility layer. This class
 * carries JPA column widths and nothing positional.
 *
 * <p><strong>Dataset provenance, corroborated twice.</strong> The provisioning job
 * {@code DUSRSECJ.jcl} discards any prior copy of the sequential dataset, then runs
 * {@code IEBGENER} over ten user records supplied <strong>in stream as ASCII card images</strong> -
 * five of role {@code A} and five of role {@code U} - writing them with
 * {@code DCB=(LRECL=80,RECFM=FB,DSORG=PS,BLKSIZE=0)}, and finally defines the indexed cluster with
 * {@code KEYS(8,0)} and {@code RECORDSIZE(80,80)} and copies the sequential dataset into it. The key
 * definition independently confirms a single-part business key of width 8 at offset 0, and the record
 * size confirms the 80-byte width the copybook sums to. Because the seed content originates in stream
 * in ASCII rather than in the mainframe encoding, no EBCDIC decode is needed to reproduce it, which is
 * why the EBCDIC dataset having no ASCII twin costs nothing.
 *
 * <p><strong>The credential column's width diverges deliberately.</strong> The legacy record holds
 * the credential as eight cleartext characters at offset 48, and legacy sign-on tests it for direct
 * equality against the value keyed at the terminal. Reproducing cleartext storage would satisfy
 * byte-for-byte parity and violate the binding no-hardcoded-credentials requirement at the same
 * time, so fidelity is deliberately broken here: {@code sec_usr_pwd} is {@code VARCHAR(60)}, sized
 * for a BCrypt digest, rather than the legacy width of 8. It is one of three columns in the schema
 * wider than its legacy field - {@code customer.cust_ssn} and {@code customer.govt_issued_id} are
 * the other two, widened for protection at rest - and the only one widened for a credential.
 *
 * <p><strong>What this class stores, and what reads it.</strong> This entity performs no hashing, no
 * verification and no comparison, and it is the width and the format of the column that are fixed here -
 * not the production or checking of the digest. Producing and checking a digest belongs to
 * {@code service.CredentialDigestService}, which wraps a BCrypt encoder at the module's single
 * configured strength, exposes a verifying comparison, and refuses at the persistence boundary any value
 * that is not digest-shaped.
 *
 * <p>The sign-on path that calls it is delivered. {@code service.AuthenticationService} reproduces the
 * legacy sign-on transaction and verifies a presented credential against this column;
 * {@code api.AuthController} maps the one route reachable without a credential;
 * {@code service.SessionTokenIssuer} and {@code config.JwtTokenProvider} mint the bearer grant that
 * carries the outcome. So requests are authenticated against this column, and this class's two standing
 * obligations on whatever writes it - write only a digest, never store or compare a cleartext credential
 * - are discharged by that service rather than merely stated here.
 *
 * <p>One consequence of this column belongs in this class's own description, because it is a property of
 * the stored value rather than of the service. The digest is one of three fields whose bytes
 * {@code service.SignOnStateService} folds into the fingerprint a bearer grant carries, so replacing the
 * credential in this column revokes every session already issued to that identity at the next request
 * rather than at the grant's expiry. Nothing about the mapping enforces that; the mapping simply makes it
 * possible, by keeping the digest here and nowhere else.
 *
 * <p>Rows do exist in this table under the seeding profiles: {@code V4__seed_user_security.sql} ships
 * from {@code classpath:db/migration/seed} and seeds ten identities, every credential an independently
 * salted
 * 60-character digest, so the not-null column is satisfied without any cleartext value. Whatever
 * component later authenticates against those rows carries two obligations this mapping cannot enforce
 * on its behalf: it must write only a digest, and it must never store or compare a cleartext
 * credential. The attribute is left uninitialised so that no default, fallback or placeholder
 * credential can exist in source.
 *
 * <p><strong>The role code is a raw character, not an enumerated field.</strong> The legacy field
 * carries {@code A} for an administrator and {@code U} for a standard user, and the communication-area
 * copybook declares exactly those two as condition names. The module does model them as
 * {@link com.carddemo.domain.enums.UserType}, but that type is deliberately not used as the attribute
 * type here. Three mechanical reasons each suffice: the column is {@code VARCHAR(1)}, so persisting
 * the constant name would not fit one character and would fail schema validation or truncate;
 * persisting the ordinal would require an integer column and fail validation too; and a converter
 * would place translation logic inside {@code domain}, breaching the one-way layer boundary.
 *
 * <p>The decisive reason, though, is behavioral. Legacy sign-on moves the stored character into the
 * communication area, tests <em>only</em> the administrator condition, and reaches the main menu
 * through an <strong>unconditional</strong> alternative. There is no third branch and no error path
 * for a code the estate never declared, so every non-administrator value - including an unexpected
 * one - routes to the main menu without raising anything. Storing the raw character preserves that
 * tolerance exactly; an enumerated attribute would reject at the persistence boundary a value the
 * legacy system silently accepted and routed. Translation belongs to the service layer, which must
 * model an unrecognised code as an absent value rather than throw. That single character is nonetheless the
 * sole authority
 * for the estate's authorization split, so whatever component gates the administrative routes must
 * derive them from it.
 *
 * <ol>
 *   <li>A predelete step (L23) discards any prior copy of the physical sequential dataset
 *       {@code AWS.M2.CARDDEMO.USRSEC.PS}.</li>
 *   <li>{@code STEP01} (L32) runs {@code IEBGENER} with the ten user records supplied
 *       <strong>in stream as ASCII card images</strong> (L35 through L44), writing them to that
 *       sequential dataset with {@code DCB=(LRECL=80,RECFM=FB,DSORG=PS,BLKSIZE=0)} (L48). Five
 *       records carry type {@code A} and five carry type {@code U}.</li>
 *   <li>{@code STEP02} defines the indexed cluster {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS} with
 *       {@code KEYS(8,0)} and {@code RECORDSIZE(80,80)}, and {@code STEP03} copies the sequential
 *       dataset into it.</li>
 * </ol>
 *
 * <p>Two independent corroborations of this mapping fall out of that job. The cluster key
 * definition {@code KEYS(8,0)} confirms a single-part business key of width 8 at offset 0, which
 * is exactly the primary key mapped below; and {@code RECORDSIZE(80,80)} together with
 * {@code LRECL=80} confirms the 80-byte record length that the copybook layout sums to. Because
 * the seed content originates in stream in ASCII rather than in the mainframe encoding, no EBCDIC
 * decode is needed to reproduce it. That is why {@code app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS}
 * having no ASCII twin costs nothing: its content is fully recoverable from the job stream.
 *
 * <h2>The credential column is a deliberate, documented parity exception</h2>
 *
 * <p>The legacy record holds the credential as eight cleartext characters at offset 48, and the
 * sign-on path at {@code app/cbl/COSGN00C.cbl} L223 tests it for direct equality against the value
 * keyed at the terminal. Reproducing cleartext storage would satisfy byte-for-byte parity and
 * violate the binding no-hardcoded-credentials requirement at the same time, so fidelity is
 * deliberately broken here: {@code sec_usr_pwd} is {@code VARCHAR(60)}, sized for a BCrypt digest,
 * and never the legacy width of 8. It is one of three columns wider than its legacy field, alongside
 * {@code customer.cust_ssn} and {@code customer.govt_issued_id}, and the only one widened for a
 * credential rather than for protection at rest. That divergence exists <em>because a binding
 * requirement demands it</em>, and it is recorded as the flagship security entry in
 * {@code docs/decision-log.md} rather than silently applied.
 *
 * <p>Hashing, verification and every other credential operation live outside this package, in
 * {@code com.carddemo.service.AuthenticationService} and {@code com.carddemo.config.SecurityConfig}.
 * This entity performs no hashing, no verification and no comparison of the stored digest: the
 * verifier is a password encoder, not an equality test.
 *
 * <p><strong>Keeping the hashing outside this package is not by itself what stops a cleartext value
 * reaching an instance; the guard below is.</strong> A column sized for a digest will hold anything
 * that fits in sixty characters, an eight-character cleartext credential included, so relying on
 * every caller to remember to hash first is a convention rather than a control. Both write paths -
 * the five-argument constructor and {@link #replaceCredentialDigest(String)} - therefore verify that
 * the value handed to them is structurally a BCrypt digest: exactly sixty characters, a recognised
 * version marker, a two-digit cost between {@value #MINIMUM_BCRYPT_COST} and
 * {@value #MAXIMUM_BCRYPT_COST}, and a radix-64 tail.
 * A cleartext credential cannot satisfy that shape and is refused rather than stored. The check is
 * structural only - it neither hashes nor verifies, and it needs no encoder - so the layer boundary
 * stays intact while the column stops being able to hold a secret in the clear.
 *
 * <p><strong>The digest is not exposed as a bean property.</strong> The reader is
 * {@link #credentialDigest()} and the writer is {@link #replaceCredentialDigest(String)}, neither of
 * which follows the JavaBean naming convention. That is deliberate: a {@code getSecUsrPwd} accessor
 * would make the digest a discoverable property and would therefore be picked up by JSON
 * serialization, by interface-based repository projections, by bean-mapping utilities and by
 * diagnostic renderers that walk properties - every one of which is a route by which stored
 * credential material could leave the process without anyone writing a line of code to send it.
 * Persistence is unaffected, because the mapping annotations sit on the fields and the provider
 * therefore uses field access rather than property access.
 *
 * <h2>The user type is a raw one-character code, not an enumerated field</h2>
 *
 * <p>{@code SEC-USR-TYPE} carries {@code A} for an administrator and {@code U} for a standard
 * user; {@code app/cpy/COCOM01Y.cpy} declares exactly those two codes as the level-88 condition
 * names {@code CDEMO-USRTYP-ADMIN} (L27) and {@code CDEMO-USRTYP-USER} (L28) on the
 * communication-area field at L26. The module does model them as
 * {@code com.carddemo.domain.enums.UserType}, but that type is deliberately <strong>not</strong>
 * used as the field type here, for three reasons any one of which is sufficient.
 *
 * <ol>
 *   <li>The column is {@code VARCHAR(1)}. {@code @Enumerated(EnumType.STRING)} persists the
 *       constant <em>name</em>, which does not fit one character and would either fail schema
 *       validation or truncate.</li>
 *   <li>{@code @Enumerated(EnumType.ORDINAL)} requires an integer column and would fail schema
 *       validation as well.</li>
 *   <li>An {@code AttributeConverter} would work technically but would place translation logic
 *       inside {@code domain}, breaching the one-way layer boundary, and would add a type to a
 *       package whose contents are fixed.</li>
 * </ol>
 *
 * <p>The decisive reason is behavioral rather than mechanical. Sign-on moves the stored character
 * into the communication area at {@code app/cbl/COSGN00C.cbl} L227, tests the administrator
 * condition at L230 and transfers control to the administrative menu program when it holds. The
 * alternative at L235 is an <strong>unconditional</strong> {@code ELSE} that transfers control to
 * the main menu program, and the construct closes at L240. There is no third branch and no error
 * path for a code the estate never declared, so <em>every</em> non-administrator value - including
 * an unexpected one - reaches the main menu without raising anything. Storing the raw character
 * preserves that tolerance exactly. An enumerated field would reject at the persistence boundary a
 * value the legacy system silently accepted and routed, so a code outside the declared pair must
 * never fail here. Translation to the enumerated form happens at the service layer, where absence
 * is modelled explicitly instead of being thrown.
 *
 * <p>That single character is the sole authority for the estate's authorization split: the
 * administrative transactions are admin-gated in {@code com.carddemo.config.SecurityConfig},
 * derived from the transaction and program table in {@code app/csd/CARDDEMO.CSD}.
 *
 * <h2>Schema contract</h2>
 *
 * <p>Flyway owns the schema and Hibernate only validates against it: the module runs with
 * {@code spring.jpa.hibernate.ddl-auto: validate} in every profile, so any mismatch between this
 * mapping and {@code V1__create_schema.sql} aborts the application context at startup rather than
 * silently adapting. The five columns, their widths and their non-nullability below are therefore
 * transcribed from {@code V1__create_schema.sql} and are not free choices.
 *
 * <ul>
 *   <li><strong>No generated identifier.</strong> The key is the legacy eight-character business
 *       user id. Across the estate the record key is the leading substring of the record image -
 *       {@code app/cbl/CBACT01C.cbl} L38 through L40 shows the pattern, a record group split into
 *       an eleven-digit key field followed by the remaining data bytes - and a surrogate key would
 *       break the record-image-to-row correspondence that byte-parity verification depends on. No
 *       sequence, no auto-numbering and no synthetic identifier exists anywhere in this schema.</li>
 *   <li><strong>No optimistic-locking column.</strong> Version columns exist only on the account
 *       and card tables, where they replace a before-and-after image comparison. This table has
 *       none.</li>
 *   <li><strong>No relationship in either direction.</strong> The authoritative foreign-key set
 *       lives in {@code V2__create_indexes.sql} and none of it touches this table, so this entity
 *       declares no association and no collection.</li>
 *   <li><strong>No rows are seeded by the reference-data migration.</strong> The table is empty
 *       after {@code V3__seed_reference_data.sql}. {@code V4__seed_user_security.sql} owns the ten
 *       sign-on identities - five of type {@code A} and five of type {@code U}, matching the job
 *       stream above - and stores each credential as a BCrypt digest. It ships from
 *       {@code classpath:db/migration/seed}, a location {@code application-prod.yml} never declares and
 *       {@code FlywayConfig} refuses under the production profile, so no production deployment ever
 *       receives a seeded login. See {@code DL-298} in {@code docs/decision-log.md}.</li>
 * </ul>
 *
 * <h2>Provenance</h2>
 *
 * <p>Legacy checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19, the stamp appearing in the copybook
 * trailer at {@code app/cpy/CSUSR01Y.cpy} L25. The legacy estate under {@code app/} is read-only
 * reference material and no source text from it is copied into this module: only member names,
 * field names, pictures, widths, offsets, line references and codes appear above.
 */
@Entity
@Table(name = "user_security")
public class UserSecurity {

    /**
     * Width of the sign-on identifier in characters: 8, declared by the copybook as eight alphanumeric
     * characters at offset 0.
     *
     * <p>Named so that the column declaration and the persistence-time rule read the one figure rather
     * than two copies of it.
     *
     * <p><strong>Published, because the key width is part of this entity's external contract.</strong>
     * Every caller that turns an operator-typed identifier into this record's key has to move it into
     * exactly this many character positions first - the legacy screen item and the legacy record field
     * are both {@code PIC X(08)}, so the terminal did that move and a REST caller has no terminal to do
     * it. The services that authenticate, add, update and delete an identity therefore read this figure
     * rather than each restating {@code 8}, which is what keeps the one width in one place. The
     * transformation itself belongs to the utility layer, not here: an entity states its layout and does
     * not perform screen-field arithmetic.
     */
    public static final int SEC_USR_ID_WIDTH = 8;

    /**
     * Exact character length of a BCrypt digest: a seven-character prefix of the form
     * {@code $2x$nn$} followed by a 53-character radix-64 tail carrying the salt and the hash.
     * A value of any other length is not a digest.
     */
    private static final int BCRYPT_DIGEST_LENGTH = 60;

    /**
     * Lowest cost factor this entity will store. Ten is the encoder's own default, so requiring at
     * least ten rejects a deliberately weakened work factor without rejecting anything the module
     * produces.
     *
     * <p>Package-private rather than private so the accepted range can be asserted against the range
     * {@code service.CredentialDigestService} verifies over, which is the invariant that matters: a
     * digest this entity stores must be one that path can read.
     */
    static final int MINIMUM_BCRYPT_COST = 10;

    /**
     * Highest cost factor this entity will store.
     *
     * <p>Thirty-one is not a policy choice: BCrypt encodes its work factor as a base-two logarithm of
     * the round count, and the algorithm is defined only up to 2^31 rounds, so a verifier refuses any
     * value above 31 outright. This bound used to be absent, which meant the two digits were checked
     * for being digits and for being at least ten and nothing else - so a digest declaring cost 32
     * through 99 was structurally well formed, passed this guard, and was written to the column. It
     * would then have failed every subsequent verification, because the verifier rejects it before
     * looking at the hash. The identity would have been created or updated successfully and been
     * unable to authenticate ever again, with nothing in the failure to say why. Refusing the write is
     * the only outcome that reports the problem to the caller who caused it.
     *
     * <p>Package-private for the same reason as {@link #MINIMUM_BCRYPT_COST}.
     */
    static final int MAXIMUM_BCRYPT_COST = 31;

    /**
     * Version markers a BCrypt digest may carry. All three denote the same algorithm and differ only
     * in how a historical implementation defect was handled; the encoder in use emits {@code $2a$}
     * and can be configured to emit either of the others, so all three are admitted.
     */
    private static final String[] BCRYPT_VERSION_MARKERS = {"$2a$", "$2b$", "$2y$"};

    /**
     * Radix-64 alphabet BCrypt encodes its salt and hash with. It is deliberately <em>not</em> the
     * standard Base64 alphabet - the ordering differs and the padding character is absent - so the
     * set is spelled out here rather than borrowed.
     */
    private static final String BCRYPT_RADIX_64_ALPHABET =
            "./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /**
     * Eight-character sign-on identifier; record offset 0, width 8. The primary key, assigned by
     * the application from the legacy business key and never generated. Stored exactly as read:
     * no trimming, padding or case folding is applied anywhere in this class.
     */
    @Id
    @Column(name = "sec_usr_id", length = SEC_USR_ID_WIDTH, nullable = false)
    private String secUsrId;

    /** Given name; record offset 8, width 20. */
    @Column(name = "sec_usr_fname", length = 20, nullable = false)
    private String secUsrFname;

    /** Family name; record offset 28, width 20. */
    @Column(name = "sec_usr_lname", length = 20, nullable = false)
    private String secUsrLname;

    /**
     * The stored BCrypt digest of the sign-on credential - <strong>never a cleartext value and never
     * a password</strong>. The legacy field at record offset 48 is eight cleartext characters; this
     * column is 60 characters wide to hold a digest instead, the single deliberate width divergence
     * described in the class documentation.
     *
     * <p>The field name matches the legacy field name so the traceability row back to
     * {@code SEC-USR-PWD} stays findable even though the content has changed. Digest production
     * and digest verification live outside this package, in
     * {@code com.carddemo.service.AuthenticationService} and
     * {@code com.carddemo.config.SecurityConfig}; nothing in this class hashes, verifies or
     * compares the value, and it is deliberately left uninitialised so that no default, fallback
     * or placeholder credential can exist in source.
     *
     * <p>Both write paths validate the digest structurally before assigning it, so this field can
     * hold a digest and cannot hold a cleartext credential. The accessors are deliberately named
     * outside the JavaBean convention - {@link #credentialDigest()} and
     * {@link #replaceCredentialDigest(String)} - so that the value is not a discoverable property and
     * cannot be emitted by serialization, a repository projection or a property-walking renderer.
     */
    @Column(name = "sec_usr_pwd", length = 60, nullable = false)
    private String secUsrPwd;

    /**
     * Role code; record offset 56, width 1. Held as a raw single character rather than an
     * enumerated field: {@code A} selects the administrative role and {@code U} the standard one,
     * and translation to {@link com.carddemo.domain.enums.UserType} belongs to the service layer.
     *
     * <p>The raw form is deliberate. Legacy sign-on tests only the administrator condition and
     * routes every other value to the main menu through an unconditional alternative, so a code
     * outside the declared pair must round-trip through persistence rather than fail. No
     * {@code @Enumerated} mapping, no {@code @Convert} and no pattern constraint is applied here,
     * because each of those would reject data the legacy system accepts.
     */
    @Column(name = "sec_usr_type", length = 1, nullable = false)
    private String secUsrType;

    /**
     * No-argument constructor required by the persistence provider for instantiation during
     * hydration. Kept {@code protected} so application code cannot create a partially populated
     * identity by accident; the five-argument constructor is the supported route.
     */
    protected UserSecurity() {
        // Intentionally empty: the provider assigns every field directly after construction.
    }

    /**
     * Creates a fully populated sign-on identity in record order.
     *
     * <p>Every field is assigned directly rather than through the corresponding setter, so the
     * partially constructed instance is never exposed to an overridable method.
     *
     * @param secUsrId    eight-character sign-on identifier, the primary key; record offset 0
     * @param secUsrFname given name; record offset 8, width 20
     * @param secUsrLname family name; record offset 28, width 20
     * @param secUsrPwd   the <strong>already hashed</strong> credential: a BCrypt digest produced
     *                    by the service layer. A cleartext value cannot be passed here - this
     *                    constructor performs no hashing, and it refuses any argument that is not
     *                    structurally a digest
     * @param secUsrType  raw one-character role code, {@code A} or {@code U}; an unrecognised code
     *                    is accepted, matching the legacy unconditional routing alternative
     * @throws IllegalArgumentException when the credential argument is not a structurally valid
     *                                  BCrypt digest of at least the minimum cost
     */
    public UserSecurity(String secUsrId,
                        String secUsrFname,
                        String secUsrLname,
                        String secUsrPwd,
                        String secUsrType) {
        this.secUsrId = secUsrId;
        this.secUsrFname = secUsrFname;
        this.secUsrLname = secUsrLname;
        this.secUsrPwd = requireBcryptDigest(secUsrPwd);
        this.secUsrType = secUsrType;
    }

    /**
     * Returns the eight-character sign-on identifier, which is the primary key.
     *
     * @return the sign-on identifier as stored, untrimmed
     */
    public String getSecUsrId() {
        return secUsrId;
    }

    /**
     * Replaces the sign-on identifier. Plain assignment: no normalisation of any kind is applied.
     *
     * @param secUsrId the sign-on identifier to store
     */
    public void setSecUsrId(String secUsrId) {
        this.secUsrId = secUsrId;
    }

    /**
     * Returns the given name.
     *
     * @return the given name as stored, untrimmed
     */
    public String getSecUsrFname() {
        return secUsrFname;
    }

    /**
     * Replaces the given name. Plain assignment: no normalisation of any kind is applied.
     *
     * @param secUsrFname the given name to store
     */
    public void setSecUsrFname(String secUsrFname) {
        this.secUsrFname = secUsrFname;
    }

    /**
     * Returns the family name.
     *
     * @return the family name as stored, untrimmed
     */
    public String getSecUsrLname() {
        return secUsrLname;
    }

    /**
     * Replaces the family name. Plain assignment: no normalisation of any kind is applied.
     *
     * @param secUsrLname the family name to store
     */
    public void setSecUsrLname(String secUsrLname) {
        this.secUsrLname = secUsrLname;
    }

    /**
     * Returns the stored BCrypt digest. This is a hash and never a password: no accessor on this
     * class can return a cleartext credential, because the write paths refuse to store one.
     * Verification is performed by the encoder in the service layer, not by comparing this value.
     *
     * <p><strong>Deliberately not named {@code getSecUsrPwd}.</strong> A JavaBean accessor would make
     * the digest a discoverable property, and every property-walking mechanism in the stack - JSON
     * serialization, interface-based repository projections, bean mapping, diagnostic rendering -
     * would then be able to emit credential material with no code written to send it. The non-bean
     * name closes all of those at once. The reader remains public because the encoder in the service
     * layer needs the stored digest in order to verify a submitted credential against it.
     *
     * @return the stored digest as persisted
     */
    public String credentialDigest() {
        return secUsrPwd;
    }

    /**
     * Replaces the stored credential digest with another already-hashed value.
     *
     * <p>This method does not hash and does not verify - hashing deliberately lives outside this
     * package - but it does <strong>refuse</strong>. The argument must be structurally a BCrypt
     * digest: exactly {@value #BCRYPT_DIGEST_LENGTH} characters, a recognised version marker, a
     * two-digit cost between {@value #MINIMUM_BCRYPT_COST} and {@value #MAXIMUM_BCRYPT_COST}, and a
     * radix-64 tail. An
     * eight-character cleartext credential cannot satisfy that shape, which is what stops a caller
     * from writing one into a column that would otherwise accept it.
     *
     * <p><strong>Deliberately not named {@code setSecUsrPwd}.</strong> The same reasoning as for the
     * reader applies, with one addition: a bean-style setter invites automatic population from
     * request binding, and credential material must never be bound from a request into a persistent
     * entity.
     *
     * @param digest the already hashed credential to store
     * @throws IllegalArgumentException when the argument is not a structurally valid BCrypt digest of
     *                                  at least the minimum cost
     */
    public void replaceCredentialDigest(String digest) {
        this.secUsrPwd = requireBcryptDigest(digest);
    }

    /**
     * Returns the raw one-character role code, unmapped.
     *
     * @return the role code as stored, typically {@code A} or {@code U}
     */
    public String getSecUsrType() {
        return secUsrType;
    }

    /**
     * Replaces the raw one-character role code. Plain assignment, with no restriction to the
     * declared pair, so an unrecognised code round-trips instead of failing.
     *
     * @param secUsrType the role code to store
     */
    public void setSecUsrType(String secUsrType) {
        this.secUsrType = secUsrType;
    }

    /**
     * Refuses a sign-on identifier that is not exactly eight characters, immediately before the row is
     * inserted or updated.
     *
     * <p>No digit class applies: the copybook declares the field as eight <em>alphanumeric</em>
     * characters, and every seeded identity carries letters. Nothing here touches the credential, which
     * has its own rule - the entity refuses a value that is not a digest of the expected form - and that
     * rule is deliberately applied on assignment rather than here, because a credential must never reach
     * an instance in clear even briefly.
     *
     * <p><strong>Why a callback rather than the constructor or the setter.</strong> The persistence
     * provider hydrates a row by instantiating the entity and assigning its fields directly, so a
     * constructor guard is bypassed on every read while a callback sits on the one path every insert and
     * every update must take. It also leaves an instance built for an assertion, a fixture or an
     * intermediate calculation unrestricted - only one about to become a row is checked.
     *
     * <p>{@code V1__create_schema.sql} carries the same key rules a second time as check constraints, so
     * a bulk load or a migration script that never constructs an entity is refused as well.
     *
     * @throws IllegalArgumentException if an identifier is absent or is not exactly the width its layout
     *         declares
     */
    @PrePersist
    @PreUpdate
    void normalizeAndValidateBeforeWrite() {
        StoredValueRules.requireFixedWidth(secUsrId, SEC_USR_ID_WIDTH, "secUsrId");
    }

    /**
     * Compares on the primary key alone. The key is an assigned business identifier rather than a
     * generated one, so it is stable from construction onward and primary-key equality is
     * well-defined for both new and managed instances.
     *
     * <p>The stored digest is deliberately excluded. Including it would make identity depend on
     * credential material and would break {@code Set} and {@code Map} membership across a
     * credential change, even though the identity itself is unchanged.
     *
     * @param o the object to compare with
     * @return {@code true} when {@code o} is a {@code UserSecurity} with an equal sign-on
     *         identifier
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserSecurity other)) {
            return false;
        }
        return Objects.equals(this.secUsrId, other.secUsrId);
    }

    /**
     * Hashes the primary key alone, consistently with {@link #equals(Object)}, and null-safely for
     * an instance whose key has not yet been assigned. Credential material never enters a
     * hash-bucket computation.
     *
     * @return the hash code of the sign-on identifier
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(secUsrId);
    }

    /**
     * Renders the sign-on identifier and nothing else.
     *
     * <p>Every other field of this entity is sensitive - the digest is credential material, both
     * names are personal data and the role code is an authorization signal - so none of them appears
     * here, in any form, masked or otherwise. The identifier is retained because it is an account
     * identifier rather than a secret and is what makes a diagnostic line about a sign-on identity
     * useful at all.
     *
     * <p>This override exists rather than relying on the inherited rendering. The inherited form is
     * harmless today, but its harmlessness is an accident of the base class rather than a stated
     * property of this one: a field added later, or a future decision to render fields reflectively,
     * would leak silently. Stating the safe rendering explicitly makes the guarantee belong to this
     * class, and it fails a review visibly if it is ever widened.
     *
     * @return a representation carrying the sign-on identifier alone
     */
    @Override
    public String toString() {
        return "UserSecurity[secUsrId=" + secUsrId + "]";
    }

    /**
     * Rejects any credential value that is not structurally a BCrypt digest, so that cleartext cannot
     * be stored in a column wide enough to hold it.
     *
     * <p>Four conditions must all hold. The value must be non-null and exactly
     * {@value #BCRYPT_DIGEST_LENGTH} characters long; it must open with one of the recognised version
     * markers; the two characters after that marker must be digits forming a cost between
     * {@value #MINIMUM_BCRYPT_COST} and {@value #MAXIMUM_BCRYPT_COST} inclusive, followed by a
     * separator; and the remaining 53 characters must all come from BCrypt's radix-64 alphabet. The check is structural only - it does not hash, does not
     * verify and does not need an encoder - so it adds no dependency to this layer.
     *
     * <p><strong>The rejection message never contains the offending value</strong>, because a rejected
     * value is by definition likely to be the very cleartext credential the caller should not have
     * had, and an exception message is one of the surfaces most likely to reach a log.
     *
     * @param digest the candidate digest
     * @return the digest, unchanged, when it is acceptable
     * @throws IllegalArgumentException when the candidate is not a valid BCrypt digest
     */
    private static String requireBcryptDigest(String digest) {
        if (digest == null) {
            throw new IllegalArgumentException(
                    "the stored credential must be a BCrypt digest and must not be null");
        }
        if (digest.length() != BCRYPT_DIGEST_LENGTH) {
            throw new IllegalArgumentException("the stored credential must be a BCrypt digest of "
                    + BCRYPT_DIGEST_LENGTH + " characters, but a value of length " + digest.length()
                    + " was supplied; storing a cleartext credential is not permitted");
        }
        boolean versionRecognised = false;
        for (String marker : BCRYPT_VERSION_MARKERS) {
            if (digest.startsWith(marker)) {
                versionRecognised = true;
                break;
            }
        }
        if (!versionRecognised) {
            throw new IllegalArgumentException("the stored credential must be a BCrypt digest opening"
                    + " with a recognised version marker; storing a cleartext credential is not"
                    + " permitted");
        }
        final char costTens = digest.charAt(4);
        final char costUnits = digest.charAt(5);
        if (costTens < '0' || costTens > '9' || costUnits < '0' || costUnits > '9'
                || digest.charAt(6) != '$') {
            throw new IllegalArgumentException("the stored credential must be a BCrypt digest whose"
                    + " version marker is followed by a two-digit cost and a separator");
        }
        final int cost = (costTens - '0') * 10 + (costUnits - '0');
        if (cost < MINIMUM_BCRYPT_COST || cost > MAXIMUM_BCRYPT_COST) {
            throw new IllegalArgumentException("the stored credential must be a BCrypt digest with a"
                    + " cost between " + MINIMUM_BCRYPT_COST + " and " + MAXIMUM_BCRYPT_COST
                    + " inclusive, but " + cost + " was supplied");
        }
        for (int index = 7; index < BCRYPT_DIGEST_LENGTH; index++) {
            if (BCRYPT_RADIX_64_ALPHABET.indexOf(digest.charAt(index)) < 0) {
                throw new IllegalArgumentException("the stored credential must be a BCrypt digest"
                        + " whose salt and hash use the BCrypt radix-64 alphabet");
            }
        }
        return digest;
    }
}
