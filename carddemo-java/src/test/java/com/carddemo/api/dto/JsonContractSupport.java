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
package com.carddemo.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The single place in this package where the module's declared serialisation settings are written
 * out by hand.
 *
 * <h2>Why one place and not one per suite</h2>
 *
 * <p>Every fast, context-free suite in this package needs a mapper in order to observe a wire shape,
 * and each such mapper is a restatement of what {@code src/main/resources/application.yml} declares
 * under {@code spring.jackson}. Restating that in each suite would put the same four settings in
 * more than twenty files, and a mapper copied twenty times is a claim about the deployed contract
 * repeated twenty times without being proved once.
 *
 * <p>Stating it once instead makes the claim checkable. {@link ApplicationJsonContractTest} obtains
 * the mapper from a real Spring context in which the module's own configuration file has been read
 * by the same configuration-data machinery a running instance uses, and compares the deployed
 * object's behaviour against {@link #declaredSettingsMapper()}. Because every suite in this package
 * routes through this one factory, an edit to the module's configuration file - or a framework
 * upgrade that moves a default - fails that comparison, rather than leaving twenty suites passing
 * against settings that are no longer in force.
 *
 * <h2>What a mapper from here does and does not evidence</h2>
 *
 * <p>It evidences the shape a type takes under the settings written below, which is exactly what a
 * per-type suite needs in order to pin property names, omissions, enumeration rendering and
 * round-trip fidelity in isolation and in milliseconds. It is not evidence about the mapper a
 * deployed instance holds; that is {@link ApplicationJsonContractTest}'s subject, and no suite in
 * this package claims otherwise.
 *
 * <h2>The four settings, and why each is written the way it is</h2>
 *
 * <ul>
 *   <li>Absent members are omitted rather than written as null. A screen field the legacy map did
 *       not carry has to be absent rather than present-and-empty, because the two are different
 *       states on every one of the seventeen migrated screens. The value-and-content form of the
 *       setter is used because the single-argument form is deprecated in the pinned databind
 *       release and this module compiles with warnings promoted to errors.</li>
 *   <li>Temporal values cross as ISO-8601 text rather than epoch numbers.</li>
 *   <li>Unknown incoming members are tolerated, which is what lets a client echo a whole response
 *       back as the next request.</li>
 *   <li>Decimals are written plainly rather than in scientific notation, because no consumer of a
 *       fixed-width monetary field can interpret an exponent.</li>
 * </ul>
 */
final class JsonContractSupport {

    /** Not instantiable: this is a factory holder with no state of its own. */
    private JsonContractSupport() {
        throw new AssertionError("JsonContractSupport is a test factory holder and is not instantiable");
    }

    /**
     * Builds a mapper carrying the four serialisation settings the module declares.
     *
     * <p>A new instance every call, so no suite can observe a setting another suite changed.
     *
     * @return a mapper configured to the module's declared settings
     */
    static ObjectMapper declaredSettingsMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    /**
     * Customer identifier carried by {@link #populatedNavigation()}; nine characters.
     *
     * <p>Deliberately not a zero-padded value. Every literal in this block is chosen so that none is
     * a substring of any other and none is a substring of the zero-padded identifiers the per-type
     * suites use as ordinary field values. A negative oracle over a short zero-padded numeric string
     * is fragile in exactly that way - {@code 00000000011} contains {@code 000000001} - and a
     * non-disclosure assertion that fails on a substring collision teaches nothing about disclosure.
     */
    static final String NAV_CUSTOMER_ID = "917253869";

    /** Customer first name carried by {@link #populatedNavigation()}. */
    static final String NAV_FIRST_NAME = "Immanuel";

    /** Customer middle name carried by {@link #populatedNavigation()}. */
    static final String NAV_MIDDLE_NAME = "Madeline";

    /** Customer last name carried by {@link #populatedNavigation()}. */
    static final String NAV_LAST_NAME = "Kessler";

    /** Account identifier carried by {@link #populatedNavigation()}; eleven characters. */
    static final String NAV_ACCOUNT_ID = "39174628501";

    /** Primary account number carried by {@link #populatedNavigation()}; sixteen characters. */
    static final String NAV_CARD_NUMBER = "4915372608194736";

    /** Signed-on user identifier carried by {@link #populatedNavigation()}; eight characters. */
    static final String NAV_USER_ID = "NAVUSR07";

    /**
     * Builds a fully populated navigation state whose identifying components are the constants above.
     *
     * <p>Every one of the sixteen components carries a value, which is what makes it useful as a
     * negative oracle: a suite that nests this instance and then asserts the absence of
     * {@link #NAV_CARD_NUMBER}, {@link #NAV_CUSTOMER_ID} and the three name parts from a rendering
     * is asserting over values that are demonstrably present in the object graph rather than over
     * values that happened to be absent.
     *
     * <p>Declared once here rather than in each suite so that adding a component to the navigation
     * record is a single edit and so that no suite silently nests a partially populated instance,
     * which would weaken its own non-disclosure assertion without failing.
     *
     * @return a navigation state with every component populated
     */
    static NavigationContext populatedNavigation() {
        return new NavigationContext(
                "CB00",
                "COBIL00C",
                "CM00",
                "COMEN01C",
                NAV_USER_ID,
                "U",
                NavigationContext.ProgramContext.REENTER,
                NAV_CUSTOMER_ID,
                NAV_FIRST_NAME,
                NAV_MIDDLE_NAME,
                NAV_LAST_NAME,
                NAV_ACCOUNT_ID,
                "Y",
                NAV_CARD_NUMBER,
                "COBIL0A",
                "COBIL00");
    }

    /**
     * Extracts the literal characters a member's value occupies in a rendered payload.
     *
     * <p>Exists because a decimal assertion must never be made against a re-parsed tree. Jackson does
     * not read JSON numbers back as exact decimals by default, so a payload parsed with
     * {@code readTree} reports {@code 0.00} as {@code 0.0} and {@code 1234567890.12} as
     * {@code 1.23456789012E9}: the trailing zero and the plain form the wire actually carried are
     * dropped by the <em>observation</em> rather than by the serializer. Asserting on the emitted
     * characters is the only way to see what a client would receive.
     *
     * <p>The extraction is scoped to one member's value on purpose. Asserting the absence of an
     * exponent marker over a whole payload would match the letter E inside a member name such as
     * {@code generalError} and would then pass or fail for a reason unrelated to the number.
     *
     * @param rendered the payload exactly as emitted by a mapper
     * @param member the member whose value token is wanted
     * @return the characters between that member's colon and the next comma or closing brace
     * @throws IllegalArgumentException if the member does not appear in the rendered payload, so that
     *     a mistyped member name fails loudly instead of yielding a misleading token
     */
    static String renderedValueToken(String rendered, String member) {
        String marker = "\"" + member + "\":";
        int markerAt = rendered.indexOf(marker);
        if (markerAt < 0) {
            throw new IllegalArgumentException(
                    "member \"" + member + "\" does not appear in the rendered payload");
        }
        int valueStart = markerAt + marker.length();
        int commaAt = rendered.indexOf(',', valueStart);
        int braceAt = rendered.indexOf('}', valueStart);
        int valueEnd = (commaAt < 0 || (braceAt >= 0 && braceAt < commaAt)) ? braceAt : commaAt;
        return rendered.substring(valueStart, valueEnd < 0 ? rendered.length() : valueEnd);
    }
}
