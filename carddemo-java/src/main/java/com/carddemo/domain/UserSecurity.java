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
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * Sign-on identity and role source: the Java translation of the legacy {@code SEC-USER-DATA}
 * record declared in {@code app/cpy/CSUSR01Y.cpy} (L17), whose fields sum to exactly 80 bytes.
 * That copybook is the only one of the eleven entity copybooks that carries the Apache-2.0 header
 * itself, at L1 through L16, so it independently corroborates the header block reproduced above.
 *
 * <h2>Verified 80-byte record layout</h2>
 *
 * <p>Zero-based offset and width, recomputed field by field from the copybook. Only the first five
 * fields become columns.
 *
 * <table>
 *   <caption>{@code SEC-USER-DATA} field positions</caption>
 *   <tr><th>Record field</th><th>Picture</th><th>Offset</th><th>Width</th><th>Column</th></tr>
 *   <tr><td>{@code SEC-USR-ID}</td><td>{@code X(08)}</td><td>0</td><td>8</td>
 *       <td>{@code sec_usr_id}, the primary key</td></tr>
 *   <tr><td>{@code SEC-USR-FNAME}</td><td>{@code X(20)}</td><td>8</td><td>20</td>
 *       <td>{@code sec_usr_fname}</td></tr>
 *   <tr><td>{@code SEC-USR-LNAME}</td><td>{@code X(20)}</td><td>28</td><td>20</td>
 *       <td>{@code sec_usr_lname}</td></tr>
 *   <tr><td>{@code SEC-USR-PWD}</td><td>{@code X(08)}</td><td>48</td><td>8</td>
 *       <td>{@code sec_usr_pwd}, widened to 60 - see the parity exception below</td></tr>
 *   <tr><td>{@code SEC-USR-TYPE}</td><td>{@code X(01)}</td><td>56</td><td>1</td>
 *       <td>{@code sec_usr_type}</td></tr>
 *   <tr><td>{@code SEC-USR-FILLER}</td><td>{@code X(23)}</td><td>57</td><td>23</td>
 *       <td>not persisted</td></tr>
 * </table>
 *
 * <p>Mapped bytes therefore end at offset 57, and the trailing 23 bytes carry no information. The
 * filler is <em>named</em> in the source - {@code SEC-USR-FILLER} rather than a bare
 * {@code FILLER} - which changes nothing about persistence: there is no filler field here and no
 * filler column in the schema. It is padding, reconstructed on output from the declared record
 * width, and the only class that ever addresses it is the fixed-width mapper
 * {@code com.carddemo.util.UserSecurityRecordMapper}. Byte offsets live exclusively in that mapper.
 * This class carries JPA column widths and nothing positional.
 *
 * <h2>Legacy dataset provenance</h2>
 *
 * <p>The provisioning job {@code app/jcl/DUSRSECJ.jcl} builds this data in three steps, and all
 * three were read from the checkout rather than inferred.
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
 * violate the binding no-hardcoded-credentials requirement at the same time, so this is the one
 * place in the eleven-entity schema where fidelity is deliberately broken: {@code sec_usr_pwd} is
 * {@code VARCHAR(60)}, sized for a BCrypt digest, and never the legacy width of 8. It is the
 * single intentional width divergence in the module - every other column width equals its picture
 * width. That divergence exists <em>because a binding requirement demands it</em>, and it is
 * recorded as the flagship security entry in {@code docs/decision-log.md} rather than silently
 * applied.
 *
 * <p>Hashing, verification and every other credential operation live outside this package, in
 * {@code com.carddemo.service.AuthenticationService} and {@code com.carddemo.config.SecurityConfig}.
 * This entity performs no hashing, no verification and no comparison of the stored digest: the
 * verifier is a password encoder, not an equality test. Keeping that logic outside {@code domain}
 * is what guarantees a cleartext value can never reach an instance of this class.
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
 *       stream above - stores each credential as a BCrypt digest, and is profile-scoped to local
 *       and test only, so no production deployment ever receives a seeded login.</li>
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
     * Eight-character sign-on identifier; record offset 0, width 8. The primary key, assigned by
     * the application from the legacy business key and never generated. Stored exactly as read:
     * no trimming, padding or case folding is applied anywhere in this class.
     */
    @Id
    @Column(name = "sec_usr_id", length = 8, nullable = false)
    private String secUsrId;

    /** Given name; record offset 8, width 20. */
    @Column(name = "sec_usr_fname", length = 20, nullable = false)
    private String secUsrFname;

    /** Family name; record offset 28, width 20. */
    @Column(name = "sec_usr_lname", length = 20, nullable = false)
    private String secUsrLname;

    /**
     * The stored BCrypt digest of the sign-on credential - <strong>never a cleartext value and
     * never a password</strong>. The legacy field at record offset 48 is eight cleartext
     * characters; this column is 60 characters wide to hold a digest instead, which is the
     * deliberate, documented parity exception described in the class documentation.
     *
     * <p>The field name matches the legacy field name so the traceability row back to
     * {@code SEC-USR-PWD} stays findable even though the content has changed. Digest production
     * and digest verification live outside this package, in
     * {@code com.carddemo.service.AuthenticationService} and
     * {@code com.carddemo.config.SecurityConfig}; nothing in this class hashes, verifies or
     * compares the value, and it is deliberately left uninitialised so that no default, fallback
     * or placeholder credential can exist in source.
     */
    @Column(name = "sec_usr_pwd", length = 60, nullable = false)
    private String secUsrPwd;

    /**
     * Role code; record offset 56, width 1. Held as a raw single character rather than an
     * enumerated field: {@code A} selects the administrative role and {@code U} the standard one,
     * and translation to {@code com.carddemo.domain.enums.UserType} happens at the service layer.
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
     *                    by the service layer. No cleartext value may be passed here - this
     *                    constructor performs no hashing and would store whatever it is given
     * @param secUsrType  raw one-character role code, {@code A} or {@code U}; an unrecognised code
     *                    is accepted, matching the legacy unconditional routing alternative
     */
    public UserSecurity(String secUsrId,
                        String secUsrFname,
                        String secUsrLname,
                        String secUsrPwd,
                        String secUsrType) {
        this.secUsrId = secUsrId;
        this.secUsrFname = secUsrFname;
        this.secUsrLname = secUsrLname;
        this.secUsrPwd = secUsrPwd;
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
     * class can return a cleartext credential, because none is ever stored. Verification is
     * performed by the encoder in the service layer, not by comparing this value.
     *
     * @return the stored digest as persisted
     */
    public String getSecUsrPwd() {
        return secUsrPwd;
    }

    /**
     * Replaces the stored digest. Plain assignment: this setter does not hash, does not verify and
     * does not validate the shape of its argument, so the caller must supply a value that has
     * already been hashed by the service layer. Hashing deliberately lives outside this package.
     *
     * @param secUsrPwd the already hashed credential to store
     */
    public void setSecUsrPwd(String secUsrPwd) {
        this.secUsrPwd = secUsrPwd;
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

    // toString is deliberately not overridden: every field of this entity is sensitive - the digest
    // is credential material, both names are personal data and the role code is an authorization
    // signal - so no rendering of an instance may be made available to a logger by default.
}
