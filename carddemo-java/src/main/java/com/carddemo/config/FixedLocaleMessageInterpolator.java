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
package com.carddemo.config;

import jakarta.validation.MessageInterpolator;
import java.util.Locale;
import java.util.Objects;
import org.hibernate.validator.messageinterpolation.ResourceBundleMessageInterpolator;

/**
 * Renders every Bean Validation constraint message in one fixed locale, so that a field error is the
 * same bytes on every host and for every caller.
 *
 * <h2>Why a constraint message is a byte contract here</h2>
 *
 * <p>A constraint failure reaches a client through {@code GlobalExceptionHandler}, which places
 * {@link jakarta.validation.ConstraintViolation#getMessage()} into
 * {@code ErrorResponse.FieldError.message()}. That text is not one of this module's own literals - it
 * is the validation provider's bundled message, and the provider resolves it against a locale. Left to
 * the framework's default, that locale is whatever {@code LocaleContextHolder} yields: for a servlet
 * request the caller's {@code Accept-Language} header, and otherwise the JVM default. The same
 * artifact given the same input would then emit {@code "size must be between 0 and 8"} on one host and
 * a translation of it on another, or on the same host to a different caller.</p>
 *
 * <p>For a migration whose external contract is stated in bytes that is a defect rather than a
 * courtesy. Nothing in the estate this module reproduces is multilingual, and no requirement asks for
 * negotiated message text.</p>
 *
 * <h2>Why {@link Locale#ROOT}</h2>
 *
 * <p>{@code ROOT} selects the provider's base bundle rather than any translation of it, so no
 * {@code _xx} bundle can be chosen by a request header or by a host's default locale. Both
 * interpolation overloads discard the locale they are handed: the framework wraps any supplied
 * interpolator in one that passes {@code LocaleContextHolder.getLocale()}, and honouring that argument
 * is precisely the behaviour being removed.</p>
 *
 * <h2>What this does not touch</h2>
 *
 * <p>Only how a message is <em>rendered</em>. Which constraints exist, which fields carry them, and
 * the two-state MISSING versus INVALID decision are untouched - that decision is taken from the
 * message <em>template</em>, which is read before interpolation and so never depended on a locale.</p>
 *
 * <h2>One statement, used by the application and by its tests</h2>
 *
 * <p>{@code WebMvcConfig} installs this on the validator the application validates with, and the
 * tests that assert rendered message text build their own provider with this same class rather than
 * restating the rule. A test that pinned the locale its own way would pass while the application
 * stayed non-deterministic, which is the disagreement this shared class prevents.</p>
 *
 * <p>Decision {@code DL-118} records this rule and the two measurements behind it.
 *
 * <p>Provenance: the field-level error surface this governs derives from the {@code CSSETATY}
 * validation-flag macro expanded 39 times in {@code app/cbl/COACTUPC.cbl}, at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy screen carried one language, so
 * its migrated contract carries one language.</p>
 */
public final class FixedLocaleMessageInterpolator implements MessageInterpolator {

    /**
     * The one locale every constraint message is rendered in.
     *
     * <p>Published so that the application, the tests and any assertion naming the locale refer to a
     * single constant rather than three agreeing literals.</p>
     */
    public static final Locale MESSAGE_LOCALE = Locale.ROOT;

    /** The provider's own interpolator, which owns bundle lookup and parameter substitution. */
    private final MessageInterpolator delegate;

    /** The locale every message is rendered in. */
    private final Locale locale;

    /**
     * Creates an interpolator pinned to {@link #MESSAGE_LOCALE}.
     */
    public FixedLocaleMessageInterpolator() {
        this(MESSAGE_LOCALE);
    }

    /**
     * Creates an interpolator pinned to the given locale.
     *
     * @param locale the locale every message is rendered in, never {@code null}
     */
    public FixedLocaleMessageInterpolator(final Locale locale) {
        this.locale = Objects.requireNonNull(locale, "locale must not be null");
        this.delegate = new ResourceBundleMessageInterpolator();
    }

    /**
     * Renders a message template in the pinned locale.
     *
     * @param messageTemplate the template to render
     * @param context         the constraint context supplying the substitution values
     * @return the rendered message, in the pinned locale
     */
    @Override
    public String interpolate(final String messageTemplate, final Context context) {
        return this.delegate.interpolate(messageTemplate, context, this.locale);
    }

    /**
     * Renders a message template in the pinned locale, discarding the requested locale.
     *
     * @param messageTemplate the template to render
     * @param context         the constraint context supplying the substitution values
     * @param requestedLocale the locale the caller asked for, deliberately ignored
     * @return the rendered message, in the pinned locale
     */
    @Override
    public String interpolate(final String messageTemplate, final Context context,
            final Locale requestedLocale) {
        // The requested locale carries the caller's Accept-Language or the host's default, and
        // honouring it is exactly the non-determinism this class exists to remove.
        return this.delegate.interpolate(messageTemplate, context, this.locale);
    }
}
