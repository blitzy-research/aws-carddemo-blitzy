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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Replaces a sensitive log value with a fixed marker and a process-scoped, non-reversible
 * correlation token.
 *
 * <h2>Why a marker alone is insufficient</h2>
 *
 * <p>A fixed marker prevents disclosure but removes the ability to determine whether two diagnostic
 * events concern the same record. This class keeps that operational property without retaining the
 * value: it authenticates the UTF-8 bytes with a random key generated when this class is initialized
 * and publishes only a truncated hexadecimal HMAC. The same value therefore produces the same token
 * during one process lifetime, while a restart produces unrelated tokens.
 *
 * <h2>Why the key is process-local</h2>
 *
 * <p>The key is neither configured nor persisted. That prevents the log stream from becoming a
 * durable pseudonymous database and removes any secret-management dependency from logging. A reader
 * of the logs cannot perform an offline dictionary comparison because the key never leaves process
 * memory.
 *
 * <h2>Output contract</h2>
 *
 * <p>A present value renders as {@code [REDACTED] ref=<hex>}; an absent value renders as the fixed
 * {@value #REDACTED} marker alone. The hexadecimal token contains only lower-case ASCII digits and
 * letters, so a caller-controlled value cannot inject whitespace, delimiters or control bytes into a
 * structured log record.
 */
public final class SensitiveLogRedactor {

    /** Fixed marker published in place of every sensitive value. */
    public static final String REDACTED = "[REDACTED]";

    /** Required JCA digest; present in every conforming Java implementation. */
    private static final String DIGEST_ALGORITHM = "SHA-256";

    /** Number of random bytes in the process-local HMAC key. */
    private static final int PROCESS_KEY_BYTES = 32;

    /** SHA-256's compression-block width, used by the standard HMAC construction. */
    private static final int HMAC_BLOCK_BYTES = 64;

    /** Number of HMAC bytes retained in the correlation token. */
    private static final int CORRELATION_TOKEN_BYTES = 12;

    /** Process-local key: generated once, held as immutable text, never configured, logged or persisted. */
    private static final String PROCESS_KEY_HEX = HexFormat.of().formatHex(generateProcessKey());

    private SensitiveLogRedactor() {
        throw new AssertionError("SensitiveLogRedactor is a utility holder and must not be instantiated");
    }

    /**
     * Replaces one sensitive value with the fixed marker and its process-scoped correlation token.
     *
     * @param sensitiveValue the value that must not reach the log; may be {@code null}
     * @return {@value #REDACTED} when the value is absent, otherwise the marker plus a lower-case
     *         hexadecimal HMAC token
     */
    public static String redact(final String sensitiveValue) {
        if (sensitiveValue == null) {
            return REDACTED;
        }
        return REDACTED + " ref=" + correlationToken(sensitiveValue);
    }

    private static String correlationToken(final String sensitiveValue) {
        try {
            final MessageDigest sha256 = MessageDigest.getInstance(DIGEST_ALGORITHM);
            final byte[] key = HexFormat.of().parseHex(PROCESS_KEY_HEX);
            final byte[] innerPad = Arrays.copyOf(key, HMAC_BLOCK_BYTES);
            final byte[] outerPad = Arrays.copyOf(key, HMAC_BLOCK_BYTES);
            for (int index = 0; index < HMAC_BLOCK_BYTES; index++) {
                innerPad[index] ^= 0x36;
                outerPad[index] ^= 0x5c;
            }
            sha256.update(innerPad);
            final byte[] innerDigest =
                    sha256.digest(sensitiveValue.getBytes(StandardCharsets.UTF_8));
            sha256.update(outerPad);
            final byte[] digest = sha256.digest(innerDigest);
            return HexFormat.of().formatHex(digest, 0, CORRELATION_TOKEN_BYTES);
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Required HMAC algorithm is unavailable", unavailable);
        }
    }

    private static byte[] generateProcessKey() {
        final byte[] key = new byte[PROCESS_KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
