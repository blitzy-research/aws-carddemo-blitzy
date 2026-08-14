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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads one of the named sequential fixtures as a list of fixed-width record images.
 *
 * <p><strong>Which files these are.</strong> The nine sequential files under
 * {@code src/test/resources/fixtures/input} are the fixtures the plan names by filename and by measured
 * byte count. Each is a copy of a production-representative dataset, laid out as a whole number of
 * fixed-width records with one line terminator after each record &mdash; so a file of fifty
 * three-hundred-byte records measures fifteen thousand and fifty bytes, not fifteen thousand.
 *
 * <p><strong>Why an entity suite reads them.</strong> Field widths and decimal scales that are only ever
 * exercised against values a test invented can be wrong in a way the test cannot see. Driving the same
 * assertions from a real record proves the mapping carries the bytes the estate actually holds, and it
 * makes a width or scale mistake fail rather than pass.
 *
 * <p><strong>What this class checks as it loads.</strong> Every record must measure exactly the declared
 * width. A short or long record means either the fixture or the declared width is wrong, and either way
 * a suite that carried on would be asserting against misaligned fields, so loading fails immediately
 * with the offending ordinal named.
 *
 * <p><strong>Encoding.</strong> The fixtures are single-byte text, so they are read as ISO-8859-1 rather
 * than UTF-8. That keeps one byte equal to one character, which is what a fixed-width offset assumes,
 * and it cannot fail on a byte that happens not to be valid UTF-8.
 *
 * <p>Instances are immutable and safe to share.
 */
public final class SeededRecordFixture {

    /** Classpath directory holding the named sequential fixtures. */
    private static final String FIXTURE_DIRECTORY = "/fixtures/input/";

    /** The fixture's file name, retained for diagnostics. */
    private final String fileName;

    /** The declared width of one record. */
    private final int recordWidth;

    /** Every record image, in file order. */
    private final List<String> records;

    /**
     * Creates a fixture over already-validated record images.
     *
     * @param fileName    the fixture's file name
     * @param recordWidth the declared width of one record
     * @param records     the record images, in file order
     */
    private SeededRecordFixture(
            final String fileName, final int recordWidth, final List<String> records) {
        this.fileName = fileName;
        this.recordWidth = recordWidth;
        this.records = List.copyOf(records);
    }

    /**
     * Loads a named fixture and splits it into fixed-width records.
     *
     * @param fileName    the file name inside the fixture directory, such as {@code acctdata.txt}
     * @param recordWidth the width of one record in bytes, taken from the legacy copybook
     * @return the loaded fixture
     * @throws IllegalStateException    if the fixture is absent, unreadable or empty
     * @throws IllegalArgumentException if any record does not measure the declared width
     */
    public static SeededRecordFixture load(final String fileName, final int recordWidth) {
        final String content = read(fileName);
        final List<String> records = new ArrayList<>();
        int ordinal = 0;

        for (final String line : content.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            ordinal++;
            final String image = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (image.length() != recordWidth) {
                throw new IllegalArgumentException("record " + ordinal + " of " + fileName
                        + " measures " + image.length() + " bytes, but the layout declares "
                        + recordWidth);
            }
            records.add(image);
        }

        if (records.isEmpty()) {
            throw new IllegalStateException("fixture " + fileName + " holds no record");
        }
        return new SeededRecordFixture(fileName, recordWidth, records);
    }

    /**
     * Reads a fixture in full.
     *
     * @param fileName the file name inside the fixture directory
     * @return the fixture text, one character per byte
     * @throws IllegalStateException if the fixture is absent or unreadable
     */
    private static String read(final String fileName) {
        final String resource = FIXTURE_DIRECTORY + fileName;
        try (InputStream stream = SeededRecordFixture.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("fixture is not on the classpath at " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (final IOException readFailure) {
            throw new IllegalStateException("fixture at " + resource + " could not be read", readFailure);
        }
    }

    /**
     * Returns the fixture's file name.
     *
     * @return the file name, useful as an assertion description
     */
    public String fileName() {
        return fileName;
    }

    /**
     * Returns the declared width of one record.
     *
     * @return the record width in bytes
     */
    public int recordWidth() {
        return recordWidth;
    }

    /**
     * Returns how many records the fixture holds.
     *
     * @return the record count
     */
    public int recordCount() {
        return records.size();
    }

    /**
     * Returns the total byte count the fixture occupies, records plus one terminator each.
     *
     * @return the file's byte count as the record geometry implies it
     */
    public int impliedByteCount() {
        return records.size() * (recordWidth + 1);
    }

    /**
     * Returns one record image.
     *
     * @param ordinal the one-based position of the record, counting from the start of the file
     * @return the record image, exactly {@link #recordWidth()} characters wide
     * @throws IllegalArgumentException if the ordinal is outside the fixture
     */
    public String record(final int ordinal) {
        if (ordinal < 1 || ordinal > records.size()) {
            throw new IllegalArgumentException("record " + ordinal + " is outside " + fileName
                    + ", which holds " + records.size() + " records");
        }
        return records.get(ordinal - 1);
    }

    /**
     * Returns every record image, in file order.
     *
     * @return an unmodifiable list of record images
     */
    public List<String> records() {
        return records;
    }

    /**
     * Returns one field of one record, sliced by offset.
     *
     * @param ordinal the one-based record position
     * @param offset  the zero-based byte offset of the field within the record
     * @param width   the field's width in bytes
     * @return the field image, exactly {@code width} characters wide
     * @throws IllegalArgumentException if the field falls outside the record
     */
    public String field(final int ordinal, final int offset, final int width) {
        final String image = record(ordinal);
        if (offset < 0 || width < 0 || offset + width > image.length()) {
            throw new IllegalArgumentException("field at offset " + offset + " for " + width
                    + " bytes falls outside the " + image.length() + "-byte record " + ordinal
                    + " of " + fileName);
        }
        return image.substring(offset, offset + width);
    }

    /**
     * Returns a short description naming the fixture and its geometry.
     *
     * @return a diagnostic description
     */
    @Override
    public String toString() {
        return "SeededRecordFixture[" + fileName + ", " + records.size() + " records of "
                + recordWidth + " bytes]";
    }
}
