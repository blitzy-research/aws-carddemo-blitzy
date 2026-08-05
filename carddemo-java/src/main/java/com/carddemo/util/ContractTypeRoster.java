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

import java.util.List;

/**
 * The neutral contract by which the published interface description learns which request and response
 * families it must describe.
 *
 * <p><strong>Why the roster is not a list inside the configuration.</strong> The families are transport
 * types owned by the boundary package, and the specification's layering rule forbids the configuration
 * package from importing the boundary. Naming the types in the configuration inverted that direction;
 * naming them nowhere would leave the published document describing only whatever a scanned controller
 * signature happened to mention, silently omitting the rest. This interface is the resolution: the
 * boundary declares the roster, the configuration reads it through a contract the base layer owns, and the
 * document stays complete without either package importing the other.
 *
 * <p>An implementation supplies types, never schemas. Every property, width, format and access mode in the
 * published document is derived from the annotations on the type itself, so nothing about a contract is
 * ever hand-written twice.
 *
 * @since 1.0.0
 */
public interface ContractTypeRoster {

    /**
     * Every request and response family the published interface description must carry a named schema for.
     *
     * <p>Entries are resolved transitively by the caller, so a family's nested rows, states and cursor
     * shapes are derived from the components that reference them rather than restated in the roster.
     *
     * @return an unmodifiable list of contract types, never {@code null} and never empty
     */
    List<Class<?>> publishedContractTypes();
}
