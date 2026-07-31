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
package com.carddemo.exception;

/**
 * Unchecked exception raised when a keyed read resolves to no record.
 *
 * <p>This type is the Java equivalent of two distinct facts in the legacy
 * z/OS CardDemo estate, and it deliberately serves both.</p>
 *
 * <ul>
 *   <li><strong>File status {@code "23"}</strong> - the VSAM indexed-read
 *       record-not-found code, published here as
 *       {@link #STATUS_RECORD_NOT_FOUND}. The estate compares that literal at
 *       exactly three sites: {@code CBACT04C} paragraph
 *       {@code 1200-GET-INTEREST-RATE} at lines 422 and 436, and
 *       {@code CBTRN02C} paragraph {@code 2700-UPDATE-TCATBAL} at line
 *       481.</li>
 *   <li><strong>The coarse I/O outcome value 12</strong> - the error arm of
 *       the normalisation every batch program applies before it branches, in
 *       which {@code "00"} becomes 0, {@code "10"} becomes 16 and anything
 *       else becomes 12 (see {@code CBACT01C} paragraph
 *       {@code 1000-ACCTFILE-GET-NEXT}, lines 92 to 116). A not-found that
 *       the caller does not excuse lands in that arm.</li>
 * </ul>
 *
 * <p>That coarse normalisation is intentionally <em>not</em> implemented here.
 * Deciding whether a status is benign, terminal or end-of-file is caller
 * control flow and belongs above this layer; this package holds exception
 * types only.</p>
 *
 * <p><strong>Callers that must not let this propagate</strong></p>
 *
 * <p>Not-found is frequently benign in the legacy design, so this exception is
 * shaped to be caught and continued from rather than to unwind a job. Two
 * callers treat it as a normal, expected outcome and therefore must not allow
 * it to escape. Both behaviours below were verified directly against the
 * COBOL, and both must be reproduced exactly.</p>
 *
 * <ol>
 *   <li>{@code InterestCalculationService} - the disclosure-group rate lookup
 *       translated from {@code CBACT04C}. Paragraph
 *       {@code 1200-GET-INTEREST-RATE} folds status {@code "23"} in with
 *       {@code "00"} as a non-error outcome, then substitutes the group key
 *       {@code DEFAULT}, space-padded to its full ten-character width, and
 *       performs the lookup again. That retry happens <strong>exactly
 *       once</strong>: paragraph {@code 1200-A-GET-DEFAULT-INT-RATE} accepts
 *       only status {@code "00"}, so a second consecutive miss becomes the
 *       coarse error value 12 and abends. <strong>There is no third
 *       fallback.</strong> The retry is the service's control flow and is
 *       deliberately not modelled in this class.</li>
 *   <li>{@code TransactionPostingService} - the transaction-category-balance
 *       stage translated from {@code CBTRN02C} paragraph
 *       {@code 2700-UPDATE-TCATBAL}. A missing category-balance row is
 *       <strong>not an error and is not a reject</strong>: the legacy raises a
 *       create flag, folds status {@code "23"} in with {@code "00"} as a
 *       non-error outcome, and then <strong>creates</strong> the row instead
 *       of updating it. The Java caller must catch this exception and insert
 *       the row - or better, use an {@code Optional}-returning repository
 *       finder so that on this path the exception is never constructed at
 *       all.</li>
 * </ol>
 *
 * <p><strong>Message content</strong></p>
 *
 * <p>The detail message is composed strictly from the supplied
 * {@code recordType}, {@code key} and, when one was supplied,
 * {@code resourceName}, in a stable {@code name=value} form. No
 * operator-facing prose is invented here and no legacy diagnostic text is
 * reproduced here: in the legacy design each not-found diagnostic is emitted
 * by the caller immediately before it decides whether the condition is
 * terminal, so those literals belong in the service that logs them. The key
 * must never carry a credential either - a not-found on the user-security
 * record carries the user identifier only, never a password.</p>
 *
 * <p><strong>Caller obligation on a terminal not-found</strong></p>
 *
 * <p>When a not-found genuinely is terminal, the verified case being a second
 * consecutive disclosure-group miss, the legacy order of events is diagnostic
 * first, raw two-byte file status second, abend third. A caller must reproduce
 * that order: log the diagnostic <em>including the raw two-byte file status</em>
 * through SLF4J <em>before</em> raising the abend, never from a {@code catch}
 * block that has already unwound past the status, and never by relying on this
 * exception's message alone. This class performs no logging and never
 * constructs an abend of its own; the two types are independent.</p>
 *
 * <p><strong>Layering and immutability</strong></p>
 *
 * <p>This class is a leaf. It declares no imports, holds no reference to an
 * entity type, a JPA type, a Spring type or a {@code Class} literal, and
 * carries the record identity as plain text, which keeps the exception package
 * free of upward dependencies and keeps the module's reflection budget at zero.
 * Every context field is final and there are no mutators, so an instance is
 * immutable once constructed; a cause must therefore be supplied to the
 * canonical constructor rather than attached afterwards.</p>
 *
 * <p>Provenance: translated from the AWS CardDemo mainframe estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL source text
 * is reproduced in this module.</p>
 */
public class RecordNotFoundException extends RuntimeException {

    /**
     * Serialization identity.
     *
     * <p>Declared explicitly because {@code Throwable} is
     * {@code Serializable} and this module compiles with
     * {@code -Xlint:all -Werror}, which promotes a missing serial version
     * identifier from a warning to a compilation error.</p>
     */
    private static final long serialVersionUID = 1L;

