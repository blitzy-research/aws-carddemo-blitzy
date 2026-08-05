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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
@DisplayName("Card browse: service-side first match and the pageable browse of the list screen")
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

                assertThat(repository.findByCardAcctId(SEEDED_ACCOUNT))
                        .as("the seeded account owns exactly one card")
                        .extracting(Card::getCardNum)
                        .containsExactly(SEEDED_CARD);
                try {
                    repository.saveAndFlush(reservedCard());

                    final List<Card> matches = repository.findByCardAcctId(SEEDED_ACCOUNT);

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

                assertThat(repository.findByCardAcctId(SEEDED_ACCOUNT))
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

                assertThat(repository.findByCardAcctId("99999999999")).isEmpty();
            });
        }
    }

    /**
     * Verifies the pageable browse contract that replaced the former test-only keyset methods.
     *
     * <p>The frozen repository surface intentionally declares no cursor or {@code Limit} finder.
     * {@code CardListService} reads inherited {@code findAll(Pageable)} pages with an explicit card-number
     * sort and emulates the VSAM start position while walking those pages. Strict greater-than cursor and
     * lookahead-row assertions therefore have no repository contract to test here. This nest instead
     * proves page continuity, the descending read order used for backward browsing, the caller's reversal
     * into ascending screen order, and the filtered pageable overload that the repository does declare.
     */
    @Nested
    @DisplayName("the pageable browse of the card-list screen")
    final class PagedBrowse {

        /** Creates the nest. */
        PagedBrowse() {
        }

        @Test
        @DisplayName("successive unfiltered pages ascend by card number without a gap or duplicate")
        void successiveUnfilteredPagesContinueInAscendingOrder() {
            runner().run(context -> {
                final CardRepository repository = context.getBean(CardRepository.class);
                final Sort ascending = Sort.by(Sort.Direction.ASC, "cardNum");

                final Page<Card> firstPage =
                        repository.findAll(PageRequest.of(0, SCREEN_ROWS, ascending));
                final Page<Card> secondPage =
                        repository.findAll(PageRequest.of(1, SCREEN_ROWS, ascending));
                final Page<Card> firstTwoPageWindow =
                        repository.findAll(PageRequest.of(0, SCREEN_ROWS * 2, ascending));
                final List<String> observed = Stream.concat(firstPage.stream(), secondPage.stream())
                        .map(Card::getCardNum)
                        .toList();

                assertThat(firstPage.getContent()).hasSize(SCREEN_ROWS);
                assertThat(firstPage.hasNext()).as("the fifty-row seed has another page").isTrue();
                assertThat(secondPage.getContent()).hasSize(SCREEN_ROWS);
                assertThat(observed).isSorted();
                assertThat(observed)
                        .as("the two seven-row pages exactly equal one fourteen-row window")
                        .containsExactlyElementsOf(firstTwoPageWindow.stream()
                                .map(Card::getCardNum)
                                .toList());
            });
        }

        @Test
        @DisplayName("a backward page is read descending and reversed into ascending screen order")
        void anUnfilteredBackwardReadReversesToTheScreenOrder() {
            runner().run(context -> {
                final CardRepository repository = context.getBean(CardRepository.class);
                final Sort descending = Sort.by(Sort.Direction.DESC, "cardNum");

                final Page<Card> read =
                        repository.findAll(PageRequest.of(0, SCREEN_ROWS, descending));
                final List<String> readOrder = read.stream().map(Card::getCardNum).toList();
                final List<String> screenOrder = readOrder.reversed();

                assertThat(read.getContent()).hasSize(SCREEN_ROWS);
                assertThat(readOrder)
                        .as("descending is the repository read order")
                        .isSortedAccordingTo(Comparator.reverseOrder());
                assertThat(screenOrder)
                        .as("the service reverses the descending read before filling the screen")
                        .isSorted();
            });
        }

        @Test
        @DisplayName("the account-filtered pageable finder narrows the same sequence and honours the "
                + "caller's requested direction")
        void theAccountFilteredPageHonoursTheSuppliedOrdering() {
            runner().run(context -> {
                final CardRepository repository = context.getBean(CardRepository.class);
                try {
                    repository.saveAndFlush(reservedCard());

                    final Page<Card> ascending = repository.findByCardAcctId(SEEDED_ACCOUNT,
                            PageRequest.of(0, SCREEN_ROWS, Sort.by(Sort.Direction.ASC, "cardNum")));
                    final Page<Card> descending = repository.findByCardAcctId(SEEDED_ACCOUNT,
                            PageRequest.of(0, SCREEN_ROWS, Sort.by(Sort.Direction.DESC, "cardNum")));

                    assertThat(ascending.getContent())
                            .extracting(Card::getCardNum)
                            .as("only the two cards of the filtered account, ascending by card number")
                            .containsExactly(SEEDED_CARD, RESERVED_CARD);
                    assertThat(ascending.hasNext()).isFalse();
                    assertThat(descending.getContent())
                            .extracting(Card::getCardNum)
                            .as("the same two rows are returned in the requested backward read order")
                            .containsExactly(RESERVED_CARD, SEEDED_CARD);
                } finally {
                    repository.deleteById(RESERVED_CARD);
                    repository.flush();
                }
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

                assertThat(crossReferences.findByXrefAcctId(SEEDED_ACCOUNT))
                        .extracting(CardCrossReference::getXrefCardNum)
                        .as("the seeded shape is one row per account")
                        .containsExactly(SEEDED_CARD);
                try {
                    cards.saveAndFlush(reservedCard());
                    crossReferences.saveAndFlush(new CardCrossReference(RESERVED_CARD, SEEDED_CUSTOMER,
                            SEEDED_ACCOUNT));

                    final List<CardCrossReference> matches =
                            crossReferences.findByXrefAcctId(SEEDED_ACCOUNT);

                    assertThat(matches)
                            .extracting(CardCrossReference::getXrefCardNum)
                            .as("the repository exposes every row of the non-unique alternate key")
                            .containsExactlyInAnyOrder(SEEDED_CARD, RESERVED_CARD);
                    assertThat(matches.stream()
                            .min(Comparator.comparing(CardCrossReference::getXrefCardNum)))
                            .get()
                            .extracting(CardCrossReference::getXrefCardNum)
                            .as("the service-owned keyed-read rule selects the lowest base record")
                            .isEqualTo(SEEDED_CARD);
                } finally {
                    crossReferences.deleteById(RESERVED_CARD);
                    crossReferences.flush();
                    cards.deleteById(RESERVED_CARD);
                    cards.flush();
                }

                assertThat(crossReferences.findByXrefAcctId(SEEDED_ACCOUNT))
                        .as("the one-to-one seed is restored")
                        .hasSize(1);
            });
        }

        @Test
        @DisplayName("an account with no cross-reference row yields an empty list rather than a failure")
        void anAccountWithNoRowYieldsAnEmptyList() {
            runner().run(context -> assertThat(context.getBean(CardCrossReferenceRepository.class)
                    .findByXrefAcctId("99999999999"))
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
