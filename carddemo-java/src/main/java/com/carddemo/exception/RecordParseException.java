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
package com.carddemo.exception;

/**
 * Signals that one record of a fixed-width sequential dataset could not be mapped, describing the
 * failure without reproducing the record.
 *
 * <p><strong>Why this type exists, and it is a confidentiality control rather than a convenience.</strong>
 * The batch tier reads eleven fixed-width layouts through Spring Batch's flat-file reader. When a
 * mapper rejects an image, that reader wraps the rejection in a framework parse exception whose
 * message <em>and</em> payload carry the offending line verbatim. Everything downstream then
 * publishes it: the framework logs the failed step with the throwable attached, so the record reaches
 * whatever collects the process output, and the job repository stores the rendered stack trace in the
 * step and job execution's exit message, so the record is <em>persisted</em> in the database for the
 * lifetime of that execution row. Three of the eleven layouts make that unacceptable rather than
 * merely untidy: the card, transaction and daily-transaction images carry a full sixteen-digit
 * primary account number, the card image additionally carries its verification code, the customer
 * image carries a national identifier, and the user-security image carries a sign-on credential.
 *
 * <p>This type is what the module raises instead. It carries the four things an operator needs to act
 * - which layout was being read, which resource it came from, which line failed, and what kind of
 * failure it was - and it carries <strong>nothing that came out of the record</strong>. The
 * distinction is deliberate: the record itself remains available in the source dataset, which is
 * where that data legitimately lives and where it is already protected; what must not happen is a
 * copy of it appearing in a log collector or a database column that neither is.
 *
 * <p><strong>The cause chain is dropped on purpose, and that is the whole point.</strong> A rendered
 * throwable includes every cause, so retaining the framework's parse exception as this exception's
 * cause would republish the record through the very channels this type exists to protect - the fix
 * would be inert. The information that matters from the chain is which types were involved, and that
 * is preserved as text through the module's bounded failure-chain helper and exposed by
 * {@link #failureChain()}. A reader of this exception therefore sees, for example, that an
 * {@code IllegalArgumentException} from a mapper was the origin, without seeing what the mapper was
 * looking at. Because the chain is text rather than a linked throwable, no future logging
 * configuration, serialisation format or exit-message renderer can re-expose the payload by walking
 * it.
 *
 * <p><strong>Terminal, like every other batch input failure in the estate.</strong> Every legacy
 * batch program treats a failed read as terminal: it writes its diagnostic, writes the raw status and
 * abends. A malformed fixed-width record has no legacy antecedent at all - a mainframe
 * fixed-blocked dataset cannot hold a record of the wrong width, because the access method enforces
 * the record length - so this type reproduces no legacy behaviour. It exists for the Java-only case
 * where a staged object has been produced or transported incorrectly, and it is unchecked so that it
 * ends the step exactly as the framework's own parse exception did, preserving that verdict while
 * removing the disclosure.
 *
 * <p><strong>Framework-free, like the rest of this package.</strong> The type extends
 * {@link RuntimeException} directly and names no framework type in its signature or its state, so the
 * exception layer keeps importing nothing beyond the platform. The layout is identified by the
 * reader's own stable name rather than by a record width, because record widths belong to the eleven
 * mappers and are deliberately absent from the reading layer.
 *
 * @see com.carddemo.util.FailureDiagnostics#failureChainOf(Throwable)
 */
public class RecordParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * The sentence that states, in the message itself, that the omission of the record is intentional.
     *
     * <p>Present so that an operator reading a bounded diagnostic does not conclude the diagnostic is
     * truncated or the tooling defective, and so that a future reader of this code finds the reason
     * beside the effect rather than only in this class's documentation.
     */
    public static final String REDACTION_NOTICE = "the record image is deliberately not reproduced, "
            + "because a fixed-width image of this estate can carry a card primary account number, a "
            + "card verification code, a national identifier or a sign-on credential";

    /** Value substituted for any absent descriptive argument, so a message is never half-formed. */
    public static final String UNKNOWN = "unknown";

    /** Line number reported when the framework offered none. */
    public static final int UNKNOWN_LINE = -1;

    private final String layout;

    private final String resourceDescription;

    private final int lineNumber;

    private final String failureChain;

    /**
     * Creates the exception from the four payload-free facts about the failure.
     *
     * @param layout              stable name of the reader whose layout rejected the record, which is
     *                            what identifies the layout to an operator; may be {@code null}
     * @param resourceDescription description of the resource being read, as the resource itself
     *                            reports it; may be {@code null}
     * @param lineNumber          one-based line number of the record that failed, or
     *                            {@link #UNKNOWN_LINE} when the framework offered none
     * @param failureChain        bounded chain of failure type names, produced by the module's
     *                            failure-chain helper; may be {@code null}
     */
    public RecordParseException(final String layout, final String resourceDescription,
            final int lineNumber, final String failureChain) {
        super(composeMessage(layout, resourceDescription, lineNumber, failureChain));
        this.layout = orUnknown(layout);
        this.resourceDescription = orUnknown(resourceDescription);
        this.lineNumber = lineNumber;
        this.failureChain = orUnknown(failureChain);
    }

    /**
     * @return the stable reader name identifying the rejected layout, never {@code null}
     */
    public String layout() {
        return this.layout;
    }

    /**
     * @return the description of the resource being read, never {@code null}
     */
    public String resourceDescription() {
        return this.resourceDescription;
    }

    /**
     * @return the one-based line number that failed, or {@link #UNKNOWN_LINE}
     */
    public int lineNumber() {
        return this.lineNumber;
    }

    /**
     * @return the bounded chain of failure type names, never {@code null} and never record content
     */
    public String failureChain() {
        return this.failureChain;
    }

    /**
     * Composes the operator-facing message from the four facts and the redaction notice.
     *
     * <p>Static because it runs before the instance exists, and self-contained because a message
     * assembled from anything outside its own arguments could reintroduce content this type exists to
     * withhold.
     *
     * @param  layout              stable reader name, or {@code null}
     * @param  resourceDescription resource description, or {@code null}
     * @param  lineNumber          one-based line number, or {@link #UNKNOWN_LINE}
     * @param  failureChain        bounded failure-type chain, or {@code null}
     * @return the complete message, never {@code null}
     */
    private static String composeMessage(final String layout, final String resourceDescription,
            final int lineNumber, final String failureChain) {
        return "a fixed-width record could not be mapped: layout=" + orUnknown(layout)
                + " resource=[" + orUnknown(resourceDescription) + "]"
                + " line=" + (lineNumber == UNKNOWN_LINE ? UNKNOWN : String.valueOf(lineNumber))
                + " failureChain=" + orUnknown(failureChain)
                + "; " + REDACTION_NOTICE;
    }

    /**
     * @param  value the supplied descriptive value
     * @return the value when it carries text, {@link #UNKNOWN} otherwise
     */
    private static String orUnknown(final String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