    /**
     * The legacy VSAM file status that this exception corresponds to.
     *
     * <p>Value {@code "23"} is the indexed-read record-not-found code. It is
     * published as a constant so that a caller normalising a raw two-byte
     * status into an outcome and a test asserting that mapping share one
     * definition. This is the status the condition <em>corresponds to</em>;
     * the raw status actually observed on a failing read must still be logged
     * by the caller, as described in the class documentation.</p>
     */
    public static final String STATUS_RECORD_NOT_FOUND = "23";

    /**
     * Descriptive name of the record type that was not found, such as
     * {@code DisclosureGroup} or {@code TransactionCategoryBalance}. Never
     * null; an absent value is held as an empty string.
     */
    private final String recordType;

    /**
     * The business key that was searched for, rendered as text. The module
     * never uses surrogate identifiers, so this is always the natural key that
     * the legacy record layout carries. Never null; an absent value is held as
     * an empty string.
     */
    private final String key;

    /**
     * The legacy DD or CICS file name of the resource that was searched, such
     * as {@code DISCGRP} or {@code TCATBAL}. Never null; an absent value is
     * held as an empty string.
     */
    private final String resourceName;

    /**
     * Creates an instance that carries no context.
     *
     * <p>This constructor exists so that the type can be used as a supplier
     * method reference, which is the shape the repository layer uses:
     * {@code findById(id).orElseThrow(RecordNotFoundException::new)}. Prefer a
     * context-carrying constructor wherever the record type and key are known,
     * because a not-found without context is difficult to diagnose.</p>
     */
    public RecordNotFoundException() {
        this(null, null, null);
    }

    /**
     * Creates an instance that identifies the record type and the key that was
     * not found.
     *
     * @param recordType descriptive name of the record type, such as
     *                   {@code DisclosureGroup}; may be null, which is
     *                   normalised to an empty string
     * @param key        the business key that was searched for, rendered as
     *                   text; may be null, which is normalised to an empty
     *                   string
     */
    public RecordNotFoundException(String recordType, String key) {
        this(recordType, key, null);
    }

    /**
     * Creates an instance that identifies the record type, the key and the
     * legacy resource that was searched.
     *
     * @param recordType   descriptive name of the record type, such as
     *                     {@code DisclosureGroup}; may be null, which is
     *                     normalised to an empty string
     * @param key          the business key that was searched for, rendered as
     *                     text; may be null, which is normalised to an empty
     *                     string
     * @param resourceName the legacy DD or CICS file name of the searched
     *                     resource, such as {@code DISCGRP}; may be null,
     *                     which is normalised to an empty string
     */
    public RecordNotFoundException(String recordType, String key, String resourceName) {
        this(recordType, key, resourceName, null);
    }

    /**
     * Canonical constructor. Every other constructor in this class delegates
     * here, so the detail message is composed in exactly one place and the
     * context fields are assigned in exactly one place.
     *
     * @param recordType   descriptive name of the record type, such as
     *                     {@code DisclosureGroup}; may be null, which is
     *                     normalised to an empty string
     * @param key          the business key that was searched for, rendered as
     *                     text; may be null, which is normalised to an empty
     *                     string
     * @param resourceName the legacy DD or CICS file name of the searched
     *                     resource, such as {@code DISCGRP}; may be null,
     *                     which is normalised to an empty string
     * @param cause        the underlying failure to chain, or null when there
     *                     is none. A cause can only be supplied here, because
     *                     an instance of this type is immutable once
     *                     constructed.
     */
    public RecordNotFoundException(String recordType, String key, String resourceName, Throwable cause) {
        super(composeMessage(recordType, key, resourceName), cause);
        this.recordType = blankIfNull(recordType);
        this.key = blankIfNull(key);
        this.resourceName = blankIfNull(resourceName);
    }

    /**
     * Returns the descriptive record-type name supplied at construction.
     *
     * @return the record type exactly as supplied, or an empty string when
     *         none was supplied; never null
     */
    public String recordType() {
        return recordType;
    }

    /**
     * Returns the business key supplied at construction.
     *
     * @return the key exactly as supplied, or an empty string when none was
     *         supplied; never null
     */
    public String key() {
        return key;
    }

    /**
     * Returns the legacy DD or CICS file name supplied at construction.
     *
     * @return the resource name exactly as supplied, or an empty string when
     *         none was supplied; never null
     */
    public String resourceName() {
        return resourceName;
    }

    /**
     * Normalises a possibly absent context value to a non-null string.
     *
     * <p>The supplied value is returned unchanged when it is present, so an
     * accessor round-trips exactly what the caller passed. No trimming is
     * applied, because the legacy keys are fixed-width and their padding is
     * significant.</p>
     *
     * @param value the value to normalise, possibly null
     * @return the value itself when it is non-null, otherwise an empty string
     */
    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    /**
     * Composes the detail message from the supplied context.
     *
     * <p>The result is a stable {@code name=value} rendering rather than
     * prose. The record type and the key are always rendered, because they are
     * the two context items the legacy diagnostics always name. The resource
     * name is rendered only when one was supplied. An absent value renders as
     * empty and never as the text {@code null}.</p>
     *
     * @param recordType   the record type, possibly null
     * @param key          the business key, possibly null
     * @param resourceName the legacy DD or CICS file name, possibly null
     * @return the composed detail message; never null
     */
    private static String composeMessage(String recordType, String key, String resourceName) {
        String normalisedResourceName = blankIfNull(resourceName);
        StringBuilder message = new StringBuilder(96);
        message.append("RecordNotFound[recordType=")
               .append(blankIfNull(recordType))
               .append(", key=")
               .append(blankIfNull(key));
        if (!normalisedResourceName.isEmpty()) {
            message.append(", resourceName=").append(normalisedResourceName);
        }
        return message.append(']').toString();
    }
}
