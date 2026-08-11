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
package com.carddemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the migrated CardDemo application.
 *
 * <h2>What this class is</h2>
 * The single startup class of the module and the only source that sits directly in
 * {@code com.carddemo}; every other production type lives in one of the layered sub-packages. Its
 * whole behaviour is to hand control to the Spring container, which is deliberate: this is the class
 * the packaged artifact names as its start class, so anything it did beyond starting the context
 * would become work that happens on every launch and in every test that loads the context.
 *
 * <h2>What it replaces</h2>
 * The legacy estate has no equivalent, because it has no single process to start. Online work was
 * reached through the transaction table of the CICS resource definition
 * {@code app/csd/CARDDEMO.CSD}, which registers eighteen transactions against eighteen programs,
 * seventeen screen mapsets, eight file entries - six base datasets plus two alternate index paths -
 * and one queue; batch work was reached by submitting job streams. The Spring context now performs
 * that registration role: the transactions become the endpoints the API layer publishes, and the job
 * streams become the jobs the batch layer defines. Sign-on hands off to whichever of the two menus
 * the authenticated user type selects, which makes it the transaction every other one is reached
 * from, and it is translated from {@code app/cbl/COSGN00C.cbl}.
 *
 * <h2>Why it carries exactly one annotation</h2>
 * Because this class sits at the root of the package tree, the component scan implied by
 * {@code @SpringBootApplication} already reaches every sub-package, so no explicit scan, entity scan
 * or repository scan is declared - narrowing the scan by hand is how a package silently stops being
 * wired. Every other enabling concern is owned by a class in {@code com.carddemo.config}: auditing
 * and the clock abstraction, the batch infrastructure, the security filter chain, web customisation,
 * schema migration, observability, the cloud clients and the published API contract each have their
 * own configuration class. Repeating any of those enabling annotations here would give the container
 * two sources for the same bean definition, so none of them is repeated.
 *
 * <h2>Why nothing runs at startup</h2>
 * Batch jobs are configured to launch on demand rather than on start-up, so this class registers no
 * runner, no lifecycle callback and no schedule. A launch therefore starts a service and processes
 * nothing until it is asked to, which is what makes the packaged artifact safe to start in any
 * environment. Mapping validation against the migrated schema does run at start-up, and a mismatch
 * is meant to stop the context rather than be caught here.
 */
@SpringBootApplication
public class CardDemoApplication {

    /**
     * Starts the application context.
     *
     * <p>No profile is selected here. The three profiles the module declares are chosen externally,
     * so that the same artifact runs against a local stack, a test harness or a production
     * environment without being rebuilt.</p>
     *
     * @param args the command-line arguments, passed through to the container so that externalised
     *             configuration supplied on the command line is honoured
     */
    public static void main(final String[] args) {
        SpringApplication.run(CardDemoApplication.class, args);
    }
}
