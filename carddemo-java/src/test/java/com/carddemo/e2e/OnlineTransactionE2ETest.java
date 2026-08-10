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
package com.carddemo.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.carddemo.api.AuthController;
import com.carddemo.api.BatchJobController;
import com.carddemo.api.ConversationStateAdapter;
import com.carddemo.api.GlobalExceptionHandler;
import com.carddemo.api.JsonRefusalBodyRenderer;
import com.carddemo.api.ModuleErrorController;
import com.carddemo.api.ReportContractAdapter;
import com.carddemo.api.ReportController;
import com.carddemo.api.ScreenStateAdapter;
import com.carddemo.api.SignOnContractAdapter;
import com.carddemo.batch.CategoryBalanceReportJobConfig;
import com.carddemo.config.AwsConfig;
import com.carddemo.config.AwsProperties;
import com.carddemo.config.JwtProperties;
import com.carddemo.config.JwtTokenProvider;
import com.carddemo.config.SecurityConfig;
import com.carddemo.config.WebMvcConfig;
import com.carddemo.domain.UserSecurity;
import com.carddemo.domain.enums.UserType;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.AuthenticationService;
import com.carddemo.service.CredentialDigestService;
import com.carddemo.service.DateValidationService;
import com.carddemo.service.JobSubmissionService;
import com.carddemo.service.MessageCatalogService;
import com.carddemo.service.NavigationService;
import com.carddemo.service.PostgresJobSubmissionCoordinator;
import com.carddemo.service.ReportRequestService;
import com.carddemo.service.SignOnAttemptGovernor;
import com.carddemo.service.SignOnStateService;
import com.carddemo.support.AbstractPostgresAndLocalStackIT;
import com.carddemo.support.SensitiveValues;
import com.carddemo.support.TestDataFactory;
import com.carddemo.util.CobolStringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.operations.SqsOperations;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SqsException;

/**
 * Gate 5 executed: every external interface contract of the online tier, exercised against the real
 * thing and asserted from what the real thing gave back.
 *
 * <h2>The one rule this class is built around</h2>
 * The gate says that every external interface - file formats, message-queue schemas, batch-trigger
 * contracts - must be verified by a local test that exercises the real contract, and that
 * <strong>self-certification is not acceptable</strong>. That single sentence decides the whole design
 * here, so it is worth naming what it rules out rather than only what it asks for:
 *
 * <ul>
 *   <li>no expectation is produced by the code that produces the subject. The seventeen job-control
 *       card images are written out below as this class's own constants, character by character. The
 *       module's own card emitter is deliberately never consulted - an expectation borrowed from the
 *       emitter proves only that the emitter agrees with itself, and would keep passing after any
 *       change to it;</li>
 *   <li>no message is asserted from a publisher's return value. The cards are <em>received back</em>
 *       from a real first-in-first-out queue running in a real emulator, and every ordering, width,
 *       substitution and survival assertion is made against the bodies the queue returned;</li>
 *   <li>no boundary is simulated. The sign-on requests cross a real HTTP boundary on a real bound
 *       port, through the shipped filter chain, into the shipped services, to identities a real
 *       migration applied to a real PostgreSQL 16 server;</li>
 *   <li>no fixed-width artefact is compared after normalisation. The four record formats are read
 *       back out of the real object store as bytes and compared as bytes.</li>
 * </ul>
 *
 * <h2>The three contracts, and what each one is here</h2>
 *
 * <p><strong>Contract one - the sign-on message and routing contract.</strong> The legacy sign-on
 * program writes its outcome into an eighty-byte message field that an operator reads, and two of its
 * texts distinguish a wrong secret from an unknown identity. A migration that blurred them would change
 * what an operator concludes while failing nothing, so all five direct texts are compared character for
 * character, and the two common texts the shared message copybook publishes are compared at their
 * <em>full padded fifty characters</em> - the padding is part of a fixed-width field and trimming it
 * would assert a narrower contract than the one that ships. The attention-key cascade is asserted in
 * its source order, and the destination is asserted for all ten delivered identities, because the
 * legacy branch is an {@code ELSE} rather than a second equality test: administrative routes to the
 * administrative menu and <em>everything else</em> routes to the main menu.
 *
 * <p><strong>Contract two - the batch trigger.</strong> One transient-data-queue write is the entire
 * online-to-batch bridge of the estate. Its resource definition fixes eighty-byte fixed unblocked
 * records, append disposition and ignore-on-error, and the emitting paragraph transmits its
 * end-of-stream sentinel rather than stopping short of it. All of that is asserted here from messages
 * drained out of the queue after driving the report-submission endpoint over HTTP - including the
 * deduplication trap that would otherwise swallow five of the seventeen cards in silence, and the
 * ignore-on-error path, which must return the operator a message and never abort the caller.
 *
 * <p><strong>Contract three - the fixed-width record formats.</strong> Eighty, one hundred, one
 * hundred and thirty-three and four hundred and thirty bytes are the four this specification stages,
 * because they are the four expected outputs Gate 1 names. The estate emits a <strong>fifth</strong>
 * fixed width - the forty-byte category-balance report line - whose own golden is compared against a real
 * run, at its own width and ordering, by {@code batch/CategoryBalanceReportJobConfigIT} rather than staged
 * here; it is asserted here as a fifth width so that a reader counting widths from this class cannot
 * arrive at four. Their staging interface is the object
 * store that replaced sequential-dataset staging, so each delivered golden is put through that real
 * store and read back, and the bytes that came back are compared to the bytes on the class path with no
 * decoding, no trimming and no normalisation of any kind. The deep byte-equivalence of a
 * <em>generated</em> artefact belongs to the pipeline specification and is not restated here; what is
 * restated is the interface, which is this class's charge.
 *
 * <h2>Credentials</h2>
 * The estate's ten identities share one eight-character cleartext credential. It appears nowhere in
 * this file - not in a literal, a comment, a method name, a display name or an assertion message -
 * because the point of digesting it in the target is that the cleartext stops existing in the module.
 * Where a successful sign-on is needed, the credential is read by offset at run time from the
 * class-path fixture's own credential window into a character array that is overwritten before the frame
 * returns, and the request body is encoded straight out of that array into bytes so the value never
 * becomes a string this class holds.
 *
 * <p><strong>No sign-on here installs a digest of its own.</strong> It did not always: a digest of the
 * fixture's window used to be installed on the record first, because the fixture then carried a fabricated
 * window that no delivered digest could accept. Substituting the stored digest before authenticating meant
 * the one property that distinguishes a correct shipped digest from a merely well-formed one - that it
 * accepts the credential - was never exercised by a sign-on at all: a suite that installed its own digest
 * first would have passed unchanged had all ten frozen digests been wrong. The fixture now reproduces the
 * delivered provisioning records, so nothing is installed and nothing is substituted, and
 * {@link TheDeliveredIdentities#aSuccessfulSignOnVerifiesAgainstTheDeliveredDigest()} observes that the
 * stored digest is byte-identical before and after an admitted sign-on.
 *
 * <p>About the <em>delivered</em> digests this class therefore asserts four things and not three: their
 * shape and cost, their mutual distinctness, that they refuse a value they were not derived from, and -
 * the claim a frozen digest exists to support - that all ten accept the credential the read-only
 * provisioning member {@code app/jcl/DUSRSECJ.jcl} provisions, recovered at run time by offset from its
 * own in-stream card images, under both an independent encoder and the module's own shipped verifier.
 *
 * <h2>Why the name and the package are load-bearing</h2>
 * The build partitions the suite by file name into two strictly complementary sets. The fast tier
 * includes {@code **}{@code /*Test.java} and excludes {@code **}{@code /e2e/**}{@code /*.java}; the
 * container-backed tier includes {@code **}{@code /e2e/**}{@code /*Test.java}. This class therefore
 * keeps its {@code Test} suffix <em>and</em> runs in the tier where containers exist. Renaming it,
 * re-suffixing it or moving it out of this package would put it in the tier that has no emulator and no
 * server, where every assertion below would fail for a reason unrelated to any contract.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Card images, message texts, resource-definition
 * attribute names, record widths, field offsets and sort-symbol declarations are external contract, and
 * contract metadata is what this gate exists to verify; no legacy implementation line is transcribed.
 *
 * <h2>Authorities, by member and line</h2>
 * Every fact asserted below is authorised by a named member at a named line in the read-only reference
 * estate, cited so a reviewer can verify any single assertion against its source without searching, and
 * so the Gate 5 rows of the traceability matrix stay findable. These are references, not transcriptions.
 *
 * <ul>
 *   <li><strong>Contract one.</strong> {@code app/cbl/COSGN00C.cbl} - the eighty-byte message field at
 *       L38; the attention-key cascade at L84-L96; the two prompts at L120 and L125; the map path at
 *       L149 and the plain-text path at L165-L166; the credential comparison at L223, which becomes a
 *       digest verification here; the delivered-type read at L227; the two destinations at L232 and
 *       L237; the three refusal texts at L242-L243, L249 and L254.
 *       {@code app/cpy/CSMSG01Y.cpy} - the two fifty-character common messages.
 *       {@code app/cpy/CSUSR01Y.cpy} - the eighty-byte identity layout, credential window at offset 48
 *       and delivered type at offset 56. {@code app/jcl/DUSRSECJ.jcl} L35-L44 - the ten delivered
 *       identities as in-stream card images. {@code app/cpy/COCOM01Y.cpy} - the communication area whose
 *       carriage becomes a token and an explicit navigation context.
 *       {@code app/cpy-bms/COSGN00.CPY} - the field-level screen contract.</li>
 *   <li><strong>Contract two.</strong> {@code app/cbl/CORPT00C.cbl} - the report-name field at L58; the
 *       seventeen card images from L81; the monthly, yearly and custom periods reaching submission at
 *       L214/L238, L240/L255 and L433/L435; the confirmation gate at L462-L510; the sentinel actually
 *       transmitted at L507; and the single queue write at L515-L535, in the paragraph whose name is
 *       misspelled in the source and spelled correctly in the target.
 *       {@code app/csd/CARDDEMO.CSD} L499-L505 - the queue definition that fixes eighty-byte fixed
 *       unblocked records, append disposition, output-only direction and ignore-on-error.
 *       {@code app/jcl/TRANREPT.jcl} and {@code app/proc/TRANREPT.prc} - the procedure the step card
 *       invokes and the sort-symbol typing the symbol cards declare.
 *       {@code app/cpy-bms/CORPT00.CPY} - the field-level screen contract.</li>
 *   <li><strong>Contract three.</strong> {@code app/cbl/CBSTM03A.CBL} - the eighty-byte statement record
 *       at L45 and the one-hundred-byte statement record at L47.
 *       {@code app/cpy/CVTRA07Y.cpy} with {@code app/cbl/CBTRN03C.cbl} - the one-hundred-and-thirty-three
 *       byte report line. {@code app/cbl/CBTRN02C.cbl} L176-L182 - the four-hundred-and-thirty byte
 *       reject record, a three-hundred-and-fifty byte source image followed by an eighty-byte trailer
 *       split into a four-digit reason and a seventy-six character description.</li>
 * </ul>
 */
@SpringBootTest(classes = OnlineTransactionE2ETest.OnlineContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.flyway.enabled=false", "spring.main.banner-mode=off",
                "spring.jpa.hibernate.ddl-auto=none", "spring.batch.job.enabled=false",
                "management.endpoint.health.validate-group-membership=false",
                "management.tracing.enabled=false",
                // The boundary is reached over the loopback interface of the process that opened it,
                // so there is no wire for a token to be observed on, and the transport requirement is
                // relaxed exactly as the test profile already relaxes it. Nothing else is relaxed.
                "carddemo.security.require-https=false"})
@DisplayName("Gate 5 executed: the sign-on contract, the batch trigger and the four record formats, "
        + "each asserted from what a real interface returned")
class OnlineTransactionE2ETest extends AbstractPostgresAndLocalStackIT {

    // =================================================================================================
    // SECTION 1 - CONTRACT ONE: THE SIGN-ON MESSAGE TEXTS
    //
    // Each text is written out here as its own literal. None is read from the response type or the
    // message catalogue that also publishes it: an expectation that borrows the subject's own constant
    // proves only that the subject agrees with itself, and would survive the constant being changed.
    // =================================================================================================

    /**
     * The prompt a submission carrying no identity returns.
     *
     * <p>The emitting program's literal says "Please enter User ID" and not "Please enter <em>your</em>
     * User ID", and the trailing space-dot-dot-dot is part of it. That is exactly why the text is
     * asserted rather than its meaning: a paraphrase reads correctly and is not the contract.
     */
    private static final String PROMPT_FOR_USER_ID = "Please enter User ID ...";

    /** The prompt returned when an identity arrived but no secret did, on the same terms. */
    private static final String PROMPT_FOR_PASSWORD = "Please enter Password ...";

    /** The text a supplied-but-wrong secret returns. */
    private static final String WRONG_PASSWORD = "Wrong Password. Try again ...";

    /** The text an identity the credential master does not hold returns. */
    private static final String USER_NOT_FOUND = "User not found. Try again ...";

    /**
     * The text the catch-all arm of the lookup cascade returns.
     *
     * <p>Asserted as a text this class knows and as one that is <em>distinct</em> from the other four,
     * which is the property that matters: the cascade has three arms and each must be reachable
     * separately, so two arms answering the same text would collapse the contract without failing any
     * single-text assertion.
     */
    private static final String UNABLE_TO_VERIFY = "Unable to verify the User ...";

    /**
     * The common courtesy text, at the <strong>full fifty characters</strong> of the fixed-width field
     * that carries it: forty-three characters of text and seven of padding.
     *
     * <p>Written as the text plus an explicit run of spaces rather than as one long literal, so that the
     * padding is visible to a reader and countable by a compiler rather than being trailing whitespace
     * an editor may strip. The field is fifty bytes wide in the shared message copybook and is then
     * widened again into the eighty-byte screen message, so on the wire it carries these seven spaces
     * and thirty more. Trimming any of it would assert a narrower contract than the one that ships.
     */
    private static final String COMMON_THANK_YOU =
            "Thank you for using CardDemo application..." + "       ";

    /**
     * The common unmapped-key text, at the full fifty characters: forty of text and ten of padding.
     *
     * <p>Same reasoning as the courtesy text above, and the two padding widths differ - seven against
     * ten - which is the arithmetic that makes a single shared "pad to fifty" helper unnecessary and a
     * literal honest.
     */
    private static final String COMMON_INVALID_KEY =
            "Invalid key pressed. Please see below..." + "          ";

    /** Width of the common-message field both texts above occupy. */
    private static final int COMMON_MESSAGE_WIDTH = 50;

    // =================================================================================================
    // SECTION 2 - THE TEN DELIVERED IDENTITIES
    // =================================================================================================

    /** Type byte an administrative record carries; the one value that routes administratively. */
    private static final String ADMIN_TYPE = "A";

    /** Type byte an ordinary record carries. */
    private static final String USER_TYPE = "U";

    /** Route an administrative identity is directed to. */
    private static final String ADMIN_MENU_ROUTE = "admin-menu";

    /** Route every non-administrative identity is directed to. */
    private static final String USER_MENU_ROUTE = "user-menu";

    /** The screen field the cursor returns to when the identity is at fault. */
    private static final String USER_ID_FIELD = "USERID";

    /** The screen field the cursor returns to when the secret is at fault. */
    private static final String PASSWORD_FIELD = "PASSWD";

    /** An identity no seed applies, for the not-found arm. */
    private static final String UNKNOWN_ID = "NOBODY00";

    /** The attention key that submits a screen; anything else takes the unmapped-key arm. */
    private static final String SUBMIT_KEY = "ENTER";

    /** The attention key the legacy cascade answers with the courtesy text. */
    private static final String EXIT_KEY = "PFK03";

    /** An attention key the cascade maps to no action, for the catch-all arm. */
    private static final String UNMAPPED_KEY = "PFK09";

    /** Width the identity, given-name and family-name fields occupy in the eighty-byte record. */
    private static final int NAME_FIELD_WIDTH = 20;

