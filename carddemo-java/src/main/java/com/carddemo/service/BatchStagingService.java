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

import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.WritableResource;
import org.springframework.stereotype.Service;

/**
 * The batch tier's staging store: the object store that replaced the legacy sequential datasets and
 * generation-data-group bases, reached only through validated logical names.
 *
 * <p>Legacy authority is the {@code DD} inventory of {@code app/jcl}: every batch job read and wrote
 * <em>named datasets</em>, and a dataset name was a name - it carried no device, no path and no way to
 * address anything the job had not been allocated. This class is the target's equivalent of that
 * property. A caller supplies a logical name; this class decides, alone, which bucket it resolves in
 * and what an admissible name looks like. Read as read-only reference at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No job control statement is transcribed.
 *
 * <h2>Why every staged resource comes from here and from nowhere else</h2>
 *
 * <p>Before this class existed the batch tier resolved staged resources two ways, and both were
 * defects. Five job configurations composed a {@code java.nio.file.Path} from a configured directory
 * and a configured name, which made an absolute name replace the directory outright and a
 * parent-traversal name escape it; the resulting path was then created, truncated, overwritten or
 * deleted. Two more handed a caller-supplied string to a resource loader, which resolves
 * {@code file:}, {@code classpath:} and URL forms - so a job parameter arriving on an HTTP request
 * could name a file on the server or a host on the network, have it read, and have its parsed content
 * reach persistence.
 *
 * <p>Both are closed by construction rather than by filtering. The bucket is configuration, never an
 * argument, so no caller chooses where a name resolves. The name itself must be a
 * <strong>safe relative object key</strong> - see {@link #requireStagingKey(String)} - so no name can
 * carry a scheme, an authority, an absolute root or a parent segment. There is therefore nothing left
 * for a caller to point at: the worst a hostile logical name achieves is a refusal.
 *
 * <h2>The contracts a staged object carries</h2>
 *
 * <ul>
 *   <li><strong>Object key.</strong> The key is the validated logical name, unchanged. Nothing is
 *       prefixed, suffixed, lower-cased, escaped or normalised, because a name that changed on the way
 *       to the store would not be the name a later read asks for.</li>
 *   <li><strong>Encoding.</strong> This class moves <em>bytes</em>. It applies no character set, so a
 *       caller's fixed-width image reaches the object exactly as composed. Callers that write text
 *       wrap {@link #writable(String)} in a writer naming their own charset, which for every migrated
 *       output is US-ASCII.</li>
 *   <li><strong>Idempotency.</strong> A write to an existing key replaces that object in full; there
 *       is no append and no partial update. Re-running a step therefore re-writes rather than
 *       accumulating, which is what the legacy allocate-and-truncate disposition did.</li>
 *   <li><strong>Empty input.</strong> An empty write produces a zero-length object rather than no
 *       object, matching a legacy allocation that produced an empty dataset; and
 *       {@link #deleteIfPresent(String)} reports the absence of a key as {@code false} rather than as
 *       a failure, matching a disposition that tolerated deleting what was not there.</li>
 * </ul>
 *
 * <h2>Every outbound call is observed</h2>
 *
 * <p>Each store operation runs inside a Micrometer observation, so the trace does not stop at the
 * boundary and a failure sets the error attributes on the span that made the call. The two low
 * cardinality tags are fixed vocabularies - the store and the operation - so the meter the observation
 * also produces cannot grow a time series per object. The key is carried as a high-cardinality tag,
 * which reaches the span and deliberately not the meter.
 *
 * <p><strong>No resource is created here.</strong> The bucket is provisioned by the platform - the
 * emulator bootstrap locally, the deployment's own provisioning elsewhere - exactly as the legacy
 * datasets were allocated outside the programs that used them. This class writes objects into an
 * existing bucket and never creates, configures, versions or tests for one.
 *
 * <p>Stateless and immutable: final class, final fields, no mutable static state, safe for
 * unsynchronised concurrent use.
 *
 * @since 1.0.0
 */
@Service
public final class BatchStagingService {

