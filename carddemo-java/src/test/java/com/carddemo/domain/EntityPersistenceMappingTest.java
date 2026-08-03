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
package com.carddemo.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.carddemo.support.SchemaColumnCatalog;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.PersistentClass;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the object-relational mapping of every persistent entity against the shipped migration,
 * column by column.
 *
 * <h2>Why this file exists</h2>
 *
 * <p>Each entity's own suite verifies the record contract it carries - construction, accessor
 * transparency, decimal fidelity, business-key identity - and each deliberately excludes column
 * names, declared widths and nullability, on the stated grounds that the mapping layer is verified
 * elsewhere. This is that elsewhere. Before this file existed the exclusion pointed at nothing: the
 * runtime does set {@code spring.jpa.hibernate.ddl-auto: validate}, so a deployed instance really
 * would refuse to start on a mismatch, but no test in the module ever started a persistence context,
 * so nothing exercised that validation. A renamed column, a widened field or a wrong scale would have
 * been caught in a deployed environment and nowhere in the build.
 *
 * <h2>What the oracle is, and why it is independent</h2>
 *
 * <p>Two artefacts are compared, and neither is derived from the other. The subject is the mapping
 * the persistence provider itself computes from the annotations on the entity classes, obtained by
 * bootstrapping Hibernate's metadata offline. The oracle is
 * {@code src/main/resources/db/migration/V1__create_schema.sql}, read through
 * {@link SchemaColumnCatalog}, which parses the shipped data-definition text. The migration is
 * authored by hand and owns the database; the annotations are authored by hand on the entities. They
 * are two independent statements of the same contract, so agreement between them is evidence rather
 * than a tautology, and no assertion here has the shape {@code f(x) == f(x)}.
 *
 * <p>A third, narrower oracle is written out by hand in this file: the field widths of the verified
 * copybook layouts. Those literals come from the estate, not from either artefact under comparison,
 * so a change made consistently to both the entity and the migration still fails here.
 *
 * <h2>Bootstrapped offline, so this stays a unit suite</h2>
 *
 * <p>The provider's metadata is built with JDBC metadata access disabled and the PostgreSQL dialect
 * named explicitly, so no database is contacted, no container starts and no socket opens. This is
 * what lets a mapping check run in the fast suite. The dialect is named rather than detected because
 * the rendered type text is dialect-specific and the assertions below compare that text against the
 * migration's; detection is impossible with no database present.
 *
 * <p>Reflection is used, and that is deliberate and in budget. The zero-reflection constraint is
 * scoped to {@code src/main/java} because its purpose is to keep the deployed record mappers free of
 * it; test sources are outside that scope, and {@link SchemaColumnCatalog} already relies on the same
 * licence. Inspecting the provider's own computed metadata is also strictly stronger than reading the
 * annotations directly, because it observes naming strategy, implicit defaults and identifier-class
 * resolution rather than only what is written at the declaration site.
 *
 * <h2>Two facts this file records rather than merely asserts</h2>
 *
 * <p>The migration declares eleven tables and the module now ships eleven entities, so every declared
 * table carries an entity class and no gap remains. The closure is asserted explicitly below, in the
 * same form the gap itself was asserted in, so it stays a recorded and reviewable fact rather than
 * something noticed by nobody. It was a two-table gap, narrowed when the card cross-reference entity
 * arrived and closed when the transaction entity followed, which is the mechanism working as intended:
 * the inventory is hand-written precisely so that an entity cannot be added without its mapping being
 * brought under assertion at the same time.
 *
 * <p>For all three composite keys the key column <em>set</em> agrees between provider and migration
 * while the declared <em>order</em> does not: the provider orders identifier-class columns
 * alphabetically, and the migration declares them in legacy copybook order. Uniqueness is
 * order-independent, so this is not a correctness defect, and schema validation does not compare
 * constraint column order, so it is invisible at start-up. It is asserted here in both forms - set
 * equality, and the order divergence itself - so that the difference is documented instead of
 * silently discovered later by someone tuning a prefix scan.
 *
 * <h2>Provenance</h2>
 *
 * <p>Layout widths are cited from the estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, whose members carry the upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced here;
 * member names, field names, widths and codes are cited only.
 */
@DisplayName("Entity persistence mapping: the provider's mapping against the shipped migration")
final class EntityPersistenceMappingTest {

    /** Base package of the persistent entities. */
    private static final String DOMAIN_PACKAGE = "com.carddemo.domain.";

    /**
     * Every entity the module ships, paired with the table it maps to.
     *
     * <p>Written out by hand from the entity inventory of the plan rather than discovered by scanning
     * the package, so that an entity added without a mapping assertion, or one silently deleted, fails
     * the inventory test below.
     */
    private static final Map<String, String> ENTITY_TABLES = entityTables();

    /**
     * The number of columns each mapped table carries.
     *
     * <p>Derived from the verified record layouts: the trailing filler of each legacy record is
     * deliberately neither an attribute nor a column, so a table carries one column per mapped field
     * plus, on the two optimistically locked tables, the version counter.
     */
    private static final Map<String, Integer> EXPECTED_COLUMN_COUNTS = expectedColumnCounts();

    /** The tables that carry an optimistic-lock counter, which is only the two updated online. */
    private static final Set<String> VERSIONED_TABLES = Set.of("account", "card");

    /**
     * Tables the migration declares for which the module ships no entity class.
     *
     * <p>Empty: every declared table is now mapped. It is kept, rather than deleted along with the
     * last gap it held, because the assertions below compare the computed set of unmapped tables
     * against it in both directions - so a table added to the migration without an entity, or an
     * entity quietly dropped, still fails here.
     */
    private static final Set<String> TABLES_WITHOUT_ENTITY = Set.of();

    /** The primary key column set of each mapped table, as the migration declares it. */
    private static final Map<String, Set<String>> EXPECTED_KEY_COLUMNS = expectedKeyColumns();

