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

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.carddemo.domain.Card;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.support.AbstractPostgresIT;

/**
 * Verifies, against a real PostgreSQL 16 server carrying the shipped migrations, the card-side
 * persistence contracts used by the keyed alternate-index reads and by the card-list screen's paged
 * base-cluster browse.
 *
 * <h2>Why a server, and why an extra row</h2>
 *
 * <p>The account finders exercised here are derived queries, whose names the persistence provider
 * resolves into SQL at bootstrap and never the compiler, so a misspelled attribute is a start-up failure
 * that only a context surfaces. The reference seed is also one-to-one - fifty accounts, fifty cards,
 * fifty cross-reference rows - which cannot expose the non-unique alternate-key contract. The
 * alternate-index nests therefore insert a <em>second</em> card and cross-reference row against one
 * seeded account, prove that the list finder returns both, and apply the legacy lowest-base-key rule in
 * the consumer exactly as the services do. The fixtures are removed before the next test observes the
 * database. The paging nest uses the complete seed to verify the explicit ordering and page continuity
 * used by {@code CardListService}.
 *
 * <p>Provenance: the browse behaviour asserted here is that of {@code app/cbl/COCRDLIC.cbl} (the list
 * screen, seven rows, base-cluster order) and {@code app/cbl/COCRDSLC.cbl} (the keyed alternate-index
 * read), with the first-match reasoning recorded as {@code DL-121}; read as read-only reference at commit
 * SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.
 */
@DisplayName("Card browse: repository-side ordered first match and the keyset browse of the list screen")
final class CardBrowseRepositoryIT extends AbstractPostgresIT {

    /**
     * The bound the two alternate-key finders now require.
     *
     * <p>Generous, because most specifications here measure what the finder returns rather than how much
     * of it. The bound's own behaviour is measured separately, on a fixture that holds more rows than the
     * bound admits.
     */
    private static final Limit ALTERNATE_KEY_ROWS = Limit.of(100);

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

    /** Verifies the non-unique account finder and the consumer-owned first-base-record rule. */
    @Nested
    @DisplayName("the alternate-index finders of the card master")
    final class CardAlternateIndexFinders {

        /** Creates the nest. */
        CardAlternateIndexFinders() {
        }

        @Test
        @DisplayName("the list finder returns every matching card while the consumer selects the lowest "
                + "base key, which is the distinction a one-to-one seed cannot show")
        void listAndConsumerSelectionDifferOnceAnAccountOwnsTwoCards() {
            runner().run(context -> {
                final CardRepository repository = context.getBean(CardRepository.class);

                assertThat(repository.findByCardAcctIdOrderByCardNumAsc(SEEDED_ACCOUNT, ALTERNATE_KEY_ROWS))
                        .as("the seeded account owns exactly one card")
                        .extracting(Card::getCardNum)
                        .containsExactly(SEEDED_CARD);
                try {
                    repository.saveAndFlush(reservedCard());

                    final List<Card> matches = repository.findByCardAcctIdOrderByCardNumAsc(SEEDED_ACCOUNT, ALTERNATE_KEY_ROWS);

                    assertThat(matches)
                            .extracting(Card::getCardNum)
                            .as("the repository exposes every row of the non-unique alternate key")
                            .containsExactlyInAnyOrder(SEEDED_CARD, RESERVED_CARD);
                    assertThat(matches.stream().min(Comparator.comparing(Card::getCardNum)))
                            .get()
                            .extracting(Card::getCardNum)
                            .as("the service-owned keyed-read rule selects the lowest base record")
                            .isEqualTo(SEEDED_CARD);
                } finally {
                    repository.deleteById(RESERVED_CARD);
                    repository.flush();
                }

                assertThat(repository.findByCardAcctIdOrderByCardNumAsc(SEEDED_ACCOUNT, ALTERNATE_KEY_ROWS))
                        .as("the one-to-one seed is restored")
                        .hasSize(1);
            });
        }

        @Test
        @DisplayName("an account owning no card yields an empty list rather than a failure, which is the "
                + "analogue of the legacy not-found response")
        void anAccountOwningNoCardYieldsEmptyRatherThanFailing() {
            runner().run(context -> {
                final CardRepository repository = context.getBean(CardRepository.class);

                assertThat(repository.findByCardAcctIdOrderByCardNumAsc("99999999999", ALTERNATE_KEY_ROWS)).isEmpty();
            });
        }
    }