    /**
     * The ten identities the credential seed applies, with the given name, family name and type each
     * carries.
     *
     * <p>Written out rather than read back from the seed, which is the point: an expectation derived
     * from the subject moves whenever the subject moves and would keep passing if a name were changed
     * or a type flipped. Order is the seed's own order, so a failure names the row that drifted.
     */
    private static final List<DeliveredIdentity> DELIVERED_IDENTITIES = List.of(
            new DeliveredIdentity("ADMIN001", "MARGARET", "GOLD", ADMIN_TYPE),
            new DeliveredIdentity("ADMIN002", "RUSSELL", "RUSSELL", ADMIN_TYPE),
            new DeliveredIdentity("ADMIN003", "RAYMOND", "WHITMORE", ADMIN_TYPE),
            new DeliveredIdentity("ADMIN004", "EMMANUEL", "CASGRAIN", ADMIN_TYPE),
            new DeliveredIdentity("ADMIN005", "GRANVILLE", "LACHAPELLE", ADMIN_TYPE),
            new DeliveredIdentity("USER0001", "LAWRENCE", "THOMAS", USER_TYPE),
            new DeliveredIdentity("USER0002", "AJITH", "KUMAR", USER_TYPE),
            new DeliveredIdentity("USER0003", "LAURITZ", "ALME", USER_TYPE),
            new DeliveredIdentity("USER0004", "AVERARDO", "MAZZI", USER_TYPE),
            new DeliveredIdentity("USER0005", "LEE", "TING", USER_TYPE));

    /** An administrative identity used where one representative of the type is enough. */
    private static final String SOME_ADMIN_ID = "ADMIN001";

    /** An ordinary identity used where one representative of the type is enough. */
    private static final String SOME_USER_ID = "USER0001";

    // =================================================================================================
    // SECTION 3 - CONTRACT TWO: THE SEVENTEEN CARDS, WRITTEN OUT INDEPENDENTLY
    //
    // Every card below is this class's own constant. The module's card emitter is never consulted, here
    // or anywhere in this file. Two cards - the sort-symbol pair - are assembled from parts rather than
    // written as one literal, because the closing quotation mark is the FIRST byte of a wider trailing
    // filler and the run of spaces after it is countable arithmetic that a reader should be able to
    // check: eighteen plus ten plus fifty-two, and sixteen plus ten plus fifty-four.
    // =================================================================================================

    /** Record size the queue's resource definition fixes, in characters. */
    private static final int CARD_WIDTH = 80;

    /** Cards the emitting paragraph transmits, the end-of-stream sentinel included. */
    private static final int CARD_COUNT = 17;

    /** Width of one substituted date slot. */
    private static final int DATE_SLOT_WIDTH = 10;

    /** Card 1: the job card. */
    private static final String CARD_JOB = "//TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,";

    /**
     * Card 2: the notify card, carrying the <strong>correct</strong> system-user symbol.
     *
     * <p>The estate does contain a misspelling of this symbol, but it is in the customer-read job
     * member and not here. It is neither corrected there nor propagated into this card.
     */
    private static final String CARD_NOTIFY = "// NOTIFY=&SYSUID";

    /** Cards 3, 5 and 7: the comment card, repeated verbatim three times. */
    private static final String CARD_COMMENT = "//*";

    /** Card 4: the procedure-library card. */
    private static final String CARD_JOBLIB = "//JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')";

    /** Card 6: the step card invoking the reporting procedure. */
    private static final String CARD_EXEC_PROC = "//STEP10 EXEC PROC=TRANREPT";

    /** Card 8: the in-stream sort-symbol declaration card. */
    private static final String CARD_SYMNAMES_DD = "//STEP05R.SYMNAMES DD *";

    /**
     * Card 9: the card-number sort symbol, typed zoned decimal at offset 263 for sixteen bytes.
     *
     * <p>The typing is a first-class contract item, not incidental text. The very same offset is typed
     * as character in the statement job, which is why the two jobs' comparators are per-job and are
     * never unified; noting that is part of asserting it.
     */
    private static final String CARD_SORT_SYMBOL_CARD_NUM = "TRAN-CARD-NUM,263,16,ZD";

    /** Card 10: the processing-date sort symbol, typed character at offset 305 for ten bytes. */
    private static final String CARD_SORT_SYMBOL_PROC_DT = "TRAN-PROC-DT,305,10,CH";

    /** Leading text of card 11, eighteen characters, before the first start-date slot. */
    private static final String START_SYMBOL_LEAD = "PARM-START-DATE,C'";

    /** Leading text of card 12, sixteen characters, before the first end-date slot. */
    private static final String END_SYMBOL_LEAD = "PARM-END-DATE,C'";

    /** The closing quotation mark that opens the trailing filler of cards 11 and 12. */
    private static final String CLOSING_QUOTE = "'";

    /** Spaces following the closing quotation mark on card 11. */
    private static final int START_SYMBOL_TRAILING_SPACES = 51;

    /** Spaces following the closing quotation mark on card 12. */
    private static final int END_SYMBOL_TRAILING_SPACES = 53;

    /** Cards 13 and 16: the in-stream terminator, repeated verbatim twice. */
    private static final String CARD_IN_STREAM_TERMINATOR = "/*";

    /** Card 14: the date-parameter in-stream declaration card. */
    private static final String CARD_DATEPARM_DD = "//STEP10R.DATEPARM DD *";

    /** Spaces between the two date slots on card 15. */
    private static final int DATE_PARAMETER_SEPARATOR_SPACES = 1;

    /** Spaces following the second date slot on card 15. */
    private static final int DATE_PARAMETER_TRAILING_SPACES = 59;

    /** Card 17: the end-of-stream sentinel, which the emitting paragraph transmits. */
    private static final String CARD_EOF_SENTINEL = "/*EOF";

    /** One-based ordinal of the card carrying the first start-date slot. */
    private static final int ORDINAL_START_DATE_SYMBOL = 11;

    /** One-based ordinal of the card carrying the first end-date slot. */
    private static final int ORDINAL_END_DATE_SYMBOL = 12;

    /** One-based ordinal of the card carrying the second start-date and end-date slots. */
    private static final int ORDINAL_DATE_PARAMETER = 15;

    /** One-based ordinals at which the comment card recurs. */
    private static final List<Integer> COMMENT_CARD_ORDINALS = List.of(3, 5, 7);

    /** One-based ordinals at which the in-stream terminator recurs. */
    private static final List<Integer> TERMINATOR_CARD_ORDINALS = List.of(13, 16);

    // =================================================================================================
    // SECTION 4 - CONTRACT TWO: THE REPORT-REQUEST SCREEN AND THE QUEUE DEFINITION
    // =================================================================================================

    /** The marker character a report-type field carries when it is marked. */
    private static final String MARKED = "Y";

    /** The affirmative confirmation the gate admits. */
    private static final String CONFIRM_YES = "Y";

    /** The negative confirmation the gate refuses in silence. */
    private static final String CONFIRM_NO = "N";

    /**
     * A confirmation value the gate recognises as neither affirmative nor negative.
     *
     * <p>One character, because the screen field is one character wide and the request contract bounds it
     * to that width - a wider value is refused before the gate is reached, which is a different contract
     * and is asserted separately. The legacy delimiter behaviour that truncates an interpolated value at
     * its first space is therefore not observable through <em>this</em> field at all; it is observable
     * through the report name, which is ten characters wide and space-padded, and that is where this
     * class asserts it.
     */
    private static final String CONFIRM_INVALID = "Q";

    /** A confirmation wider than the one character the screen field declares. */
    private static final String CONFIRM_OVERSIZED = "YES";

    /** The text the queue-write refusal arm returns to the operator. */
    private static final String UNABLE_TO_WRITE_QUEUE = "Unable to Write TDQ (JOBS)...";

    /** Prefix of the confirmation prompt an unanswered gate returns. */
    private static final String CONFIRM_PROMPT_PREFIX = "Please confirm to print the ";

    /** Suffix of the confirmation prompt an unanswered gate returns. */
    private static final String CONFIRM_PROMPT_SUFFIX = " report...";

    /** Suffix of the acknowledgement an accepted submission returns. */
    private static final String SUBMITTED_SUFFIX = " report submitted for printing ...";

    /** Resolved period name of the month-to-date arm, evaluated first. */
    private static final String PERIOD_MONTHLY = "Monthly";

    /** Resolved period name of the year-to-date arm, evaluated second. */
    private static final String PERIOD_YEARLY = "Yearly";

    /** Resolved period name of the operator-range arm, evaluated third. */
    private static final String PERIOD_CUSTOM = "Custom";

    /** Canonical region, exactly as every configuration document declares it. */
    private static final String CANONICAL_REGION = "us-east-1";

    /** Canonical object-store staging bucket, exactly as every configuration document declares it. */
    private static final String CANONICAL_BUCKET = "carddemo-batch-staging";

    /**
     * Canonical submission queue.
     *
     * <p>The migration plan names this resource {@code JOBS}, after the transient-data queue it
     * replaces, and the suffix is mandatory rather than decorative because the queue service refuses a
     * first-in-first-out queue whose name omits it. It is <em>not</em> module-namespaced, and that
     * asymmetry with the other three resources is deliberate and is what the configuration ships.
     */
    private static final String CANONICAL_QUEUE = "JOBS.fifo";

    /** Canonical notification topic, exactly as every configuration document declares it. */
    private static final String CANONICAL_TOPIC = "carddemo-job-notifications";

    /** Canonical message group; one stable value is what preserves append order. */
    private static final String CANONICAL_MESSAGE_GROUP = "carddemo-job-submission";

    /** Message attribute naming the submission a card belongs to. */
    private static final String SUBMISSION_ID_ATTRIBUTE = "carddemo-submission-id";

    /** Message attribute naming a card's one-based ordinal within its submission. */
    private static final String CARD_ORDINAL_ATTRIBUTE = "carddemo-card-ordinal";

    /** Message attribute naming how many cards the submission holds. */
    private static final String CARD_COUNT_ATTRIBUTE = "carddemo-card-count";

    /** Queue attribute the service sets on a first-in-first-out queue. */
    private static final String FIFO_ATTRIBUTE_SET = "true";

    // =================================================================================================
    // SECTION 5 - CONTRACT THREE: THE FOUR FIXED-WIDTH RECORD FORMATS
    // =================================================================================================

    /** Class-path directory holding the delivered golden artefacts. */
    private static final String EXPECTED_FIXTURE_DIRECTORY = "/fixtures/expected/";

    /** Object-key prefix this specification stages its round-trip artefacts under. */
    private static final String ROUND_TRIP_KEY_PREFIX = "gate5/interface-contract/";

    /**
     * The four golden-backed record formats, each with its contractual width and its delivered golden.
     *
     * <p>The widths are the contract and are written out rather than derived: eighty for the statement
     * record, one hundred for its hypertext counterpart - which is the resolution of the eighty-against-
     * one-hundred conflict between two steps of the statement job, settled in favour of the emitting
     * program's own field width - one hundred and thirty-three for the report line, and four hundred and
     * thirty for the rejected daily transaction, being its three-hundred-and-fifty-byte source image
     * followed by an eighty-byte trailer of a four-digit reason code and a seventy-six-character
     * description.
     *
     * <p><strong>These four are the ones this specification stages, not the whole width contract.</strong>
     * The estate emits a fifth fixed width - {@link #CATEGORY_BALANCE_REPORT_WIDTH} bytes, the
     * category-balance report the {@code PRTCATBL} job stream produces - which is not staged here because
     * Gate 1 names four expected outputs and this specification stages exactly those. Its own committed
     * golden is compared against a real run, at its own width and its own ordering, by
     * {@code batch/CategoryBalanceReportJobConfigIT}, and the width is asserted below so that a reader of
     * this list cannot mistake these four for the estate's whole set of fixed widths.
     */
    private static final List<RecordFormat> RECORD_FORMATS = List.of(
            new RecordFormat("statement text record", "statement.txt", 80),
            new RecordFormat("statement hypertext record", "statement-html.txt", 100),
            new RecordFormat("transaction report line", "transaction-report.txt", 133),
            new RecordFormat("rejected daily transaction", "daily-reject.txt", 430));

    /**
     * The fifth fixed width the estate emits: the category-balance report line.
     *
     * <p>Read from the delivered job configuration rather than repeated as a literal, so this specification
     * and the job cannot drift apart. The job stream declares {@code SORTOUT DCB=(LRECL=40)} over a sort of
     * account identifier, type code and category code, all ascending - which is the fourth of the estate's
     * four external sort specifications, and the one the plan's "three distinct specifications" omits.
     */
    private static final int CATEGORY_BALANCE_REPORT_WIDTH =
            CategoryBalanceReportJobConfig.REPORT_RECORD_LENGTH;

    /** The batch-control surface, which starts a job and is therefore administrator-only. */
    private static final String BATCH_CONTROL_PATH = "/api/batch/jobs";

    /** The sign-on boundary, as the module maps it. */
    private static final String SIGN_ON_PATH = AuthController.SIGN_ON_PATH;

    /** The report-request boundary, as the module maps it. */
    private static final String REPORT_REQUEST_PATH = ReportController.REPORT_REQUEST_PATH;

    /** Prefix a bearer session is presented behind. */
    private static final String BEARER_PREFIX = "Bearer ";

    /** Number of dot-delimited parts a signed session token carries. */
    private static final int TOKEN_PART_COUNT = 3;

    /**
     * Width of the non-reversible fingerprint a session is identified by in a diagnostic.
     *
     * <p>Long enough to tell two sessions apart in a failure report, far too short to be worth
     * attacking, and derived by a one-way digest so it cannot be turned back into the token.
     */
    private static final int FINGERPRINT_CHARACTERS = 8;

    /** Alphabet the fingerprint is rendered in, written out so no locale-sensitive formatter is used. */
    private static final String HEXADECIMAL = "0123456789abcdef";

    /**
     * Capacity of the buffer one sign-on body is composed in.
     *
     * <p>Generous rather than exact: the buffer is overwritten before the frame that allocated it
     * returns, so its size costs nothing, and a body that overflowed a tight buffer would fail with a
     * capacity error rather than with the contract this class is asserting.
     */
    private static final int JSON_BODY_BUFFER_CHARS = 256;

    /**
     * Distance between a lower-case and an upper-case ASCII letter, for the in-place substitution.
     *
     * <p>Named rather than written inline so that the fold reads as the table substitution it is rather
     * than as arithmetic on characters.
     */
    private static final int LOWER_TO_UPPER_DISTANCE = 'a' - 'A';

    /** A probe value that is not a credential of anything, for proving the fold agrees with the module's. */
    private static final String FOLD_PROBE = "aZ9 mIxEd-cAsE";

    /** Reads and writes JSON without binding this class to a published contract type. */
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate http;

    /**
     * The credential master, spied rather than replaced.
     *
     * <p>A spy delegates to the shipped repository by default, so every read and write in this class goes
     * to the real server. Only the assertion on the catch-all arm of the lookup cascade stubs it, because
     * that arm answers a credential store that cannot be <em>reached</em> and a reachable store cannot
     * produce it. The framework resets the spy after each test method, so the stub cannot leak.
     */
    @MockitoSpyBean
    private UserSecurityRepository users;

    @Autowired
    private AwsProperties aws;

    /**
     * The shipped verifier, asked whether it agrees with an independent encoder about a stored digest.
     *
     * <p>Nothing is installed with it. An independent encoder answers whether a delivered digest is a
     * digest of the provisioned value; it does not answer whether the module the migration ships would
     * agree, because a cost factor, a version marker or a pre-comparison transformation the module applies
     * and a bare encoder does not would separate the two. Asking both, and requiring the same answer, is
     * what closes that gap.</p>
     */
    @Autowired
    private CredentialDigestService digests;

    /**
     * The queue client the submission service publishes through, spied rather than replaced.
     *
     * <p>A spy delegates to the shipped client by default, so every submission in this class reaches the
     * real emulator over the real transport and every card assertion is made against a genuinely
     * delivered message. Only the tolerance group stubs it, to reach the one path a working queue cannot
     * produce: a refused write. The framework resets a spy after each test method, so a stub cannot leak
     * into a neighbouring specification.
     *
     * <p>This is the injected boundary and not a container: no emulator, no queue and no client is
     * created here, and the object being spied is the one the shipped configuration built.
     */
    @MockitoSpyBean
    private SqsOperations queueBoundary;

    /** Creates the specification. */
    OnlineTransactionE2ETest() {
        super();
    }

    /**
     * Returns the shared server to its seeded state, so the ten delivered identities are the ones this
     * specification reasons about whatever ran before it.
     *
     * @throws SQLException if the seeded state cannot be restored
     */
    @BeforeAll
    static void restoreDeliveredIdentities() throws SQLException {
        restoreSeededState();
    }

