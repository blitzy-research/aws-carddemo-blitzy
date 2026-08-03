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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the deployed schema migration and exposes each table's declared columns.
 *
 * <p><strong>Why a test reads the migration.</strong> A record layout survives the migration only if
 * three descriptions of it agree: the legacy copybook that defines the byte geometry, the relational
 * column the migration creates, and the entity that carries the value at runtime. A suite that checks
 * only the entity can be satisfied by an entity and a schema that are wrong in the same way. Reading the
 * migration gives each entity suite an independent second description to compare its copybook-derived
 * constants against.
 *
 * <p><strong>Why the migration rather than the live database.</strong> The migration file is the
 * authority for what a deployment creates, and reading it needs no container, so an entity suite stays a
 * unit test. A live-database check belongs to the repository integration tier, which runs the same
 * migration.
 *
 * <p><strong>Why parsing rather than reflection.</strong> The column widths this catalogue returns are
 * read out of text. Nothing here inspects an annotation, loads a class by name or touches
 * {@code java.lang.reflect}, so the reflection-free posture the plan requires is unaffected by these
 * suites.
 *
 * <p><strong>What is deliberately not modelled.</strong> The catalogue records a column's name, its
 * declared type, the width or precision inside that type, its scale where the type carries one, whether
 * it is nullable, and the table's primary-key columns. It does not model defaults, foreign keys, checks
 * or indexes, because no entity suite asserts against those; the index definitions live in a separate
 * migration and are verified where they are used.
 *
 * <p>Instances are immutable and safe to share. The catalogue is loaded once per suite through
 * {@link #load()}.
 */
public final class SchemaColumnCatalog {

    /** Classpath location of the schema-creating migration. */
    private static final String MIGRATION_RESOURCE = "/db/migration/schema/V1__create_schema.sql";

    /** Matches one {@code CREATE TABLE name ( body );} statement, body captured lazily. */
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*?)\\)\\s*;", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** Matches the column list of a primary-key clause. */
    private static final Pattern PRIMARY_KEY_PATTERN = Pattern.compile(
            "PRIMARY\\s+KEY\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    /** Matches a parenthesised type argument list, as in {@code VARCHAR(11)} or {@code NUMERIC(12,2)}. */
    private static final Pattern TYPE_ARGUMENTS_PATTERN = Pattern.compile(
            "^(\\w+)\\s*\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)$");

    /** Every table found in the migration, keyed by table name, in declaration order. */
    private final Map<String, TableDefinition> tables;

    /**
     * Creates a catalogue over an already-parsed table map.
     *
     * @param tables the parsed tables, which the constructor takes ownership of
     */
    private SchemaColumnCatalog(final Map<String, TableDefinition> tables) {
        this.tables = Collections.unmodifiableMap(tables);
    }

    /**
     * Loads and parses the schema-creating migration from the classpath.
     *
     * @return a catalogue over every table the migration creates
     * @throws IllegalStateException if the migration cannot be found or cannot be read
     */
    public static SchemaColumnCatalog load() {
        final String sql = readMigration();
        final Map<String, TableDefinition> parsed = new LinkedHashMap<>();
        final Matcher tableMatcher = TABLE_PATTERN.matcher(stripComments(sql));

        while (tableMatcher.find()) {
            final String tableName = tableMatcher.group(1).toLowerCase(Locale.ROOT);
            parsed.put(tableName, parseTable(tableName, tableMatcher.group(2)));
        }

        if (parsed.isEmpty()) {
            throw new IllegalStateException(
                    "the schema migration " + MIGRATION_RESOURCE + " declares no table");
        }
        return new SchemaColumnCatalog(parsed);
    }

    /**
     * Reads the migration resource in full.
     *
     * @return the migration text
     * @throws IllegalStateException if the resource is absent or unreadable
     */
    private static String readMigration() {
        try (InputStream stream = SchemaColumnCatalog.class.getResourceAsStream(MIGRATION_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "the schema migration is not on the classpath at " + MIGRATION_RESOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException readFailure) {
            throw new IllegalStateException(
                    "the schema migration at " + MIGRATION_RESOURCE + " could not be read", readFailure);
        }
    }

    /**
     * Removes end-of-line comments so a comment cannot be mistaken for a column or a constraint.
     *
     * @param sql the raw migration text
     * @return the same text with every {@code --} comment removed
     */
    private static String stripComments(final String sql) {
        final StringBuilder stripped = new StringBuilder(sql.length());
        for (final String line : sql.split("\n", -1)) {
            final int commentStart = line.indexOf("--");
            stripped.append(commentStart < 0 ? line : line.substring(0, commentStart)).append('\n');
        }
        return stripped.toString();
    }

    /**
     * Parses one table body into its columns and its primary key.
     *
     * @param tableName the table's name, already lower-cased
     * @param body      the text between the table's parentheses, comments already removed
     * @return the parsed table
     */
    private static TableDefinition parseTable(final String tableName, final String body) {
        final Map<String, ColumnDefinition> columns = new LinkedHashMap<>();
        final List<String> primaryKey = new ArrayList<>();

        for (final String element : splitTopLevel(body)) {
            final String trimmed = element.trim().replaceAll("\\s+", " ");
            if (trimmed.isEmpty()) {
                continue;
            }
            final String upper = trimmed.toUpperCase(Locale.ROOT);
            if (upper.startsWith("CONSTRAINT") || upper.startsWith("PRIMARY KEY")
                    || upper.startsWith("FOREIGN KEY") || upper.startsWith("CHECK")
                    || upper.startsWith("UNIQUE")) {
                final Matcher keyMatcher = PRIMARY_KEY_PATTERN.matcher(trimmed);
                if (keyMatcher.find()) {
                    for (final String keyColumn : keyMatcher.group(1).split(",")) {
                        primaryKey.add(keyColumn.trim().toLowerCase(Locale.ROOT));
                    }
                }
                continue;
            }
            final ColumnDefinition column = parseColumn(trimmed);
            columns.put(column.name(), column);
        }

        if (columns.isEmpty()) {
            throw new IllegalStateException("table " + tableName + " declares no column");
        }
        return new TableDefinition(tableName, columns, List.copyOf(primaryKey));
    }

    /**
     * Splits a table body on the commas that separate its elements, ignoring commas inside parentheses
     * so that a type such as {@code NUMERIC(12,2)} is not torn in half.
     *
     * @param body the text between the table's parentheses
     * @return the body's top-level elements, in declaration order
     */
    private static List<String> splitTopLevel(final String body) {
        final List<String> elements = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        int depth = 0;

        for (int index = 0; index < body.length(); index++) {
            final char character = body.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
            }
            if (character == ',' && depth == 0) {
                elements.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        elements.add(current.toString());
        return elements;
    }

    /**
     * Parses one column declaration.
     *
     * @param declaration the whitespace-normalised declaration, such as
     *                    {@code acct_id VARCHAR(11) NOT NULL}
     * @return the parsed column
     */
    private static ColumnDefinition parseColumn(final String declaration) {
        final String[] tokens = declaration.split(" ");
        if (tokens.length < 2) {
            throw new IllegalStateException("column declaration [" + declaration + "] has no type");
        }
        final String name = tokens[0].toLowerCase(Locale.ROOT);
        final String type = tokens[1].toUpperCase(Locale.ROOT).replace(" ", "");
        final boolean nullable = !declaration.toUpperCase(Locale.ROOT).contains("NOT NULL");

        int precision = -1;
        int scale = -1;
        final Matcher argumentMatcher = TYPE_ARGUMENTS_PATTERN.matcher(type);
        if (argumentMatcher.matches()) {
            precision = Integer.parseInt(argumentMatcher.group(2));
            scale = argumentMatcher.group(3) == null ? -1 : Integer.parseInt(argumentMatcher.group(3));
        }
        return new ColumnDefinition(name, type, precision, scale, nullable);
    }

    /**
     * Returns the names of every table the migration creates, in declaration order.
     *
     * @return the table names
     */
    public List<String> tableNames() {
        return List.copyOf(tables.keySet());
    }

    /**
     * Returns the column names of one table, in declaration order.
     *
     * @param table the table name
     * @return the column names
     * @throws IllegalArgumentException if the migration declares no such table
     */
    public List<String> columnNames(final String table) {
        return List.copyOf(table(table).columns().keySet());
    }

    /**
     * Returns a column's declared type, including any parenthesised arguments.
     *
     * @param table  the table name
     * @param column the column name
     * @return the declared type, upper-cased and free of internal whitespace
     * @throws IllegalArgumentException if the table or the column is absent
     */
    public String declaredType(final String table, final String column) {
        return column(table, column).type();
    }

    /**
     * Returns the width a character column declares, or the precision a numeric column declares.
     *
     * @param table  the table name
     * @param column the column name
     * @return the declared width or precision
     * @throws IllegalArgumentException if the table or the column is absent
     * @throws IllegalStateException    if the column's type carries no parenthesised argument
     */
    public int declaredWidth(final String table, final String column) {
        final ColumnDefinition definition = column(table, column);
        if (definition.precision() < 0) {
            throw new IllegalStateException(
                    table + "." + column + " is declared " + definition.type() + ", which has no width");
        }
        return definition.precision();
    }

    /**
     * Returns the scale a numeric column declares.
     *
     * @param table  the table name
     * @param column the column name
     * @return the declared scale
     * @throws IllegalArgumentException if the table or the column is absent
     * @throws IllegalStateException    if the column's type carries no scale
     */
    public int declaredScale(final String table, final String column) {
        final ColumnDefinition definition = column(table, column);
        if (definition.scale() < 0) {
            throw new IllegalStateException(
                    table + "." + column + " is declared " + definition.type() + ", which has no scale");
        }
        return definition.scale();
    }

    /**
     * Reports whether a column permits a null value.
     *
     * @param table  the table name
     * @param column the column name
     * @return {@code true} when the column is not declared {@code NOT NULL}
     * @throws IllegalArgumentException if the table or the column is absent
     */
    public boolean isNullable(final String table, final String column) {
        return column(table, column).nullable();
    }

    /**
     * Returns the primary-key columns of one table, in the order the key declares them.
     *
     * @param table the table name
     * @return the primary-key columns
     * @throws IllegalArgumentException if the migration declares no such table
     */
    public List<String> primaryKeyColumns(final String table) {
        return table(table).primaryKey();
    }

    /**
     * Looks up one table.
     *
     * @param table the table name, matched case-insensitively
     * @return the table definition
     * @throws IllegalArgumentException if the migration declares no such table
     */
    private TableDefinition table(final String table) {
        final TableDefinition definition = tables.get(table.toLowerCase(Locale.ROOT));
        if (definition == null) {
            throw new IllegalArgumentException("the schema migration declares no table named " + table
                    + "; it declares " + tables.keySet());
        }
        return definition;
    }

    /**
     * Looks up one column.
     *
     * @param table  the table name, matched case-insensitively
     * @param column the column name, matched case-insensitively
     * @return the column definition
     * @throws IllegalArgumentException if the table or the column is absent
     */
    private ColumnDefinition column(final String table, final String column) {
        final TableDefinition definition = table(table);
        final ColumnDefinition columnDefinition =
                definition.columns().get(column.toLowerCase(Locale.ROOT));
        if (columnDefinition == null) {
            throw new IllegalArgumentException("table " + definition.name() + " declares no column named "
                    + column + "; it declares " + definition.columns().keySet());
        }
        return columnDefinition;
    }

    /**
     * One parsed table.
     *
     * @param name       the table's lower-cased name
     * @param columns    the table's columns, keyed by lower-cased name, in declaration order
     * @param primaryKey the primary-key columns, in key order
     */
    private record TableDefinition(
            String name, Map<String, ColumnDefinition> columns, List<String> primaryKey) {
    }

    /**
     * One parsed column.
     *
     * @param name      the column's lower-cased name
     * @param type      the declared type, upper-cased, including any parenthesised arguments
     * @param precision the character width or numeric precision, or {@code -1} when the type has none
     * @param scale     the numeric scale, or {@code -1} when the type has none
     * @param nullable  whether the column permits a null value
     */
    private record ColumnDefinition(
            String name, String type, int precision, int scale, boolean nullable) {
    }
}
