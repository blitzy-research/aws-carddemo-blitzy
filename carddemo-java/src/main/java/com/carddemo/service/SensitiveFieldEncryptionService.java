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

import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.carddemo.util.SensitiveFieldCodec;

/**
 * The single place in this module where key material for regulated customer fields is resolved, and
 * the only sanctioned producer and reader of the protected values those fields are stored as.
 *
 * <h2>What it owns</h2>
 *
 * <p>The legacy customer record carries two regulated identifiers in clear text - a nine-digit
 * national identifier at offset 279 of the 500-byte image and a twenty-character government-issued
 * identifier at offset 288. Neither may reach a relational column in that form. This service turns a
 * cleartext identifier into an authenticated, versioned envelope on the way in and back into
 * cleartext on the way out, so that the customer entity only ever holds a protected value and the
 * database only ever stores one.
 *
 * <p>The cryptography itself lives in the utility layer, in {@link SensitiveFieldCodec}, which is
 * pure and framework-free. This class adds exactly one thing to it: the key, resolved from
 * configuration once at construction. That split is deliberate - the codec can be exercised
 * exhaustively without a Spring context, and the key never appears in a signature, a return value or
 * a log line.
 *
 * <h2>The key is externalised, and has no fallback</h2>
 *
 * <p>The key is bound from {@value #FIELD_ENCRYPTION_KEY_PROPERTY} as Base64 text and must decode to
 * exactly {@link SensitiveFieldCodec#KEY_LENGTH_BYTES} bytes. No default is declared anywhere in
 * this class, and the shared configuration file declares no value either: the local and test
 * overlays bind a throwaway development value, and the production overlay binds an environment
 * reference with no fallback. A deployment that forgets the variable therefore fails to start
 * instead of silently encrypting under a value that looks deliberate, which is the whole point of
 * the no-fallback rule.
 *
 * <p>Three failure modes are separated at construction, because they have different operational
 * causes and different fixes: the property is absent or blank, the property is present but is not
 * Base64, and the property decodes but is the wrong length. Each is reported by naming the property
 * and the condition. None of them echoes the configured value, not even truncated.
 *
 * <h2>Null tolerance mirrors the schema</h2>
 *
 * <p>The national-identifier column is the one intentionally nullable column in the schema, because
 * the reference-data seed leaves it null rather than embedding real identifiers in a checked-in
 * artifact. {@link #protectNullable(String)} and {@link #revealNullable(String)} therefore map
 * {@code null} to {@code null} rather than to an empty envelope or to the literal text
 * {@code "null"}, so a seeded row round-trips unchanged. The government-issued identifier column is
 * not nullable, and the non-nullable methods are the ones used for it.
 *
 * <h2>Every sealed value is bound to the column it was sealed for</h2>
 *
 * <p>An envelope on its own says nothing about where it belongs, so a value lifted out of one
 * protected column and written into another would decrypt cleanly and read as though it had always
 * been there. The field-bound methods close that route: {@link #protect(String, String)} seals the
 * column's own name alongside the cleartext, separated by the ASCII unit separator, and
 * {@link #reveal(String, String)} refuses an envelope whose recovered binding is not the column it
 * was asked for. The binding is inside the authenticated payload, so it cannot be edited without
 * failing authentication first, and the separator is a control character the regulated fields cannot
 * contain, so no cleartext value can forge one.</p>
 *
 * <p>The unbound {@link #protect(String)} and {@link #reveal(String)} remain for values that are not
 * column-scoped; a caller sealing a column uses the field-bound pair, and
 * {@link #requireProtectedOrNull(String, String)} is the guard that keeps cleartext from reaching a
 * protected column at all. Neither form will seal a value that is already an envelope, because
 * sealing twice produces a value that reads back as ciphertext and is unrecoverable in practice.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Immutable after construction and safe to share. The key is held as a private copy of the
 * decoded material, is never handed out, and is never mutated; the codec is stateless.
 *
 * <h2>Provenance</h2>
 *
 * <p>Derived from the AWS CardDemo z/OS mainframe application at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Encryption at rest is a documented
 * divergence from the legacy design rather than a translation of it, and it is recorded as such in
 * {@code docs/decision-log.md}.
 */