    /**
     * Returns the shared server to its seeded state once this specification has finished with it.
     *
     * <h4>Why an exit restore is owed even though no test here rewrites a digest</h4>
     * Nothing in this class replaces a delivered credential digest any more - that is the whole point of
     * {@link #admitSignOn(String)} presenting the provisioned value instead - so the ten digests are the
     * delivered ones throughout. What this class does write is submission state: every accepted report
     * request reaches the shipped coordinator, which records the submission on the shared server. A
     * neighbouring specification that counts submissions, or that asserts a table is as the seed left it,
     * would otherwise be answering for rows this class created.
     *
     * <p>Paired with {@link #restoreDeliveredIdentities()} rather than replacing it: the entry restore
     * makes this class independent of whatever ran before, and the exit restore makes whatever runs next
     * independent of this class. Neither is inferred from the other.
     *
     * @throws SQLException if the seeded state cannot be restored
     */
    @AfterAll
    static void leaveTheSeededStateBehind() throws SQLException {
        restoreSeededState();
    }

    /**
     * Empties the shared submission queue before every test, and proves it started empty.
     *
     * <p>Asserting the count rather than discarding it is what turns "the queue was clean" from an
     * assumption into a fact: a specification that drained a neighbour's leftovers would otherwise
     * report a card-count failure belonging to a test that had already passed.
     */
    @BeforeEach
    void startFromAnEmptyQueue() {
        assertThat(resetJobSubmissionQueue())
                .as("the shared submission queue must be empty before a card-count assertion; a "
                        + "message left by a neighbouring specification would be drained here and "
                        + "counted against this one")
                .isZero();
    }

    /**
     * Empties the shared submission queue after every test, whatever the outcome.
     *
     * <p>Unconditional because a half-drained queue disrupts a neighbour as surely as a full one, and
     * the resulting failure then belongs to a test that had already passed.
     */
    @AfterEach
    void leaveAnEmptyQueue() {
        resetJobSubmissionQueue();
    }

    // =================================================================================================
    // GROUP 1 - THE TEN DELIVERED IDENTITIES
    // =================================================================================================

    @Nested
    @DisplayName("the ten identities the credential seed delivered")
    class TheDeliveredIdentities {

        /** Creates the nested specification. */
        TheDeliveredIdentities() {
            // Intentionally empty: this group holds no state of its own.
        }

        @Test
        @DisplayName("are all ten, each carrying the identifier, the given name, the family name and "
                + "the type the provisioning stream carried")
        void allTenArrivedWithTheirDeliveredFields() {
            for (final DeliveredIdentity expected : DELIVERED_IDENTITIES) {
                final UserSecurity delivered = identity(expected.userId());

                assertThat(delivered.getSecUsrId())
                        .as("identifier of the delivered record for %s", expected.userId())
                        .isEqualTo(expected.userId());
                assertThat(delivered.getSecUsrFname())
                        .as("given name of %s; the logical value is the trimmed one, because the "
                                + "legacy field is space-padded to %d bytes",
                                expected.userId(), NAME_FIELD_WIDTH)
                        .isEqualTo(expected.givenName());
                assertThat(delivered.getSecUsrLname())
                        .as("family name of %s", expected.userId())
                        .isEqualTo(expected.familyName());
                assertThat(delivered.getSecUsrType())
                        .as("type byte of %s, which is the single field that decides the destination",
                                expected.userId())
                        .isEqualTo(expected.type());
            }
        }

        @Test
        @DisplayName("carry the two type codes the module's own vocabulary declares, so the seed and "
                + "the enumeration cannot drift apart")
        void theDeliveredTypeCodesAreTheDeclaredOnes() {
            assertThat(ADMIN_TYPE)
                    .as("the administrative code this specification expects on the delivered records "
                            + "must be the code the module's user-type vocabulary declares; two "
                            + "independent declarations of one byte are exactly how a seed and an "
                            + "enumeration drift apart without either failing on its own")
                    .isEqualTo(UserType.ADMIN.getCode());
            assertThat(USER_TYPE)
                    .as("and likewise the ordinary code")
                    .isEqualTo(UserType.USER.getCode());
            for (final DeliveredIdentity expected : DELIVERED_IDENTITIES) {
                assertThat(UserType.fromCode(identity(expected.userId()).getSecUsrType()))
                        .as("the code stored for %s must be one the vocabulary recognises; an "
                                + "unrecognised code is still a successful read and follows the "
                                + "non-administrative route, so it would reroute silently",
                                expected.userId())
                        .isPresent();
            }
        }

        @Test
        @DisplayName("split five administrative against five ordinary, which is what makes both "
                + "routing arms reachable from delivered data alone")
        void theTypesSplitFiveAndFive() {
            assertThat(DELIVERED_IDENTITIES).hasSize(TestDataFactory.SEEDED_USER_COUNT);
            assertThat(DELIVERED_IDENTITIES.stream()
                    .filter(identity -> ADMIN_TYPE.equals(identity.type()))
                    .count())
                    .as("administrative identities among the ten delivered")
                    .isEqualTo(5L);
            assertThat(DELIVERED_IDENTITIES.stream()
                    .filter(identity -> USER_TYPE.equals(identity.type()))
                    .count())
                    .as("ordinary identities among the ten delivered")
                    .isEqualTo(5L);
        }