    /** The property naming the bucket every staged object resolves in. */
    public static final String BATCH_STAGING_BUCKET_PROPERTY = "carddemo.aws.s3.batch-staging-bucket";

    /** The one character that separates segments of a staging key. */
    public static final char KEY_SEGMENT_SEPARATOR = '/';

    /** The longest object key the store accepts, in characters. */
    public static final int MAX_KEY_LENGTH = 1024;

    /** The parent-directory segment, refused wherever a key could carry it. */
    public static final String PARENT_SEGMENT = "..";

    /** The current-directory segment, refused for the same reason. */
    public static final String CURRENT_SEGMENT = ".";

    /** Observation name shared by every outbound store call this class makes. */
    public static final String OBSERVATION_NAME = "carddemo.batch.staging";

    /** Low-cardinality tag naming which store operation an observation covers. */
    public static final String TAG_OPERATION = "operation";

    /** Low-cardinality tag naming the store kind, fixed for the lifetime of this module. */
    public static final String TAG_STORE = "store";

    /** High-cardinality tag carrying the object key; reaches the span and never the meter. */
    public static final String TAG_OBJECT_KEY = "objectKey";

    /** The one store kind this class addresses. */
    public static final String STORE_OBJECT = "s3";

    /** Operation tag: a readable handle on a staged object was resolved. */
    public static final String OPERATION_READ = "read";

    /** Operation tag: a writable handle on a staged object was resolved. */
    public static final String OPERATION_WRITE = "write";

    /** Operation tag: a complete object was uploaded in one call. */
    public static final String OPERATION_UPLOAD = "upload";

    /** Operation tag: a staged object was removed if it was there. */
    public static final String OPERATION_DELETE = "delete";

    /** Operation tag: the presence of a staged object was tested. */
    public static final String OPERATION_EXISTS = "exists";

    /** Operation tag: the byte length of a staged object was read. */
    public static final String OPERATION_LENGTH = "length";

    /** The one logger this class writes through. */
    private static final Logger LOGGER = LoggerFactory.getLogger(BatchStagingService.class);

    /** The store client the framework's integration publishes. */
    private final S3Operations objectStore;

    /** The bucket every staged object resolves in; configuration, never an argument. */
    private final String bucket;

    /** The registry every outbound call is observed against. */
    private final ObservationRegistry observationRegistry;

    /**
     * Creates the staging store over the configured bucket.
     *
     * @param objectStore         the store client; must not be {@code null}
     * @param bucket              the configured staging bucket; must not be {@code null} or blank
     * @param observationRegistry the registry outbound calls are observed against; must not be
     *                            {@code null}
     * @throws NullPointerException     if a collaborator is {@code null}
     * @throws IllegalArgumentException if the configured bucket is blank
     */
    public BatchStagingService(final S3Operations objectStore,
            @Value("${" + BATCH_STAGING_BUCKET_PROPERTY + "}") final String bucket,
            final ObservationRegistry observationRegistry) {
        this.objectStore = Objects.requireNonNull(objectStore, "objectStore must not be null");
        this.bucket = requireBucket(bucket);
        this.observationRegistry =
                Objects.requireNonNull(observationRegistry, "observationRegistry must not be null");
    }

    /**
     * @return the bucket every staged object of this deployment resolves in
     */
    public String bucket() {
        return this.bucket;
    }

    // ==================================================================================================
    // The name rule - the whole of what a caller may decide
    // ==================================================================================================