@Service
public class SensitiveFieldEncryptionService {

    /**
     * Configuration key carrying the Base64-encoded 256-bit field-encryption key. Held as a constant
     * so the binding expression on the constructor and the diagnostics that name the key cannot
     * drift apart.
     */
    public static final String FIELD_ENCRYPTION_KEY_PROPERTY = "carddemo.security.field-encryption.key";

    /**
     * Canonical binding name of the customer national-identifier column, being the table and column
     * name joined by a dot. Held as a constant so that the value sealed into an envelope and the value
     * checked when it is read back cannot drift apart, and so that a caller cannot bind the column
     * under a spelling of its own.
     */
    public static final String CUSTOMER_SSN_FIELD = "customer.cust_ssn";

    /**
     * Separator between the bound field name and the cleartext inside a field-bound envelope. The
     * ASCII unit separator is used because it cannot appear in a column name and cannot appear in any
     * regulated value this service seals, so the first occurrence in a recovered payload always marks
     * the end of the binding.
     */
    private static final char FIELD_BINDING_SEPARATOR = '\u001F';

    /**
     * Diagnostic channel. It records that protection is configured and the key length observed; it
     * never records key material, cleartext or ciphertext.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(SensitiveFieldEncryptionService.class);

    /**
     * Decoded key material, held as a private copy of the decoded array so that the caller's array
     * cannot be mutated underneath the service and the service's copy cannot escape.
     */
    private final byte[] key;