    /**
     * Widths taken from the verified copybook layouts, keyed {@code table.column}.
     *
     * <p>This is the third oracle described in the class documentation: these literals are cited from
     * the estate rather than read from either the entity annotations or the migration, so a width
     * changed consistently in both still fails here. Only character columns appear; the decimal
     * columns are covered by their own scale assertions and the version counter has no legacy field.
     */
    private static final Map<String, Integer> COPYBOOK_WIDTHS = copybookWidths();

    /** Every monetary or rate column, paired with the exact numeric type the migration declares. */
    private static final Map<String, String> DECIMAL_TYPES = decimalTypes();

    /**
     * The columns that hold an encrypted value, and are therefore sized for ciphertext rather than for
     * the legacy field they carry.
     */
    private static final Set<String> ENCRYPTED_COLUMNS =
            Set.of("customer.cust_ssn", "customer.govt_issued_id");

    /** Width every encrypted column is declared at, ciphertext being far wider than its plaintext. */
    private static final int ENCRYPTED_COLUMN_WIDTH = 255;

    /** The one column deliberately wider than its copybook field, and the width it carries. */
    private static final String CREDENTIAL_COLUMN = "user_security.sec_usr_pwd";

    /** Width of a BCrypt digest, which is what the credential column stores. */
    private static final int BCRYPT_DIGEST_WIDTH = 60;

    /** Width the legacy copybook declares for the credential field it replaces. */
    private static final int LEGACY_CREDENTIAL_WIDTH = 8;

    /** The migration text, parsed once, serving as the data-definition oracle. */
    private static final SchemaColumnCatalog SCHEMA = SchemaColumnCatalog.load();

    /** Registry backing {@link #metadata}; released once the class finishes. */
    private static StandardServiceRegistry registry;

    /** The provider's computed mapping for every entity, built once for the whole class. */
    private static Metadata metadata;

    /**
     * Builds the entity inventory.
     *
     * @return each entity's simple class name paired with its mapped table name
     */
    private static Map<String, String> entityTables() {
        final Map<String, String> tables = new LinkedHashMap<>();
        tables.put("Account", "account");
        tables.put("Card", "card");
        tables.put("CardCrossReference", "card_cross_reference");
        tables.put("Customer", "customer");
        tables.put("DailyTransaction", "daily_transaction");
        tables.put("DisclosureGroup", "disclosure_group");
        tables.put("Transaction", "transaction");
        tables.put("TransactionCategory", "transaction_category");
        tables.put("TransactionCategoryBalance", "transaction_category_balance");
        tables.put("TransactionType", "transaction_type");
        tables.put("UserSecurity", "user_security");
        return Map.copyOf(tables);
    }

    /**
     * Builds the per-table column count expectation.
     *
     * @return each mapped table paired with the number of columns it carries
     */
    private static Map<String, Integer> expectedColumnCounts() {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("account", 13);
        counts.put("card", 7);
        counts.put("card_cross_reference", 3);
        counts.put("customer", 18);
        counts.put("daily_transaction", 13);
        counts.put("disclosure_group", 4);
        counts.put("transaction", 13);
        counts.put("transaction_category", 3);
        counts.put("transaction_category_balance", 4);
        counts.put("transaction_type", 2);
        counts.put("user_security", 5);
        return Map.copyOf(counts);
    }

    /**
     * Builds the primary key expectation.
     *
     * @return each mapped table paired with its primary key column set
     */
    private static Map<String, Set<String>> expectedKeyColumns() {
        final Map<String, Set<String>> keys = new LinkedHashMap<>();
        keys.put("account", Set.of("acct_id"));
        keys.put("card", Set.of("card_num"));
        keys.put("card_cross_reference", Set.of("xref_card_num"));
        keys.put("customer", Set.of("cust_id"));
        keys.put("daily_transaction", Set.of("dalytran_id"));
        keys.put("disclosure_group",
                Set.of("dis_acct_group_id", "dis_tran_type_cd", "dis_tran_cat_cd"));
        keys.put("transaction", Set.of("tran_id"));
        keys.put("transaction_category", Set.of("tran_type_cd", "tran_cat_cd"));
        keys.put("transaction_category_balance",
                Set.of("trancat_acct_id", "trancat_type_cd", "trancat_cd"));
        keys.put("transaction_type", Set.of("tran_type"));
        keys.put("user_security", Set.of("sec_usr_id"));
        return Map.copyOf(keys);
    }

