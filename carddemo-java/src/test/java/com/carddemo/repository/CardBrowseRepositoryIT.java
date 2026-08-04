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

package com.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.carddemo.domain.Card;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.support.AbstractPostgresIT;

/**
 * Verifies, against a real PostgreSQL 16 server carrying the shipped migrations, that the card side of
 * the persistence layer distinguishes a first-match read from a multi-row read, and that the card-list
 * screen's keyset browse resolves as declared in both directions and with the account filter on and off.
 *
 * <h2>Why a server, and why an extra row</h2>
 *
 * <p>Every method exercised here is a derived query, whose name the persistence provider resolves into
 * SQL at bootstrap and never the compiler, so a misspelled attribute is a start-up failure that only a
 * context surfaces. The reference seed is also one-to-one - fifty accounts, fifty cards, fifty
 * cross-reference rows - which means it can never distinguish a first-match finder from a multi-row one:
 * both return a single element for every seeded account. Each nest below therefore inserts a
 * <em>second</em> card, and a second cross-reference row, against one seeded account, proves that the
 * two finders now differ, and removes them again, so the seeded one-to-one shape is restored before the
 * next test observes it.
 *
 * <p>Provenance: the browse behaviour asserted here is that of {@code app/cbl/COCRDLIC.cbl} (the list
 * screen, seven rows, base-cluster order) and {@code app/cbl/COCRDSLC.cbl} (the keyed alternate-index
 * read), with the first-match reasoning recorded as {@code DL-121}; read as read-only reference at commit
 * SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.
 */
@DisplayName("Card browse: first match against all matches, and the keyset browse of the list screen")
final class CardBrowseRepositoryIT extends AbstractPostgresIT {

    /** The screen row count of the card list, from its seven-occurrence row table. */
    private static final int SCREEN_ROWS = 7;

    /** A seeded account identifier, from {@code app/data/ASCII/carddata.txt}. */
    private static final String SEEDED_ACCOUNT = "00000000050";

    /** The seeded card of that account, and the lower of the two once the extra one exists. */
    private static final String SEEDED_CARD = "0500024453765740";

    /** The seeded customer of that account, from {@code app/data/ASCII/cardxref.txt}. */
    private static final String SEEDED_CUSTOMER = "000000050";

    /**
     * A card number reserved for this class, chosen above the seeded one so that ascending order puts the
     * seeded card first and a first-match finder therefore has to return the seeded one.
     */
    private static final String RESERVED_CARD = "9900000000000001";

    /** Creates the test class. */
    CardBrowseRepositoryIT() {
    }

