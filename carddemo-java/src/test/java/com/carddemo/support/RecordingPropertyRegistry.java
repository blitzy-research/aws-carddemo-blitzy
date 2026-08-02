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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Captures what a {@code @DynamicPropertySource} method registers, so the registration itself can be
 * asserted without booting a Spring context.
 *
 * <h2>Why this exists rather than a context that reads the properties back</h2>
 * The thing under test is a mapping: which key names are published, and which running-container value
 * each one resolves to. Booting a context to observe that mapping would answer the question only
 * indirectly - the observed environment is the merge of this source with several property files, so a
 * key that this source failed to publish could still appear, supplied by a file, and the test would
 * pass while the contract was broken. Recording the registration directly is the only way to assert
 * that the source publishes a key rather than that something, somewhere, does.
 *
 * <p>Registration order is preserved, and values are resolved on demand through the supplier that was
 * registered rather than at registration time, so a caller observes exactly what the Spring TestContext
 * Framework would observe.</p>
 *
 * <p>Provenance: this support type has no legacy antecedent - the legacy estate carries no test harness
 * of any kind. Checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
public final class RecordingPropertyRegistry implements DynamicPropertyRegistry {

    /** Suppliers by key name, in registration order. */
    private final Map<String, Supplier<Object>> registered = new LinkedHashMap<>();

    /**
     * Records a registration.
     *
     * <p>A duplicate key is rejected rather than silently overwritten. A registration method that
     * published the same key twice would be a defect the recorder must not hide, because the second
     * value would win in a real environment and the first would look effective while doing nothing.</p>
     *
     * @param name          the property name being registered; must not be null or blank
     * @param valueSupplier the supplier of the property value; must not be null
     */
    @Override
    public void add(final String name, final Supplier<Object> valueSupplier) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("property name must not be null or blank");
        }
        if (valueSupplier == null) {
            throw new IllegalArgumentException("value supplier must not be null for property " + name);
        }
        if (registered.containsKey(name)) {
            throw new IllegalStateException("property " + name + " was registered more than once");
        }
        registered.put(name, valueSupplier);
    }

    /**
     * Returns the registered key names in registration order.
     *
     * @return the key names, never null
     */
    public List<String> names() {
        return List.copyOf(registered.keySet());
    }

    /**
     * Resolves one registered property by invoking the supplier that was registered for it.
     *
     * @param name the property name; must have been registered
     * @return the resolved value rendered as text, or the literal {@code null} if the supplier yields
     *         no value
     */
    public String valueOf(final String name) {
        final Supplier<Object> supplier = registered.get(name);
        if (supplier == null) {
            throw new IllegalArgumentException("property " + name + " was not registered");
        }
        return String.valueOf(supplier.get());
    }

    /**
     * Reports how many properties were registered.
     *
     * @return the registration count
     */
    public int size() {
        return registered.size();
    }
}