    /**
     * Builds the copybook width oracle.
     *
     * @return each character column, keyed {@code table.column}, paired with its legacy field width
     */
    private static Map<String, Integer> copybookWidths() {
        final Map<String, Integer> widths = new LinkedHashMap<>();
        // Account record, app/cpy/CVACT01Y.cpy, 300 bytes.
        widths.put("account.acct_id", 11);
        widths.put("account.acct_active_status", 1);
        widths.put("account.acct_open_date", 10);
        widths.put("account.acct_expiration_date", 10);
        widths.put("account.acct_reissue_date", 10);
        widths.put("account.acct_addr_zip", 10);
        widths.put("account.acct_group_id", 10);
        // Card record, app/cpy/CVACT02Y.cpy, 150 bytes.
        widths.put("card.card_num", 16);
        widths.put("card.card_acct_id", 11);
        widths.put("card.card_cvv_cd", 3);
        widths.put("card.card_embossed_name", 50);
        widths.put("card.card_expiration_date", 10);
        widths.put("card.card_active_status", 1);
        // Card cross-reference record, app/cpy/CVACT03Y.cpy, 50 bytes of which only 36 carry data: the
        // trailing 14 bytes are filler and are mapped by no column, which is why three widths summing
        // to 36 account for the whole of this table.
        widths.put("card_cross_reference.xref_card_num", 16);
        widths.put("card_cross_reference.xref_cust_id", 9);
        widths.put("card_cross_reference.xref_acct_id", 11);
        // Customer record, app/cpy/CVCUS01Y.cpy, 500 bytes.
        widths.put("customer.cust_id", 9);
        widths.put("customer.first_name", 25);
        widths.put("customer.middle_name", 25);
        widths.put("customer.last_name", 25);
        widths.put("customer.addr_line_1", 50);
        widths.put("customer.addr_line_2", 50);
        widths.put("customer.addr_line_3", 50);
        widths.put("customer.addr_state_cd", 2);
        widths.put("customer.addr_country_cd", 3);
        widths.put("customer.addr_zip", 10);
        widths.put("customer.phone_num_1", 15);
        widths.put("customer.phone_num_2", 15);
        widths.put("customer.cust_dob", 10);
        widths.put("customer.eft_account_id", 10);
        widths.put("customer.pri_card_holder_ind", 1);
        widths.put("customer.fico_credit_score", 3);
        // Daily transaction record, app/cpy/CVTRA06Y.cpy, 350 bytes.
        widths.put("daily_transaction.dalytran_id", 16);
        widths.put("daily_transaction.dalytran_type_cd", 2);
        widths.put("daily_transaction.dalytran_cat_cd", 4);
        widths.put("daily_transaction.dalytran_source", 10);
        widths.put("daily_transaction.dalytran_desc", 100);
        widths.put("daily_transaction.dalytran_merchant_id", 9);
        widths.put("daily_transaction.dalytran_merchant_name", 50);
        widths.put("daily_transaction.dalytran_merchant_city", 50);
        widths.put("daily_transaction.dalytran_merchant_zip", 10);
        widths.put("daily_transaction.dalytran_card_num", 16);
        widths.put("daily_transaction.dalytran_orig_ts", 26);
        widths.put("daily_transaction.dalytran_proc_ts", 26);
        // Disclosure group record, app/cpy/CVTRA02Y.cpy, 50 bytes.
        widths.put("disclosure_group.dis_acct_group_id", 10);
        widths.put("disclosure_group.dis_tran_type_cd", 2);
        widths.put("disclosure_group.dis_tran_cat_cd", 4);
        // Posted transaction record, app/cpy/CVTRA05Y.cpy, 350 bytes. Byte-for-byte parallel to the
        // daily transaction record above, but note the four merchant columns: this table names them
        // for the merchant attribute alone while the daily table prefixes them, so the two blocks
        // deliberately differ rather than one being a copy of the other.
        widths.put("transaction.tran_id", 16);
        widths.put("transaction.tran_type_cd", 2);
        widths.put("transaction.tran_cat_cd", 4);
        widths.put("transaction.tran_source", 10);
        widths.put("transaction.tran_desc", 100);
        widths.put("transaction.merchant_id", 9);
        widths.put("transaction.merchant_name", 50);
        widths.put("transaction.merchant_city", 50);
        widths.put("transaction.merchant_zip", 10);
        widths.put("transaction.tran_card_num", 16);
        widths.put("transaction.tran_orig_ts", 26);
        widths.put("transaction.tran_proc_ts", 26);
        // Transaction category record, app/cpy/CVTRA04Y.cpy, 60 bytes.
        widths.put("transaction_category.tran_type_cd", 2);
        widths.put("transaction_category.tran_cat_cd", 4);
        widths.put("transaction_category.tran_cat_type_desc", 50);
        // Transaction category balance record, app/cpy/CVTRA01Y.cpy, 50 bytes.
        widths.put("transaction_category_balance.trancat_acct_id", 11);
        widths.put("transaction_category_balance.trancat_type_cd", 2);
        widths.put("transaction_category_balance.trancat_cd", 4);
        // Transaction type record, app/cpy/CVTRA03Y.cpy, 60 bytes.
        widths.put("transaction_type.tran_type", 2);
        widths.put("transaction_type.tran_type_desc", 50);
        // User security record, app/cpy/CSUSR01Y.cpy, 80 bytes. The credential field is deliberately
        // absent: it is the one column whose width departs from its copybook field, and it has its own
        // assertion below rather than an entry here.
        widths.put("user_security.sec_usr_id", 8);
        widths.put("user_security.sec_usr_fname", 20);
        widths.put("user_security.sec_usr_lname", 20);
        widths.put("user_security.sec_usr_type", 1);
        return Map.copyOf(widths);
    }

    /**
     * Builds the decimal type oracle.
     *
     * <p>Every entry has scale two, because every legacy field these carry is a {@code V99} zoned
     * decimal. The precision differs by field: the five account amounts are ten digits before the
     * decimal point, the transaction amount and the category balance are nine, and the interest rate
     * is four.
     *
     * @return each decimal column, keyed {@code table.column}, paired with its declared type
     */
    private static Map<String, String> decimalTypes() {
        final Map<String, String> types = new LinkedHashMap<>();
        types.put("account.acct_curr_bal", "NUMERIC(12,2)");
        types.put("account.acct_credit_limit", "NUMERIC(12,2)");
        types.put("account.acct_cash_credit_limit", "NUMERIC(12,2)");
        types.put("account.acct_curr_cyc_credit", "NUMERIC(12,2)");
        types.put("account.acct_curr_cyc_debit", "NUMERIC(12,2)");
        types.put("daily_transaction.dalytran_amt", "NUMERIC(11,2)");
        types.put("transaction.tran_amt", "NUMERIC(11,2)");
        types.put("transaction_category_balance.tran_cat_bal", "NUMERIC(11,2)");
        types.put("disclosure_group.dis_int_rate", "NUMERIC(6,2)");
        return Map.copyOf(types);
    }

    /**
     * Bootstraps the provider's metadata once for the whole class, with no database present.
     *
     * <p>JDBC metadata access is disabled so nothing is contacted, and the dialect is named because it
     * cannot be detected without a connection and because the rendered type text compared below is
     * dialect-specific.
     */
    @BeforeAll
    static void bootstrapProviderMetadata() {
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .build();
        final MetadataSources sources = new MetadataSources(registry);
        for (final String entity : ENTITY_TABLES.keySet()) {
            sources.addAnnotatedClassName(DOMAIN_PACKAGE + entity);
        }
        metadata = sources.buildMetadata();
    }

    /** Releases the registry built for this class. */
    @AfterAll
    static void releaseProviderMetadata() {
        if (registry != null) {
            StandardServiceRegistryBuilder.destroy(registry);
            registry = null;
            metadata = null;
        }
    }