    /**
     * Resolves and validates the field-encryption key.
     *
     * @param base64Key Base64-encoded key material bound from
     *                  {@value #FIELD_ENCRYPTION_KEY_PROPERTY}
     * @throws IllegalStateException when the property is blank, is not valid Base64, or does not
     *                               decode to exactly {@link SensitiveFieldCodec#KEY_LENGTH_BYTES}
     *                               bytes. The message names the property and the condition and
     *                               never the configured value
     */
    public SensitiveFieldEncryptionService(
            @Value("${" + FIELD_ENCRYPTION_KEY_PROPERTY + "}") final String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("configuration property " + FIELD_ENCRYPTION_KEY_PROPERTY
                    + " is required and must not be blank; no default is provided");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException("configuration property " + FIELD_ENCRYPTION_KEY_PROPERTY
                    + " must be Base64-encoded key material", notBase64);
        }
        if (decoded.length != SensitiveFieldCodec.KEY_LENGTH_BYTES) {
            Arrays.fill(decoded, (byte) 0);
            throw new IllegalStateException("configuration property " + FIELD_ENCRYPTION_KEY_PROPERTY
                    + " must decode to exactly " + SensitiveFieldCodec.KEY_LENGTH_BYTES
                    + " bytes of key material");
        }
        this.key = decoded;
        LOGGER.info("Customer field encryption is configured with a {}-bit key",
                SensitiveFieldCodec.KEY_LENGTH_BYTES * Byte.SIZE);
    }

    /**
     * Protects a required cleartext value, returning the envelope to be stored.
     *
     * @param cleartext the value to protect; must not be {@code null}
     * @return the {@code ENC1} envelope carrying the value
     * @throws NullPointerException     when the cleartext is {@code null}; callers holding an
     *                                  optional value use {@link #protectNullable(String)} instead
     * @throws IllegalArgumentException when the value is already an envelope of this scheme, because
     *                                  sealing it again would make it unrecoverable in practice
     */
    public String protect(final String cleartext) {
        Objects.requireNonNull(cleartext, "cleartext must not be null");
        requireNotAlreadyProtected(cleartext, null);
        return SensitiveFieldCodec.protect(cleartext, this.key);
    }

    /**
     * Protects an optional cleartext value, mapping {@code null} to {@code null}.
     *
     * <p>Used for the nullable national-identifier column, where absence is a legitimate stored
     * state and must round-trip as absence.
     *
     * @param cleartext the value to protect, or {@code null} when no value is held
     * @return the envelope, or {@code null} when the input was {@code null}
     */
    public String protectNullable(final String cleartext) {
        return cleartext == null ? null : protect(cleartext);
    }

    /**
     * Reads a required protected value back to cleartext.
     *
     * @param envelope the stored envelope; must not be {@code null}
     * @return the original cleartext
     * @throws NullPointerException     when the envelope is {@code null}
     * @throws IllegalArgumentException when the stored value is not an envelope of this scheme, which
     *                                 indicates unprotected data reached the column
     * @throws IllegalStateException    when authentication fails, meaning the value was altered or
     *                                 the configured key is not the one it was sealed with
     */
    public String reveal(final String envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        return SensitiveFieldCodec.reveal(envelope, this.key);
    }

    /**
     * Reads an optional protected value back to cleartext, mapping {@code null} to {@code null}.
     *
     * @param envelope the stored envelope, or {@code null} when no value is held
     * @return the original cleartext, or {@code null} when the input was {@code null}
     */
    public String revealNullable(final String envelope) {
        return envelope == null ? null : reveal(envelope);
    }

    /**
     * Reports whether a stored value carries the structural shape of this scheme's envelope, without
     * attempting to decrypt it.
     *
     * <p>Offered so that a caller inspecting persisted data - a migration check, a diagnostic, a
     * fixture assertion - can distinguish protected from unprotected content using the same rule the
     * entity's persistence guard applies, rather than a second, possibly divergent one.
     *
     * @param candidate the value to inspect; {@code null} is answered {@code false}
     * @return {@code true} only when the candidate is shaped like an envelope of this scheme
     */
    public boolean isProtected(final String candidate) {
        return SensitiveFieldCodec.hasEnvelopeShape(candidate);
    }

    /**
     * Protects a required cleartext value and binds it to the column it is being stored in.
     *
     * <p>The column's name is sealed inside the authenticated payload alongside the value, so an
     * envelope written for one column cannot later be read as another column's value. Use this form
     * whenever the destination is a protected column; {@link #CUSTOMER_SSN_FIELD} names the one such
     * column the migrated schema has.
     *
     * @param fieldName the binding name of the destination column, conventionally
     *                  {@code table.column}; must not be {@code null}, blank, or contain the binding
     *                  separator
     * @param cleartext the value to protect; must not be {@code null}
     * @return the {@code ENC1} envelope carrying the bound value
     * @throws NullPointerException     when the cleartext is {@code null}; callers holding an
     *                                  optional value use {@link #protectNullable(String, String)}
     * @throws IllegalArgumentException when the field name is unusable, or when the value is already
     *                                  an envelope of this scheme
     */
    public String protect(final String fieldName, final String cleartext) {
        requireUsableFieldName(fieldName);
        Objects.requireNonNull(cleartext, "cleartext must not be null");
        requireNotAlreadyProtected(cleartext, fieldName);
        return SensitiveFieldCodec.protect(bind(fieldName, cleartext), this.key);
    }

    /**
     * Protects an optional cleartext value bound to a column, mapping {@code null} to {@code null}.
     *
     * @param fieldName the binding name of the destination column
     * @param cleartext the value to protect, or {@code null} when no value is held
     * @return the bound envelope, or {@code null} when the input was {@code null}
     * @throws IllegalArgumentException when the field name is unusable, or when the value is already
     *                                  an envelope of this scheme
     */
    public String protectNullable(final String fieldName, final String cleartext) {
        requireUsableFieldName(fieldName);
        return cleartext == null ? null : protect(fieldName, cleartext);
    }

    /**
     * Reads a required field-bound value back to cleartext, refusing an envelope written for another
     * column.
     *
     * <p>Authentication happens first, so a tampered envelope fails before its binding is examined.
     * The binding check that follows is what makes a cross-column replay visible: the recovered
     * payload must open with this column's name, and the value returned is everything after the
     * separator.
     *
     * @param fieldName the binding name the envelope must carry
     * @param envelope  the stored envelope; must not be {@code null}
     * @return the original cleartext
     * @throws NullPointerException     when the envelope is {@code null}
     * @throws IllegalArgumentException when the field name is unusable, or when the stored value is
     *                                  not an envelope of this scheme
     * @throws IllegalStateException    when authentication fails, or when the envelope carries a
     *                                  binding other than {@code fieldName}, meaning it was not
     *                                  written for this column
     */
    public String reveal(final String fieldName, final String envelope) {
        requireUsableFieldName(fieldName);
        Objects.requireNonNull(envelope, "envelope must not be null");
        final String recovered = SensitiveFieldCodec.reveal(envelope, this.key);
        final int separator = recovered.indexOf(FIELD_BINDING_SEPARATOR);
        if (separator < 0 || !fieldName.equals(recovered.substring(0, separator))) {
            throw new IllegalStateException("the stored value carries a field binding other than "
                    + fieldName + "; it was not written for this field");
        }
        return recovered.substring(separator + 1);
    }

    /**
     * Reads an optional field-bound value back to cleartext, mapping {@code null} to {@code null}.
     *
     * @param fieldName the binding name the envelope must carry
     * @param envelope  the stored envelope, or {@code null} when no value is held
     * @return the original cleartext, or {@code null} when the input was {@code null}
     */
    public String revealNullable(final String fieldName, final String envelope) {
        requireUsableFieldName(fieldName);
        return envelope == null ? null : reveal(fieldName, envelope);
    }

    /**
     * Passes a value through only when it is already protected, so cleartext cannot reach a protected
     * column.
     *
     * <p>This is the persistence-boundary guard. A caller assembling a row calls it with the value it
     * intends to store: {@code null} is a legitimate stored state for the nullable national-identifier
     * column and passes, an envelope passes, and anything else is refused before the write rather than
     * discovered afterwards in a column that was supposed to hold none.
     *
     * @param fieldName the binding name of the destination column, used in the failure message
     * @param value     the value about to be stored, which may be {@code null}
     * @return the value unchanged when it is {@code null} or already an envelope
     * @throws IllegalArgumentException when the field name is unusable, or when the value is present
     *                                 and is not an envelope of this scheme
     */
    public String requireProtectedOrNull(final String fieldName, final String value) {
        requireUsableFieldName(fieldName);
        if (value == null || isProtected(value)) {
            return value;
        }
        throw new IllegalArgumentException("refusing to store an unprotected value in field "
                + fieldName + "; seal it with protect(String, String) first");
    }

    /**
     * Composes the bound payload as the field name, the separator and the cleartext.
     *
     * @param fieldName the already validated binding name
     * @param cleartext the already validated cleartext
     * @return the payload to seal
     */
    private static String bind(final String fieldName, final String cleartext) {
        return fieldName + FIELD_BINDING_SEPARATOR + cleartext;
    }

    /**
     * Rejects a binding name that is absent, blank, or able to forge a binding boundary.
     *
     * @param fieldName the candidate binding name
     * @throws IllegalArgumentException when the name is {@code null}, blank, or contains the binding
     *                                 separator
     */
    private static void requireUsableFieldName(final String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("a field name is required and must not be blank");
        }
        if (fieldName.indexOf(FIELD_BINDING_SEPARATOR) >= 0) {
            throw new IllegalArgumentException(
                    "a field name must not contain the field-binding separator");
        }
    }

    /**
     * Rejects a value that is already an envelope of this scheme.
     *
     * <p>Sealing an envelope again yields a value that satisfies every structural check and recovers
     * to ciphertext rather than to the original, which is unrecoverable in practice once the inner
     * layer's provenance is lost. Refusing is therefore the only safe answer, and it is a strictly
     * better failure than the silent corruption the alternative produces.
     *
     * @param cleartext the value about to be sealed
     * @param fieldName the destination column's binding name, or {@code null} for an unbound seal
     * @throws IllegalArgumentException when the value is already an envelope of this scheme
     */
    private static void requireNotAlreadyProtected(final String cleartext, final String fieldName) {
        if (SensitiveFieldCodec.hasEnvelopeShape(cleartext)) {
            throw new IllegalArgumentException("the value presented"
                    + (fieldName == null ? "" : " for field " + fieldName) + " is already a "
                    + SensitiveFieldCodec.ENVELOPE_PREFIX
                    + " envelope; sealing it a second time would make it unreadable");
        }
    }
}