    /**
     * Verifies the bounded keyset browse of the base cluster that the card-list screen walks.
     *
     * <p>The list program browses the base cluster by card number: it positions on a retained record
     * identifier and reads onward from it. That is a keyset read, and the repository declares its two
     * directions as ordered, limit-bounded finders. An offset page is deliberately not the mechanism and
     * is not tested here as if it were: it recounts and discards every earlier row on each fetch, and a
     * card inserted or removed between two fetches shifts the window so that a row is delivered twice or
     * missed - a defect the retained-key browse cannot have.
     *
     * <p>The inclusive first row of a greater-or-equal browse start is the inherited keyed read, so the
     * two finders here are strict and the nest proves exactly that: the boundary row is never repeated,
     * the order is the one the finder name declares, and a window plus its continuation tile the sequence
     * with no gap.
     */
    @Nested
    @DisplayName("the bounded keyset browse of the card-list screen")
    final class KeysetBrowse {

        /** Creates the nest. */
        KeysetBrowse() {
        }

        @Test
        @DisplayName("a forward window and its continuation tile the ascending sequence without a gap or "
                + "a repeated boundary row")
        void successiveForwardWindowsTileTheAscendingSequence() {
            runner().run(context -> {
                final CardRepository repository = context.getBean(CardRepository.class);

                final List<String> firstWindow = cardNumbersOf(
                        repository.findByCardNumGreaterThanOrderByCardNumAsc("", Limit.of(SCREEN_ROWS)));
                final List<String> continuation = cardNumbersOf(
                        repository.findByCardNumGreaterThanOrderByCardNumAsc(
                                firstWindow.get(firstWindow.size() - 1), Limit.of(SCREEN_ROWS)));
                final List<String> wideWindow = cardNumbersOf(
                        repository.findByCardNumGreaterThanOrderByCardNumAsc(
                                "", Limit.of(SCREEN_ROWS * 2)));

                assertThat(firstWindow)
                        .as("a blank bound is below every stored card number, so the walk opens at the "
                                + "first row and the limit truncates to one screen")
                        .hasSize(SCREEN_ROWS)
                        .isSorted();
                assertThat(continuation)
                        .as("the bound is strict, so the row the previous window ended on is not repeated")
                        .hasSize(SCREEN_ROWS)
                        .isSorted()
                        .doesNotContain(firstWindow.get(firstWindow.size() - 1));
                assertThat(Stream.concat(firstWindow.stream(), continuation.stream()).toList())
                        .as("one window plus its continuation equals one window of twice the size")
                        .containsExactlyElementsOf(wideWindow);
            });
        }

        @Test
        @DisplayName("a backward window is read descending and reverses into ascending screen order")
        void aBackwardWindowReversesIntoScreenOrder() {
            runner().run(context -> {
                final CardRepository repository = context.getBean(CardRepository.class);
                final List<String> ascending = cardNumbersOf(
                        repository.findByCardNumGreaterThanOrderByCardNumAsc(
                                "", Limit.of(SCREEN_ROWS + 1)));
                final String boundary = ascending.get(SCREEN_ROWS);

                final List<String> readOrder = cardNumbersOf(
                        repository.findByCardNumLessThanOrderByCardNumDesc(
                                boundary, Limit.of(SCREEN_ROWS)));

                assertThat(readOrder)
                        .as("descending is the repository read order the legacy backward walk uses")
                        .hasSize(SCREEN_ROWS)
                        .isSortedAccordingTo(Comparator.reverseOrder())
                        .doesNotContain(boundary);
                assertThat(readOrder.reversed())
                        .as("the service fills its bottom slot first, so reversing the read order is the "
                                + "ascending page the operator sees")
                        .containsExactlyElementsOf(ascending.subList(0, SCREEN_ROWS));
            });
        }

