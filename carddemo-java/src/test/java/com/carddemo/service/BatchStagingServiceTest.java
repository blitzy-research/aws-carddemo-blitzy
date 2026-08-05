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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * Unit specification for {@link BatchStagingService}.
 *
 * <h2>What this specification is for</h2>
 *
 * <p>This class is the single place a caller-supplied name becomes an addressed object, so it is the
 * single place the whole class of caller-controlled locators is refused. That makes the name rule the
 * most load-bearing behaviour in the batch tier's staging path: a name that slipped through as a scheme,
 * an absolute path or a parent traversal would let a job parameter decide <em>what the process reads</em>
 * rather than which staged generation it reads.
 *
 * <p>The rule is therefore specified positively - what is admitted - and then negatively against each
 * family of locator the legacy resource-loading path would have resolved. The negative cases are not
 * hypothetical: the revision this replaced passed a job parameter straight to a resource loader, which
 * resolves {@code file:}, {@code classpath:} and {@code http:} locations by design.
 *
 * <h2>Why the store is a stand-in here and a real emulator elsewhere</h2>
 *
 * <p>These are assertions about the rule, the bucket a key resolves in, the operation the store is asked
 * to perform and the bytes handed to it - all of which are decided before any network call and are
 * observable on a recorded interaction. That a real object store accepts what this class sends is a
 * different claim, and a neighbouring integration specification makes it against a running emulator.
 *
 * <p>Provenance: the batch staging replacement for sequential-dataset and generation-group staging, at
 * commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is transcribed here.
 */
@DisplayName("BatchStagingService - one rule closes every caller-controlled locator")
class BatchStagingServiceTest {

    /** The bucket the configured deployment stages into. */
    private static final String BUCKET = "carddemo-batch-staging";

    /** A well-formed logical name used wherever the name itself is not what is under test. */
    private static final String KEY = "generation/transaction-backup.txt";

    /** The store the service is built over. */
    private S3Operations objectStore;

    /** The service under test. */
    private BatchStagingService staging;

    @BeforeEach
    void createService() {
        this.objectStore = mock(S3Operations.class);
        this.staging = new BatchStagingService(this.objectStore, BUCKET,
                ObservationRegistry.NOOP);
    }

    // ==================================================================================================

    @Nested
    @DisplayName("The name rule")
    class TheNameRule {

        @ParameterizedTest
        @DisplayName("admits a relative name of letters, digits, dot, underscore, hyphen and separators")
        @ValueSource(strings = {"a", "0", "backup.txt", "TRANSACT.BKUP", "daily_tran-2022.txt",
            "generation/0001/transaction-backup.txt", "a.b.c", "A-Z_0-9.txt", "one/two/three/four"})
        void admitsASafeRelativeName(final String name) {
            assertThat(BatchStagingService.requireStagingKey(name))
                    .as("an admitted name is answered unchanged, never rewritten")
                    .isEqualTo(name);
        }

        @ParameterizedTest
        @DisplayName("refuses every scheme, because a colon is not an admissible character")
        @ValueSource(strings = {"file:/etc/passwd", "file:///etc/passwd", "classpath:application.yml",
            "http://169.254.169.254/latest/meta-data/", "https://example.invalid/object",
            "s3://other-bucket/object", "jar:file:/tmp/a.jar!/b", "C:/windows/win.ini"})
        void refusesAScheme(final String locator) {
            // The message is deliberately not asserted here: some of these locators also end in a
            // separator or carry a traversal segment, and whichever rule reaches them first is the one
            // that reports. The claim under test is that none of them reaches the store.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> BatchStagingService.requireStagingKey(locator));
        }

