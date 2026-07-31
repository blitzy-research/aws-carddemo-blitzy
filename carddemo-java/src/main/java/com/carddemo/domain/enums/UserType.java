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
package com.carddemo.domain.enums;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * CardDemo user type: administrator versus standard user.
 *
 * <p>Translated from the two level-88 condition names declared on the communication-area field
 * {@code CDEMO-USER-TYPE} ({@code PIC X(01)}, {@code app/cpy/COCOM01Y.cpy} L26). The administrator
 * condition name {@code CDEMO-USRTYP-ADMIN} carries code {@code A} (L27) and the standard-user
 * condition name {@code CDEMO-USRTYP-USER} carries code {@code U} (L28). Those are the only two
 * codes the estate declares, so this type has exactly two constants and no synthetic third state.
 *
 * <p>The persisted origin of the value is {@code SEC-USR-TYPE} ({@code PIC X(01)},
 * {@code app/cpy/CSUSR01Y.cpy} L22): the single character at byte offset 57 of the 80-byte
 * {@code SEC-USER-DATA} record, whose layout sums exactly, as id 8 + first name 20 + last name 20
 * + password 8 + type 1 + filler 23 = 80. The provisioning job {@code app/jcl/DUSRSECJ.jcl} seeds
 * ten user records in stream, five of type {@code A} and five of type {@code U}.
 *
 * <h2>Routing tolerance: do not tighten this</h2>
 *
 * <p>Sign-on ({@code app/cbl/COSGN00C.cbl}) moves {@code SEC-USR-TYPE} straight into
 * {@code CDEMO-USER-TYPE} at L227, tests the administrator condition at L230, and when that test
 * holds transfers control to the administrative menu program {@code COADM01C}. The alternative at
 * L235 is an <strong>unconditional</strong> {@code ELSE} that transfers control to the main menu
 * program {@code COMEN01C}, and the construct closes at L240. That alternative is <em>not</em> a
 * second test of the standard-user condition: there is no third branch and no error path for a
 * code the estate never declared.
 *
 * <p>Two consequences bind every member below.
 *
 * <ul>
 *   <li>Only code {@code A} is an administrator. <em>Every</em> other value, including an
 *       unexpected one such as {@code X}, reaches the main menu. It does not raise an error and it
 *       does not abort sign-on.</li>
 *   <li>{@link #fromCode(String)} therefore never throws. Absence is modelled explicitly as
 *       {@link Optional#empty()}, and an absent result can never answer {@code true} to
 *       {@link #isAdmin()} because there is no instance on which to call it, which is precisely
 *       what the unconditional alternative encodes. Callers compose the two as
 *       {@code fromCode(raw).map(UserType::isAdmin).orElse(false)}.</li>
 * </ul>
 *
 * <p>A throwing lookup would be the more conventional Java shape and it would be wrong: it would
 * abort a sign-on that the legacy program completes. Faithful beats idiomatic.
 *
 * <h2>Representation</h2>
 *
 * <p>The code is carried as a one-character {@link String} rather than as a {@code char}, used
 * consistently for the field, the accessor, the lookup index key and the lookup parameter. The
 * source field is a single character, but every boundary that supplies it hands over a
 * {@code String}: the user-security entity persists {@code sec_usr_type} as {@code VARCHAR(1)},
 * the fixed-width record mapper slices one byte out of the 80-byte image, and the REST layer
 * carries text. A {@code String} parameter can be screened for {@code null} and for any
 * unrecognised value inside this type, so no caller needs a conversion step that could itself
 * throw on a blank or absent code.
 *
 * <h2>Boundaries</h2>
 *
 * <p>This type is deliberately <strong>not</strong> a persistence field type. The user-security
 * entity stores the raw one-character column and translation happens in the service layer, so this
 * file carries no persistence annotation of any kind, no enumerated-mapping annotation and no
 * attribute converter: mapping by constant name would overflow a one-character column, mapping by
 * ordinal would require an integer column, and either would push translation logic down into the
 * domain layer.
 *
 * <p>Screen-flow state is out of scope. The program-context condition names that sit beside the
 * user type in {@code app/cpy/COCOM01Y.cpy} (L29 to L31) model presentation state and belong to
 * {@code com.carddemo.api.dto.NavigationContext}, not to this package. The password field of the
 * user record ({@code app/cpy/CSUSR01Y.cpy} L21) is likewise absent here; it becomes a hashed
 * column on the user-security entity.
 *
 * <p>Provenance of the migrated behaviour, cited and never transcribed: source checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated {@code 2022-07-19}.
 */
public enum UserType {

    /**
     * Administrator. Carries code {@code A}, the value of the level-88 condition name
     * {@code CDEMO-USRTYP-ADMIN} at {@code app/cpy/COCOM01Y.cpy} L27, and the only code that
     * reaches the administrative menu program at {@code app/cbl/COSGN00C.cbl} L230.
     */
    ADMIN("A"),

    /**
     * Standard user. Carries code {@code U}, the value of the level-88 condition name
     * {@code CDEMO-USRTYP-USER} at {@code app/cpy/COCOM01Y.cpy} L28.
     *
     * <p>It reaches the main menu program, but so does every code that is not {@code A}, because
     * the alternative at {@code app/cbl/COSGN00C.cbl} L235 is unconditional. This constant records
     * the declared standard-user value; it does not gate the main-menu route.
     */
    USER("U");

    /**
     * Immutable index from one-character code to user type, built once during class
     * initialization. It is never reassigned, never mutated and never exposed, so no caller can
     * add to, remove from or shadow the two codes the estate declares.
     */
    private static final Map<String, UserType> BY_CODE = indexByCode();

    /**
     * The raw one-character code, exactly as the level-88 value declares it and exactly as it is
     * persisted. Never {@code null} and always one character long.
     */
    private final String code;

    /**
     * @param code the raw one-character code this constant carries
     */
    UserType(String code) {
        this.code = code;
    }

    /**
     * Builds the immutable code index from the declared constants themselves, so that it cannot
     * fall out of step with them if the declared set is ever revisited.
     *
     * <p>Enum constants are created before any other static field of the type is initialized, so
     * {@link #values()} is already fully populated when this runs.
     *
     * @return an unmodifiable map from one-character code to user type
     */
    private static Map<String, UserType> indexByCode() {
        Map<String, UserType> index = new HashMap<>();
        for (UserType userType : values()) {
            index.put(userType.code, userType);
        }
        return Map.copyOf(index);
    }

    /**
     * Resolves a raw one-character code to its declared user type without ever throwing.
     *
     * <p>Returns {@link Optional#empty()} for {@code null}, for a blank or wrong-length value, and
     * for any code the estate does not declare. That includes a lower-case {@code a}, which is not
     * an administrator: sign-on compares the raw character and applies no case fold, so neither
     * does this lookup. Absence is a legitimate outcome rather than an error, because the legacy
     * program routes an unrecognised type to the main menu instead of failing on it, as described
     * in the class documentation. Screening {@code null} before probing the index is required
     * rather than defensive noise, because the unmodifiable map produced by
     * {@link Map#copyOf(Map)} rejects a {@code null} key.
     *
     * @param code the raw one-character code, as read from {@code SEC-USR-TYPE} or from the
     *             {@code sec_usr_type} column; may be {@code null}
     * @return the matching user type, or {@link Optional#empty()} when the code is absent or is
     *         not one of the two declared values
     */
    public static Optional<UserType> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_CODE.get(code));
    }

    /**
     * Returns the raw one-character code this constant carries.
     *
     * @return {@code "A"} for {@link #ADMIN}, {@code "U"} for {@link #USER}; never {@code null}
     */
    public String getCode() {
        return code;
    }

    /**
     * Reports whether this user type is the administrator, mirroring the condition tested at
     * {@code app/cbl/COSGN00C.cbl} L230.
     *
     * <p>True for {@link #ADMIN} only. Every other outcome is a non-administrator, including the
     * absent result of an unrecognised code, which is what the unconditional alternative at L235
     * encodes.
     *
     * @return {@code true} if and only if this is {@link #ADMIN}
     */
    public boolean isAdmin() {
        return this == ADMIN;
    }
}