        @Test
        @DisplayName("the ends of the sequence answer empty rather than raising, which is the browse's "
                + "end-of-file arm")
        void theEndsOfTheSequenceAnswerEmpty() {
            runner().run(context -> {
                final CardRepository repository = context.getBean(CardRepository.class);

                assertThat(repository.findByCardNumLessThanOrderByCardNumDesc("", Limit.of(SCREEN_ROWS)))
                        .as("read backwards, no row lies before a blank bound, which is why a backward "
                                + "walk opened on the initial value yields nothing at once")
                        .isEmpty();
                assertThat(repository.findByCardNumGreaterThanOrderByCardNumAsc(
                                "9999999999999999", Limit.of(SCREEN_ROWS)))
                        .as("nothing follows the highest possible card number")
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("the ordered-first account finder returns the one card a keyed read of the "
                + "alternate-index path would return, bounded to that single row")
        void theOrderedFirstAccountFinderReturnsTheLowestBaseKey() {
            runner().run(context -> {
                final CardRepository repository = context.getBean(CardRepository.class);

                assertThat(repository.findFirstByCardAcctIdOrderByCardNumAsc(SEEDED_ACCOUNT))
                        .get()
                        .extracting(Card::getCardNum)
                        .isEqualTo(SEEDED_CARD);
                try {
                    repository.saveAndFlush(reservedCard());

                    assertThat(repository.findFirstByCardAcctIdOrderByCardNumAsc(SEEDED_ACCOUNT))
                            .get()
                            .extracting(Card::getCardNum)
                            .as("with two cards on the account the ordered-first read still returns the "
                                    + "lowest base key, which is what the legacy keyed read returns")
                            .isEqualTo(SEEDED_CARD);
                    assertThat(repository.findByCardAcctIdOrderByCardNumAsc(SEEDED_ACCOUNT, ALTERNATE_KEY_ROWS))
                            .as("while the list form still exposes both, because the two answer "
                                    + "different questions")
                            .hasSize(2);
                } finally {
                    repository.deleteById(RESERVED_CARD);
                    repository.flush();
                }
                assertThat(repository.findFirstByCardAcctIdOrderByCardNumAsc("99999999999"))
                        .as("an account owning no card is the legacy not-found response, not a failure")
                        .isEmpty();
            });
        }
    }

    /** Verifies the non-unique cross-reference finder and its consumer-owned first-row rule. */
    @Nested
    @DisplayName("the alternate-index finders of the cross-reference")
    final class CrossReferenceFinders {

        /** Creates the nest. */
        CrossReferenceFinders() {
        }

        @Test
        @DisplayName("the list finder returns every row while the consumer selects the lowest base key")
        void listAndConsumerSelectionPreserveTheLegacyFirstRecordRule() {
            runner().run(context -> {
                final CardRepository cards = context.getBean(CardRepository.class);
                final CardCrossReferenceRepository crossReferences =
                        context.getBean(CardCrossReferenceRepository.class);

                assertThat(crossReferences.findByXrefAcctIdOrderByXrefCardNumAsc(SEEDED_ACCOUNT, ALTERNATE_KEY_ROWS))
                        .extracting(CardCrossReference::getXrefCardNum)
                        .as("the seeded shape is one row per account")
                        .containsExactly(SEEDED_CARD);
                try {
                    cards.saveAndFlush(reservedCard());
                    crossReferences.saveAndFlush(new CardCrossReference(RESERVED_CARD, SEEDED_CUSTOMER,
                            SEEDED_ACCOUNT));

                    final List<CardCrossReference> matches =
                            crossReferences.findByXrefAcctIdOrderByXrefCardNumAsc(SEEDED_ACCOUNT, ALTERNATE_KEY_ROWS);

                    assertThat(matches)
                            .extracting(CardCrossReference::getXrefCardNum)
                            .as("the repository exposes every row of the non-unique alternate key")
                            .containsExactlyInAnyOrder(SEEDED_CARD, RESERVED_CARD);
                    assertThat(crossReferences
                            .findFirstByXrefAcctIdOrderByXrefCardNumAsc(SEEDED_ACCOUNT))
                            .get()
                            .extracting(CardCrossReference::getXrefCardNum)
                            .as("while the ordered-first finder reads only the row a keyed read of the "
                                    + "path returns, which is the lowest base record")
                            .isEqualTo(SEEDED_CARD);
                    assertThat(matches.stream()
                            .min(Comparator.comparing(CardCrossReference::getXrefCardNum))
                            .map(CardCrossReference::getXrefCardNum))
                            .as("and the two agree, which is what makes moving the rule into the "
                                    + "repository a de-duplication rather than a change")
                            .contains(crossReferences
                                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(SEEDED_ACCOUNT)
                                    .orElseThrow()
                                    .getXrefCardNum());
                } finally {
                    crossReferences.deleteById(RESERVED_CARD);
                    crossReferences.flush();
                    cards.deleteById(RESERVED_CARD);
                    cards.flush();
                }

                assertThat(crossReferences.findByXrefAcctIdOrderByXrefCardNumAsc(SEEDED_ACCOUNT, ALTERNATE_KEY_ROWS))
                        .as("the one-to-one seed is restored")
                        .hasSize(1);
            });
        }

        @Test
        @DisplayName("an account with no cross-reference row yields an empty result from both forms "
                + "rather than a failure")
        void anAccountWithNoRowYieldsAnEmptyList() {
            runner().run(context -> {
                final CardCrossReferenceRepository crossReferences =
                        context.getBean(CardCrossReferenceRepository.class);

                assertThat(crossReferences.findByXrefAcctIdOrderByXrefCardNumAsc("99999999999", ALTERNATE_KEY_ROWS)).isEmpty();
                assertThat(crossReferences.findFirstByXrefAcctIdOrderByXrefCardNumAsc("99999999999"))
                        .isEmpty();
            });
        }
    }

    /**
     * Returns the card numbers of one read window in read order.
     *
     * @param window the rows the read returned
     * @return their card numbers, in the order the window presents them
     */
    private static List<String> cardNumbersOf(final List<Card> window) {
        return window.stream().map(Card::getCardNum).toList();
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
                        JdbcTemplateAutoConfiguration.class,
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