    /**
     * Supplies every entity paired with its table, for the parameterized mapping tests.
     *
     * @return each entity simple name paired with its mapped table name
     */
    static List<org.junit.jupiter.params.provider.Arguments> everyEntity() {
        final List<org.junit.jupiter.params.provider.Arguments> arguments = new ArrayList<>();
        ENTITY_TABLES.forEach((entity, table) ->
                arguments.add(org.junit.jupiter.params.provider.Arguments.of(entity, table)));
        return List.copyOf(arguments);
    }

    /**
     * Returns the provider's binding for an entity.
     *
     * @param entity the entity's simple class name
     * @return the provider's computed mapping for that entity
     */
    private static PersistentClass bindingOf(final String entity) {
        final PersistentClass binding = metadata.getEntityBinding(DOMAIN_PACKAGE + entity);
        assertThat(binding).as("the provider must bind %s as an entity", entity).isNotNull();
        return binding;
    }

    /**
     * Returns the columns the provider maps for an entity, in the provider's own order.
     *
     * @param entity the entity's simple class name
     * @return every column of that entity's table
     */
    private static Collection<Column> columnsOf(final String entity) {
        return bindingOf(entity).getTable().getColumns();
    }

    /**
     * Returns the names of the columns the provider maps for an entity.
     *
     * @param entity the entity's simple class name
     * @return that entity's column names, sorted so comparison is order-independent
     */
    private static Set<String> columnNamesOf(final String entity) {
        return new TreeSet<>(columnsOf(entity).stream().map(Column::getName).toList());
    }

    /**
     * Returns the names of the primary key columns the provider computes for an entity, in the
     * provider's declared order.
     *
     * @param entity the entity's simple class name
     * @return that entity's key column names, order preserved
     */
    private static List<String> keyColumnNamesOf(final String entity) {
        return bindingOf(entity).getTable().getPrimaryKey().getColumns().stream()
                .map(Column::getName)
                .toList();
    }