        @Test
        @DisplayName("carry ten stored digests, each of the required shape and cost, and no two alike")
        void theStoredDigestsAreShapedAndDistinct() throws SQLException {
            // Asserted about the DELIVERED digests, so the seed is reapplied first. No test in this class
            // replaces a digest, but the shared server outlives the class and a claim about what the
            // migration delivered must be answered by the migration's own rows and nothing else.
            restoreSeededState();

            final List<String> stored = new ArrayList<>(DELIVERED_IDENTITIES.size());
            for (final DeliveredIdentity expected : DELIVERED_IDENTITIES) {
                final String digest = identity(expected.userId()).credentialDigest();

                assertThat(TestDataFactory.hasStoredDigestShape(digest))
                        .as("the value stored for %s must have the required length, a recognised "
                                + "version marker and the module's cost factor of %d; the digest "
                                + "itself is deliberately not named in this diagnostic",
                                expected.userId(), TestDataFactory.BCRYPT_WORK_FACTOR)
                        .isTrue();
                assertThat(TestDataFactory.BCRYPT_VERSION_MARKERS.stream()
                        .anyMatch(digest::startsWith))
                        .as("the value stored for %s must open with one of the recognised version "
                                + "markers %s; the digest itself is deliberately not named here",
                                expected.userId(), TestDataFactory.BCRYPT_VERSION_MARKERS)
                        .isTrue();
                assertThat(digest.length())
                        .as("stored digest length for %s", expected.userId())
                        .isEqualTo(TestDataFactory.BCRYPT_DIGEST_LENGTH);
                stored.add(digest);
            }

            assertThat(stored)
                    .as("ten records of one shared credential must carry ten distinct digests, which "
                            + "is only true if each carries its own salt; a repeated digest would mean "
                            + "a shared salt and would make one record's replacement invisible")
                    .hasSize(TestDataFactory.SEEDED_DIGEST_COUNT)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("accept the credential the read-only provisioning member provisions, which is the "
                + "one claim a frozen digest exists to support")
        void theDeliveredDigestsAcceptTheProvisionedCredential() throws SQLException {
            // The digests being interrogated are the ones V4 froze, so the seed is reapplied first and
            // nothing between here and the assertion writes to the credential column.
            restoreSeededState();

            final PasswordEncoder encoder =
                    new BCryptPasswordEncoder(TestDataFactory.BCRYPT_WORK_FACTOR);
            int accepted = 0;
            for (final DeliveredIdentity expected : DELIVERED_IDENTITIES) {
                final String digest = identity(expected.userId()).credentialDigest();

                assertThat(TestDataFactory.digestAcceptsProvisioningCredential(encoder, digest))
                        .as("the digest the migration froze for %s must accept the credential the "
                                + "read-only provisioning member %s carries in its own credential "
                                + "window, recovered by offset at run time. This is the whole claim a "
                                + "frozen digest makes: ten digests nobody can verify are ten digests "
                                + "that could each be wrong while every shape, distinctness and "
                                + "refusal check still passed. Neither the credential nor the digest "
                                + "is named in this diagnostic, and neither may ever be",
                                expected.userId(), TestDataFactory.PROVISIONING_MEMBER)
                        .isTrue();
                assertThat(OnlineTransactionE2ETest.this.shippedVerifierAccepts(digest))
                        .as("and the module's own shipped verifier must reach the same conclusion for "
                                + "%s; agreement between an independent encoder and the shipped one is "
                                + "what rules out a cost factor or version marker the module accepts "
                                + "and nothing else does", expected.userId())
                        .isTrue();
                accepted++;
            }

            assertThat(accepted)
                    .as("all ten delivered digests must have been interrogated; a loop that ran over "
                            + "fewer would report success for identities nobody checked")
                    .isEqualTo(TestDataFactory.SEEDED_DIGEST_COUNT);
        }

        @Test
        @DisplayName("refuse a value they were not derived from, so acceptance is not vacuous")
        void theStoredDigestsRefuseAValueTheyWereNotDerivedFrom() throws SQLException {
            restoreSeededState();

            final PasswordEncoder encoder =
                    new BCryptPasswordEncoder(TestDataFactory.BCRYPT_WORK_FACTOR);
            for (final DeliveredIdentity expected : DELIVERED_IDENTITIES) {
                assertThat(TestDataFactory.digestRefusesOtherValues(encoder,
                        identity(expected.userId()).credentialDigest()))
                        .as("the digest stored for %s must refuse a value it was not derived from; a "
                                + "digest that accepted everything would satisfy an acceptance check "
                                + "and be worthless", expected.userId())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("ACCEPT the delivered credential - all ten, read off a real server, with nothing "
                + "installed and nothing substituted")
        void everyDeliveredDigestAcceptsTheDeliveredCredential() throws SQLException {
            // THE PROPERTY THE TWO TESTS ABOVE CANNOT ESTABLISH, AND THE REASON THEY CANNOT.
            //
            // Shape, cost, distinctness and refusal are every one of them satisfied by a digest of ANY
            // value at all. A wrong literal in V4__seed_user_security.sql yields sixty characters, a
            // recognised marker, the module's cost factor, ten independent salts, and a refusal of the
            // probe - and admits nobody. Acceptance is the only property that separates a CORRECT
            // shipped digest from a well-formed one.
            //
            // The seed is reapplied first so this is a claim about what the MIGRATION delivered rather
            // than about anything a neighbouring test may have written.
            restoreSeededState();

            final PasswordEncoder encoder =
                    new BCryptPasswordEncoder(TestDataFactory.BCRYPT_WORK_FACTOR);

            assertThat(TestDataFactory.fixtureCredentialWindowIsFoldInvariant())
                    .as("the sign-on transaction folds a submitted credential to upper case before "
                            + "comparing it, so a direct match against the credential equals a real "
                            + "sign-on only while the credential is invariant under that fold")
                    .isTrue();

            for (final DeliveredIdentity expected : DELIVERED_IDENTITIES) {
                assertThat(TestDataFactory.digestAcceptsFixtureCredentialWindow(encoder,
                        identity(expected.userId()).credentialDigest()))
                        .as("the digest delivered for %s must accept the delivered credential; the "
                                + "credential is deliberately not named in this diagnostic",
                                expected.userId())
                        .isTrue();
            }
            assertThat(DELIVERED_IDENTITIES).hasSize(TestDataFactory.SEEDED_USER_COUNT);
        }

        @Test
        @DisplayName("are what a successful sign-on actually verifies against: the stored digest is "
                + "byte-identical before and after an admitted request")
        void aSuccessfulSignOnVerifiesAgainstTheDeliveredDigest() throws Exception {
            // The end-to-end half of the statement above, and the one that closes the substitution.
            // Every admitted sign-on in this class used to install a digest of its own onto the record
            // first, so the request was verified against a value this class had just written and the
            // delivered digest was never exercised. Capturing the stored digest either side of an
            // admitted request is what proves that is no longer happening: an installation would show
            // up here as a changed digest.
            restoreSeededState();
            final String before = identity(SOME_ADMIN_ID).credentialDigest();

            final ResponseEntity<String> reply = admitSignOn(SOME_ADMIN_ID);

            assertThat(reply.getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                    .as("the request is admitted, so the delivered digest genuinely accepted the "
                            + "submitted credential across a real boundary")
                    .isNotNull();
            assertThat(identity(SOME_ADMIN_ID).credentialDigest())
                    .as("and the stored digest is untouched, character for character - so what was "
                            + "verified against is what the migration delivered, not a substitute")
                    .isEqualTo(before);
            assertThat(TestDataFactory.hasStoredDigestShape(before))
                    .as("the digest verified against is a digest, not a cleartext value in the column")
                    .isTrue();
        }

        @Test
        @DisplayName("are reached with a value folded exactly as the boundary folds it, proved on a "
                + "probe that is not a credential")
        void theFoldAppliedHereIsTheFoldTheBoundaryApplies() {
            final char[] probe = FOLD_PROBE.toCharArray();
            asciiUpperFoldInPlace(probe);

            assertThat(new String(probe))
                    .as("the in-place fold this specification applies to the presented value must be "
                            + "the very fold the sign-on program applies before it compares anything - "
                            + "a twenty-six character ASCII substitution that leaves every other "
                            + "character alone. Proved on a probe rather than on the credential, so the "
                            + "credential never becomes a string; a locale-aware routine would differ "
                            + "here and would make an admitted sign-on impossible for a reason "
                            + "unrelated to any contract")
                    .isEqualTo(CobolStringUtils.asciiUpperFold(FOLD_PROBE));
            assertThat(new String(probe))
                    .as("and it must genuinely have folded something, or the agreement above would be "
                            + "vacuous")
                    .isNotEqualTo(FOLD_PROBE);
        }

        @Test
        @DisplayName("hold no readable secret: the delivered record's own rendering discloses neither "
                + "a credential nor a digest")
        void theDeliveredRecordDisclosesNoCredentialMaterial() {
            final String rendered = identity(SOME_ADMIN_ID).toString();

            assertThat(rendered)
                    .as("the record's own rendering must disclose no digest, because anything that "
                            + "logs a record would then disclose credential material with no code "
                            + "written to send it")
                    .doesNotContain(TestDataFactory.BCRYPT_VERSION_MARKERS.get(0))
                    .doesNotContain(TestDataFactory.BCRYPT_VERSION_MARKERS.get(1))
                    .doesNotContain(TestDataFactory.BCRYPT_VERSION_MARKERS.get(2));
            assertThat(rendered)
                    .as("it must still name the identity, or a diagnostic could not say which record "
                            + "it described")
                    .contains(SOME_ADMIN_ID);
        }
    }

    // =================================================================================================
    // GROUP 2 - CONTRACT ONE: THE SEVEN OPERATOR-VISIBLE MESSAGE TEXTS
    // =================================================================================================

    @Nested
    @DisplayName("contract one: the seven operator-visible message texts, over the real boundary")
    class TheSignOnMessageTexts {

        /** Creates the nested specification. */
        TheSignOnMessageTexts() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("a submission naming no identity returns the first prompt, and returns the "
                + "cursor to the identifier field")
        void anEmptySubmissionReturnsTheFirstPrompt() throws IOException {
            final JsonNode body = signOnAndParse("", "", SUBMIT_KEY);

            assertThat(textOf(body, "message"))
                    .as("the first prompt, character for character including its trailing ellipsis")
                    .isEqualTo(PROMPT_FOR_USER_ID);
            assertThat(textOf(body, "focusScreenFieldId"))
                    .as("the legacy program moves the cursor to the identifier field on this arm, so "
                            + "the reply must name that field and not the secret one")
                    .isEqualTo(USER_ID_FIELD);
        }

        @Test
        @DisplayName("a submission naming an identity but no secret returns the second prompt, and "
                + "returns the cursor to the secret field")
        void anIdentityWithNoSecretReturnsTheSecondPrompt() throws IOException {
            final JsonNode body = signOnAndParse(SOME_USER_ID, "", SUBMIT_KEY);

            assertThat(textOf(body, "message"))
                    .as("the second prompt, character for character")
                    .isEqualTo(PROMPT_FOR_PASSWORD);
            assertThat(textOf(body, "focusScreenFieldId"))
                    .as("the cursor moves to the secret field on this arm")
                    .isEqualTo(PASSWORD_FIELD);
        }

        @Test
        @DisplayName("the two presence checks are evaluated in order, so a submission missing both "
                + "reports the identifier and never the secret")
        void thePresenceChecksAreEvaluatedInOrder() throws IOException {
            final JsonNode bothMissing = signOnAndParse("", "", SUBMIT_KEY);

            assertThat(textOf(bothMissing, "message"))
                    .as("the legacy construct is an ordered evaluation that stops at the first match, "
                            + "so with both fields blank the identifier prompt wins; reporting the "
                            + "secret here would be a reordering of the cascade")
                    .isEqualTo(PROMPT_FOR_USER_ID)
                    .isNotEqualTo(PROMPT_FOR_PASSWORD);
        }

        @Test
        @DisplayName("a wrong secret and an unknown identity return different texts, and each returns "
                + "the cursor to its own field")
        void aWrongSecretAndAnUnknownIdentityAreDistinguished() throws IOException {
            final JsonNode wrongSecret =
                    signOnAndParse(SOME_USER_ID, "NOTRIGHT", SUBMIT_KEY);
            final JsonNode unknownIdentity =
                    signOnAndParse(UNKNOWN_ID, "NOTRIGHT", SUBMIT_KEY);

            assertThat(textOf(wrongSecret, "message"))
                    .as("the wrong-secret text, character for character")
                    .isEqualTo(WRONG_PASSWORD);
            assertThat(textOf(wrongSecret, "focusScreenFieldId"))
                    .as("a wrong secret returns the cursor to the secret field")
                    .isEqualTo(PASSWORD_FIELD);
            assertThat(textOf(unknownIdentity, "message"))
                    .as("the not-found text, character for character")
                    .isEqualTo(USER_NOT_FOUND);
            assertThat(textOf(unknownIdentity, "focusScreenFieldId"))
                    .as("an unknown identity returns the cursor to the identifier field")
                    .isEqualTo(USER_ID_FIELD);
            assertThat(textOf(wrongSecret, "message"))
                    .as("blurring the two would change what an operator concludes while failing "
                            + "nothing")
                    .isNotEqualTo(textOf(unknownIdentity, "message"));
        }

        @Test
        @DisplayName("a credential store that cannot be reached returns the catch-all text, and returns "
                + "the cursor to the identifier field")
        void anUnreachableCredentialStoreReturnsTheCatchAllText() throws IOException {
            // The third arm of the lookup cascade answers a store that could not be READ - neither a
            // wrong secret nor an absent record - so it is unreachable while the store is reachable. The
            // repository is therefore made to fail for this one turn, which is the only way to observe
            // the arm at all; everything else about the turn is the shipped boundary, the shipped filter
            // chain and the shipped service.
            doThrow(new DataAccessResourceFailureException(
                    "the credential store is unreachable, for the catch-all arm of the lookup cascade"))
                    .when(OnlineTransactionE2ETest.this.users).findById(anyString());

            final JsonNode body = signOnAndParse(SOME_USER_ID, "NOTRIGHT", SUBMIT_KEY);

            assertThat(textOf(body, "message"))
                    .as("the catch-all text, character for character. It must NOT be the not-found "
                            + "text: an operator told an identity does not exist will create it, and an "
                            + "operator told the check could not be made will retry, so blurring the two "
                            + "changes what an operator does")
                    .isEqualTo(UNABLE_TO_VERIFY)
                    .isNotEqualTo(USER_NOT_FOUND);
            assertThat(textOf(body, "focusScreenFieldId"))
                    .as("this arm returns the cursor to the identifier field, as the not-found arm does "
                            + "and unlike the wrong-secret arm")
                    .isEqualTo(USER_ID_FIELD);
            assertThat(textOf(body, "nextRoute"))
                    .as("and it directs the client nowhere new: a turn that could not verify has not "
                            + "admitted anyone")
                    .isNotEqualTo(ADMIN_MENU_ROUTE)
                    .isNotEqualTo(USER_MENU_ROUTE);
        }

        @Test
        @DisplayName("the catch-all text of the lookup cascade is distinct from the other four, so "
                + "three arms stay three arms")
        void theCatchAllTextIsDistinctFromEveryOtherArm() {
            assertThat(UNABLE_TO_VERIFY)
                    .as("the third arm of the lookup cascade must answer a text of its own; two arms "
                            + "sharing a text would collapse the contract without failing any "
                            + "single-text assertion")
                    .isNotEqualTo(WRONG_PASSWORD)
                    .isNotEqualTo(USER_NOT_FOUND)
                    .isNotEqualTo(PROMPT_FOR_USER_ID)
                    .isNotEqualTo(PROMPT_FOR_PASSWORD);
            assertThat(UNABLE_TO_VERIFY)
                    .as("and it must be the exact text the program composes")
                    .isEqualTo("Unable to verify the User ...");
        }

        @Test
        @DisplayName("the exit key returns the courtesy text at its full fifty characters, padding "
                + "included")
        void theExitKeyReturnsTheCourtesyTextFullyPadded() throws IOException {
            final JsonNode body = signOnAndParse(SOME_USER_ID, "", EXIT_KEY);

            final String message = textOf(body, "message");
            assertThat(message)
                    .as("the shared message field is fifty bytes wide, so the seven trailing spaces "
                            + "are part of the value; trimming here would assert a narrower contract "
                            + "than the one that ships")
                    .isEqualTo(COMMON_THANK_YOU);
            assertThat(message.length())
                    .as("width of the common courtesy message as delivered")
                    .isEqualTo(COMMON_MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("an unmapped key returns the unmapped-key text at its full fifty characters, "
                + "padding included")
        void anUnmappedKeyReturnsTheUnmappedKeyTextFullyPadded() throws IOException {
            final JsonNode body = signOnAndParse(SOME_USER_ID, "", UNMAPPED_KEY);

            final String message = textOf(body, "message");
            assertThat(message)
                    .as("ten trailing spaces here against seven on the courtesy text; the two widths "
                            + "differ, which is why each is written out rather than derived")
                    .isEqualTo(COMMON_INVALID_KEY);
            assertThat(message.length())
                    .as("width of the common unmapped-key message as delivered")
                    .isEqualTo(COMMON_MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("the attention-key cascade is evaluated in its source order: submit drives the "
                + "attempt, the exit key is courteous, everything else is unmapped")
        void theAttentionKeyCascadeKeepsItsSourceOrder() throws IOException {
            final String submitted = textOf(signOnAndParse("", "", SUBMIT_KEY), "message");
            final String exited = textOf(signOnAndParse("", "", EXIT_KEY), "message");
            final String unmapped = textOf(signOnAndParse("", "", UNMAPPED_KEY), "message");

            assertThat(submitted)
                    .as("the submit arm is first and drives the credential attempt, so it answers a "
                            + "prompt rather than either common text")
                    .isEqualTo(PROMPT_FOR_USER_ID);
            assertThat(exited)
                    .as("the exit arm is second and answers the courtesy text even though the "
                            + "submission carried no identity, which is what proves it is evaluated "
                            + "before any credential work")
                    .isEqualTo(COMMON_THANK_YOU);
            assertThat(unmapped)
                    .as("every other key falls to the catch-all arm")
                    .isEqualTo(COMMON_INVALID_KEY);
            assertThat(List.of(submitted, exited, unmapped))
                    .as("the three arms must answer three different things, or the ordering would be "
                            + "unobservable")
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a refusal discloses no submitted secret, no stored digest and no framework text")
        void aRefusalDisclosesNothingItShouldNot() {
            final String submitted = "NOTRIGHT";
            final String refusal = signOnRaw(SOME_USER_ID, submitted, SUBMIT_KEY);

            assertThat(refusal)
                    .as("the submitted secret must not be echoed anywhere in the reply")
                    .doesNotContain(submitted);
            assertThat(refusal)
                    .as("nor may the stored digest reach an operator; each recognised version marker "
                            + "is checked because a digest is identifiable by its marker alone")
                    .doesNotContain(TestDataFactory.BCRYPT_VERSION_MARKERS.get(0))
                    .doesNotContain(TestDataFactory.BCRYPT_VERSION_MARKERS.get(1))
                    .doesNotContain(TestDataFactory.BCRYPT_VERSION_MARKERS.get(2));
            assertThat(refusal)
                    .as("nor may a framework, driver or stack-trace fragment reach an operator, "
                            + "because the legacy screen carried only its own eighty-byte message")
                    .doesNotContain("org.springframework")
                    .doesNotContain("org.postgresql")
                    .doesNotContain("Exception");
        }
    }

    // =================================================================================================
    // GROUP 3 - CONTRACT ONE: THE ROUTING OUTCOME AND THE SESSION IT ISSUES
    // =================================================================================================

    @Nested
    @DisplayName("contract one: the destination the delivered type decides, for every delivered "
            + "identity")
    class TheRoutingOutcome {

        /** Creates the nested specification. */
        TheRoutingOutcome() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("routes all five administrative identities to the administrative menu and all "
                + "five ordinary ones to the main menu, over the real boundary")
        void everyDeliveredIdentityReachesTheDestinationItsTypeDecides() throws IOException {
            for (final DeliveredIdentity expected : DELIVERED_IDENTITIES) {
                final ResponseEntity<String> reply = admitSignOn(expected.userId());

                assertThat(reply.getStatusCode())
                        .as("sign-on outcome for user %s", expected.userId())
                        .isEqualTo(HttpStatus.OK);

                final JsonNode body = JSON.readTree(reply.getBody());
                final String expectedRoute = ADMIN_TYPE.equals(expected.type())
                        ? ADMIN_MENU_ROUTE
                        : USER_MENU_ROUTE;

                assertThat(textOf(body, "nextRoute"))
                        .as("destination for user %s, whose delivered type is %s; the legacy branch is "
                                + "an ELSE rather than a second equality test, so the administrative "
                                + "type routes administratively and EVERY other value routes to the "
                                + "main menu", expected.userId(), expected.type())
                        .isEqualTo(expectedRoute);
                assertThat(textOf(body, "userId"))
                        .as("the admitted reply must name the identity it admitted")
                        .isEqualTo(expected.userId());
                assertThat(textOf(body, "userType"))
                        .as("and must carry the type read off the delivered record rather than one "
                                + "inferred from the identifier's spelling")
                        .isEqualTo(expected.type());
                assertThat(textOf(body, "message"))
                        .as("an admitted sign-on carries no refusal text for user %s",
                                expected.userId())
                        .doesNotContain(WRONG_PASSWORD)
                        .doesNotContain(USER_NOT_FOUND)
                        .doesNotContain(UNABLE_TO_VERIFY);
            }
        }

        @Test
        @DisplayName("issues the session in the authorization header, as three signed parts, and never "
                + "in the reply body")
        void theSessionIsIssuedInTheHeaderAndNotTheBody() {
            final ResponseEntity<String> reply = admitSignOn(SOME_ADMIN_ID);
            final String token = bearerTokenOf(reply);
            final String[] parts = token.split("\\.", -1);

            // Both assertions are made through a predicate rather than over the token itself. A failed
            // pattern match prints its subject, and a failed doesNotContain prints the needle AND the
            // haystack, so the assertion that proves a session token is not disclosed would have been the
            // thing that disclosed it - into a build log, at precisely the moment something is wrong.
            assertThat(SensitiveValues.hasSignedTokenShape(token))
                    .as("the session must be a signed three-part token rather than a server-side "
                            + "session identifier, because the migrated tier carries its state in the "
                            + "token instead of in a communication area. Token: %s",
                            SensitiveValues.describe(token))
                    .isTrue();
            assertThat(SensitiveValues.absentFrom(reply.getBody(), token))
                    .as("and it must not also travel in the body, which anything that logs a response "
                            + "body would then record")
                    .isTrue();
        }

        @Test
        @DisplayName("carries the navigation state that stands in for the legacy communication area, "
                + "naming the identity and its type")
        void theReplyCarriesTheNavigationStateThatReplacedTheCommunicationArea() throws IOException {
            final JsonNode body = JSON.readTree(admitSignOn(SOME_USER_ID).getBody());
            final JsonNode context = body.get("navigationContext");

            assertThat(context)
                    .as("the reply must carry the navigation record; it is the client-echoed state that "
                            + "replaced the communication area the legacy transaction passed along")
                    .isNotNull();
            assertThat(context.isObject())
                    .as("the navigation state must be a structure the client can echo back, not a "
                            + "scalar: %s", context)
                    .isTrue();
            assertThat(context.toString())
                    .as("and it must carry the identity and the type forward, which is what the "
                            + "communication area's user fields did")
                    .contains(SOME_USER_ID)
                    .contains(USER_TYPE);
        }

        @Test
        @DisplayName("the session an ordinary identity receives does not open the batch-control "
                + "surface, so the destination is a hint and the authority check is the boundary")
        void anOrdinarySessionIsRefusedTheBatchControlSurface() {
            final String token = bearerTokenOf(admitSignOn(SOME_USER_ID));

            final ResponseEntity<String> reply = get(BATCH_CONTROL_PATH, token);

            assertThat(reply.getStatusCode())
                    .as("starting a batch job was never a screen transaction on the estate, so the "
                            + "surface that starts one is administrator-only; an ordinary session must "
                            + "be refused rather than merely undirected. Exactly forbidden, and not "
                            + "either-of-two: this caller presented a session the module itself issued a "
                            + "moment earlier, so an unauthorized answer would mean the credential was "
                            + "not accepted at all - which is a broken session, a different defect, and "
                            + "one an alternative would have hidden here")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("the three surfaces this class drives are the ones the module maps, so a refusal "
                + "cannot be a refusal of a path that does not exist")
        void theSurfacesDrivenHereAreTheOnesTheModuleMaps() {
            assertThat(SIGN_ON_PATH)
                    .as("the sign-on boundary, as the module maps it")
                    .isEqualTo("/api/auth/signon");
            assertThat(REPORT_REQUEST_PATH)
                    .as("the report-request boundary, as the module maps it")
                    .isEqualTo("/api/reports/request");
            assertThat(BATCH_CONTROL_PATH)
                    .as("the batch-control surface must sit beneath the prefix the module gates, or the "
                            + "refusal asserted below would be a refusal of an unmapped path and would "
                            + "prove nothing about the rule")
                    .startsWith(BatchJobController.BATCH_CONTROL_PATH_PREFIX)
                    .isEqualTo(BatchJobController.BATCH_JOBS_PATH);
        }

        @Test
        @DisplayName("the authority rule on the batch-control surface discriminates by role rather than "
                + "refusing everyone: an administrative session is not turned away on authority")
        void anAdministrativeSessionIsNotRefusedOnAuthority() {
            final String token = bearerTokenOf(admitSignOn(SOME_ADMIN_ID));

            final ResponseEntity<String> reply = get(BATCH_CONTROL_PATH, token);

            assertThat(reply.getStatusCode())
                    .as("the rule that closes this surface to an ordinary session must be a role rule "
                            + "and not a blanket denial; if an administrative session were refused here "
                            + "too, the refusal asserted above would prove nothing about the role. What "
                            + "this session then reaches is the batch tier's own contract and is not "
                            + "this class's charge")
                    .isNotEqualTo(HttpStatus.FORBIDDEN)
                    .isNotEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("an unauthenticated request reaches neither the report boundary nor the "
                + "batch-control surface, so nothing above depends on either being open")
        void anUnauthenticatedRequestReachesNeitherProtectedSurface() {
            assertThat(get(BATCH_CONTROL_PATH, null).getStatusCode())
                    .as("the batch-control surface refuses a caller presenting no credential at all, and "
                            + "it must say so as unauthorized: no identity was established, so there is "
                            + "no entitlement to have been found wanting. Accepting forbidden here as an "
                            + "alternative would let the two refusals become interchangeable, and the "
                            + "distinction is the one the estate drew - a wrong password and an "
                            + "insufficient user type were never the same answer")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(postJson(REPORT_REQUEST_PATH,
                    JSON.createObjectNode().toString().getBytes(StandardCharsets.UTF_8), null)
                    .getStatusCode())
                    .as("and so does the report-request boundary, on the same terms and for the same "
                            + "reason, which is why every submission below presents a session")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // =================================================================================================
    // GROUP 4 - CONTRACT TWO: THE SEVENTEEN CARDS, DRAINED OUT OF A REAL QUEUE
    // =================================================================================================

    @Nested
    @DisplayName("contract two: the batch trigger, asserted from messages the real queue returned")
    class TheBatchTriggerCards {

        /** Creates the nested specification. */
        TheBatchTriggerCards() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("the queue the cards land on is a first-in-first-out queue that does not "
                + "deduplicate on content, which is what lets the repeated cards survive")
        void theQueueIsFifoAndDoesNotDeduplicateOnContent() {
            assertThat(jobSubmissionQueueIsFifo())
                    .as("append disposition on the legacy definition becomes first-in-first-out "
                            + "delivery here, and only a first-in-first-out queue honours a message "
                            + "group at all")
                    .isTrue();

            final Map<QueueAttributeName, String> attributes = jobSubmissionQueueAttributes();
            assertThat(attributes.get(QueueAttributeName.FIFO_QUEUE))
                    .as("the service's own view of the queue must report the first-in-first-out "
                            + "attribute as set")
                    .isEqualTo(FIFO_ATTRIBUTE_SET);
            assertThat(attributes.get(QueueAttributeName.CONTENT_BASED_DEDUPLICATION))
                    .as("content-based deduplication must NOT be enabled. Three cards carry identical "
                            + "comment text and two carry an identical terminator, so under "
                            + "content-based deduplication five of the seventeen would be discarded "
                            + "inside the deduplication window - silently, with every individual card "
                            + "assertion still passing")
                    .isNotEqualTo(FIFO_ATTRIBUTE_SET);
        }

        @Test
        @DisplayName("a month-to-date submission publishes exactly seventeen messages, in the "
                + "authored order, each exactly eighty characters, one message per card")
        void theMonthToDateSubmissionPublishesTheSeventeenCardsInOrder() {
            final ReportSubmission submitted = submitMonthly();

            assertThat(submitted.acknowledged())
                    .as("the submission must be acknowledged, or the cards below were never authored (%s)",
                            submitted)
                    .isTrue();

            final List<String> bodies = submitted.cardBodies();
            final List<String> expected =
                    expectedCards(PINNED_WINDOW_START_DATE.toString(),
                            PINNED_WINDOW_END_DATE.toString());

            assertThat(bodies)
                    .as("the emitting paragraph tests its end-of-stream card BEFORE writing it and "
                            + "leaves the loop only at the next pass, so the sentinel is itself "
                            + "transmitted and a complete submission is exactly %d messages (%s)",
                            CARD_COUNT, submitted)
                    .hasSize(CARD_COUNT);
            for (int ordinal = 1; ordinal <= CARD_COUNT; ordinal++) {
                final String delivered = bodies.get(ordinal - 1);
                assertThat(delivered)
                        .as("card %d as the queue returned it, compared byte for byte against this "
                                + "specification's own constant rather than against the emitter's",
                                ordinal)
                        .isEqualTo(expected.get(ordinal - 1));
                assertThat(usAsciiLength(delivered))
                        .as("the queue definition fixes an eighty-byte fixed unblocked record, so card "
                                + "%d must be exactly %d encoded bytes - no more, and not padded to "
                                + "more either", ordinal, CARD_WIDTH)
                        .isEqualTo(CARD_WIDTH);
            }
        }

        @Test
        @DisplayName("all three periods reach submission, and each publishes the full seventeen cards")
        void allThreePeriodsReachSubmission() {
            final ReportSubmission monthly = submitMonthly();
            assertThat(monthly.acknowledged())
                    .as("the month-to-date arm is evaluated first and must reach submission (%s)", monthly)
                    .isTrue();
            assertThat(monthly.message())
                    .as("its acknowledgement names the period it resolved")
                    .isEqualTo(PERIOD_MONTHLY + SUBMITTED_SUFFIX);
            assertThat(monthly.cardBodies()).hasSize(CARD_COUNT);
            resetJobSubmissionQueue();

            final ReportSubmission yearly = submitYearly();
            assertThat(yearly.acknowledged())
                    .as("the year-to-date arm is evaluated second and must reach submission (%s)", yearly)
                    .isTrue();
            assertThat(yearly.message()).isEqualTo(PERIOD_YEARLY + SUBMITTED_SUFFIX);
            assertThat(yearly.cardBodies()).hasSize(CARD_COUNT);
            resetJobSubmissionQueue();

            final ReportSubmission custom = submitCustom(CONFIRM_YES);
            assertThat(custom.acknowledged())
                    .as("the operator-range arm is evaluated third and must reach submission (%s)", custom)
                    .isTrue();
            assertThat(custom.message()).isEqualTo(PERIOD_CUSTOM + SUBMITTED_SUFFIX);
            assertThat(custom.cardBodies()).hasSize(CARD_COUNT);
        }

        @Test
        @DisplayName("the repeated cards all survive: three identical comment cards at ordinals three, "
                + "five and seven, and two identical terminators at thirteen and sixteen")
        void theRepeatedCardsAllSurviveDeduplication() {
            final List<String> bodies = submitMonthly().cardBodies();

            assertThat(bodies).hasSize(CARD_COUNT);
            for (final int ordinal : COMMENT_CARD_ORDINALS) {
                assertThat(bodies.get(ordinal - 1))
                        .as("the comment card must be present at ordinal %d; a deduplicating queue "
                                + "would have discarded the second and third copies and left twelve "
                                + "messages, with every surviving card still matching", ordinal)
                        .isEqualTo(padded(CARD_COMMENT));
            }
            for (final int ordinal : TERMINATOR_CARD_ORDINALS) {
                assertThat(bodies.get(ordinal - 1))
                        .as("the in-stream terminator must be present at ordinal %d", ordinal)
                        .isEqualTo(padded(CARD_IN_STREAM_TERMINATOR));
            }
            assertThat(bodies.stream().filter(body -> body.equals(padded(CARD_COMMENT))).count())
                    .as("exactly three comment cards must have been delivered")
                    .isEqualTo((long) COMMENT_CARD_ORDINALS.size());
            assertThat(bodies.stream()
                    .filter(body -> body.equals(padded(CARD_IN_STREAM_TERMINATOR)))
                    .count())
                    .as("exactly two in-stream terminators must have been delivered")
                    .isEqualTo((long) TERMINATOR_CARD_ORDINALS.size());
        }

        @Test
        @DisplayName("the two sort symbols keep their declared typing: the card number zoned decimal "
                + "at offset 263 for sixteen bytes, the processing date character at 305 for ten")
        void theSortSymbolsKeepTheirDeclaredTyping() {
            final List<String> bodies = submitMonthly().cardBodies();

            assertThat(bodies.get(8))
                    .as("the card-number symbol is typed zoned decimal, and the offset and length are "
                            + "the record positions the sort reads; the same offset is typed as "
                            + "CHARACTER in the statement job, which is why the two jobs' comparators "
                            + "are per-job and are never unified")
                    .isEqualTo(padded(CARD_SORT_SYMBOL_CARD_NUM))
                    .contains("263,16,ZD");
            assertThat(bodies.get(9))
                    .as("the processing-date symbol is typed character at offset 305 for ten bytes, "
                            + "which is the width of the date slots substituted below")
                    .isEqualTo(padded(CARD_SORT_SYMBOL_PROC_DT))
                    .contains("305,10,CH");
        }

        @Test
        @DisplayName("the four date slots are ten characters each and hold only two distinct values, "
                + "so card eleven matches card fifteen's first slot and card twelve its second")
        void theFourDateSlotsHoldTwoDistinctValues() {
            final List<String> bodies = submitMonthly().cardBodies();

            final String startSlotOnSymbolCard = slotOf(bodies.get(ORDINAL_START_DATE_SYMBOL - 1),
                    START_SYMBOL_LEAD.length());
            final String endSlotOnSymbolCard = slotOf(bodies.get(ORDINAL_END_DATE_SYMBOL - 1),
                    END_SYMBOL_LEAD.length());
            final String parameterCard = bodies.get(ORDINAL_DATE_PARAMETER - 1);
            final String startSlotOnParameterCard = slotOf(parameterCard, 0);
            final String endSlotOnParameterCard =
                    slotOf(parameterCard, DATE_SLOT_WIDTH + DATE_PARAMETER_SEPARATOR_SPACES);

            for (final String slot : List.of(startSlotOnSymbolCard, endSlotOnSymbolCard,
                    startSlotOnParameterCard, endSlotOnParameterCard)) {
                assertThat(slot.length())
                        .as("each substituted slot occupies exactly ten characters inside a fixed "
                                + "eighty-column frame")
                        .isEqualTo(DATE_SLOT_WIDTH);
            }

            assertThat(startSlotOnSymbolCard)
                    .as("the source moves one start-date value into BOTH start slots, so the symbol "
                            + "card's slot and the parameter card's first slot are the same value; two "
                            + "different values here would mean the sort filtered one range while the "
                            + "program was told another")
                    .isEqualTo(startSlotOnParameterCard);
            assertThat(endSlotOnSymbolCard)
                    .as("and one end-date value into both end slots")
                    .isEqualTo(endSlotOnParameterCard);
            assertThat(startSlotOnSymbolCard)
                    .as("the month-to-date window opens on the first of the pinned month, taken from "
                            + "the shared pinned clock so this assertion means the same thing on every "
                            + "run")
                    .isEqualTo(PINNED_WINDOW_START_DATE.toString());
            assertThat(endSlotOnSymbolCard)
                    .as("and closes on its last day, derived by the arm itself rather than by a "
                            + "month-length lookup")
                    .isEqualTo(PINNED_WINDOW_END_DATE.toString());
        }

        @Test
        @DisplayName("the closing quotation mark of each sort-symbol card is the first byte of a wider "
                + "trailing filler, so the byte after the slot is the quote and the rest are spaces")
        void theSortSymbolCardsCloseTheirQuoteBeforeTheirPadding() {
            final List<String> bodies = submitMonthly().cardBodies();

            final String startCard = bodies.get(ORDINAL_START_DATE_SYMBOL - 1);
            final int startQuoteAt = START_SYMBOL_LEAD.length() + DATE_SLOT_WIDTH;
            assertThat(startCard.substring(startQuoteAt, startQuoteAt + 1))
                    .as("eighteen characters of lead plus a ten-character slot puts the closing quote "
                            + "at column %d, because it is the first byte of the fifty-two byte "
                            + "trailing filler and not a character appended after the padding",
                            startQuoteAt + 1)
                    .isEqualTo(CLOSING_QUOTE);
            assertThat(startCard.substring(startQuoteAt + 1))
                    .as("and everything after the quote is the remaining %d spaces of that filler",
                            START_SYMBOL_TRAILING_SPACES)
                    .isEqualTo(spaces(START_SYMBOL_TRAILING_SPACES));

            final String endCard = bodies.get(ORDINAL_END_DATE_SYMBOL - 1);
            final int endQuoteAt = END_SYMBOL_LEAD.length() + DATE_SLOT_WIDTH;
            assertThat(endCard.substring(endQuoteAt, endQuoteAt + 1))
                    .as("sixteen characters of lead plus a ten-character slot puts this card's closing "
                            + "quote at column %d", endQuoteAt + 1)
                    .isEqualTo(CLOSING_QUOTE);
            assertThat(endCard.substring(endQuoteAt + 1))
                    .as("followed by the remaining %d spaces", END_SYMBOL_TRAILING_SPACES)
                    .isEqualTo(spaces(END_SYMBOL_TRAILING_SPACES));
        }

        @Test
        @DisplayName("the seventeenth message is the end-of-stream sentinel, which the paragraph "
                + "transmits rather than stopping short of")
        void theSeventeenthMessageIsTheTransmittedSentinel() {
            final List<String> bodies = submitMonthly().cardBodies();

            assertThat(bodies).hasSize(CARD_COUNT);
            assertThat(bodies.get(CARD_COUNT - 1))
                    .as("the terminator test fires BEFORE the write, so the sentinel is written and "
                            + "the loop ends at the next pass; a submission of sixteen messages would "
                            + "mean the reader never learns the stream is whole")
                    .isEqualTo(padded(CARD_EOF_SENTINEL));
            assertThat(bodies.subList(0, CARD_COUNT - 1))
                    .as("and it appears once, at the end, rather than anywhere earlier")
                    .doesNotContain(padded(CARD_EOF_SENTINEL));
        }

        @Test
        @DisplayName("every message carries the one stable message group, so append order is what the "
                + "queue preserves")
        void everyMessageCarriesTheOneStableMessageGroup() {
            final List<Message> delivered = submitMonthly().messages();

            assertThat(delivered).hasSize(CARD_COUNT);
            for (final Message message : delivered) {
                assertThat(message.attributes().get(MessageSystemAttributeName.MESSAGE_GROUP_ID))
                        .as("append disposition on the legacy definition becomes one stable message "
                                + "group; two groups would let the queue interleave two streams and "
                                + "the authored order would stop being the delivered order")
                        .isEqualTo(CANONICAL_MESSAGE_GROUP);
            }
        }

        @Test
        @DisplayName("the application publishes to this queue and never reads it, so a submitted stream "
                + "is still whole when a reader arrives")
        void theApplicationPublishesAndNeverConsumes() {
            final ReportSubmission first = submitMonthly();

            assertThat(first.cardBodies())
                    .as("the queue definition declares this destination output-only, so nothing in the "
                            + "application may consume from it. Every one of the %d cards must therefore "
                            + "still be on the queue when this specification - the first reader - "
                            + "arrives; a listener anywhere in the module would have taken them and this "
                            + "drain would come back short (%s)", CARD_COUNT, first)
                    .hasSize(CARD_COUNT);
            assertThat(drainJobSubmissionQueue(CARD_COUNT))
                    .as("and once read, they are gone: a second drain must return nothing, which is what "
                            + "makes the count above a measurement of one submission rather than of an "
                            + "accumulation")
                    .isEmpty();
        }

        @Test
        @DisplayName("every message carries the reassembly envelope: one submission identity, its own "
                + "ordinal, and the declared card total")
        void everyMessageCarriesTheReassemblyEnvelope() {
            final List<Message> delivered = submitMonthly().messages();

            assertThat(delivered).hasSize(CARD_COUNT);
            final String submission =
                    attributeOf(delivered.get(0), SUBMISSION_ID_ATTRIBUTE);
            assertThat(submission)
                    .as("a card must name the submission it belongs to, or a reader could not group "
                            + "two concurrent streams apart")
                    .isNotBlank();
            for (int ordinal = 1; ordinal <= CARD_COUNT; ordinal++) {
                final Message message = delivered.get(ordinal - 1);
                assertThat(attributeOf(message, SUBMISSION_ID_ATTRIBUTE))
                        .as("card %d must belong to the same submission as the first", ordinal)
                        .isEqualTo(submission);
                assertThat(attributeOf(message, CARD_ORDINAL_ATTRIBUTE))
                        .as("card %d must declare its own one-based ordinal, which is what lets a "
                                + "reader restore the authored order whatever order the transport "
                                + "delivered", ordinal)
                        .isEqualTo(Integer.toString(ordinal));
                assertThat(attributeOf(message, CARD_COUNT_ATTRIBUTE))
                        .as("card %d must declare the stream total, which is the second way a reader "
                                + "knows the stream is whole", ordinal)
                        .isEqualTo(Integer.toString(CARD_COUNT));
            }
        }
    }

    // =================================================================================================
    // GROUP 5 - CONTRACT TWO: THE CONFIRMATION GATE
    //
    // Every arm is asserted by COUNTING WHAT THE QUEUE HELD, because "did not publish" is the whole
    // content of three of the four arms and a message-free queue is the only honest evidence of it.
    // =================================================================================================

    @Nested
    @DisplayName("contract two: the confirmation gate, with its clause order preserved")
    class TheConfirmationGate {

        /** Creates the nested specification. */
        TheConfirmationGate() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("an unanswered confirmation publishes nothing and returns the prompt, composed "
                + "from the resolved period name")
        void anUnansweredConfirmationPublishesNothing() {
            final ReportSubmission submitted = submitCustom("");

            assertThat(submitted.cardBodies())
                    .as("the whole of the emitting loop sits inside the guard the prompt raises, so an "
                            + "unanswered confirmation must leave the queue completely empty (%s)",
                            submitted)
                    .isEmpty();
            assertThat(submitted.acknowledged())
                    .as("and the turn must not report a submission")
                    .isFalse();
            assertThat(submitted.message())
                    .as("the prompt is composed from the space-delimited period name - the name is ten "
                            + "characters wide and space-padded, and the legacy string verb truncates "
                            + "an interpolated value at its first space, so the prompt reads as the "
                            + "legacy screen read rather than carrying four trailing spaces (%s)",
                            submitted)
                    .isEqualTo(CONFIRM_PROMPT_PREFIX + PERIOD_CUSTOM + CONFIRM_PROMPT_SUFFIX);
            assertThat(submitted.status())
                    .as("a blocked confirmation is a completed screen turn and not a transport fault, "
                            + "so the operator reads it from a normal reply")
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("a declined confirmation publishes nothing, and says nothing, because the source "
                + "sets no message text on that arm")
        void aDeclinedConfirmationPublishesNothingAndSaysNothing() {
            final ReportSubmission submitted = submitCustom(CONFIRM_NO);

            assertThat(submitted.cardBodies())
                    .as("the declined arm raises the error flag, and the flag is in the emitting "
                            + "loop's own guard, so not one card is written (%s)", submitted)
                    .isEmpty();
            assertThat(submitted.acknowledged()).isFalse();
            assertThat(submitted.message())
                    .as("the source composes no text at all on this arm. Inventing a cancellation "
                            + "message would be output the legacy never produced, so the reply must "
                            + "stay silent (%s)", submitted)
                    .isEmpty();
            assertThat(submitted.status()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("an unrecognised confirmation publishes nothing and quotes the entered value back")
        void anUnrecognisedConfirmationPublishesNothingAndQuotesTheValueBack() {
            final ReportSubmission submitted = submitCustom(CONFIRM_INVALID);

            assertThat(submitted.cardBodies())
                    .as("the catch-all arm raises the error flag too, so nothing is published (%s)",
                            submitted)
                    .isEmpty();
            assertThat(submitted.acknowledged()).isFalse();
            assertThat(submitted.message())
                    .as("the diagnostic quotes the entered value back, which is why the confirmation "
                            + "field is carried verbatim rather than reduced to a boolean: accept, "
                            + "silent reset and quoted-back are three distinct outcomes and the "
                            + "character itself has to survive to reach the third (%s)", submitted)
                    .startsWith("\"" + CONFIRM_INVALID + "\"");
            assertThat(submitted.status()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("a confirmation wider than the one character the screen field declares is refused "
                + "before the gate, and publishes nothing")
        void anOversizedConfirmationIsRefusedBeforeTheGate() {
            final ReportSubmission submitted = submitCustom(CONFIRM_OVERSIZED);

            assertThat(submitted.cardBodies())
                    .as("a submission the map could not have carried must not reach the queue (%s)",
                            submitted)
                    .isEmpty();
            assertThat(submitted.acknowledged()).isFalse();
            assertThat(submitted.status())
                    .as("the screen field is one character wide, so a wider value exceeds the widths "
                            + "the report-request map declares and is refused as a malformed request "
                            + "rather than answered as a screen turn (%s)", submitted)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("an affirmative confirmation is the one arm that publishes, and it publishes all "
                + "seventeen")
        void anAffirmativeConfirmationPublishesTheFullStream() {
            final ReportSubmission submitted = submitCustom(CONFIRM_YES);

            assertThat(submitted.cardBodies())
                    .as("the affirmative arm continues past the gate, and a complete submission is the "
                            + "sentinel-inclusive %d (%s)", CARD_COUNT, submitted)
                    .hasSize(CARD_COUNT);
            assertThat(submitted.acknowledged()).isTrue();
            assertThat(submitted.cardBodies())
                    .as("and the operator-supplied range reaches the cards it was given")
                    .containsExactlyElementsOf(expectedCards(PINNED_WINDOW_START_DATE.toString(),
                            PINNED_WINDOW_END_DATE.toString()));
        }

        @Test
        @DisplayName("the four arms are answered in the source's own order: prompt, then affirmative, "
                + "then declined, then the catch-all")
        void theFourArmsKeepTheirSourceOrder() {
            final String promptText = submitCustom("").message();
            resetJobSubmissionQueue();
            final String declinedText = submitCustom(CONFIRM_NO).message();
            resetJobSubmissionQueue();
            final String unrecognisedText = submitCustom(CONFIRM_INVALID).message();

            assertThat(promptText)
                    .as("the presence test comes first and takes precedence over the three-way gate; "
                            + "a blank value must reach the prompt and never the catch-all")
                    .isEqualTo(CONFIRM_PROMPT_PREFIX + PERIOD_CUSTOM + CONFIRM_PROMPT_SUFFIX)
                    .isNotEqualTo(unrecognisedText);
            assertThat(declinedText)
                    .as("the declined arm precedes the catch-all, so a negative value must be silent "
                            + "rather than quoted back")
                    .isEmpty();
            assertThat(declinedText)
                    .as("and its silence must therefore differ from the catch-all's diagnostic")
                    .isNotEqualTo(unrecognisedText);
            assertThat(unrecognisedText)
                    .as("and only a value matching neither reaches the catch-all")
                    .isNotEmpty();
        }
    }

    // =================================================================================================
    // GROUP 6 - CONTRACT TWO: IGNORE-ON-ERROR
    //
    // The legacy queue definition tolerates a failed write. The paragraph logs, raises its flag, sets a
    // message and sends the screen - and the flag it raised is in the emitting loop's own guard, so the
    // cards after the failing one are never sent. It NEVER abends the transaction. This group asserts
    // continuation, and deliberately never asserts that anything was thrown.
    // =================================================================================================

    @Nested
    @DisplayName("contract two: a failed queue write is tolerated - it logs, stops publishing, and "
            + "returns the operator a message")
    class TheIgnoreOnErrorContract {

        /** Creates the nested specification. */
        TheIgnoreOnErrorContract() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("returns a normal reply carrying the queue-write message, and lets no exception "
                + "escape to the caller")
        void aFailedWriteIsToleratedAndTheCallerIsNeverAborted() {
            refuseEveryPublish();

            final ReportSubmission submitted = submitMonthly();

            assertThat(submitted.status())
                    .as("the definition's ignore-on-error tolerance means the operator reads the "
                            + "failure from a screen. A server-fault status would mean the transaction "
                            + "had been abandoned, which the legacy paragraph never did")
                    .isEqualTo(HttpStatus.OK);
            assertThat(submitted.status().is5xxServerError())
                    .as("and specifically not a server fault")
                    .isFalse();
            assertThat(submitted.message())
                    .as("the refusal arm's own text, character for character (%s)", submitted)
                    .isEqualTo(UNABLE_TO_WRITE_QUEUE);
            assertThat(submitted.acknowledged())
                    .as("a refused submission must not be reported as accepted")
                    .isFalse();
            assertThat(submitted.body())
                    .as("and the reply must carry no exception, class name or stack fragment; the "
                            + "failure is logged, not propagated")
                    .doesNotContain("Exception")
                    .doesNotContain("io.awspring")
                    .doesNotContain("software.amazon");
        }

        @Test
        @DisplayName("publishes nothing further after the refusal, because the flag it raised is the "
                + "emitting loop's own guard")
        void aFailedWriteStopsTheCardsThatWouldHaveFollowed() {
            refuseEveryPublish();

            submitMonthly();

            assertThat(drainJobSubmissionQueue(CARD_COUNT))
                    .as("with every write refused, the queue must hold nothing at all: the first "
                            + "refusal raises the flag the loop's guard tests, so the remaining cards "
                            + "are never offered")
                    .isEmpty();
        }

        @Test
        @DisplayName("leaves the surface working: a submission after the refusal publishes the full "
                + "seventeen again")
        void theSurfaceStillWorksAfterARefusal() {
            refuseEveryPublish();
            submitMonthly();
            resetJobSubmissionQueue();

            allowEveryPublish();
            final ReportSubmission recovered = submitMonthly();

            assertThat(recovered.acknowledged())
                    .as("a tolerated failure must leave no residue - it is one turn's outcome and not "
                            + "a state the surface stays in (%s)", recovered)
                    .isTrue();
            assertThat(recovered.cardBodies())
                    .as("and the next submission publishes the whole stream")
                    .hasSize(CARD_COUNT);
        }
    }

    // =================================================================================================
    // GROUP 7 - THE CANONICAL RESOURCE NAMES
    // =================================================================================================

    @Nested
    @DisplayName("the canonical resource names the configuration binds")
    class TheCanonicalResourceNames {

        /** Creates the nested specification. */
        TheCanonicalResourceNames() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("bind the four resources and the region exactly as every configuration document "
                + "declares them")
        void theBoundNamesAreTheCanonicalOnes() {
            assertThat(OnlineTransactionE2ETest.this.aws.region())
                    .as("the region is bound in the module's own namespace, and the framework's "
                            + "namespace derives its region from it, so one document names it once")
                    .isEqualTo(CANONICAL_REGION);
            assertThat(OnlineTransactionE2ETest.this.aws.s3().batchStagingBucket())
                    .as("the object-store staging bucket, which replaced sequential-dataset staging")
                    .isEqualTo(CANONICAL_BUCKET);
            assertThat(OnlineTransactionE2ETest.this.aws.sqs().jobQueue())
                    .as("the submission queue. The plan names this resource after the transient-data "
                            + "queue it replaces and suffixes it because the queue service refuses a "
                            + "first-in-first-out name that omits the suffix; it is deliberately NOT "
                            + "module-namespaced, unlike the other three")
                    .isEqualTo(CANONICAL_QUEUE)
                    .endsWith(AwsProperties.Sqs.FIFO_QUEUE_NAME_SUFFIX);
            assertThat(OnlineTransactionE2ETest.this.aws.sqs().messageGroupId())
                    .as("one stable message group is what preserves append order")
                    .isEqualTo(CANONICAL_MESSAGE_GROUP);
            assertThat(OnlineTransactionE2ETest.this.aws.sns().jobNotificationTopic())
                    .as("the notification topic")
                    .isEqualTo(CANONICAL_TOPIC);
        }

        @Test
        @DisplayName("point every client at the emulator, so no client can address a real account")
        void everyClientAddressesTheEmulator() {
            assertThat(OnlineTransactionE2ETest.this.aws.hasEndpointOverride())
                    .as("an absent endpoint override does NOT fail closed - the client falls back to "
                            + "the region's real public endpoint - so the override must be present")
                    .isTrue();
            assertThat(OnlineTransactionE2ETest.this.aws.endpointOverrideUri())
                    .as("and it must be a usable address")
                    .isPresent();
            assertThat(jobSubmissionQueueUrl())
                    .as("the queue the submissions above reached is the emulator's, named for the "
                            + "canonical resource")
                    .contains(CANONICAL_QUEUE);
            assertThat(jobNotificationTopicArn())
                    .as("and the notification topic the emulator allocated carries the canonical name")
                    .contains(CANONICAL_TOPIC);
        }

        @Test
        @DisplayName("stage into a bucket with object versioning enabled, which is what carries the "
                + "retained-generation semantics of the legacy output data sets")
        void theStagingBucketRetainsGenerations() {
            assertThat(stagingBucketVersioningStatus(CANONICAL_BUCKET))
                    .as("an unversioned bucket accepts every staged object and keeps only the newest, "
                            + "losing the retention the legacy generation groups carried - and losing "
                            + "it silently, with no operation failing")
                    .isEqualTo(BucketVersioningStatus.ENABLED);
        }
    }

    // =================================================================================================
    // GROUP 8 - CONTRACT THREE: THE FOUR FIXED-WIDTH RECORD FORMATS
    //
    // The interface, not the arithmetic. Each delivered golden is put through the REAL object store that
    // replaced sequential-dataset staging and read back, and the bytes that came back are compared to the
    // bytes on the class path with no decoding, no trimming and no normalisation. The byte-equivalence of
    // a GENERATED artefact is the pipeline specification's charge and is not restated here.
    // =================================================================================================

    @Nested
    @DisplayName("contract three: the four golden-backed fixed-width formats round-tripped through the "
            + "real staging interface, and the fifth width the estate emits beside them")
    class TheFourRecordFormats {

        /** Creates the nested specification. */
        TheFourRecordFormats() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("each artefact divides exactly into records of its contractual width, so no "
                + "partial record can be present")
        void eachArtefactDividesExactlyIntoItsRecords() {
            for (final RecordFormat format : RECORD_FORMATS) {
                final byte[] golden = goldenBytes(format.fixtureName());

                assertThat(golden.length % format.width())
                        .as("the %s is a fixed-width stream at %d bytes, so its length must divide "
                                + "exactly; a remainder means a partial record, which no reader of a "
                                + "fixed-width data set could interpret (%s holds %d bytes)",
                                format.description(), format.width(), format.fixtureName(),
                                golden.length)
                        .isZero();
                assertThat(golden.length / format.width())
                        .as("and it must hold at least one whole %s", format.description())
                        .isPositive();
            }
        }

        @Test
        @DisplayName("the four golden-backed widths are eighty, one hundred, one hundred and thirty-three, "
                + "and four hundred and thirty, and no two are the same")
        void theFourWidthsAreTheContractualOnes() {
            final List<Integer> widths = RECORD_FORMATS.stream().map(RecordFormat::width).toList();

            assertThat(widths)
                    .as("the four record formats this specification stages. One hundred is the resolution "
                            + "of the eighty-against-one-hundred conflict between two steps of the "
                            + "statement job, settled in favour of the emitting program's own field "
                            + "width; four hundred and thirty is a three-hundred-and-fifty-byte source "
                            + "image plus an eighty-byte failure trailer")
                    .containsExactly(80, 100, 133, 430)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the estate emits FIVE fixed widths, not four: the four staged above plus the forty-"
                + "byte category-balance report line, whose own golden is compared against a real run by "
                + "batch/CategoryBalanceReportJobConfigIT")
        void theFifthFixedWidthIsPartOfTheContract() {
            // WHY THIS TEST EXISTS. The four widths above are the four Gate 1 expected outputs, and it is
            // easy to read that list as the estate's whole fixed-width contract - the plan's own width
            // inventory did exactly that, and so omitted the category-balance report. The report is a
            // genuine external file format: the PRTCATBL job stream declares SORTOUT DCB=(LRECL=40) and
            // sorts by account, type and category, all ascending, which is the fourth of the estate's four
            // external sort specifications. It is not staged here because Gate 1 names four expected
            // outputs; its own committed golden is compared against a real run, at its width and its
            // ordering, by its own job integration test.
            final List<Integer> stagedWidths = RECORD_FORMATS.stream().map(RecordFormat::width).toList();

            assertThat(CATEGORY_BALANCE_REPORT_WIDTH)
                    .as("the category-balance report line is forty bytes, read from the job configuration "
                            + "the batch tier delivers rather than repeated as a literal here")
                    .isEqualTo(40);
            assertThat(stagedWidths)
                    .as("and it is a fifth width rather than one of the four staged above, so a reader "
                            + "counting widths from this class alone cannot arrive at four")
                    .doesNotContain(CATEGORY_BALANCE_REPORT_WIDTH);

            final List<Integer> everyWidth = new ArrayList<>(stagedWidths);
            everyWidth.add(Integer.valueOf(CATEGORY_BALANCE_REPORT_WIDTH));
            assertThat(everyWidth)
                    .as("five distinct fixed widths in the whole estate: 40, 80, 100, 133 and 430")
                    .hasSize(5)
                    .doesNotHaveDuplicates()
                    .containsExactlyInAnyOrder(40, 80, 100, 133, 430);
        }

        @Test
        @DisplayName("each artefact survives the real staging interface byte for byte, with no "
                + "decoding, trimming or normalisation")
        void eachArtefactSurvivesTheStagingInterfaceByteForByte() {
            for (final RecordFormat format : RECORD_FORMATS) {
                final byte[] golden = goldenBytes(format.fixtureName());
                final String key = ROUND_TRIP_KEY_PREFIX + format.fixtureName();
                try {
                    stageObject(CANONICAL_BUCKET, key, golden);

                    assertThat(stagedObjectExists(CANONICAL_BUCKET, key))
                            .as("the %s must be present in the staging area after being staged there",
                                    format.description())
                            .isTrue();
                    final byte[] readBack = stagedObjectBytes(CANONICAL_BUCKET, key);
                    assertThat(readBack)
                            .as("the %s must come back out of the store as the exact bytes that went "
                                    + "in. Compared as bytes rather than as text because a "
                                    + "fixed-width record is defined in bytes: a platform-default "
                                    + "decoding, a line-separator rewrite or a trailing-space trim "
                                    + "would each change the record while leaving it readable",
                                    format.description())
                            .isEqualTo(golden);
                    assertThat(readBack.length % format.width())
                            .as("and it must still divide exactly into %d-byte records after the "
                                    + "round trip", format.width())
                            .isZero();
                } finally {
                    // Version-aware, because the canonical staging bucket carries object versioning: an
                    // ordinary delete would add a delete marker, hide the key from the listing this nest
                    // asserts on, and leave every staged copy of the golden fetchable by version
                    // identifier in a bucket every later specification shares. See DL-287.
                    deleteEveryStagedVersionUnder(CANONICAL_BUCKET, key);
                }
            }
        }

        @Test
        @DisplayName("no artefact carries a carriage return or a trailing partial record, because a "
                + "fixed-width stream has neither")
        void noArtefactCarriesALineSeparatorArtefact() {
            for (final RecordFormat format : RECORD_FORMATS) {
                final byte[] golden = goldenBytes(format.fixtureName());
                int carriageReturns = 0;
                for (final byte value : golden) {
                    if (value == '\r') {
                        carriageReturns++;
                    }
                }

                assertThat(carriageReturns)
                        .as("the %s must carry no carriage return. A platform that wrote its own line "
                                + "separator would add one byte per record and shift every field after "
                                + "the first record", format.description())
                        .isZero();
                assertThat(golden.length)
                        .as("and it must be a whole number of %d-byte records with no trailing blank "
                                + "record", format.width())
                        .isGreaterThanOrEqualTo(format.width());
            }
        }

        @Test
        @DisplayName("the staged artefacts are listable under their prefix, which is how a reader names "
                + "a published generation without predicting its key")
        void theStagedArtefactsAreListableUnderTheirPrefix() {
            final String key = ROUND_TRIP_KEY_PREFIX + RECORD_FORMATS.get(0).fixtureName();
            try {
                stageObject(CANONICAL_BUCKET, key, goldenBytes(RECORD_FORMATS.get(0).fixtureName()));

                assertThat(stagedObjectKeysUnder(CANONICAL_BUCKET, ROUND_TRIP_KEY_PREFIX))
                        .as("a generation number is allocated by the store at publication time, so a "
                                + "reader asks the store what it published rather than composing a key "
                                + "from an execution identifier")
                        .contains(key);
            } finally {
                deleteEveryStagedVersionUnder(CANONICAL_BUCKET, key);
            }
        }
    }

    // =================================================================================================
    // THE SEVENTEEN EXPECTED CARDS - THIS CLASS'S OWN ORACLE
    //
    // Assembled here from the constants declared at the head of this file and from nothing else. The
    // module's card emitter is not consulted, imported or referenced anywhere in this file, because an
    // expectation borrowed from the emitter proves only that the emitter agrees with itself.
    // =================================================================================================

    /**
     * Builds the seventeen expected card images for one reporting window.
     *
     * @param  startDate the ten-character start-date slot
     * @param  endDate   the ten-character end-date slot
     * @return the seventeen images, in the authored order, each padded to the record width
     */
    private static List<String> expectedCards(final String startDate, final String endDate) {
        assertThat(startDate.length())
                .as("a start-date slot is ten characters wide, so a differently shaped value could not "
                        + "be substituted into the fixed frame")
                .isEqualTo(DATE_SLOT_WIDTH);
        assertThat(endDate.length())
                .as("and so is an end-date slot")
                .isEqualTo(DATE_SLOT_WIDTH);

        final List<String> cards = new ArrayList<>(CARD_COUNT);
        cards.add(padded(CARD_JOB));
        cards.add(padded(CARD_NOTIFY));
        cards.add(padded(CARD_COMMENT));
        cards.add(padded(CARD_JOBLIB));
        cards.add(padded(CARD_COMMENT));
        cards.add(padded(CARD_EXEC_PROC));
        cards.add(padded(CARD_COMMENT));
        cards.add(padded(CARD_SYMNAMES_DD));
        cards.add(padded(CARD_SORT_SYMBOL_CARD_NUM));
        cards.add(padded(CARD_SORT_SYMBOL_PROC_DT));
        // Cards 11 and 12: the closing quotation mark is the FIRST byte of a wider trailing filler, so
        // the byte immediately after the slot is the quote and every byte after that is a space. Written
        // as lead plus slot plus quote plus a counted run of spaces so the arithmetic is checkable:
        // 18 + 10 + 1 + 51 = 80, and 16 + 10 + 1 + 53 = 80.
        cards.add(START_SYMBOL_LEAD + startDate + CLOSING_QUOTE
                + spaces(START_SYMBOL_TRAILING_SPACES));
        cards.add(END_SYMBOL_LEAD + endDate + CLOSING_QUOTE + spaces(END_SYMBOL_TRAILING_SPACES));
        cards.add(padded(CARD_IN_STREAM_TERMINATOR));
        cards.add(padded(CARD_DATEPARM_DD));
        // Card 15: the two slots again, separated by one space and followed by 59 more.
        // 10 + 1 + 10 + 59 = 80.
        cards.add(startDate + spaces(DATE_PARAMETER_SEPARATOR_SPACES) + endDate
                + spaces(DATE_PARAMETER_TRAILING_SPACES));
        cards.add(padded(CARD_IN_STREAM_TERMINATOR));
        cards.add(padded(CARD_EOF_SENTINEL));

        for (int ordinal = 1; ordinal <= cards.size(); ordinal++) {
            assertThat(cards.get(ordinal - 1).length())
                    .as("expected card %d must itself be exactly %d characters, or this class's own "
                            + "oracle would be wrong before any comparison was made", ordinal,
                            CARD_WIDTH)
                    .isEqualTo(CARD_WIDTH);
        }
        assertThat(cards).hasSize(CARD_COUNT);
        return List.copyOf(cards);
    }

    /**
     * Left-justifies one card's content and space-fills it to the record width.
     *
     * @param  content the card's content
     * @return the content padded to the record width
     */
    private static String padded(final String content) {
        assertThat(content.length())
                .as("card content of %d characters cannot be padded to a %d-byte record",
                        content.length(), CARD_WIDTH)
                .isLessThanOrEqualTo(CARD_WIDTH);
        return content + spaces(CARD_WIDTH - content.length());
    }

    /**
     * Returns a run of spaces.
     *
     * @param  count how many spaces
     * @return the run
     */
    private static String spaces(final int count) {
        return " ".repeat(count);
    }

    /**
     * Reads one ten-character date slot out of a delivered card.
     *
     * @param  card   the delivered card image
     * @param  offset the zero-based offset the slot starts at
     * @return the slot's characters, untrimmed
     */
    private static String slotOf(final String card, final int offset) {
        return card.substring(offset, offset + DATE_SLOT_WIDTH);
    }

    /**
     * Returns how many bytes a card occupies in the encoding a fixed-width card image is defined in.
     *
     * <p>Measured with an encoder-free length after an explicit encode, so a character outside the
     * range widens or narrows the result and is caught, rather than being silently replaced.
     *
     * @param  card the delivered card
     * @return the encoded byte count
     */
    private static int usAsciiLength(final String card) {
        return card.getBytes(StandardCharsets.US_ASCII).length;
    }

    // =================================================================================================
    // SIGN-ON HELPERS
    //
    // The credential never becomes a string this class holds. It is read by offset out of the read-only
    // provisioning member's own in-stream card images into a character array, encoded straight into the
    // request bytes, and both the array and the buffer that carried it are overwritten before the frame
    // returns. Nothing here writes to the credential column.
    // =================================================================================================

    /**
     * Signs the given delivered identity on successfully, over the real boundary and against the
     * <strong>delivered</strong> credential digest.
     *
     * <h4>Nothing is installed, and that is the point</h4>
     * A digest of the fixture's credential window used to be installed on the record before the request
     * was sent. It had to be, because the fixture then carried a fabricated window that none of the ten
     * delivered digests could accept - so every admitted sign-on in this class was authenticating against
     * a digest this class had just written, and the ten digests the migration actually delivered were
     * never exercised by any of them. The consequence was that the one property distinguishing a correct
     * shipped digest from a merely well-formed one, that it accepts the credential, was never exercised
     * end to end: a wrong literal in the seed migration would have left every test here green.
     *
     * <p>The fixture now reproduces the delivered provisioning records, so nothing is installed and
     * nothing is substituted, and the value presented is the one the legacy provisioning member
     * provisions, folded exactly as the boundary folds it. All ten delivered digests accept it, which
     * {@link TheDeliveredIdentities#theDeliveredDigestsAcceptTheProvisionedCredential()} asserts directly
     * against the value recovered from the read-only estate. Everything here is therefore real end to
     * end: a real request crosses a real HTTP boundary on a real bound port, through the shipped filter
     * chain, into the shipped service, where the shipped encoder compares the submitted value against the
     * digest the migration itself loaded onto a real server.
     *
     * @param  userId the delivered identity to admit
     * @return the whole reply, status and headers included
     */
    private ResponseEntity<String> admitSignOn(final String userId) {
        final char[] credential = presentableCredential();
        byte[] body = null;
        try {
            body = signOnBodyBytes(userId, credential, SUBMIT_KEY);
            return postJson(SIGN_ON_PATH, body, null);
        } finally {
            Arrays.fill(credential, ' ');
            if (body != null) {
                Arrays.fill(body, (byte) ' ');
            }
        }
    }

    /**
     * Reads the provisioning member's credential window and folds it, in place, to the form the boundary
     * will compare.
     *
     * <h4>Why the fold has to happen here</h4>
     * The sign-on program upper-cases both submitted fields before it compares anything, using a
     * twenty-six character ASCII substitution table rather than a locale-aware routine. Folding here
     * makes the presented value invariant under the boundary's own transformation, so a refusal can only
     * mean that the digest refused the value and never that the two sides disagreed about case.
     *
     * <p>The provisioned window happens to carry no lower-case character today, so the fold is currently
     * a no-op over it. That is a measurement of the read-only member, not a property this specification
     * may rely on: dropping the fold would leave a member edited to carry a lower-case window failing
     * every admitted sign-on here for a reason unrelated to any contract, and the fold costs nothing.
     *
     * <p>The fold is applied over the character array rather than by calling the module's string utility,
     * because that utility takes a {@code String} and the value must not become one:
     * {@link #theFoldAppliedHereIsTheFoldTheBoundaryApplies()} proves the two agree, using a probe value
     * that is not a credential.
     *
     * @return the folded window, freshly allocated, for the caller to overwrite
     */
    private static char[] presentableCredential() {
        final char[] window = TestDataFactory.provisioningCredentialWindow();
        asciiUpperFoldInPlace(window);
        return window;
    }

    /**
     * Applies the twenty-six character ASCII upper-case substitution in place.
     *
     * <p>A character outside the table is never touched, which is the substitution semantics the legacy
     * conversion verb has and the reason a locale-aware or Unicode-aware routine cannot stand in for it.
     *
     * @param value the characters to fold, modified in place
     */
    private static void asciiUpperFoldInPlace(final char[] value) {
        for (int index = 0; index < value.length; index++) {
            final char character = value[index];
            if (character >= 'a' && character <= 'z') {
                value[index] = (char) (character - LOWER_TO_UPPER_DISTANCE);
            }
        }
    }

    /**
     * Reports whether the <em>shipped</em> verifier accepts the provisioned credential for one digest.
     *
     * <h4>Why the shipped verifier is asked as well as an independent one</h4>
     * An independent encoder answers whether the digest is a digest of the provisioned value. It does not
     * answer whether the module the migration ships would agree - a cost factor, a version marker or a
     * pre-comparison transformation the module applies and a bare encoder does not would separate the
     * two. Asking both, and requiring the same answer, is what closes that gap.
     *
     * <p>The credential window is read, wrapped, compared and overwritten inside this frame. It never
     * becomes a {@code String}, never reaches the caller and therefore cannot appear in an assertion
     * message, a diagnostic or a log line.
     *
     * @param  digest the stored digest to interrogate
     * @return {@code true} when the shipped verifier accepts the provisioned credential for that digest
     */
    private boolean shippedVerifierAccepts(final String digest) {
        final char[] credential = TestDataFactory.provisioningCredentialWindow();
        try {
            return this.digests.matches(CharBuffer.wrap(credential), digest);
        } finally {
            Arrays.fill(credential, ' ');
        }
    }

    /**
     * Encodes one sign-on request body straight out of a character array.
     *
     * <p>The credential is written into a character buffer and encoded from it, and the buffer's backing
     * array is overwritten before this method returns. It therefore never becomes a {@code String}, so
     * it cannot be interned, cannot reach a rendering of this class and cannot appear in a diagnostic.
     *
     * @param  userId     the identity to submit
     * @param  credential the credential characters, which are not modified here
     * @param  keyAction  the attention key to submit
     * @return the encoded request body
     */
    private static byte[] signOnBodyBytes(final String userId, final char[] credential,
            final String keyAction) {
        final CharBuffer json = CharBuffer.allocate(JSON_BODY_BUFFER_CHARS);
        try {
            json.put("{\"userId\":\"").put(userId).put("\",\"password\":\"").put(credential)
                    .put("\",\"keyAction\":\"").put(keyAction).put("\"}");
            json.flip();
            final ByteBuffer encoded = StandardCharsets.UTF_8.encode(json);
            final byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } finally {
            Arrays.fill(json.array(), ' ');
        }
    }

    /**
     * Submits one sign-on carrying a value that is not a credential of anything, and parses the reply.
     *
     * @param  userId    the identity to submit, exactly as given
     * @param  secret    the value to submit in the secret field, exactly as given
     * @param  keyAction the attention key to submit
     * @return the reply body parsed as JSON
     * @throws IOException if the reply cannot be parsed
     */
    private JsonNode signOnAndParse(final String userId, final String secret, final String keyAction)
            throws IOException {
        return JSON.readTree(signOnRaw(userId, secret, keyAction));
    }

    /**
     * Submits one sign-on carrying a value that is not a credential of anything, and returns the reply
     * body as raw text.
     *
     * @param  userId    the identity to submit
     * @param  secret    the value to submit in the secret field
     * @param  keyAction the attention key to submit
     * @return the reply body as text, never {@code null}
     */
    private String signOnRaw(final String userId, final String secret, final String keyAction) {
        final Map<String, String> request = new LinkedHashMap<>();
        request.put("userId", userId);
        request.put("password", secret);
        // The legacy program dispatches on the attention key before it looks at anything else, so a
        // submission naming no key takes the unmapped-key arm and never reaches a credential check.
        request.put("keyAction", keyAction);
        final String body = postJson(SIGN_ON_PATH, writeJson(request), null).getBody();
        return body == null ? "" : body;
    }

    /**
     * Reads one delivered identity off the server, failing with an actionable diagnostic if it is
     * absent.
     *
     * @param  userId the identity to read
     * @return the record the credential seed applied
     */
    private UserSecurity identity(final String userId) {
        final Optional<UserSecurity> found = this.users.findById(userId);
        assertThat(found)
                .as("the credential seed must have applied identity %s; %s expects all %d, and an "
                        + "absent one means the seed migration did not run or did not run to completion",
                        userId, "V4__seed_user_security.sql", TestDataFactory.SEEDED_USER_COUNT)
                .isPresent();
        return found.orElseThrow();
    }

    /**
     * Recovers the bearer session from a reply, asserting that it travels in the header rather than the
     * body.
     *
     * <p>The two assertions are made over booleans, because the header value <em>is</em> the session: an
     * assertion that named it would put a live credential into the diagnostic of every failure that
     * reached this helper, and this helper is reached by most of the class.
     *
     * @param  reply the whole reply
     * @return the bare token, without its presentation prefix
     */
    private static String bearerTokenOf(final ResponseEntity<String> reply) {
        final String header = reply.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        assertThat(header != null)
                .as("an admitted sign-on must issue its session in the authorization header; a "
                        + "body-carried credential would be recorded by anything that logs a response "
                        + "body")
                .isTrue();
        assertThat(header.startsWith(BEARER_PREFIX))
                .as("and it must be presented behind the '%s' scheme prefix; reported as a verdict "
                        + "because the value being inspected is the session itself", BEARER_PREFIX)
                .isTrue();
        return header.substring(BEARER_PREFIX.length());
    }

    /**
     * Renders a non-reversible short fingerprint of a value, for naming it in a diagnostic safely.
     *
     * <p>A one-way digest truncated to {@link #FINGERPRINT_CHARACTERS} characters: enough to tell two
     * sessions apart across a failure report, useless for recovering either. Rendered through an explicit
     * alphabet rather than a formatter, so no locale can influence the output.
     *
     * @param  value the value to fingerprint
     * @return the fingerprint
     */
    private static String fingerprintOf(final String value) {
        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(
                    "SHA-256 is required of every Java platform, so its absence is not a condition this "
                            + "specification can report around", unavailable);
        }
        final StringBuilder rendered = new StringBuilder(FINGERPRINT_CHARACTERS);
        for (int index = 0; rendered.length() < FINGERPRINT_CHARACTERS; index++) {
            final int unsigned = digest[index] & 0xFF;
            rendered.append(HEXADECIMAL.charAt(unsigned >>> 4)).append(HEXADECIMAL.charAt(unsigned & 0xF));
        }
        return rendered.substring(0, FINGERPRINT_CHARACTERS);
    }

    // =================================================================================================
    // REPORT-SUBMISSION HELPERS
    // =================================================================================================

    /**
     * Submits a month-to-date report request with an affirmative confirmation and drains the queue.
     *
     * @return the turn's reply and whatever the queue returned
     */
    private ReportSubmission submitMonthly() {
        return submitReport(MARKED, null, null, CONFIRM_YES, false);
    }

    /**
     * Submits a year-to-date report request with an affirmative confirmation and drains the queue.
     *
     * @return the turn's reply and whatever the queue returned
     */
    private ReportSubmission submitYearly() {
        return submitReport(null, MARKED, null, CONFIRM_YES, false);
    }

    /**
     * Submits an operator-range report request over the pinned window, with the given confirmation, and
     * drains the queue.
     *
     * @param  confirm the confirmation value to submit, exactly as given
     * @return the turn's reply and whatever the queue returned
     */
    private ReportSubmission submitCustom(final String confirm) {
        return submitReport(null, null, MARKED, confirm, true);
    }

    /**
     * Drives one report-request turn over the real boundary and then reads the queue back.
     *
     * <p>The queue is drained <em>after</em> the reply arrives, and every card assertion in this class
     * is made against what the drain returned rather than against anything the request produced. That
     * ordering is the whole of the gate's no-self-certification rule.
     *
     * @param  monthly    the month-to-date marker, or {@code null} for unmarked
     * @param  yearly     the year-to-date marker, or {@code null} for unmarked
     * @param  custom     the operator-range marker, or {@code null} for unmarked
     * @param  confirm    the confirmation value, exactly as given
     * @param  withDates  whether to supply the six date parts of the pinned window
     * @return the turn's reply and whatever the queue returned
     */
    private ReportSubmission submitReport(final String monthly, final String yearly,
            final String custom, final String confirm, final boolean withDates) {
        final Session session = establishSession(SOME_ADMIN_ID);

        // TURN ONE - THE PSEUDO-CONVERSATIONAL FIRST ENTRY, WHICH IS PART OF THE CONTRACT.
        //
        // The legacy transaction tests its re-entry gate before it reads a single screen field. On a
        // first entry it sets the gate, blanks the outbound map, positions the cursor on the first
        // report-type field and sends - and processes nothing at all. Only the following turn, arriving
        // with the gate set, reaches the report-type evaluation, the date checks, the confirmation gate
        // and the emitting loop.
        //
        // The migrated tier keeps that exactly, with the client echoing the navigation record where the
        // legacy passed a communication area. A specification that submitted once and expected a
        // published stream would therefore be asserting against a screen-paint, and would report the
        // whole batch trigger as broken when nothing was. Driving both turns is reproducing the
        // pseudo-conversation, not working around it.
        final ResponseEntity<String> firstEntry = postJson(REPORT_REQUEST_PATH,
                writeJson(reportRequest(null, null, null, null, false, session.navigationContext())),
                session.token());
        final JsonNode reEnteredContext = navigationContextOf(firstEntry);

        // TURN TWO - THE SUBMISSION, echoing the record the first turn returned.
        final ResponseEntity<String> reply = postJson(REPORT_REQUEST_PATH,
                writeJson(reportRequest(monthly, yearly, custom, confirm, withDates,
                        reEnteredContext)),
                session.token());
        final List<Message> drained = drainJobSubmissionQueue(CARD_COUNT);
        return ReportSubmission.of(reply, drained);
    }

    /**
     * Builds one report-request body.
     *
     * @param  monthly    the month-to-date marker, or {@code null} for unmarked
     * @param  yearly     the year-to-date marker, or {@code null} for unmarked
     * @param  custom     the operator-range marker, or {@code null} for unmarked
     * @param  confirm    the confirmation value, exactly as given, or {@code null} for none
     * @param  withDates  whether to supply the six date parts of the pinned window
     * @param  context    the navigation record to echo
     * @return the request map, ready to serialise
     */
    private static Map<String, Object> reportRequest(final String monthly, final String yearly,
            final String custom, final String confirm, final boolean withDates,
            final JsonNode context) {
        final Map<String, Object> request = new LinkedHashMap<>();
        request.put("monthlySelection", monthly);
        request.put("yearlySelection", yearly);
        request.put("customSelection", custom);
        if (withDates) {
            request.put("startMonth", twoDigits(PINNED_WINDOW_START_DATE.getMonthValue()));
            request.put("startDay", twoDigits(PINNED_WINDOW_START_DATE.getDayOfMonth()));
            request.put("startYear", Integer.toString(PINNED_WINDOW_START_DATE.getYear()));
            request.put("endMonth", twoDigits(PINNED_WINDOW_END_DATE.getMonthValue()));
            request.put("endDay", twoDigits(PINNED_WINDOW_END_DATE.getDayOfMonth()));
            request.put("endYear", Integer.toString(PINNED_WINDOW_END_DATE.getYear()));
        }
        request.put("confirm", confirm);
        request.put("keyAction", SUBMIT_KEY);
        request.put("navigationContext", context);
        return request;
    }

    /**
     * Recovers the navigation record one turn returned, so the next turn can echo it.
     *
     * @param  reply the whole reply
     * @return the navigation record the turn issued
     */
    private static JsonNode navigationContextOf(final ResponseEntity<String> reply) {
        assertThat(reply.getStatusCode())
                .as("a turn whose navigation record the next turn echoes must have completed; reply "
                        + "was %s %s", reply.getStatusCode(), reply.getBody())
                .isEqualTo(HttpStatus.OK);
        final JsonNode body;
        try {
            body = JSON.readTree(reply.getBody());
        } catch (final IOException parseFailure) {
            throw new IllegalStateException("a turn answered a body that is not JSON: "
                    + reply.getBody(), parseFailure);
        }
        final JsonNode context = body.get("navigationContext");
        assertThat(context)
                .as("every turn must return the navigation record the next one echoes; without it the "
                        + "following turn is another first entry and processes nothing: %s",
                        reply.getBody())
                .isNotNull();
        return context;
    }

    /**
     * Signs one delivered identity on and returns both halves of the state a following turn needs: the
     * bearer session, and the navigation record that stands in for the legacy communication area.
     *
     * @param  userId the delivered identity to admit
     * @return the session and the echoable navigation record
     */
    private Session establishSession(final String userId) {
        final ResponseEntity<String> reply = admitSignOn(userId);
        assertThat(reply.getStatusCode())
                .as("a following turn needs an admitted sign-on, so this one must have succeeded for "
                        + "user %s; reply was %s", userId, reply.getBody())
                .isEqualTo(HttpStatus.OK);
        return new Session(bearerTokenOf(reply), navigationContextOf(reply));
    }

    /**
     * Renders a two-digit fixed-width date part.
     *
     * @param  value the month or day
     * @return the value as exactly two digits
     */
    private static String twoDigits(final int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    // =================================================================================================
    // TRANSPORT HELPERS
    // =================================================================================================

    /**
     * Posts one JSON body to the running boundary, optionally presenting a session.
     *
     * <p>The body is passed as bytes rather than as an object so that a request carrying a credential
     * never has to become a string for a converter to serialise.
     *
     * @param  path  the path to reach
     * @param  body  the encoded JSON body
     * @param  token the bearer session to present, or {@code null} for none
     * @return the whole reply, status and headers included
     */
    private ResponseEntity<String> postJson(final String path, final byte[] body, final String token) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setAcceptCharset(List.of(StandardCharsets.UTF_8));
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return this.http.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers),
                String.class);
    }

    /**
     * Gets one path from the running boundary, optionally presenting a session.
     *
     * @param  path  the path to reach
     * @param  token the bearer session to present, or {@code null} for none
     * @return the whole reply
     */
    private ResponseEntity<String> get(final String path, final String token) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return this.http.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /**
     * Builds an absolute URL against the port the boundary opened.
     *
     * @param  path the path to reach
     * @return the absolute URL
     */
    private String url(final String path) {
        return "http://localhost:" + this.port + path;
    }

    /**
     * Serialises a request map to JSON bytes.
     *
     * @param  request the request to serialise
     * @return the encoded body
     */
    private static byte[] writeJson(final Map<String, ?> request) {
        try {
            return JSON.writeValueAsBytes(request);
        } catch (final IOException serialisationFailure) {
            throw new IllegalStateException("the request body could not be serialised, which is a "
                    + "fault in this specification rather than in the subject", serialisationFailure);
        }
    }

    /**
     * Returns one textual field of a reply body, or the empty string when it is absent or null.
     *
     * @param  body the parsed reply body
     * @param  name the field to read
     * @return the value, untrimmed, or the empty string
     */
    private static String textOf(final JsonNode body, final String name) {
        assertThat(body)
                .as("the boundary must answer with a body; a null body carries no contract at all")
                .isNotNull();
        final JsonNode field = body.get(name);
        return field == null || field.isNull() ? "" : field.asText();
    }

    /**
     * Returns one user message attribute of a delivered message, or the empty string when absent.
     *
     * @param  message the delivered message
     * @param  name    the attribute to read
     * @return the attribute's value, or the empty string
     */
    private static String attributeOf(final Message message, final String name) {
        final MessageAttributeValue value = message.messageAttributes().get(name);
        return value == null || value.stringValue() == null ? "" : value.stringValue();
    }

    /**
     * Reads one delivered golden artefact as the exact bytes the class path holds.
     *
     * @param  fixtureName the artefact's file name
     * @return its exact bytes
     */
    private static byte[] goldenBytes(final String fixtureName) {
        final String resource = EXPECTED_FIXTURE_DIRECTORY + fixtureName;
        try (InputStream stream = OnlineTransactionE2ETest.class.getResourceAsStream(resource)) {
            assertThat(stream)
                    .as("the delivered golden %s must be on the test class path; it is a delivered "
                            + "test resource and the record-format contract cannot be asserted without "
                            + "it", resource)
                    .isNotNull();
            return stream.readAllBytes();
        } catch (final IOException readFailure) {
            throw new IllegalStateException("the delivered golden " + resource
                    + " could not be read", readFailure);
        }
    }

    // =================================================================================================
    // FAILURE INJECTION FOR THE IGNORE-ON-ERROR CONTRACT
    //
    // The queue client is a Mockito spy over the real one, so every other test in this class publishes
    // through the genuine transport to the genuine emulator. Only the tolerance group stubs it, and the
    // framework resets the spy after each test method, which is why nothing here has to undo it.
    // =================================================================================================

    /**
     * Makes every publish through the injected queue boundary fail, exactly as an unreachable
     * transient-data queue failed.
     *
     * <p>Stubbed on the spy with a do-first form rather than a when-then form, because a when-then form
     * would invoke the real publish while arranging the stub and would put a genuine message on the
     * queue that this specification then could not explain.
     */
    private void refuseEveryPublish() {
        doThrow(SqsException.builder()
                        .message("the queue refused the write, for the ignore-on-error contract")
                        .build())
                .when(this.queueBoundary).<String>send(any());
    }

    /**
     * Returns the spied queue boundary to real publishing within one test.
     *
     * <p>Needed by the one assertion that a tolerated failure leaves no residue: it refuses, then
     * recovers, inside a single test method, so it cannot rely on the framework's own reset between
     * methods.
     */
    private void allowEveryPublish() {
        reset(this.queueBoundary);
    }

    // =================================================================================================
    // VALUE TYPES
    // =================================================================================================

    /**
     * One identity the credential seed delivered, as this class expects it.
     *
     * @param userId     the eight-character identifier
     * @param givenName  the given name, as the logical trimmed value
     * @param familyName the family name, as the logical trimmed value
     * @param type       the one-character type code that decides the destination
     */
    private record DeliveredIdentity(String userId, String givenName, String familyName,
            String type) {
    }

    /**
     * The two halves of the state one admitted sign-on hands to the next turn.
     *
     * @param token             the bearer session, taken from the authorization header
     * @param navigationContext the navigation record the client echoes in place of the legacy
     *                          communication area
     */
    private record Session(String token, JsonNode navigationContext) {

        /**
         * Renders the session without its token, so a diagnostic that names the record cannot leak it.
         *
         * <p>The generated rendering of a record names every component, and one of these components is a
         * live session credential. Overriding it means a future assertion written as {@code .as("%s",
         * session)} is safe by construction rather than by the author having remembered.
         *
         * @return a rendering carrying a non-reversible fingerprint in place of the token
         */
        @Override
        public String toString() {
            return "Session[token=<redacted:" + fingerprintOf(this.token) + "> navigationContext="
                    + this.navigationContext + "]";
        }
    }

    /**
     * One fixed-width record format of the estate.
     *
     * @param description  what the format is, for a diagnostic
     * @param fixtureName  the delivered golden's file name
     * @param width        the contractual record width in bytes
     */
    private record RecordFormat(String description, String fixtureName, int width) {
    }

    /**
     * The outcome of one report-request turn: what the boundary answered, and what the queue held
     * afterwards.
     *
     * @param status      the reply status
     * @param body        the reply body as text, never {@code null}
     * @param acknowledged whether the turn reported an accepted submission
     * @param message     the operator-visible message, untrimmed, or the empty string
     * @param messages    the messages the queue returned, in delivery order
     */
    private record ReportSubmission(HttpStatus status, String body, boolean acknowledged,
            String message, List<Message> messages) {

        /**
         * Assembles one outcome from a reply and a drain.
         *
         * @param  reply   the whole reply
         * @param  drained the messages the queue returned
         * @return the outcome
         */
        static ReportSubmission of(final ResponseEntity<String> reply, final List<Message> drained) {
            final String body = reply.getBody() == null ? "" : reply.getBody();
            boolean acknowledged = false;
            String message = "";
            if (!body.isEmpty()) {
                try {
                    final JsonNode parsed = JSON.readTree(body);
                    final JsonNode accepted = parsed.get("submissionAccepted");
                    acknowledged = accepted != null && accepted.asBoolean();
                    message = textOf(parsed, "message");
                } catch (final IOException parseFailure) {
                    throw new IllegalStateException("the report-request boundary answered a body that "
                            + "is not JSON, which no client of this contract could read: " + body,
                            parseFailure);
                }
            }
            return new ReportSubmission(HttpStatus.valueOf(reply.getStatusCode().value()), body,
                    acknowledged, message, List.copyOf(drained));
        }

        /**
         * Returns the delivered card bodies, in delivery order.
         *
         * @return the bodies the queue returned
         */
        List<String> cardBodies() {
            return this.messages.stream().map(Message::body).toList();
        }

        /**
         * Renders the turn for a diagnostic: the status, the message and how many cards the queue held.
         *
         * <p>The body is included in full because a turn that failed for an unexpected reason is only
         * actionable if the reply that explains it is in the failure text. It carries no credential: the
         * request type declares the secret write-only and the reply type has no member for one.
         *
         * @return the turn, as a single line
         */
        @Override
        public String toString() {
            return "turn[status=" + this.status + " accepted=" + this.acknowledged
                    + " message=\"" + this.message + "\" cardsDrained=" + this.messages.size()
                    + " body=" + this.body + "]";
        }
    }

    /**
     * The online surface under test: the sign-on and report boundaries, the shipped filter chain, the
     * shipped services, the repository the delivered identities live in and the real queue client.
     *
     * <p>Assembled explicitly rather than by scanning, so the graph is exactly the surface and a reader
     * can see in one place what took part - and so that a component scan cannot sweep this test tree
     * into the context. <strong>Nothing is stubbed:</strong> the credential encoder, the token provider,
     * the message catalogue, the date validator, the submission coordinator and the filter chain are the
     * shipped ones; the identities come off a real server; and the queue client is the real one, bound
     * by the base class to the emulator this process started.
     *
     * <p>The clock is the shared pinned instant, which is what makes the derived reporting window a
     * value this specification can name.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({AuthController.class, ReportController.class, ModuleErrorController.class,
            SignOnContractAdapter.class, ReportContractAdapter.class, ConversationStateAdapter.class,
            ScreenStateAdapter.class, GlobalExceptionHandler.class, JsonRefusalBodyRenderer.class,
            AuthenticationService.class, SignOnAttemptGovernor.class,
            CredentialDigestService.class, SignOnStateService.class,
            MessageCatalogService.class, NavigationService.class, DateValidationService.class,
            ReportRequestService.class, JobSubmissionService.class,
            PostgresJobSubmissionCoordinator.class,
            SecurityConfig.class, JwtTokenProvider.class, WebMvcConfig.class, AwsConfig.class})
    @EnableConfigurationProperties({JwtProperties.class, AwsProperties.class})
    @EnableJpaRepositories(basePackageClasses = UserSecurityRepository.class)
    @EntityScan(basePackageClasses = UserSecurity.class)
    static class OnlineContext {

        /** Creates the configuration. */
        OnlineContext() {
            // Intentionally empty: this slice contributes beans, not state.
        }

        /**
         * The pinned clock every collaborator in the graph reads.
         *
         * @return the shared fixed clock
         */
        @Bean
        Clock fixedClock() {
            return FIXED_CLOCK;
        }
    }
}
