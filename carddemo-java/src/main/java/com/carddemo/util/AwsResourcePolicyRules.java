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
package com.carddemo.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Judges one attached resource policy document against the two properties this module requires of every
 * policy it is willing to run against: that it opens the resource to nobody, and that it refuses the
 * resource to everybody over an unencrypted transport.
 *
 * <h2>Why a resource policy is judged at all</h2>
 *
 * <p>The three cloud resources this module writes to - a staging bucket carrying statements, reports and
 * rejected records, a queue carrying eighty-column job-control cards, and a topic carrying completion
 * notices - are addressed by name and by owning account, and both of those are already established
 * elsewhere. Neither says anything about who <em>else</em> may reach them. A bucket in the right account
 * with a policy granting {@code s3:GetObject} to every principal is the correct bucket, owned by the
 * correct account, publishing card numbers and account balances to the internet; a queue whose policy
 * grants {@code sqs:SendMessage} to every principal is the correct queue accepting job-control cards
 * from anyone who can name it. Ownership and openness are independent properties, and this class is
 * where the second one is decided.
 *
 * <h2>The two rules, stated exactly</h2>
 *
 * <ol>
 *   <li><strong>No open grant.</strong> No statement may take effect {@code Allow} for every principal
 *       with no condition attached. A wildcard principal is recognised in each of its written forms -
 *       the bare {@code "*"}, a qualified map whose value is {@code "*"}, and an array containing
 *       {@code "*"} - and an {@code Allow} carrying {@code NotPrincipal} is treated as open too,
 *       because allowing everyone <em>except</em> a named few is a grant to everyone else. A
 *       <em>conditioned</em> {@code Allow} to a wildcard principal is not an open grant: that is the
 *       shape the notification service's own default topic policy takes, where the condition confines
 *       it to the owning account, and refusing it would refuse every correctly-provisioned topic.
 *   <li><strong>Insecure transport refused.</strong> Some statement must take effect {@code Deny}, for
 *       every principal, over every action of the resource's own service, when
 *       {@value #SECURE_TRANSPORT_CONDITION_KEY} is {@code false}. Both the {@code Bool} and the
 *       {@code BoolIfExists} operators satisfy it, the second being the stricter of the two because it
 *       also denies a call that does not report the key at all.
 * </ol>
 *
 * <p>A document that satisfies both is {@link PolicyPosture#SOUND}. Anything else is named by the
 * property it fails, so a caller can refuse with a reason rather than with a verdict.
 *
 * <h2>Why the verdict is an enumeration rather than an exception</h2>
 *
 * <p>Four of the five outcomes are conditions a deployer has to act on, and each calls for different
 * wording: a policy that is absent is a provisioning omission, one that cannot be read is a malformed
 * document, one that grants openly is an exposure, and one that permits plain transport is a weaker
 * posture than this module accepts. Returning the finding lets the caller compose the refusal that fits
 * the resource it was reading - a bucket, a queue and a topic each carry different consequences - and it
 * keeps this class free of any wording about resources it knows nothing about.
 *
 * <h2>Nothing from the document is ever returned, logged or thrown</h2>
 *
 * <p>The only value leaving this class is one of five enumeration constants. No statement identifier, no
 * principal, no action, no resource identifier and no fragment of the document itself is returned or
 * carried in any message, because the document names accounts, roles and resources, and a caller that
 * refuses a start-up publishes whatever it is handed. This class declares no logger for the same reason.
 *
 * <h2>Why it reads with a mapper of its own</h2>
 *
 * <p>The mapper the boundary uses is configured for the boundary: strict scalar coercion, closed request
 * bodies and refused control characters, all of which are properties of an inbound HTTP body rather than
 * of a policy document. A security rule whose verdict could be altered by a later customisation of an
 * unrelated boundary would be a security rule nobody could reason about, so the reader here is private,
 * fixed at its defaults, and shared by nothing.
 *
 * <h2>Fail-closed by construction</h2>
 *
 * <p>Every ambiguity resolves against the document. A document that is too long to be a policy the
 * services accept, that is not an object, that carries no statement array, or that carries a statement
 * which is not an object, is {@link PolicyPosture#UNREADABLE} rather than sound; a {@code Deny} whose
 * shape this class cannot fully recognise does not count towards rule two. The failure mode is a refused
 * deployment, which is visible, rather than an accepted one, which is not.
 *
 * <h2>Provenance</h2>
 *
 * <p>This class has no legacy antecedent. The migrated estate's files were VSAM clusters reached through
 * a transaction manager and a batch scheduler, with no attachable policy of any kind - the CICS
 * definitions carried {@code READINTEG(UNCOMMITTED)}, {@code RECOVERY(NONE)} and {@code JOURNAL(NO)} and
 * nothing resembling a principal. What it preserves is the property the mainframe had by construction: a
 * dataset was reachable only from within the estate that owned it. Legacy estate read at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19; no legacy source text is reproduced here.
 *
 * @since 1.0.0
 */
public final class AwsResourcePolicyRules {

    /**
     * The condition key whose {@code false} value names a call arriving over an unencrypted transport.
     *
     * <p>Published because the refusal a caller composes names it, and a caller that spelled it
     * differently from the rule that reads it would report a property nobody could act on.</p>
     */
    public static final String SECURE_TRANSPORT_CONDITION_KEY = "aws:SecureTransport";

    /**
     * The longest document this class will parse, in characters.
     *
     * <p>Twenty kibibytes is the ceiling the object store itself places on a bucket policy, so a longer
     * document is one no service this module addresses would have accepted. It bounds the work a single
     * malformed or hostile document can cause and expresses no service level.</p>
     */
    public static final int MAX_POLICY_DOCUMENT_LENGTH = 20_480;

    /** The written form of "every principal" and, in an action, of "every action". */
    private static final String EVERY = "*";

    /** Member carrying the statement list. */
    private static final String STATEMENT_MEMBER = "Statement";

    /** Member carrying a statement's effect. */
    private static final String EFFECT_MEMBER = "Effect";

    /** Member carrying the principals a statement applies to. */
    private static final String PRINCIPAL_MEMBER = "Principal";

    /** Member carrying the principals a statement excludes, which is a grant to every other. */
    private static final String NOT_PRINCIPAL_MEMBER = "NotPrincipal";

    /** Member carrying the actions a statement applies to. */
    private static final String ACTION_MEMBER = "Action";

    /** Member carrying a statement's conditions. */
    private static final String CONDITION_MEMBER = "Condition";

    /** Condition operator comparing a boolean key. */
    private static final String BOOLEAN_OPERATOR = "Bool";

    /** Condition operator comparing a boolean key that also fires when the key is absent. */
    private static final String BOOLEAN_IF_EXISTS_OPERATOR = "BoolIfExists";

    /** Effect that grants. */
    private static final String ALLOW_EFFECT = "Allow";

    /** Effect that refuses. */
    private static final String DENY_EFFECT = "Deny";

    /** The condition value that names an unencrypted transport. */
    private static final String FALSE_TEXT = "false";

    /**
     * The reader, private to this class and left at its defaults.
     *
     * <p>Configured by nothing and shared with nothing, so the verdicts below cannot be changed by a
     * customisation made for another purpose. Thread-safe once built, and built once.</p>
     */
    private static final ObjectMapper POLICY_READER = JsonMapper.builder().build();

    /**
     * What one attached resource policy was found to be.
     *
     * <p>Ordered by the stage that establishes each, which is also the order a caller's refusals read
     * in: a document has to be present before it can be read, readable before its grants can be
     * examined, and free of open grants before the transport rule is worth applying.</p>
     */
    public enum PolicyPosture {

        /** No document is attached to the resource at all. */
        ABSENT,

        /**
         * A document is attached and this class will not draw a conclusion from it: it is longer than
         * any service would have accepted, is not an object, carries no statement, or carries a
         * statement that is not an object.
         */
        UNREADABLE,

        /** Some statement grants every principal, with no condition confining it. */
        PUBLICLY_GRANTED,

        /** No statement denies every principal every action of the service over plain transport. */
        INSECURE_TRANSPORT_PERMITTED,

        /** Both rules hold. */
        SOUND
    }

    /** Not instantiable: a rule with no state has no instance worth holding. */
    private AwsResourcePolicyRules() {
        throw new AssertionError("AwsResourcePolicyRules is a rule set and is never instantiated");
    }

    /**
     * Judges one attached resource policy.
     *
     * @param  document         the policy document as the service reported it, or {@code null} when the
     *                          service reported none
     * @param  serviceNamespace the service's own action namespace - {@code s3}, {@code sqs} or
     *                          {@code sns} - whose wildcard the transport denial must cover
     * @return what the document was found to be; never {@code null}
     * @throws NullPointerException     if {@code serviceNamespace} is {@code null}
     * @throws IllegalArgumentException if {@code serviceNamespace} is blank or holds a character outside
     *                                  the lower-case letters and digits a service namespace is made of
     */
    public static PolicyPosture postureOf(final String document, final String serviceNamespace) {
        final String namespace = requiredNamespace(serviceNamespace);
        if (document == null || document.isBlank()) {
            return PolicyPosture.ABSENT;
        }
        if (document.length() > MAX_POLICY_DOCUMENT_LENGTH) {
            return PolicyPosture.UNREADABLE;
        }
        final JsonNode root;
        try {
            root = POLICY_READER.readTree(document);
        } catch (final JsonProcessingException unreadable) {
            // The failure itself is deliberately not read: its message quotes the document, and the
            // document names accounts, roles and resources. The verdict is the whole of what escapes.
            return PolicyPosture.UNREADABLE;
        }
        if (root == null || !root.isObject()) {
            return PolicyPosture.UNREADABLE;
        }
        final List<JsonNode> statements = statementsOf(root);
        if (statements.isEmpty()) {
            return PolicyPosture.UNREADABLE;
        }
        // Rule one is applied across every statement before rule two is considered, because an open
        // grant is the graver finding and a document can carry both.
        for (final JsonNode statement : statements) {
            if (grantsEveryPrincipalUnconditionally(statement)) {
                return PolicyPosture.PUBLICLY_GRANTED;
            }
        }
        for (final JsonNode statement : statements) {
            if (deniesEveryActionOverInsecureTransport(statement, namespace)) {
                return PolicyPosture.SOUND;
            }
        }
        return PolicyPosture.INSECURE_TRANSPORT_PERMITTED;
    }

    /**
     * Collects the statements of a policy, or nothing at all when any of them is not an object.
     *
     * <p>A single statement written as an object rather than as a one-element array is accepted, because
     * the services accept it; a list containing anything that is not a statement is rejected whole,
     * because a document this class cannot read completely is one it must not pass.</p>
     *
     * @param  root the policy document
     * @return the statements, or an empty list when the document carries none it can read
     */
    private static List<JsonNode> statementsOf(final JsonNode root) {
        final JsonNode declared = member(root, STATEMENT_MEMBER);
        final List<JsonNode> statements = new ArrayList<>();
        if (declared.isObject()) {
            statements.add(declared);
            return statements;
        }
        if (!declared.isArray()) {
            return List.of();
        }
        for (final JsonNode element : declared) {
            if (!element.isObject()) {
                return List.of();
            }
            statements.add(element);
        }
        return statements;
    }

    /**
     * Decides whether one statement is an open grant.
     *
     * @param  statement the statement
     * @return {@code true} when it allows every principal with no condition confining it
     */
    private static boolean grantsEveryPrincipalUnconditionally(final JsonNode statement) {
        if (!ALLOW_EFFECT.equalsIgnoreCase(textOf(member(statement, EFFECT_MEMBER)))) {
            return false;
        }
        if (isConditioned(statement)) {
            return false;
        }
        if (!member(statement, NOT_PRINCIPAL_MEMBER).isMissingNode()) {
            // Allowing every principal except a named few is a grant to every other principal, and it
            // is the form an exposure most often takes, because it reads as a restriction.
            return true;
        }
        return namesEveryPrincipal(member(statement, PRINCIPAL_MEMBER));
    }

    /**
     * Decides whether one statement is the transport denial rule two requires.
     *
     * @param  statement the statement
     * @param  namespace the service's action namespace
     * @return {@code true} when it denies every principal every action of the service over plain
     *         transport
     */
    private static boolean deniesEveryActionOverInsecureTransport(final JsonNode statement,
            final String namespace) {
        if (!DENY_EFFECT.equalsIgnoreCase(textOf(member(statement, EFFECT_MEMBER)))) {
            return false;
        }
        if (!namesEveryPrincipal(member(statement, PRINCIPAL_MEMBER))) {
            return false;
        }
        if (!coversEveryAction(member(statement, ACTION_MEMBER), namespace)) {
            return false;
        }
        final JsonNode condition = member(statement, CONDITION_MEMBER);
        return refusesPlainTransport(member(condition, BOOLEAN_OPERATOR))
                || refusesPlainTransport(member(condition, BOOLEAN_IF_EXISTS_OPERATOR));
    }

    /**
     * Decides whether one condition operator compares the transport key against {@code false}.
     *
     * @param  operator the operator's own map of keys to values
     * @return {@code true} when it names the transport key with the value that means plain transport
     */
    private static boolean refusesPlainTransport(final JsonNode operator) {
        return names(member(operator, SECURE_TRANSPORT_CONDITION_KEY), FALSE_TEXT);
    }

    /**
     * Decides whether a statement carries at least one condition.
     *
     * @param  statement the statement
     * @return {@code true} when a condition object with at least one operator is present
     */
    private static boolean isConditioned(final JsonNode statement) {
        final JsonNode condition = member(statement, CONDITION_MEMBER);
        return condition.isObject() && !condition.isEmpty();
    }

    /**
     * Decides whether a principal element names every principal.
     *
     * <p>Recognises the bare wildcard, an array containing it, and a qualified map - {@code AWS},
     * {@code CanonicalUser} or any other qualifier - whose value is it.</p>
     *
     * @param  principal the principal element
     * @return {@code true} when every principal is named
     */
    private static boolean namesEveryPrincipal(final JsonNode principal) {
        if (names(principal, EVERY)) {
            return true;
        }
        if (principal.isObject()) {
            for (final Map.Entry<String, JsonNode> qualified : principal.properties()) {
                if (names(qualified.getValue(), EVERY)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Decides whether an action element covers every action of one service.
     *
     * @param  action    the action element
     * @param  namespace the service's action namespace
     * @return {@code true} when the element names every action, or every action of that service
     */
    private static boolean coversEveryAction(final JsonNode action, final String namespace) {
        return names(action, EVERY) || names(action, namespace + ":" + EVERY);
    }

    /**
     * Decides whether an element is, or contains, one exact value, compared without regard to case.
     *
     * <p>Policy element values are compared case-insensitively by the services themselves, and every
     * value this class compares - an effect, a wildcard, a service-qualified wildcard and the word
     * {@code false} - is a keyword rather than a name, so no comparison here can be loosened by it.</p>
     *
     * @param  element   a textual element, an array of them, or anything else
     * @param  candidate the value looked for
     * @return {@code true} when the element is that value or an array containing it
     */
    private static boolean names(final JsonNode element, final String candidate) {
        if (element.isTextual()) {
            return candidate.equalsIgnoreCase(element.asText().strip());
        }
        if (element.isArray()) {
            for (final JsonNode member : element) {
                if (member.isTextual() && candidate.equalsIgnoreCase(member.asText().strip())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Reads one member of an object without regard to the case of its name.
     *
     * <p>Policy element names are case-insensitive at the services, so a document written with
     * {@code effect} rather than {@code Effect} takes effect there and must be read the same way here.
     * Anything absent, and every member of anything that is not an object, reads as missing rather than
     * as {@code null}, so no caller has to test for one.</p>
     *
     * @param  object the object to read
     * @param  name   the member's name
     * @return the member, or a missing node
     */
    private static JsonNode member(final JsonNode object, final String name) {
        if (object == null || !object.isObject()) {
            return MissingNode.getInstance();
        }
        for (final Map.Entry<String, JsonNode> property : object.properties()) {
            if (property.getKey().equalsIgnoreCase(name)) {
                return property.getValue();
            }
        }
        return MissingNode.getInstance();
    }

    /**
     * Renders a textual element as a stripped string, and anything else as nothing.
     *
     * @param  element the element
     * @return its stripped text, or an empty string when it is not textual
     */
    private static String textOf(final JsonNode element) {
        return element.isTextual() ? element.asText().strip() : "";
    }

    /**
     * Requires the caller's service namespace to be one.
     *
     * @param  serviceNamespace the declared namespace
     * @return the namespace, stripped and folded to lower case
     * @throws NullPointerException     if it is {@code null}
     * @throws IllegalArgumentException if it is blank or holds any other character
     */
    private static String requiredNamespace(final String serviceNamespace) {
        final String namespace =
                Objects.requireNonNull(serviceNamespace, "serviceNamespace must not be null")
                        .strip()
                        .toLowerCase(Locale.ROOT);
        if (namespace.isEmpty()) {
            throw new IllegalArgumentException("serviceNamespace must not be blank: the transport"
                    + " denial is required to cover every action of one named service, and a blank"
                    + " namespace would accept a denial covering nothing");
        }
        for (int index = 0; index < namespace.length(); index++) {
            final char character = namespace.charAt(index);
            final boolean permitted = (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9');
            if (!permitted) {
                throw new IllegalArgumentException("serviceNamespace must hold only lower-case letters"
                        + " and digits, because it is composed into the service-qualified wildcard the"
                        + " transport denial is matched against");
            }
        }
        return namespace;
    }
}