    /**
     * Requires a logical staging name to be a safe relative object key, and answers it unchanged.
     *
     * <p>Admissible characters are letters, digits, {@code .}, {@code _}, {@code -} and the segment
     * separator {@code /}. Every other character is refused, which is what closes the whole class of
     * caller-controlled locators in one rule rather than in a list of prohibitions: a colon cannot
     * appear, so no scheme - {@code file:}, {@code classpath:}, {@code http:} - is expressible; a
     * backslash cannot appear, so no platform path is; and a control byte cannot appear, so a name
     * cannot forge a line in a log record that reports it.
     *
     * <p>Four further rules make the name relative and confined: it may not begin with the separator,
     * because a leading separator is an absolute root; it may not end with one, because that names a
     * container rather than an object; no segment may be empty, because {@code a//b} is two names
     * pretending to be one; and no segment may be {@code .} or {@code ..}, because either lets a name
     * address something other than what it appears to.
     *
     * <p>Static, so a caller can validate a configured or supplied name at construction time - before
     * any store call and before any step runs - rather than discovering the refusal mid-stream.
     *
     * @param  logicalName the logical staging name to check
     * @return {@code logicalName}, unchanged
     * @throws NullPointerException     if {@code logicalName} is {@code null}
     * @throws IllegalArgumentException if the name is not a safe relative object key
     */
    public static String requireStagingKey(final String logicalName) {
        Objects.requireNonNull(logicalName, "logicalName must not be null");
        if (logicalName.isEmpty()) {
            throw new IllegalArgumentException(
                    "a staging name must not be empty; an empty name addresses the bucket itself");
        }
        if (logicalName.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("a staging name must be at most " + MAX_KEY_LENGTH
                    + " characters but this one is " + logicalName.length());
        }
        if (logicalName.charAt(0) == KEY_SEGMENT_SEPARATOR) {
            throw new IllegalArgumentException("a staging name must be relative, so it must not begin"
                    + " with '" + KEY_SEGMENT_SEPARATOR + "'");
        }
        if (logicalName.charAt(logicalName.length() - 1) == KEY_SEGMENT_SEPARATOR) {
            throw new IllegalArgumentException("a staging name must name an object, so it must not end"
                    + " with '" + KEY_SEGMENT_SEPARATOR + "'");
        }
        for (int index = 0; index < logicalName.length(); index++) {
            final char character = logicalName.charAt(index);
            if (!isAdmissibleKeyCharacter(character)) {
                throw new IllegalArgumentException("a staging name admits only letters, digits, '.',"
                        + " '_', '-' and '" + KEY_SEGMENT_SEPARATOR + "'; the character at zero-based"
                        + " position " + index + " is code point " + (int) character
                        + ", so no scheme, no authority, no platform path and no control byte can"
                        + " reach the store");
            }
        }
        requireSafeSegments(logicalName);
        return logicalName;
    }

    // ==================================================================================================
    // Reading, writing, removing
    // ==================================================================================================

    /**
     * Resolves a readable handle on one staged object.
     *
     * <p>The handle is resolved, not read: no byte is transferred until a caller opens it, which is what
     * lets a framework reader own the stream's lifetime. A key the store does not hold resolves
     * successfully and reports itself absent, so a caller decides whether absence is a failure - the
     * legacy jobs differ on exactly that point.
     *
     * @param  logicalName the logical staging name
     * @return a readable handle on the staged object
     * @throws NullPointerException     if {@code logicalName} is {@code null}
     * @throws IllegalArgumentException if the name is not a safe relative object key
     */
    public Resource readable(final String logicalName) {
        final String key = requireStagingKey(logicalName);
        return observed(OPERATION_READ, key, () -> this.objectStore.download(this.bucket, key));
    }

    /**
     * Resolves a writable handle on one staged object.
     *
     * <p>The object is written when the caller closes the stream the handle opens, and the write
     * replaces any object already under the key in full. Nothing is written by resolving the handle.
     *
     * @param  logicalName the logical staging name
     * @return a writable handle on the staged object
     * @throws NullPointerException     if {@code logicalName} is {@code null}
     * @throws IllegalArgumentException if the name is not a safe relative object key
     */
    public WritableResource writable(final String logicalName) {
        final String key = requireStagingKey(logicalName);
        return observed(OPERATION_WRITE, key, () -> this.objectStore.createResource(this.bucket, key));
    }

    /**
     * Opens a stream that writes one staged object, replacing whatever the key held.
     *
     * @param  logicalName the logical staging name
     * @return an open stream; the object exists once it is closed
     * @throws NullPointerException     if {@code logicalName} is {@code null}
     * @throws IllegalArgumentException if the name is not a safe relative object key
     * @throws UncheckedIOException     if the stream cannot be opened
     */
    public OutputStream outputStream(final String logicalName) {
        final WritableResource destination = writable(logicalName);
        try {
            return destination.getOutputStream();
        } catch (final IOException failure) {
            throw new UncheckedIOException(
                    "the staged object " + logicalName + " could not be opened for writing", failure);
        }
    }

