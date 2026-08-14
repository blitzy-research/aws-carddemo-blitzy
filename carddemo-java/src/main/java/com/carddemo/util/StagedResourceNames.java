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

import java.nio.file.Path;
import java.util.Objects;

/**
 * The one statement of what a configured dataset name may be, shared by every batch job configuration
 * that resolves such a name against a staging root.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>Five job configurations bind logical dataset names from configuration and resolve each one against a
 * staging directory, then create, write, truncate or delete the resolved file. Each of them screened the
 * bound value for null and blank only. That is sufficient to catch a missing value and insufficient to
 * keep the result inside the staging root: resolving an absolute path against a directory yields the
 * absolute path itself, and resolving a name carrying parent references walks upward out of the root. A
 * value that did either would still be a perfectly well-formed configuration value, and the file the job
 * then created, truncated or deleted would be somewhere the job was never meant to reach.
 *
 * <p>The five configurations each held their own copy of the blank check. Adding a containment rule to
 * five copies is how one copy is later widened alone, so the rule lives here once and every configuration
 * reads it. There is one implementation, one wording, and one test.
 *
 * <h2>What a dataset name may be</h2>
 *
 * <p>A single path element and nothing else: not null, not blank, not absolute, carrying no path
 * separator of either convention, and not the current-directory or parent-directory reference. That is
 * exactly what the legacy names this replaces were - {@code AWS.M2.CARDDEMO.DALYTRAN.PS} is one dataset
 * name, not a path - so the rule refuses nothing any faithful configuration would supply.
 *
 * <p><strong>The separator check names both conventions explicitly rather than asking the
 * platform.</strong> The platform separator on the deployment target is the forward slash, so a value
 * carrying a backslash would pass a platform-sensitive check and then be a legal single file name whose own
 * text looks like a path - which is exactly the value a reviewer would expect this rule to have refused.
 * Refusing both makes the rule the same rule wherever the module runs, and no legacy dataset name contains either character.
 *
 * <h2>What this deliberately does not do</h2>
 *
 * <p>It does not validate the staging root. A root is legitimately a multi-segment path and may be
 * absolute, so the same rule applied to it would refuse every real deployment. It does not touch the file
 * system: nothing here resolves, creates, opens or stats anything, so it is safe to call while binding
 * configuration, before any directory exists. And it states no format rule beyond containment - a dataset
 * naming convention belongs to the configuration that declares the default, not here.
 *
 * @since 1.0.0
 */
public final class StagedResourceNames {

    /** The forward slash, the platform separator on the deployment target. */
    public static final char POSIX_SEPARATOR = '/';

    /** The backslash, refused for the reason the class documentation gives. */
    public static final char WINDOWS_SEPARATOR = '\\';

    /** The current-directory reference, which resolves to the staging root itself. */
    public static final String CURRENT_DIRECTORY = ".";

    /** The parent-directory reference, which resolves above the staging root. */
    public static final String PARENT_DIRECTORY = "..";

    /**
     * Utility holder.
     *
     * @throws AssertionError always, because the class is never instantiated
     */
    private StagedResourceNames() {
        throw new AssertionError("StagedResourceNames is a utility holder and is never instantiated");
    }

    /**
     * Validates a configured dataset name that will be resolved against a staging root.
     *
     * <p>The returned value is the supplied value unchanged. Nothing is trimmed, folded or rewritten,
     * because a name that needs correcting is a configuration defect and correcting it silently would run
     * the job against a resource nobody configured.
     *
     * @param  value the configured value
     * @param  key   the configuration key the value was bound from, named in every diagnostic so a
     *               defect is actionable without reading this class
     * @return the validated value, unchanged
     * @throws NullPointerException     if the value is {@code null}
     * @throws IllegalArgumentException if the value is blank, absolute, carries a path separator of
     *                                  either convention, or is the current- or parent-directory
     *                                  reference
     */
    public static String requireSimpleName(final String value, final String key) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, () -> key + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " must name a resource; a blank value would resolve"
                    + " to the staging directory itself rather than to a dataset");
        }
        if (value.indexOf(POSIX_SEPARATOR) >= 0 || value.indexOf(WINDOWS_SEPARATOR) >= 0) {
            throw new IllegalArgumentException(key + " must be a single name and must not contain a path"
                    + " separator, because it is resolved against the staging directory and a value"
                    + " carrying a separator can name a location outside it");
        }
        if (CURRENT_DIRECTORY.equals(value) || PARENT_DIRECTORY.equals(value)) {
            throw new IllegalArgumentException(key + " must name a resource and must not be a directory"
                    + " reference, because a reference resolves to the staging directory itself or above"
                    + " it rather than to a dataset");
        }
        if (Path.of(value).isAbsolute()) {
            throw new IllegalArgumentException(key + " must be a single name and must not be absolute,"
                    + " because resolving an absolute value against the staging directory yields the"
                    + " absolute value itself and so escapes the directory entirely");
        }
        return value;
    }
}
