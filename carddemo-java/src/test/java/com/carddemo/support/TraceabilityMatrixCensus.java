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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a member's paragraph-unit count out of the published traceability matrix.
 *
 * <h2>Why a reader rather than a constant</h2>
 * The matrix is the frozen model the integration sign-off is granted against: 544 rows, being the 528
 * paragraphs of the twenty-eight programs' procedure divisions plus the fourteen and two supplied by the
 * two procedural copybooks. A suite that wants to state how many units its member contributes has exactly
 * two options. It can write the figure down as its own constant, which is how six suites came to publish
 * six figures the matrix does not carry - 88 against 85, 37 against 34, 42 against 39, 48 against 45, 27
 * against 26 and 38 against 35 - each arrived at by counting something the matrix deliberately does not
 * count: identification-division entries, an inline copy directive, or a procedural copybook's paragraphs
 * a second time against every member that includes it. Or it can read the figure from the matrix, which is
 * what this class does.
 *
 * <p>The figure is taken from the matrix <strong>three independent ways</strong> and the three must agree,
 * so this is a measurement of the document rather than a restatement of one line of it:
 * <ul>
 *   <li>the subtotal beside the member in the census table;</li>
 *   <li>the count the member's own section declares in its opening sentence;</li>
 *   <li>the number of data rows in the matrix that cite that member.</li>
 * </ul>
 * A matrix whose three statements disagree fails here, and a suite claiming a figure the matrix does not
 * carry fails at its own assertion. Neither can be satisfied by editing one place.
 *
 * <p><strong>A procedural copybook is counted once, in its own section.</strong> {@code CSUTLDPY.cpy}
 * contributes fourteen units and {@code CSSTRPFY.cpy} two, and those sixteen belong to the copybooks, not
 * to the members that include them - the second copybook is included by five members, so counting its
 * paragraphs against each of them would report ten units for two and the total would no longer be 544.
 *
 * <p>Provenance: the matrix cites checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec} and
 * upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Only counts, member names
 * and paragraph names are read here; no legacy source text is involved.
 */
public final class TraceabilityMatrixCensus {

    /** The published matrix, relative to the module directory the build runs in. */
    public static final Path MATRIX = Path.of("..", "docs", "traceability-matrix.md");

    /** Total data rows the matrix carries, which is the gated invariant of the sign-off. */
    public static final int TOTAL_UNITS = 544;

    /** Where a program member is cited from. */
    private static final String PROGRAM_DIRECTORY = "app/cbl/";

    /** Where a copybook member is cited from. */
    private static final String COPYBOOK_DIRECTORY = "app/cpy/";

    /** Not instantiable: this is a reader over a document, and it holds no state. */
    private TraceabilityMatrixCensus() {
        throw new AssertionError("TraceabilityMatrixCensus is a reader and is never constructed");
    }

    /**
     * Reads how many paragraph units the matrix records against one member.
     *
     * @param  member the member file name as the matrix cites it, such as {@code COACTUPC.cbl} or
     *                {@code CSSTRPFY.cpy}
     * @return the paragraph-unit count the matrix records, which its census subtotal, its section heading
     *         and its rows all agree on
     * @throws IllegalStateException if the matrix does not carry the member, or if its three statements of
     *                               the figure disagree
     */
    public static int unitsOf(final String member) {
        final String matrix = read();
        final int subtotal = censusSubtotalOf(matrix, member);
        final int declared = declaredUnitsOf(matrix, member);
        final int rows = rowsCiting(matrix, member);

        if (subtotal != declared || subtotal != rows) {
            throw new IllegalStateException("the traceability matrix disagrees with itself about "
                    + member + ": census subtotal " + subtotal + ", section heading " + declared
                    + ", rows " + rows);
        }
        return subtotal;
    }

    /**
     * Counts every data row the matrix carries, across all members.
     *
     * @return the number of rows citing a legacy member
     */
    public static int totalRows() {
        final String matrix = read();
        int rows = 0;
        for (final String line : matrix.split("\n", -1)) {
            if (line.startsWith("| " + PROGRAM_DIRECTORY) || line.startsWith("| " + COPYBOOK_DIRECTORY)) {
                rows++;
            }
        }
        return rows;
    }

    /**
     * Reads the published matrix.
     *
     * @return its whole text
     */
    private static String read() {
        try {
            return Files.readString(MATRIX, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("the traceability matrix could not be read at "
                    + MATRIX.toAbsolutePath(), unreadable);
        }
    }

    /**
     * Reads the subtotal beside the member in the census table.
     *
     * @param  matrix the matrix text
     * @param  member the member file name
     * @return the subtotal
     */
    private static int censusSubtotalOf(final String matrix, final String member) {
        final Matcher subtotal = Pattern.compile("\\|\\s*`" + Pattern.quote(member)
                + "`\\s*\\|\\s*(\\d+)\\s*\\|").matcher(matrix);
        if (!subtotal.find()) {
            throw new IllegalStateException("the traceability matrix census names no member " + member);
        }
        return Integer.parseInt(subtotal.group(1));
    }

    /**
     * Reads the count the member's own section declares.
     *
     * @param  matrix the matrix text
     * @param  member the member file name
     * @return the declared count
     */
    private static int declaredUnitsOf(final String matrix, final String member) {
        final String heading = "\n## " + member.substring(0, member.lastIndexOf('.')) + "\n";
        final int section = matrix.indexOf(heading);
        if (section < 0) {
            throw new IllegalStateException("the traceability matrix has no section for " + member);
        }
        // Sliced to the member's OWN section before matching. Searching from the heading to the end of the
        // document would let a member whose section had lost its count silently borrow the next member's,
        // reporting agreement between two figures that describe different members - which is the one
        // failure a census of this kind must not be able to miss.
        final int nextSection = matrix.indexOf("\n## ", section + heading.length());
        final String ownSection =
                nextSection < 0 ? matrix.substring(section) : matrix.substring(section, nextSection);
        final Matcher declared =
                Pattern.compile("\\*\\*(\\d+) paragraph units?\\.\\*\\*").matcher(ownSection);
        if (!declared.find()) {
            throw new IllegalStateException("the section for " + member + " declares no unit count");
        }
        return Integer.parseInt(declared.group(1));
    }

    /**
     * Counts the matrix rows citing the member.
     *
     * @param  matrix the matrix text
     * @param  member the member file name
     * @return how many rows cite it
     */
    private static int rowsCiting(final String matrix, final String member) {
        final String citation = "| " + directoryOf(member) + member + " |";
        int rows = 0;
        for (final String line : matrix.split("\n", -1)) {
            if (line.startsWith(citation)) {
                rows++;
            }
        }
        if (rows == 0) {
            throw new IllegalStateException("the traceability matrix carries no row citing " + member);
        }
        return rows;
    }

    /**
     * The legacy directory the member is cited from, decided by its extension.
     *
     * @param  member the member file name
     * @return the directory prefix
     */
    private static String directoryOf(final String member) {
        return member.toLowerCase(Locale.ROOT).endsWith(".cpy")
                ? COPYBOOK_DIRECTORY
                : PROGRAM_DIRECTORY;
    }
}