    /**
     * Opens a stream that reads one staged object.
     *
     * @param  logicalName the logical staging name
     * @return an open stream over the staged object
     * @throws NullPointerException     if {@code logicalName} is {@code null}
     * @throws IllegalArgumentException if the name is not a safe relative object key
     * @throws UncheckedIOException     if the stream cannot be opened
     */
    public InputStream inputStream(final String logicalName) {
        final Resource source = readable(logicalName);
        try {
            return source.getInputStream();
        } catch (final IOException failure) {
            throw new UncheckedIOException(
                    "the staged object " + logicalName + " could not be opened for reading", failure);
        }
    }

    /**
     * Writes one staged object from a complete byte image, replacing whatever the key held.
     *
     * <p>The image is uploaded exactly as supplied: no separator is inserted, no character set is
     * applied and no trailing byte is added, so an image composed of fixed-width records produces an
     * object whose length is exactly the record count multiplied by the record length. An empty image
     * produces a zero-length object.
     *
     * @param  content     the complete byte image; must not be {@code null}
     * @param  logicalName the logical staging name
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if the name is not a safe relative object key
     * @throws UncheckedIOException     if the image cannot be uploaded
     */
    public void write(final String logicalName, final byte[] content) {
        Objects.requireNonNull(content, "content must not be null");
        final String key = requireStagingKey(logicalName);
        final byte[] image = content.clone();
        observed(OPERATION_UPLOAD, key, () -> {
            try (InputStream body = new ByteArrayInputStream(image)) {
                return this.objectStore.upload(this.bucket, key, body);
            } catch (final IOException failure) {
                throw new UncheckedIOException(
                        "the staged object " + key + " could not be uploaded", failure);
            }
        });
        LOGGER.debug("Staged object written: key={} bytes={}", key, image.length);
    }

    /**
     * Removes one staged object if the store holds it, and reports whether it did.
     *
     * <p>Absence is not a failure. The legacy dispositions this replaces tolerated deleting a dataset
     * that had never been allocated, and a first run is exactly that case.
     *
     * @param  logicalName the logical staging name
     * @return {@code true} when an object was removed, {@code false} when the key held none
     * @throws NullPointerException     if {@code logicalName} is {@code null}
     * @throws IllegalArgumentException if the name is not a safe relative object key
     */
    public boolean deleteIfPresent(final String logicalName) {
        final String key = requireStagingKey(logicalName);
        return Boolean.TRUE.equals(observed(OPERATION_DELETE, key, () -> {
            if (!this.objectStore.objectExists(this.bucket, key)) {
                return Boolean.FALSE;
            }
            this.objectStore.deleteObject(this.bucket, key);
            return Boolean.TRUE;
        }));
    }

    /**
     * Reports whether the store holds one staged object.
     *
     * @param  logicalName the logical staging name
     * @return {@code true} when the key holds an object
     * @throws NullPointerException     if {@code logicalName} is {@code null}
     * @throws IllegalArgumentException if the name is not a safe relative object key
     */
    public boolean exists(final String logicalName) {
        final String key = requireStagingKey(logicalName);
        return Boolean.TRUE.equals(observed(OPERATION_EXISTS, key,
                () -> Boolean.valueOf(this.objectStore.objectExists(this.bucket, key))));
    }

    /**
     * Reads the byte length of one staged object.
     *
     * @param  logicalName the logical staging name
     * @return the object's length in bytes
     * @throws NullPointerException     if {@code logicalName} is {@code null}
     * @throws IllegalArgumentException if the name is not a safe relative object key
     */
    public long contentLength(final String logicalName) {
        final String key = requireStagingKey(logicalName);
        final Long length = observed(OPERATION_LENGTH, key,
                () -> Long.valueOf(this.objectStore.download(this.bucket, key).contentLength()));
        return length == null ? 0L : length.longValue();
    }

