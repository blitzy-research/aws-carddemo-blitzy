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

import com.carddemo.repository.RecordWriter;
import org.mockito.Mockito;

/**
 * Builds the write-primitive double that a service test needs, so that seven test classes agree on what
 * a successful insert looks like.
 *
 * <p>A bare mock of {@link RecordWriter} answers {@code null} to every insert, which is not what a
 * successful write looks like to any caller: each of the write paragraphs reads the inserted record back
 * to compose its own success text from the stored identifier. The double therefore hands the record
 * straight back, which is what the real primitive does once the provider has assigned nothing - every key
 * in this estate is assigned by the program before the write, so an insert returns the same instance it
 * was given.
 *
 * <p>A test that needs a <em>failing</em> write re-stubs the returned double for the one call it cares
 * about, which is why this hands back the mock rather than hiding it behind an interface.
 */
public final class RecordWriterDoubles {

    private RecordWriterDoubles() {
        throw new AssertionError("RecordWriterDoubles is a factory of doubles and is never instantiated");
    }

    /**
     * @return a write-primitive double whose inserts succeed and hand the record back
     */
    public static RecordWriter passthrough() {
        return Mockito.mock(RecordWriter.class, invocation -> {
            final String method = invocation.getMethod().getName();
            if ("insert".equals(method) || "insertIndependently".equals(method)) {
                return invocation.getArgument(0);
            }
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
    }
}