    /**
     * Registers both card-side repositories and their entities for a runner-assembled context.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = CardRepository.class)
    @EntityScan(basePackageClasses = Card.class)
    static class RepositoriesUnderTest {

        /** Creates the configuration. */
        RepositoriesUnderTest() {
        }
    }

    @Nested
    @DisplayName("the alternate-index finders of the card master")
    final class CardAlternateIndexFinders {

        /** Creates the nest. */
        CardAlternateIndexFinders() {
        }

        @Test
        @DisplayName("the first-match finder returns the lowest card number of the account while the list "
                + "finder returns every one of them, ascending - which is the distinction a one-to-one "
                + "seed cannot show")
        void firstMatchAndListDifferOnceAnAccountOwnsTwoCards() {
            runner().run(context -> {
                final CardRepository repository = context.getBean(CardRepository.class);

                assertThat(repository.findFirstByCardAcctIdOrderByCardNumAsc(SEEDED_ACCOUNT))
                        .as("the seeded account owns exactly one card")
                        .isPresent();
                assertThat(repository.findByCardAcctIdOrderByCardNumAsc(SEEDED_ACCOUNT))
                        .extracting(Card::getCardNum)
                        .containsExactly(SEEDED_CARD);
                try {
                    repository.saveAndFlush(reservedCard());

                    assertThat(repository.findFirstByCardAcctIdOrderByCardNumAsc(SEEDED_ACCOUNT))
                            .get()
                            .extracting(Card::getCardNum)
                            .as("a keyed read of a duplicate-bearing alternate index returns the first "
                                    + "record in ascending base-key order")
                            .isEqualTo(SEEDED_CARD);
                    assertThat(repository.findByCardAcctIdOrderByCardNumAsc(SEEDED_ACCOUNT))
                            .extracting(Card::getCardNum)
                            .as("the list form returns both, and the first element is the first-match row")
                            .containsExactly(SEEDED_CARD, RESERVED_CARD);
                } finally {
                    repository.deleteById(RESERVED_CARD);
                    repository.flush();
                }

                assertThat(repository.findByCardAcctIdOrderByCardNumAsc(SEEDED_ACCOUNT))
                        .as("the one-to-one seed is restored")
                        .hasSize(1);
            });
        }

        @Test
        @DisplayName("an account owning no card yields an empty list and an empty optional rather than a "
                + "failure, which is the analogue of the legacy not-found response")
        void anAccountOwningNoCardYieldsEmptyRatherThanFailing() {
            runner().run(context -> {
                final CardRepository repository = context.getBean(CardRepository.class);

                assertThat(repository.findByCardAcctIdOrderByCardNumAsc("99999999999")).isEmpty();
                assertThat(repository.findFirstByCardAcctIdOrderByCardNumAsc("99999999999")).isEmpty();
            });
        }
    }

    @Nested
    @DisplayName("the keyset browse of the card-list screen")
    final class KeysetBrowse {

        /** Creates the nest. */
        KeysetBrowse() {
        }

        @Test
        @DisplayName("an unfiltered forward read resumes strictly after the cursor, ascends by card number "
                + "and stops at the limit, so one extra row answers the further-page question")
        void anUnfilteredForwardReadResumesStrictlyAfterTheCursor() {
            runner().run(context -> {
                final CardRepository repository = context.getBean(CardRepository.class);

                final List<Card> firstPage = repository
                        .findByCardNumGreaterThanOrderByCardNumAsc("", Limit.of(SCREEN_ROWS + 1));

                assertThat(firstPage).hasSize(SCREEN_ROWS + 1);
                assertThat(firstPage).extracting(Card::getCardNum).isSorted();

                final String cursor = firstPage.get(SCREEN_ROWS - 1).getCardNum();
                final List<Card> secondPage = repository
                        .findByCardNumGreaterThanOrderByCardNumAsc(cursor, Limit.of(SCREEN_ROWS + 1));

                assertThat(secondPage).extracting(Card::getCardNum)
                        .as("strictly after the cursor, so the last row of the first page is not repeated")
                        .doesNotContain(cursor)
                        .allSatisfy(number -> assertThat(number).isGreaterThan(cursor));
                assertThat(secondPage.get(0).getCardNum())
                        .as("the eighth row of the first page is the first row of the second, which is "
                                + "exactly what makes the extra row a lookahead and not a gap")
                        .isEqualTo(firstPage.get(SCREEN_ROWS).getCardNum());
            });
        }

        @Test
        @DisplayName("an unfiltered backward read resumes strictly before the cursor and descends, and "
                + "reversing it reproduces the ascending page the legacy screen presents")
        void anUnfilteredBackwardReadDescendsAndReversesToTheScreenOrder() {
            runner().run(context -> {
                final CardRepository repository = context.getBean(CardRepository.class);

                final List<Card> ascending = repository
                        .findByCardNumGreaterThanOrderByCardNumAsc("", Limit.of(SCREEN_ROWS + 1));
                final String cursor = ascending.get(SCREEN_ROWS).getCardNum();

                final List<Card> read = repository
                        .findByCardNumLessThanOrderByCardNumDesc(cursor, Limit.of(SCREEN_ROWS));

                assertThat(read).hasSize(SCREEN_ROWS);
                assertThat(read).extracting(Card::getCardNum)
                        .as("descending is the read order")
                        .isSortedAccordingTo(java.util.Comparator.reverseOrder());
                assertThat(read.reversed()).extracting(Card::getCardNum)
                        .as("reversing recovers the page the operator sees, which is the seven rows "
                                + "immediately below the cursor in ascending order")
                        .isEqualTo(ascending.subList(0, SCREEN_ROWS).stream()
                                .map(Card::getCardNum).toList());
            });
        }

        @Test
        @DisplayName("the account-filtered forms narrow the same sequence without reordering it, so a "
                + "filtered page keeps the card-number cursor semantics of an unfiltered one")
        void theAccountFilteredFormsNarrowWithoutReordering() {
            runner().run(context -> {
                final CardRepository repository = context.getBean(CardRepository.class);
                try {
                    repository.saveAndFlush(reservedCard());

                    assertThat(repository.findByCardAcctIdAndCardNumGreaterThanOrderByCardNumAsc(
                            SEEDED_ACCOUNT, "", Limit.of(SCREEN_ROWS + 1)))
                            .extracting(Card::getCardNum)
                            .as("only the two cards of the filtered account, ascending by card number")
                            .containsExactly(SEEDED_CARD, RESERVED_CARD);
                    assertThat(repository.findByCardAcctIdAndCardNumGreaterThanOrderByCardNumAsc(
                            SEEDED_ACCOUNT, SEEDED_CARD, Limit.of(SCREEN_ROWS + 1)))
                            .extracting(Card::getCardNum)
                            .as("strictly after the cursor, within the filter")
                            .containsExactly(RESERVED_CARD);
                    assertThat(repository.findByCardAcctIdAndCardNumLessThanOrderByCardNumDesc(
                            SEEDED_ACCOUNT, RESERVED_CARD, Limit.of(SCREEN_ROWS + 1)))
                            .extracting(Card::getCardNum)
                            .as("strictly before the cursor, within the filter, descending")
                            .containsExactly(SEEDED_CARD);
                } finally {
                    repository.deleteById(RESERVED_CARD);
                    repository.flush();
                }
            });
        }
    }

    @Nested
    @DisplayName("the alternate-index finders of the cross-reference")
    final class CrossReferenceFinders {

        /** Creates the nest. */
        CrossReferenceFinders() {
        }

        @Test
        @DisplayName("the first-match finder returns the lowest card number of the account while the list "
                + "finder returns every row, and the first element of the list is the first-match row")
        void firstMatchIsTheFirstElementOfTheList() {
            runner().run(context -> {
                final CardRepository cards = context.getBean(CardRepository.class);
                final CardCrossReferenceRepository crossReferences =
                        context.getBean(CardCrossReferenceRepository.class);

                assertThat(crossReferences.findByXrefAcctIdOrderByXrefCardNumAsc(SEEDED_ACCOUNT))
                        .extracting(CardCrossReference::getXrefCardNum)
                        .as("the seeded shape is one row per account")
                        .containsExactly(SEEDED_CARD);
                try {
                    cards.saveAndFlush(reservedCard());
                    crossReferences.saveAndFlush(new CardCrossReference(RESERVED_CARD, SEEDED_CUSTOMER,
                            SEEDED_ACCOUNT));

                    assertThat(crossReferences.findFirstByXrefAcctIdOrderByXrefCardNumAsc(SEEDED_ACCOUNT))
                            .get()
                            .extracting(CardCrossReference::getXrefCardNum)
                            .isEqualTo(SEEDED_CARD);
                    assertThat(crossReferences.findByXrefAcctIdOrderByXrefCardNumAsc(SEEDED_ACCOUNT))
                            .extracting(CardCrossReference::getXrefCardNum)
                            .containsExactly(SEEDED_CARD, RESERVED_CARD);
                } finally {
                    crossReferences.deleteById(RESERVED_CARD);
                    crossReferences.flush();
                    cards.deleteById(RESERVED_CARD);
                    cards.flush();
                }

                assertThat(crossReferences.findByXrefAcctIdOrderByXrefCardNumAsc(SEEDED_ACCOUNT))
                        .as("the one-to-one seed is restored")
                        .hasSize(1);
            });
        }

        @Test
        @DisplayName("an account with no cross-reference row yields an empty list rather than a failure")
        void anAccountWithNoRowYieldsAnEmptyList() {
            runner().run(context -> assertThat(context.getBean(CardCrossReferenceRepository.class)
                    .findByXrefAcctIdOrderByXrefCardNumAsc("99999999999"))
                    .isEmpty());
        }
    }

    /**
     * Builds the second card of the seeded account, carrying the reserved card number.
     *
     * @return a card ready to be stored
     */
    private static Card reservedCard() {
        return new Card(RESERVED_CARD, SEEDED_ACCOUNT, "123", "REPOSITORY IT FIXTURE", "2099-12-31", "Y");
    }

    /**
     * Assembles a context carrying the card-side repositories, against the shared server.
     *
     * @return the configured runner
     */
    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(RepositoriesUnderTest.class)
                .withPropertyValues(
                        "spring.datasource.url=" + jdbcUrl(),
                        "spring.datasource.username=" + databaseUser(),
                        "spring.datasource.password=" + databasePassword(),
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.jpa.open-in-view=false");
    }
}