    // ==================================================================================================
    // Internals
    // ==================================================================================================

    /**
     * Runs one store call inside an observation, so the trace reaches the boundary and a failure is
     * recorded on the span that made the call.
     *
     * @param  <T>       what the call answers
     * @param  operation the operation tag, one of this class's fixed vocabulary
     * @param  key       the object key, carried as a high-cardinality tag
     * @param  call      the store call
     * @return whatever the call answered
     */
    private <T> T observed(final String operation, final String key, final Supplier<T> call) {
        // Observation.observe records a failure on the span before rethrowing it, so an outbound failure
        // sets the span's error attributes rather than ending the trace at an untraced boundary. The two
        // stream-returning operations observe the RESOLUTION of a handle rather than the byte transfer,
        // because the transfer happens when the caller writes and closes; the whole-image write below
        // observes the transfer itself, which is why the archive path uses it.
        return Observation.createNotStarted(OBSERVATION_NAME, this.observationRegistry)
                .lowCardinalityKeyValue(TAG_STORE, STORE_OBJECT)
                .lowCardinalityKeyValue(TAG_OPERATION, operation)
                .highCardinalityKeyValue(TAG_OBJECT_KEY, key)
                .observe(call);
    }

    /**
     * Answers whether one character may appear in a staging key.
     *
     * @param  character the character to test
     * @return {@code true} when the character is admissible
     */
    private static boolean isAdmissibleKeyCharacter(final char character) {
        return (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '.' || character == '_' || character == '-'
                || character == KEY_SEGMENT_SEPARATOR;
    }

    /**
     * Requires every segment of a key to be non-empty and to name neither the current nor the parent
     * container.
     *
     * @param logicalName the key, already known to hold only admissible characters
     * @throws IllegalArgumentException if a segment is empty, {@code .} or {@code ..}
     */
    private static void requireSafeSegments(final String logicalName) {
        int segmentStart = 0;
        while (segmentStart <= logicalName.length()) {
            final int separator = logicalName.indexOf(KEY_SEGMENT_SEPARATOR, segmentStart);
            final int segmentEnd = separator < 0 ? logicalName.length() : separator;
            final String segment = logicalName.substring(segmentStart, segmentEnd);
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("a staging name must not hold an empty segment, so"
                        + " two separators may not be adjacent");
            }
            if (CURRENT_SEGMENT.equals(segment) || PARENT_SEGMENT.equals(segment)) {
                throw new IllegalArgumentException("a staging name must not hold the segment '"
                        + segment + "', because a relative name that can name its own container can"
                        + " address an object outside the staging area it was given");
            }
            if (separator < 0) {
                return;
            }
            segmentStart = separator + 1;
        }
    }

    /**
     * Requires the configured bucket to be present.
     *
     * @param  bucket the configured value
     * @return the bucket, unchanged
     * @throws NullPointerException     if {@code bucket} is {@code null}
     * @throws IllegalArgumentException if {@code bucket} is blank
     */
    private static String requireBucket(final String bucket) {
        Objects.requireNonNull(bucket, "bucket must not be null");
        if (bucket.isBlank()) {
            throw new IllegalArgumentException("the batch staging bucket must be configured under "
                    + BATCH_STAGING_BUCKET_PROPERTY + "; a blank value would resolve every staged"
                    + " object nowhere");
        }
        return bucket;
    }

    /**
     * Names the resource a {@link S3Resource} stands for, bounded and free of anything a caller
     * supplied, for use in a diagnostic.
     *
     * <p>The store's own description of a resource is deliberately never logged: it renders a location,
     * which on a caller-influenced path is a value a caller supplied. The logical key is what a reader
     * needs and is the only thing published.
     *
     * @param  logicalName the logical staging name
     * @return the name to publish in a diagnostic
     */
    public String describe(final String logicalName) {
        return STORE_OBJECT + KEY_SEGMENT_SEPARATOR + KEY_SEGMENT_SEPARATOR + this.bucket
                + KEY_SEGMENT_SEPARATOR + requireStagingKey(logicalName);
    }
}