    /**
     * Finds one mapped column of an entity by name.
     *
     * @param entity the entity's simple class name
     * @param columnName the column to find
     * @return that column
     */
    private static Column columnOf(final String entity, final String columnName) {
        return columnsOf(entity).stream()
                .filter(column -> column.getName().equals(columnName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the provider maps no column named " + columnName + " on " + entity));
    }

    /**
     * Returns the width the provider maps for a character column, as an {@code int}.
     *
     * <p>The narrowing here is deliberate rather than incidental. {@link Column#getLength()} answers a
     * boxed {@link Long}, and a boxed {@code Long} never compares equal to a boxed {@link Integer}
     * however identical the two widths are, so comparing the provider's answer straight against an
     * {@code Integer} oracle silently fails on every column. Narrowing once, here, keeps both sides of
     * every width comparison in the same type.
     *
     * <p>{@link Math#toIntExact(long)} is used rather than a cast so that a width which genuinely
     * exceeded {@code int} range would raise instead of wrapping around into a plausible-looking
     * smaller number.
     *
     * @param entity the entity simple class name
     * @param columnName the mapped column name
     * @return the mapped width of that column
     */
    private static int mappedWidthOf(final String entity, final String columnName) {
        final Column column = columnOf(entity, columnName);
        final Long width = column.getLength();

        assertThat(width).as("the provider maps no width for %s.%s", entity, columnName).isNotNull();

        return Math.toIntExact(width);
    }

    /**
     * Returns the entity that maps a given table.
     *
     * @param table the mapped table name
     * @return that table's entity simple class name
     */
    private static String entityFor(final String table) {
        return ENTITY_TABLES.entrySet().stream()
                .filter(entry -> entry.getValue().equals(table))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entity maps table " + table));
    }

    /** The inventory of entities and the tables they map. */
    @Nested
    @DisplayName("Mapped entities")
    class MappedEntities {

        /**
         * The provider binds every entity the module ships, to the table named on it.
         *
         * @param entity the entity's simple class name
         * @param table the table it must map to
         */
        @ParameterizedTest(name = "{0} maps to {1}")
        @MethodSource("com.carddemo.domain.EntityPersistenceMappingTest#everyEntity")
        @DisplayName("every entity binds to the table named on it")
        void everyEntityBindsToItsTable(final String entity, final String table) {
            assertThat(bindingOf(entity).getTable().getName()).isEqualTo(table);
        }

        /** Eleven entities are mapped, and each to a table of its own. */
        @Test
        @DisplayName("eleven entities are mapped, each to a distinct table")
        void elevenEntitiesAreMappedEachToADistinctTable() {
            assertThat(ENTITY_TABLES).hasSize(11);
            assertThat(new LinkedHashSet<>(ENTITY_TABLES.values())).hasSize(11);
        }

        /** Every mapped table is one the migration declares. */
        @Test
        @DisplayName("every mapped table is declared by the migration")
        void everyMappedTableIsDeclaredByTheMigration() {
            assertThat(SCHEMA.tableNames()).containsAll(ENTITY_TABLES.values());
        }

        /**
         * Every table the migration declares is mapped by an entity, and that is recorded here rather
         * than left to be noticed by nobody. The comparison runs in both directions, so it fails just
         * as loudly if a table were added to the migration without an entity to map it.
         */
        @Test
        @DisplayName("the migration declares no table that an entity fails to map")
        void theMigrationDeclaresNoTableWithoutAnEntity() {
            final Set<String> unmapped = new TreeSet<>(SCHEMA.tableNames());
            unmapped.removeAll(ENTITY_TABLES.values());

            assertThat(unmapped)
                    .as("an entity added for that table must be added to this suite's inventory")
                    .isEqualTo(new TreeSet<>(TABLES_WITHOUT_ENTITY));
        }

        /** The migration declares eleven tables in total, and all eleven are mapped. */
        @Test
        @DisplayName("the migration declares eleven tables, each of which carries an entity")
        void theMigrationDeclaresElevenTables() {
            assertThat(SCHEMA.tableNames()).hasSize(11);
            assertThat(ENTITY_TABLES.values()).hasSize(11);
            assertThat(TABLES_WITHOUT_ENTITY).isEmpty();
        }
    }

    /** Which columns exist, on both sides of the comparison. */
    @Nested
    @DisplayName("Column inventory")
    class ColumnInventory {

        /**
         * The provider maps exactly the columns the migration declares, in both directions: a column
         * present in one and absent from the other fails here.
         *
         * @param entity the entity's simple class name
         * @param table its mapped table name
         */
        @ParameterizedTest(name = "{1} maps exactly the columns the migration declares")
        @MethodSource("com.carddemo.domain.EntityPersistenceMappingTest#everyEntity")
        @DisplayName("every table's mapped columns are exactly the migration's columns")
        void everyTablesMappedColumnsAreExactlyTheMigrationsColumns(final String entity,
                final String table) {
            assertThat(columnNamesOf(entity))
                    .as("mapped columns of %s", table)
                    .isEqualTo(new TreeSet<>(SCHEMA.columnNames(table)));
        }

        /**
         * Each table carries the number of columns its record layout implies.
         *
         * @param entity the entity's simple class name
         * @param table its mapped table name
         */
        @ParameterizedTest(name = "{1} carries its expected column count")
        @MethodSource("com.carddemo.domain.EntityPersistenceMappingTest#everyEntity")
        @DisplayName("every table carries the column count its layout implies")
        void everyTableCarriesItsExpectedColumnCount(final String entity, final String table) {
            assertThat(columnsOf(entity))
                    .as("mapped column count of %s", table)
                    .hasSize(EXPECTED_COLUMN_COUNTS.get(table));
        }

        /** Eighty-five columns are mapped across the eleven entities. */
        @Test
        @DisplayName("eighty-five columns are mapped in total")
        void eightyFiveColumnsAreMappedInTotal() {
            int mapped = 0;
            for (final String entity : ENTITY_TABLES.keySet()) {
                mapped += columnsOf(entity).size();
            }

            assertThat(mapped).isEqualTo(85);
            assertThat(EXPECTED_COLUMN_COUNTS.values().stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(85);
        }

        /**
         * No column name repeats within a table, which would silently collapse two fields onto one.
         *
         * @param entity the entity's simple class name
         * @param table its mapped table name
         */
        @ParameterizedTest(name = "{1} declares no duplicate column name")
        @MethodSource("com.carddemo.domain.EntityPersistenceMappingTest#everyEntity")
        @DisplayName("no table maps two fields onto one column")
        void noTableMapsTwoFieldsOntoOneColumn(final String entity, final String table) {
            assertThat(columnsOf(entity).stream().map(Column::getName).toList())
                    .as("column names of %s", table)
                    .doesNotHaveDuplicates();
        }

        /**
         * Every mapped column name is lower case with underscores, matching the migration's
         * convention, so no implicit naming strategy has camel-cased anything.
         *
         * @param entity the entity's simple class name
         * @param table its mapped table name
         */
        @ParameterizedTest(name = "{1} names every column in the migration's convention")
        @MethodSource("com.carddemo.domain.EntityPersistenceMappingTest#everyEntity")
        @DisplayName("every mapped column name is lower case with underscores")
        void everyMappedColumnNameIsLowerCaseWithUnderscores(final String entity,
                final String table) {
            for (final Column column : columnsOf(entity)) {
                assertThat(column.getName())
                        .as("column name on %s", table)
                        .isEqualTo(column.getName().toLowerCase(Locale.ROOT))
                        .matches("[a-z][a-z0-9_]*");
            }
        }
    }

    /** The declared type of every column, which carries its width, precision and scale. */
    @Nested
    @DisplayName("Declared types")
    class DeclaredTypes {

        /**
         * Every mapped column renders to exactly the type the migration declares.
         *
         * <p>This is the strongest single assertion in the file. The rendered text carries the type
         * name together with its width or its precision and scale, so a widened character column, a
         * lost decimal place and a changed numeric type all fail here. Comparison ignores case only,
         * because the migration is written in upper case and the provider renders in lower.
         *
         * @param entity the entity's simple class name
         * @param table its mapped table name
         */
        @ParameterizedTest(name = "every column of {1} renders as the migration declares it")
        @MethodSource("com.carddemo.domain.EntityPersistenceMappingTest#everyEntity")
        @DisplayName("every mapped column renders as exactly the type the migration declares")
        void everyMappedColumnRendersAsTheMigrationDeclaresIt(final String entity,
                final String table) {
            for (final Column column : columnsOf(entity)) {
                assertThat(column.getSqlType(metadata))
                        .as("declared type of %s.%s", table, column.getName())
                        .isEqualToIgnoringCase(SCHEMA.declaredType(table, column.getName()));
            }
        }

        /**
         * Every monetary and rate column carries scale two and its own precision, and no such column
         * is a floating-point type.
         *
         * @param qualifiedColumn the column, keyed {@code table.column}
         */
        @ParameterizedTest(name = "{0} is an exact decimal at scale two")
        @MethodSource("com.carddemo.domain.EntityPersistenceMappingTest#everyDecimalColumn")
        @DisplayName("every monetary and rate column is an exact decimal at scale two")
        void everyMonetaryColumnIsAnExactDecimalAtScaleTwo(final String qualifiedColumn) {
            final String table = qualifiedColumn.substring(0, qualifiedColumn.indexOf('.'));
            final String columnName = qualifiedColumn.substring(qualifiedColumn.indexOf('.') + 1);
            final Column column = columnOf(entityFor(table), columnName);

            assertThat(column.getSqlType(metadata))
                    .as("declared type of %s", qualifiedColumn)
                    .isEqualToIgnoringCase(DECIMAL_TYPES.get(qualifiedColumn))
                    .doesNotContainIgnoringCase("float")
                    .doesNotContainIgnoringCase("double")
                    .doesNotContainIgnoringCase("real");
            assertThat(column.getScale()).as("scale of %s", qualifiedColumn).isEqualTo(2);
            assertThat(SCHEMA.declaredScale(table, columnName)).isEqualTo(2);
        }

        /** Nine decimal columns exist, and every one of them is covered above. */
        @Test
        @DisplayName("nine decimal columns exist, and each is asserted")
        void nineDecimalColumnsExistAndEachIsAsserted() {
            final Set<String> rendered = new TreeSet<>();
            for (final Map.Entry<String, String> entry : ENTITY_TABLES.entrySet()) {
                for (final Column column : columnsOf(entry.getKey())) {
                    if (column.getSqlType(metadata).toLowerCase(Locale.ROOT).startsWith("numeric")) {
                        rendered.add(entry.getValue() + "." + column.getName());
                    }
                }
            }

            assertThat(rendered).isEqualTo(new TreeSet<>(DECIMAL_TYPES.keySet())).hasSize(9);
        }

        /**
         * Every character column matches the width of the legacy field it carries.
         *
         * <p>The oracle here is neither the entity nor the migration but the copybook layout, so a
         * width changed consistently in both still fails. The encrypted columns and the credential
         * column are excluded because each deliberately departs from its field width, and each has its
         * own assertion.
         *
         * <p>Both sides are collected into maps and compared whole rather than asserted one column at
         * a time. A per-column assertion inside a loop stops at the first mismatch and says nothing
         * about the columns behind it, which would let a second discrepancy hide behind the first; a
         * whole-map comparison reports every disagreeing column in one failure.
         */
        @Test
        @DisplayName("every character column matches its copybook field width")
        void everyCharacterColumnMatchesItsCopybookFieldWidth() {
            final Map<String, Integer> mapped = new LinkedHashMap<>();
            final Map<String, Integer> declared = new LinkedHashMap<>();

            for (final String qualifiedColumn : COPYBOOK_WIDTHS.keySet()) {
                final String table = qualifiedColumn.substring(0, qualifiedColumn.indexOf('.'));
                final String columnName = qualifiedColumn.substring(qualifiedColumn.indexOf('.') + 1);

                mapped.put(qualifiedColumn, mappedWidthOf(entityFor(table), columnName));
                declared.put(qualifiedColumn, SCHEMA.declaredWidth(table, columnName));
            }

            assertThat(mapped)
                    .as("the width the provider maps for every character column")
                    .isEqualTo(COPYBOOK_WIDTHS);
            assertThat(declared)
                    .as("the width the migration declares for every character column")
                    .isEqualTo(COPYBOOK_WIDTHS);
        }

        /**
         * The copybook width oracle covers every character column except the three that deliberately
         * depart from their field width, so no column escapes it unnoticed.
         */
        @Test
        @DisplayName("the copybook width oracle covers every character column but the three exceptions")
        void theCopybookWidthOracleCoversEveryCharacterColumnButTheExceptions() {
            final Set<String> characterColumns = new TreeSet<>();
            for (final Map.Entry<String, String> entry : ENTITY_TABLES.entrySet()) {
                for (final Column column : columnsOf(entry.getKey())) {
                    if (column.getSqlType(metadata).toLowerCase(Locale.ROOT).startsWith("varchar")) {
                        characterColumns.add(entry.getValue() + "." + column.getName());
                    }
                }
            }
            final Set<String> covered = new TreeSet<>(COPYBOOK_WIDTHS.keySet());
            covered.addAll(ENCRYPTED_COLUMNS);
            covered.add(CREDENTIAL_COLUMN);

            assertThat(characterColumns).isEqualTo(covered);
        }

        /**
         * The two encrypted columns are sized for ciphertext rather than for the legacy field they
         * carry, in both the mapping and the migration.
         *
         * @param qualifiedColumn the column, keyed {@code table.column}
         */
        @ParameterizedTest(name = "{0} is sized for ciphertext")
        @ValueSource(strings = {"customer.cust_ssn", "customer.govt_issued_id"})
        @DisplayName("every encrypted column is sized for ciphertext, not for its legacy field")
        void everyEncryptedColumnIsSizedForCiphertext(final String qualifiedColumn) {
            final String table = qualifiedColumn.substring(0, qualifiedColumn.indexOf('.'));
            final String columnName = qualifiedColumn.substring(qualifiedColumn.indexOf('.') + 1);

            assertThat(columnOf(entityFor(table), columnName).getLength())
                    .as("mapped width of %s", qualifiedColumn)
                    .isEqualTo(ENCRYPTED_COLUMN_WIDTH);
            assertThat(SCHEMA.declaredWidth(table, columnName))
                    .isEqualTo(ENCRYPTED_COLUMN_WIDTH);
            assertThat(ENCRYPTED_COLUMNS).contains(qualifiedColumn);
        }

        /**
         * The credential column is sized for a BCrypt digest, which is far wider than the legacy
         * plaintext field it replaces. This is the documented parity exception, and it is asserted
         * rather than assumed so that a change back towards the legacy width fails loudly.
         */
        @Test
        @DisplayName("the credential column is sized for a digest, not for the legacy plaintext field")
        void theCredentialColumnIsSizedForADigest() {
            final Column column = columnOf("UserSecurity", "sec_usr_pwd");

            assertThat(column.getLength()).isEqualTo(BCRYPT_DIGEST_WIDTH);
            assertThat(SCHEMA.declaredWidth("user_security", "sec_usr_pwd"))
                    .isEqualTo(BCRYPT_DIGEST_WIDTH);
            assertThat(BCRYPT_DIGEST_WIDTH)
                    .as("a digest cannot fit the width the legacy copybook declared")
                    .isGreaterThan(LEGACY_CREDENTIAL_WIDTH);
            assertThat(COPYBOOK_WIDTHS)
                    .as("the credential column must stay out of the copybook width oracle")
                    .doesNotContainKey(CREDENTIAL_COLUMN);
        }
    }

    /** Which columns accept an absent value. */
    @Nested
    @DisplayName("Nullability")
    class Nullability {

        /**
         * Every mapped column's nullability equals the migration's.
         *
         * @param entity the entity's simple class name
         * @param table its mapped table name
         */
        @ParameterizedTest(name = "every column of {1} agrees with the migration on nullability")
        @MethodSource("com.carddemo.domain.EntityPersistenceMappingTest#everyEntity")
        @DisplayName("every mapped column agrees with the migration on nullability")
        void everyMappedColumnAgreesWithTheMigrationOnNullability(final String entity,
                final String table) {
            for (final Column column : columnsOf(entity)) {
                assertThat(column.isNullable())
                        .as("nullability of %s.%s", table, column.getName())
                        .isEqualTo(SCHEMA.isNullable(table, column.getName()));
            }
        }

        /**
         * Exactly one column in the whole mapping accepts an absent value: the encrypted national
         * identifier, which the legacy record leaves optional. Every other column is mandatory,
         * because a fixed-width record always occupies its whole width.
         */
        @Test
        @DisplayName("exactly one mapped column accepts an absent value")
        void exactlyOneMappedColumnAcceptsAnAbsentValue() {
            final Set<String> nullable = new TreeSet<>();
            for (final Map.Entry<String, String> entry : ENTITY_TABLES.entrySet()) {
                for (final Column column : columnsOf(entry.getKey())) {
                    if (column.isNullable()) {
                        nullable.add(entry.getValue() + "." + column.getName());
                    }
                }
            }

            assertThat(nullable).containsExactly("customer.cust_ssn");
        }

        /**
         * No key column is nullable, on either side of the comparison.
         *
         * @param entity the entity's simple class name
         * @param table its mapped table name
         */
        @ParameterizedTest(name = "no key column of {1} is nullable")
        @MethodSource("com.carddemo.domain.EntityPersistenceMappingTest#everyEntity")
        @DisplayName("no key column anywhere is nullable")
        void noKeyColumnAnywhereIsNullable(final String entity, final String table) {
            for (final String keyColumn : keyColumnNamesOf(entity)) {
                assertThat(columnOf(entity, keyColumn).isNullable())
                        .as("nullability of key column %s.%s", table, keyColumn)
                        .isFalse();
                assertThat(SCHEMA.isNullable(table, keyColumn)).isFalse();
            }
        }
    }

    /** The identity of every mapped row. */
    @Nested
    @DisplayName("Primary keys")
    class PrimaryKeys {

        /**
         * Every table's key column set agrees with the migration's, and with the expectation written
         * out in this file.
         *
         * @param entity the entity's simple class name
         * @param table its mapped table name
         */
        @ParameterizedTest(name = "{1} keys on exactly the migration's key columns")
        @MethodSource("com.carddemo.domain.EntityPersistenceMappingTest#everyEntity")
        @DisplayName("every table keys on exactly the migration's key columns")
        void everyTableKeysOnExactlyTheMigrationsKeyColumns(final String entity,
                final String table) {
            final Set<String> mapped = new TreeSet<>(keyColumnNamesOf(entity));

            assertThat(mapped)
                    .as("key columns of %s", table)
                    .isEqualTo(new TreeSet<>(SCHEMA.primaryKeyColumns(table)))
                    .isEqualTo(new TreeSet<>(EXPECTED_KEY_COLUMNS.get(table)));
        }

        /**
         * Every key column is one of the table's own mapped columns, so no key names a column the
         * mapping does not carry.
         *
         * @param entity the entity's simple class name
         * @param table its mapped table name
         */
        @ParameterizedTest(name = "every key column of {1} is a mapped column")
        @MethodSource("com.carddemo.domain.EntityPersistenceMappingTest#everyEntity")
        @DisplayName("every key column is one of its own table's mapped columns")
        void everyKeyColumnIsOneOfItsOwnTablesMappedColumns(final String entity,
                final String table) {
            assertThat(columnNamesOf(entity))
                    .as("mapped columns of %s", table)
                    .containsAll(keyColumnNamesOf(entity));
        }

        /** Eight tables key on one column and three on several, matching the copybook layouts. */
        @Test
        @DisplayName("eight tables key on a single column and three on a composite")
        void eightTablesKeyOnASingleColumnAndThreeOnAComposite() {
            final List<String> single = new ArrayList<>();
            final List<String> composite = new ArrayList<>();
            ENTITY_TABLES.forEach((entity, table) -> {
                if (keyColumnNamesOf(entity).size() == 1) {
                    single.add(table);
                } else {
                    composite.add(table);
                }
            });

            assertThat(single).containsExactlyInAnyOrder(
                    "account", "card", "card_cross_reference", "customer", "daily_transaction",
                    "transaction", "transaction_type", "user_security");
            assertThat(composite).containsExactlyInAnyOrder(
                    "disclosure_group", "transaction_category", "transaction_category_balance");
            assertThat(single).hasSize(8);
            assertThat(single.size() + composite.size()).isEqualTo(ENTITY_TABLES.size());
        }

        /**
         * The provider orders composite key columns alphabetically while the migration declares them
         * in legacy copybook order, and for all three composite keys those two orders differ.
         *
         * <p>This is recorded rather than corrected. Uniqueness does not depend on column order, so
         * neither side is wrong, and schema validation does not compare constraint column order, so
         * the difference cannot fail a start-up. It is asserted here so the divergence is a documented
         * fact: anyone reasoning about the backing index's usefulness for a prefix scan needs to know
         * that the provider's declared order is alphabetical and not the legacy one.
         */
        @Test
        @DisplayName("composite key column order differs between provider and migration, by design")
        void compositeKeyColumnOrderDiffersBetweenProviderAndMigration() {
            final Map<String, List<String>> migrationOrder = new LinkedHashMap<>();
            migrationOrder.put("disclosure_group",
                    List.of("dis_acct_group_id", "dis_tran_type_cd", "dis_tran_cat_cd"));
            migrationOrder.put("transaction_category", List.of("tran_type_cd", "tran_cat_cd"));
            migrationOrder.put("transaction_category_balance",
                    List.of("trancat_acct_id", "trancat_type_cd", "trancat_cd"));

            migrationOrder.forEach((table, declared) -> {
                final List<String> mapped = keyColumnNamesOf(entityFor(table));

                assertThat(SCHEMA.primaryKeyColumns(table))
                        .as("the migration declares %s in legacy order", table)
                        .containsExactlyElementsOf(declared);
                assertThat(mapped)
                        .as("the provider orders %s alphabetically", table)
                        .containsExactlyElementsOf(new ArrayList<>(new TreeSet<>(declared)));
                assertThat(mapped)
                        .as("the two orders differ for %s, which is the documented divergence", table)
                        .isNotEqualTo(declared)
                        .containsExactlyInAnyOrderElementsOf(declared);
            });
        }

        /**
         * No identifier anywhere is generated. Every key is supplied by the application, because it is
         * the legacy business key: a surrogate would sever the correspondence between a record image
         * and its row that byte-level output parity depends on.
         *
         * <p>The provider reports its chosen strategy directly, and for a key the application assigns
         * that strategy is {@code assigned}. Any identity column, sequence or table generator would
         * report something else here, so this single check covers every form a surrogate could take.
         *
         * @param entity the entity's simple class name
         * @param table its mapped table name
         */
        @ParameterizedTest(name = "{1} assigns its identifier rather than generating one")
        @MethodSource("com.carddemo.domain.EntityPersistenceMappingTest#everyEntity")
        @DisplayName("no table generates an identifier value")
        void noTableGeneratesAnIdentifierValue(final String entity, final String table) {
            final org.hibernate.mapping.KeyValue identifier = bindingOf(entity).getIdentifier();

            assertThat(identifier)
                    .as("%s identifier", table)
                    .isInstanceOf(org.hibernate.mapping.SimpleValue.class);
            final org.hibernate.mapping.SimpleValue value =
                    (org.hibernate.mapping.SimpleValue) identifier;
            assertThat(value.getIdentifierGeneratorStrategy())
                    .as("%s must assign its key, never generate it", table)
                    .isEqualTo("assigned");
            assertThat(value.getCustomIdGeneratorCreator())
                    .as("%s must declare no custom identifier generator", table)
                    .isNull();
        }

        /**
         * A single-column key is bound as a basic value and a composite key as a component, which is
         * the provider's own distinction and confirms the identifier classes really are in play for
         * the three composite tables.
         *
         * @param entity the entity's simple class name
         * @param table its mapped table name
         */
        @ParameterizedTest(name = "{1} binds its key as the provider's matching kind")
        @MethodSource("com.carddemo.domain.EntityPersistenceMappingTest#everyEntity")
        @DisplayName("a single-column key binds as a basic value and a composite key as a component")
        void aSingleColumnKeyBindsAsABasicValueAndACompositeAsAComponent(final String entity,
                final String table) {
            final boolean composite = EXPECTED_KEY_COLUMNS.get(table).size() > 1;

            assertThat(bindingOf(entity).getIdentifier() instanceof org.hibernate.mapping.Component)
                    .as("%s identifier is a component only when its key is composite", table)
                    .isEqualTo(composite);
            assertThat(bindingOf(entity).getIdentifierProperty() == null)
                    .as("%s exposes no single identifier property only when its key is composite",
                            table)
                    .isEqualTo(composite);
        }
    }

    /** The optimistic-lock counter, and the two tables that carry one. */
    @Nested
    @DisplayName("Version columns")
    class VersionColumns {

        /**
         * A version counter appears on exactly the two tables updated from the online path, and
         * nowhere else.
         *
         * @param entity the entity's simple class name
         * @param table its mapped table name
         */
        @ParameterizedTest(name = "{1} carries a version counter only if it is updated online")
        @MethodSource("com.carddemo.domain.EntityPersistenceMappingTest#everyEntity")
        @DisplayName("a version counter appears on exactly the two tables updated online")
        void aVersionCounterAppearsOnExactlyTheTwoTablesUpdatedOnline(final String entity,
                final String table) {
            final boolean expected = VERSIONED_TABLES.contains(table);

            assertThat(bindingOf(entity).getVersion() != null)
                    .as("%s version mapping", table)
                    .isEqualTo(expected);
            assertThat(columnNamesOf(entity).contains("version"))
                    .as("%s version column", table)
                    .isEqualTo(expected);
            assertThat(SCHEMA.columnNames(table).contains("version"))
                    .as("%s version column in the migration", table)
                    .isEqualTo(expected);
        }

        /**
         * Each version counter is a mandatory whole-number column, on both sides of the comparison.
         *
         * @param table the versioned table
         */
        @ParameterizedTest(name = "{0} declares its counter as a mandatory whole number")
        @ValueSource(strings = {"account", "card"})
        @DisplayName("every version counter is a mandatory whole-number column")
        void everyVersionCounterIsAMandatoryWholeNumberColumn(final String table) {
            final Column column = columnOf(entityFor(table), "version");

            assertThat(column.getSqlType(metadata))
                    .as("declared type of %s.version", table)
                    .isEqualToIgnoringCase(SCHEMA.declaredType(table, "version"))
                    .isEqualToIgnoringCase("BIGINT");
            assertThat(column.isNullable()).isFalse();
            assertThat(SCHEMA.isNullable(table, "version")).isFalse();
        }

        /**
         * The counter is named on the version mapping itself, not merely present as a column, so the
         * provider really will use it for optimistic locking.
         *
         * @param table the versioned table
         */
        @ParameterizedTest(name = "{0} uses its counter as the optimistic-lock version")
        @ValueSource(strings = {"account", "card"})
        @DisplayName("every version counter is the provider's optimistic-lock version")
        void everyVersionCounterIsTheProvidersOptimisticLockVersion(final String table) {
            assertThat(bindingOf(entityFor(table)).getVersion().getName()).isEqualTo("version");
        }

        /** Exactly two of the ten mapped tables carry a counter. */
        @Test
        @DisplayName("exactly two of the ten mapped tables carry a counter")
        void exactlyTwoOfTheTenMappedTablesCarryACounter() {
            final Set<String> versioned = new TreeSet<>();
            ENTITY_TABLES.forEach((entity, table) -> {
                if (bindingOf(entity).getVersion() != null) {
                    versioned.add(table);
                }
            });

            assertThat(versioned).isEqualTo(new TreeSet<>(VERSIONED_TABLES)).hasSize(2);
        }
    }

    /**
     * Supplies every decimal column for the scale assertions.
     *
     * @return each decimal column keyed {@code table.column}
     */
    static Set<String> everyDecimalColumn() {
        return DECIMAL_TYPES.keySet();
    }
}