        @Test
        @DisplayName("refuses a scheme because the colon itself is inadmissible, not because of a list")
        void theColonIsWhatMakesASchemeInexpressible() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> BatchStagingService.requireStagingKey("file:/etc/passwd"))
                    .withMessageContaining("admits only letters")
                    .withMessageContaining("code point " + (int) ':');
        }

        @ParameterizedTest
        @DisplayName("refuses an absolute name, a name of a container, and an empty segment")
        @ValueSource(strings = {"/etc/passwd", "/generation/backup.txt", "generation/",
            "generation//backup.txt", "//backup.txt", "a//b//c"})
        void refusesAnAbsoluteOrContainerName(final String name) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> BatchStagingService.requireStagingKey(name));
        }

        @ParameterizedTest
        @DisplayName("refuses a parent or current segment wherever it appears in the name")
        @ValueSource(strings = {"..", ".", "../secret", "generation/../../etc/passwd",
            "generation/./backup.txt", "a/b/../c", "a/../b"})
        void refusesATraversalSegment(final String name) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> BatchStagingService.requireStagingKey(name))
                    .withMessageContaining("segment");
        }

        @ParameterizedTest
        @DisplayName("refuses a platform path separator and a control byte, so no log line can be forged")
        @ValueSource(strings = {"generation\\backup.txt", "..\\..\\secret", "backup.txt\nWARN forged",
            "backup\r\n.txt", "backup\u0000.txt", "back up.txt", "backup%2e%2e.txt", "b?a=1", "b#c",
            "b*c", "généré.txt"})
        void refusesAPlatformPathOrControlByte(final String name) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> BatchStagingService.requireStagingKey(name))
                    .withMessageContaining("code point");
        }

        @Test
        @DisplayName("refuses an empty name, because an empty name addresses the bucket itself")
        void refusesAnEmptyName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> BatchStagingService.requireStagingKey(""))
                    .withMessageContaining("empty");
        }

        @Test
        @DisplayName("refuses an absent name rather than resolving a default")
        void refusesAnAbsentName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> BatchStagingService.requireStagingKey(null))
                    .withMessageContaining("logicalName");
        }

        @Test
        @DisplayName("admits a name at the service's own length limit and refuses one beyond it")
        void enforcesTheLengthLimitAtItsBoundary() {
            final String atLimit = "a".repeat(BatchStagingService.MAX_KEY_LENGTH);
            final String beyondLimit = "a".repeat(BatchStagingService.MAX_KEY_LENGTH + 1);

            assertThat(BatchStagingService.requireStagingKey(atLimit)).isEqualTo(atLimit);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> BatchStagingService.requireStagingKey(beyondLimit))
                    .withMessageContaining(String.valueOf(BatchStagingService.MAX_KEY_LENGTH));
        }

        @Test
        @DisplayName("is applied by every operation, so no operation can be reached with a locator")
        void isAppliedByEveryOperation() {
            final String locator = "file:/etc/passwd";

            assertThatIllegalArgumentException().isThrownBy(() -> staging.readable(locator));
            assertThatIllegalArgumentException().isThrownBy(() -> staging.writable(locator));
            assertThatIllegalArgumentException().isThrownBy(() -> staging.outputStream(locator));
            assertThatIllegalArgumentException().isThrownBy(() -> staging.inputStream(locator));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> staging.write(locator, new byte[0]));
            assertThatIllegalArgumentException().isThrownBy(() -> staging.deleteIfPresent(locator));
            assertThatIllegalArgumentException().isThrownBy(() -> staging.exists(locator));
            assertThatIllegalArgumentException().isThrownBy(() -> staging.contentLength(locator));
            assertThatIllegalArgumentException().isThrownBy(() -> staging.describe(locator));

            verifyNoInteractions(objectStore);
        }
    }

    // ==================================================================================================

    @Nested
    @DisplayName("The configured bucket")
    class TheConfiguredBucket {

        @Test
        @DisplayName("is the bucket every key resolves in, and is reported as configured")
        void isReportedAsConfigured() {
            assertThat(staging.bucket()).isEqualTo(BUCKET);
        }

        @ParameterizedTest
        @DisplayName("must be configured, because a blank one would resolve every object nowhere")
        @ValueSource(strings = {"", " ", "\t", "   "})
        void refusesABlankBucket(final String blank) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new BatchStagingService(objectStore, blank,
                            ObservationRegistry.NOOP))
                    .withMessageContaining(BatchStagingService.BATCH_STAGING_BUCKET_PROPERTY);
        }

        @Test
        @DisplayName("refuses an absent collaborator, so a half-built store cannot be published")
        void refusesAnAbsentCollaborator() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new BatchStagingService(null, BUCKET,
                            ObservationRegistry.NOOP))
                    .withMessageContaining("objectStore");
            assertThatNullPointerException()
                    .isThrownBy(() -> new BatchStagingService(objectStore, null,
                            ObservationRegistry.NOOP))
                    .withMessageContaining("bucket");
            assertThatNullPointerException()
                    .isThrownBy(() -> new BatchStagingService(objectStore, BUCKET, null))
                    .withMessageContaining("observationRegistry");
        }

        @Test
        @DisplayName("names a staged object in a diagnostic without rendering the store's own location")
        void describesAnObjectWithoutTheStoresLocation() {
            assertThat(staging.describe(KEY))
                    .isEqualTo("s3//" + BUCKET + "/" + KEY);
        }
    }

    // ==================================================================================================

    @Nested
    @DisplayName("Reading")
    class Reading {

        @Test
        @DisplayName("resolves a handle in the configured bucket and transfers nothing to do it")
        void resolvesAHandleWithoutTransferring() {
            final S3Resource handle = mock(S3Resource.class);
            when(objectStore.download(BUCKET, KEY)).thenReturn(handle);

            assertThat(staging.readable(KEY)).isSameAs(handle);

            verify(objectStore).download(BUCKET, KEY);
            verify(objectStore, never()).upload(anyString(), anyString(), any(InputStream.class));
        }

        @Test
        @DisplayName("opens a stream over the staged object")
        void opensAStreamOverTheObject() throws IOException {
            final byte[] image = "0000000001".getBytes(StandardCharsets.US_ASCII);
            final S3Resource handle = mock(S3Resource.class);
            when(handle.getInputStream()).thenReturn(new ByteArrayInputStream(image));
            when(objectStore.download(BUCKET, KEY)).thenReturn(handle);

            try (InputStream body = staging.inputStream(KEY)) {
                assertThat(body.readAllBytes()).isEqualTo(image);
            }
        }

        @Test
        @DisplayName("reports a stream that cannot be opened as a failure naming the logical key only")
        void reportsAnUnopenableStream() throws IOException {
            final S3Resource handle = mock(S3Resource.class);
            when(handle.getInputStream()).thenThrow(new IOException("no such object"));
            when(objectStore.download(BUCKET, KEY)).thenReturn(handle);

            assertThatExceptionOfType(UncheckedIOException.class)
                    .isThrownBy(() -> staging.inputStream(KEY))
                    .withMessageContaining(KEY)
                    .withCauseInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("reads a staged object's byte length")
        void readsTheObjectsLength() {
            final S3Resource handle = mock(S3Resource.class);
            when(handle.contentLength()).thenReturn(1050L);
            when(objectStore.download(BUCKET, KEY)).thenReturn(handle);

            assertThat(staging.contentLength(KEY)).isEqualTo(1050L);
        }
    }

    // ==================================================================================================

    @Nested
    @DisplayName("Writing")
    class Writing {

        @Test
        @DisplayName("resolves a writable handle in the configured bucket and writes nothing to do it")
        void resolvesAWritableHandle() {
            final S3Resource handle = mock(S3Resource.class);
            when(objectStore.createResource(BUCKET, KEY)).thenReturn(handle);

            assertThat(staging.writable(KEY)).isSameAs(handle);

            verify(objectStore).createResource(BUCKET, KEY);
        }

        @Test
        @DisplayName("opens a stream the caller closes to publish the object")
        void opensAStreamTheCallerCloses() throws IOException {
            final ByteArrayOutputStream published = new ByteArrayOutputStream();
            final S3Resource handle = mock(S3Resource.class);
            when(handle.getOutputStream()).thenReturn(published);
            when(objectStore.createResource(BUCKET, KEY)).thenReturn(handle);

            try (OutputStream body = staging.outputStream(KEY)) {
                body.write("record".getBytes(StandardCharsets.US_ASCII));
            }

            assertThat(published.toString(StandardCharsets.US_ASCII)).isEqualTo("record");
        }

        @Test
        @DisplayName("reports a stream that cannot be opened as a failure naming the logical key only")
        void reportsAnUnopenableStream() throws IOException {
            final S3Resource handle = mock(S3Resource.class);
            when(handle.getOutputStream()).thenThrow(new IOException("denied"));
            when(objectStore.createResource(BUCKET, KEY)).thenReturn(handle);

            assertThatExceptionOfType(UncheckedIOException.class)
                    .isThrownBy(() -> staging.outputStream(KEY))
                    .withMessageContaining(KEY)
                    .withCauseInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("uploads a complete image byte for byte, adding no separator and no trailing byte")
        void uploadsAnImageByteForByte() throws IOException {
            final byte[] image = new byte[700];
            for (int index = 0; index < image.length; index++) {
                image[index] = (byte) ('0' + (index % 10));
            }
            when(objectStore.upload(anyString(), anyString(), any(InputStream.class)))
                    .thenReturn(mock(S3Resource.class));

            staging.write(KEY, image);

            final ArgumentCaptor<InputStream> body = ArgumentCaptor.forClass(InputStream.class);
            verify(objectStore).upload(eq(BUCKET), eq(KEY), body.capture());
            assertThat(body.getValue().readAllBytes())
                    .as("an image of fixed-width records must arrive at exactly its own length")
                    .isEqualTo(image);
        }

        @Test
        @DisplayName("uploads an empty image as a zero-length object rather than refusing it")
        void uploadsAnEmptyImage() throws IOException {
            when(objectStore.upload(anyString(), anyString(), any(InputStream.class)))
                    .thenReturn(mock(S3Resource.class));

            staging.write(KEY, new byte[0]);

            final ArgumentCaptor<InputStream> body = ArgumentCaptor.forClass(InputStream.class);
            verify(objectStore).upload(eq(BUCKET), eq(KEY), body.capture());
            assertThat(body.getValue().readAllBytes()).isEmpty();
        }

        @Test
        @DisplayName("copies the image it was handed, so a caller reusing its buffer cannot alter it")
        void copiesTheImageItWasHanded() throws IOException {
            final byte[] image = "AAAA".getBytes(StandardCharsets.US_ASCII);
            final List<byte[]> uploaded = new ArrayList<>();
            when(objectStore.upload(anyString(), anyString(), any(InputStream.class)))
                    .thenAnswer(invocation -> {
                        uploaded.add(invocation.getArgument(2, InputStream.class).readAllBytes());
                        return mock(S3Resource.class);
                    });

            staging.write(KEY, image);
            image[0] = 'B';

            assertThat(uploaded).hasSize(1);
            assertThat(new String(uploaded.get(0), StandardCharsets.US_ASCII)).isEqualTo("AAAA");
        }

        @Test
        @DisplayName("refuses an absent image rather than staging a zero-length object for it")
        void refusesAnAbsentImage() {
            assertThatNullPointerException()
                    .isThrownBy(() -> staging.write(KEY, null))
                    .withMessageContaining("content");
            verifyNoInteractions(objectStore);
        }
    }

    // ==================================================================================================

    @Nested
    @DisplayName("Removing and probing")
    class RemovingAndProbing {

        @Test
        @DisplayName("removes an object the store holds and reports that it did")
        void removesAnObjectTheStoreHolds() {
            when(objectStore.objectExists(BUCKET, KEY)).thenReturn(true);

            assertThat(staging.deleteIfPresent(KEY)).isTrue();

            verify(objectStore).deleteObject(BUCKET, KEY);
        }

        @Test
        @DisplayName("treats an absent object as success and asks the store to remove nothing")
        void treatsAnAbsentObjectAsSuccess() {
            when(objectStore.objectExists(BUCKET, KEY)).thenReturn(false);

            assertThat(staging.deleteIfPresent(KEY)).isFalse();

            verify(objectStore, never()).deleteObject(anyString(), anyString());
        }

        @Test
        @DisplayName("reports presence and absence from the store rather than from a cached answer")
        void reportsPresenceFromTheStore() {
            when(objectStore.objectExists(BUCKET, KEY)).thenReturn(true, false);

            assertThat(staging.exists(KEY)).isTrue();
            assertThat(staging.exists(KEY)).isFalse();
        }
    }

    // ==================================================================================================

    @Nested
    @DisplayName("Observing the boundary")
    class ObservingTheBoundary {

        /** Every observation the registry saw, in the order it stopped them. */
        private final List<Observation.Context> observed = new ArrayList<>();

        /** The service under test, bound to a registry that records rather than discards. */
        private BatchStagingService recorded;

        @BeforeEach
        void bindARecordingRegistry() {
            final ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(new ObservationHandler<>() {
                @Override
                public void onStop(final Observation.Context context) {
                    observed.add(context);
                }

                @Override
                public boolean supportsContext(final Observation.Context context) {
                    return true;
                }
            });
            this.recorded = new BatchStagingService(objectStore, BUCKET, registry);
        }

        @Test
        @DisplayName("names the store and the operation on every call, so a span reaches the boundary")
        void namesTheStoreAndTheOperation() {
            when(objectStore.download(BUCKET, KEY)).thenReturn(mock(S3Resource.class));
            when(objectStore.objectExists(BUCKET, KEY)).thenReturn(false);

            this.recorded.readable(KEY);
            this.recorded.exists(KEY);
            this.recorded.deleteIfPresent(KEY);

            assertThat(this.observed).hasSize(3);
            assertThat(this.observed).extracting(Observation.Context::getName)
                    .containsOnly(BatchStagingService.OBSERVATION_NAME);
            assertThat(this.observed).allSatisfy(context -> assertThat(tag(context,
                    BatchStagingService.TAG_STORE)).isEqualTo(BatchStagingService.STORE_OBJECT));
            assertThat(this.observed)
                    .extracting(context -> tag(context, BatchStagingService.TAG_OPERATION))
                    .as("the operation tag is a fixed word from the service's own vocabulary")
                    .containsExactly(BatchStagingService.OPERATION_READ,
                            BatchStagingService.OPERATION_EXISTS,
                            BatchStagingService.OPERATION_DELETE);
            assertThat(this.observed).allSatisfy(context ->
                    assertThat(highCardinalityTag(context, BatchStagingService.TAG_OBJECT_KEY))
                            .isEqualTo(KEY));
        }

        @Test
        @DisplayName("records an outbound failure on the span that made the call, then rethrows it")
        void recordsAnOutboundFailureOnTheSpan() {
            final RuntimeException refused = new IllegalStateException("the store refused the call");
            when(objectStore.download(BUCKET, KEY)).thenThrow(refused);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> this.recorded.readable(KEY))
                    .isSameAs(refused);

            assertThat(this.observed).hasSize(1);
            assertThat(this.observed.get(0).getError())
                    .as("a boundary failure that set no error attribute would leave the trace claiming "
                            + "the call succeeded")
                    .isSameAs(refused);
        }

        @Test
        @DisplayName("observes the whole transfer when an image is written, not merely its resolution")
        void observesTheWholeTransferOfAnImage() {
            when(objectStore.upload(anyString(), anyString(), any(InputStream.class)))
                    .thenReturn(mock(S3Resource.class));

            this.recorded.write(KEY, new byte[350]);

            assertThat(this.observed).hasSize(1);
            assertThat(tag(this.observed.get(0), BatchStagingService.TAG_OPERATION))
                    .isEqualTo(BatchStagingService.OPERATION_UPLOAD);
        }

        /**
         * Reads one low-cardinality tag off a recorded observation.
         *
         * @param  context the recorded observation
         * @param  name    the tag name
         * @return the tag's value, or {@code null} when the observation carries no such tag
         */
        private String tag(final Observation.Context context, final String name) {
            for (final KeyValue keyValue : context.getLowCardinalityKeyValues()) {
                if (keyValue.getKey().equals(name)) {
                    return keyValue.getValue();
                }
            }
            return null;
        }

        /**
         * Reads one high-cardinality tag off a recorded observation.
         *
         * @param  context the recorded observation
         * @param  name    the tag name
         * @return the tag's value, or {@code null} when the observation carries no such tag
         */
        private String highCardinalityTag(final Observation.Context context, final String name) {
            for (final KeyValue keyValue : context.getHighCardinalityKeyValues()) {
                if (keyValue.getKey().equals(name)) {
                    return keyValue.getValue();
                }
            }
            return null;
        }
    }

    // ==================================================================================================

    @Nested
    @DisplayName("The published contract")
    class ThePublishedContract {

        @Test
        @DisplayName("names the property the bucket is bound from, so one document states it")
        void namesTheBucketProperty() {
            assertThat(BatchStagingService.BATCH_STAGING_BUCKET_PROPERTY)
                    .isEqualTo("carddemo.aws.s3.batch-staging-bucket");
        }

        @Test
        @DisplayName("names one observation and a fixed low-cardinality operation vocabulary")
        void namesOneObservationAndAFixedVocabulary() {
            assertThat(BatchStagingService.OBSERVATION_NAME).isEqualTo("carddemo.batch.staging");
            assertThat(BatchStagingService.TAG_STORE).isEqualTo("store");
            assertThat(BatchStagingService.TAG_OPERATION).isEqualTo("operation");
            assertThat(BatchStagingService.TAG_OBJECT_KEY).isEqualTo("objectKey");
            assertThat(List.of(BatchStagingService.OPERATION_READ,
                            BatchStagingService.OPERATION_WRITE,
                            BatchStagingService.OPERATION_UPLOAD,
                            BatchStagingService.OPERATION_DELETE,
                            BatchStagingService.OPERATION_EXISTS,
                            BatchStagingService.OPERATION_LENGTH))
                    .as("an operation tag is a fixed word, never a key or a caller-supplied value")
                    .containsExactly("read", "write", "upload", "delete", "exists", "length")
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("states the segment vocabulary the name rule refuses")
        void statesTheSegmentVocabulary() {
            assertThat(BatchStagingService.KEY_SEGMENT_SEPARATOR).isEqualTo('/');
            assertThat(BatchStagingService.PARENT_SEGMENT).isEqualTo("..");
            assertThat(BatchStagingService.CURRENT_SEGMENT).isEqualTo(".");
            assertThat(BatchStagingService.MAX_KEY_LENGTH).isEqualTo(1024);
        }
    }
}
