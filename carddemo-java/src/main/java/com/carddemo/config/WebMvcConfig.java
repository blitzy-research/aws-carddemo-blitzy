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

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The web tier's single customisation hook, and it customises nothing.
 *
 * <p>This class contributes no override to the web framework's configuration. That is its purpose, not
 * an unfinished state: the auto-configured web tier is already exactly what this module's published
 * interface contract requires, and the most valuable thing a configuration hook can do here is to
 * occupy the seat and record, in a reviewable place, every adjustment that was considered and
 * deliberately declined. An empty hook is auditable; an absent one leaves the next reader to
 * rediscover each decision.</p>
 *
 * <h2>Provenance</h2>
 * Part of the migration of the AWS CardDemo z/OS application at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p>The legacy authority for the existence of a web tier at all is the CICS resource definition
 * {@code app/csd/CARDDEMO.CSD} (505 lines), which carries eighteen {@code TRANSACTION} definitions and
 * seventeen {@code MAPSET} definitions. Those transaction-to-program-to-mapset bindings are what become
 * the REST endpoint groups of this module, so that file establishes <em>that</em> there is a request
 * surface to serve. It prescribes nothing whatsoever about how to serve it: a 3270 mapset has no view
 * template, no static asset, no upload, no locale negotiation and no theme, so none of the framework
 * knobs that exist for those concepts has a legacy counterpart to be faithful to. Reading the resource
 * definition is therefore what justifies declining the customisations listed below, rather than merely
 * omitting them.</p>
 *
 * <p>No resource-definition, screen-map or program source text is reproduced here, and nothing under
 * {@code app/} is read at runtime. Only member names, definition kinds and counts cross over.</p>
 *
 * <h2>The two contracts this class must not disturb</h2>
 * <ol>
 *   <li><strong>Deterministic JSON.</strong> {@code src/main/resources/application.yml} configures the
 *       serialisation layer, and one of those settings is load-bearing rather than cosmetic: plain
 *       decimal output. Every monetary and rate value in this module is a fixed-scale decimal carried
 *       across from a zoned-decimal field, and a decimal rendered in scientific notation is a corrupted
 *       value on the wire, failing byte-equivalence and interface-contract verification in one stroke.
 *       Alongside it sit the omission of null-valued properties, ISO-8601 rendering of dates rather than
 *       epoch numbers, and lenient handling of unknown request properties. Those four settings are the
 *       response and request shape.
 *       <p>Every one of them is applied by the auto-configured serialiser, which means they survive only
 *       for as long as that serialiser and its message converter survive. Substituting either &mdash;
 *       whether by publishing a hand-built serialiser bean, by a builder customiser that re-derives one,
 *       by contributing a replacement JSON message converter, or by overriding the message-converter
 *       list rather than extending it &mdash; discards all four <em>silently</em>. The build stays green,
 *       the endpoints keep answering, and the amounts are wrong. That is why this class holds no
 *       serialiser of any kind and does not touch the converter list.</p></li>
 *   <li><strong>The management base path.</strong> The health probe and the metrics scrape endpoint are
 *       resolved literally, as text, by three sibling files: the {@code HEALTHCHECK} of the module
 *       {@code Dockerfile}, the health gate that dependent services in {@code docker-compose.yml} wait
 *       on, and the scrape target in {@code config/prometheus/prometheus.yml}. The base path they all
 *       spell out is stated explicitly in {@code application.yml} for exactly that reason. Introducing a
 *       servlet path prefix here, or moving the dispatcher's mapping, would relocate those endpoints and
 *       break the container image and the metrics pipeline together, so this class introduces
 *       neither.</li>
 * </ol>
 *
 * <h2>Why this is a configurer and never an explicit web-MVC enablement</h2>
 * This class implements {@link WebMvcConfigurer} and is <strong>not</strong> annotated with the
 * framework's explicit web-MVC enablement annotation. The distinction is the single most consequential
 * decision in this file. That annotation does not add configuration on top of the auto-configured web
 * tier; it replaces it, switching the auto-configuration off entirely. Doing so here would discard the
 * configured message converters &mdash; and with them all four serialisation settings above &mdash; the
 * management endpoints' web exposure, and the generated interface document and its viewer, all at once,
 * and it would do so without a single compiler diagnostic. Implementing the configurer interface
 * instead means this bean is collected by the auto-configuration and consulted <em>as part of</em> it,
 * which is what leaves every default intact.
 *
 * <h2>Declined customisations</h2>
 * Every adjustment this hook could have made is listed below with the reason it was not made, so that
 * the minimality of the class body is a documented decision rather than something a reader has to infer
 * from silence. <strong>The class body registers none of them.</strong> It declares no method beyond its
 * constructor, and each entry below identifies its subject by description rather than by the framework's
 * method or annotation name, so a mechanical audit that greps this file for any of those names finds
 * nothing at all &mdash; neither a live registration nor a false positive in a comment.
 * <ul>
 *   <li><strong>Type conversion for request binding.</strong> Declined. Every fixed-width identifier in
 *       the estate &mdash; account, card, customer, transaction &mdash; is a zero-padded external text
 *       field, and it is carried through this module as a bounded string and never as a numeric type,
 *       precisely because a leading zero and an exact external width are part of the contract. Strings
 *       need no converter, so the framework binds them natively and byte-faithfully. Registering a
 *       converter would introduce the one component able to trim or renumber a value that must not be
 *       trimmed or renumbered, in exchange for nothing.</li>
 *   <li><strong>Handler interceptors.</strong> Declined. Request instrumentation belongs to
 *       {@code com.carddemo.config.ObservabilityConfig}, which owns the meters and the trace bridge for
 *       the whole module. An interceptor added here would either duplicate that instrumentation or
 *       compete with it, and split ownership of the measurement surface that establishes this
 *       implementation's performance baseline.</li>
 *   <li><strong>Path matching and content negotiation.</strong> Declined. The defaults already serve the
 *       management base path and the two interface-document paths declared in {@code application.yml};
 *       there is nothing to repair. A negotiation override exists to give a browser a friendly default
 *       representation, and this module has no browser client: its consumers send an explicit
 *       {@code Accept} header, and its verification is performed by tests.</li>
 *   <li><strong>Static resource handling, a welcome page and a site icon.</strong> Declined. There is no
 *       single-page application, no bundled asset directory and no packaged web library to serve,
 *       because no browser user interface is in scope: the terminal screen contract becomes request and
 *       response types exercised by tests. The framework's own default resource and welcome-page
 *       mappings are still registered by the auto-configuration, as they always are; with no such
 *       resource present they simply never match, and this class adds nothing to them.</li>
 *   <li><strong>View resolution, view-name forwarding and any template engine.</strong> Declined, and
 *       structurally impossible to need: this module renders no view. The build declares no template
 *       engine at all, so there is nothing to integrate even if a view were wanted.</li>
 *   <li><strong>Cross-origin request mapping.</strong> Declined. No browser origin calls these endpoints,
 *       so there is no cross-origin case to permit. Were one ever genuinely required by a test, it would
 *       have to be narrow and explicit; a wildcard origin, and above all a wildcard origin combined with
 *       credentials, is never an acceptable shape.</li>
 *   <li><strong>Locale and theme resolution.</strong> Declined. The legacy screens are single-locale and
 *       carry no theming concept whatsoever, and the message text they emit is reproduced verbatim as an
 *       external contract by {@code com.carddemo.service.MessageCatalogService} rather than resolved per
 *       request. A locale resolver would introduce the possibility of that fixed text varying.</li>
 *   <li><strong>Multipart request handling.</strong> Declined. Nothing in scope uploads through the web
 *       tier. Batch input arrives as staged fixed-width files read by the batch tier, which is where the
 *       legacy sequential datasets landed.</li>
 *   <li><strong>Asynchronous request configuration.</strong> Declined, on two independent grounds. No
 *       endpoint in this module returns an asynchronous result; and configuring it would mean stating a
 *       time-out or a task-executor size, which is a performance figure this migration is not entitled
 *       to invent &mdash; the legacy estate documents no numeric service level of any kind, so
 *       performance here is measured and never asserted. Orderly shutdown, likewise, is already declared
 *       in {@code application.yml} and is not restated here.</li>
 *   <li><strong>Argument resolvers and return-value handlers.</strong> Declined. The navigation state
 *       that the legacy pseudo-conversation carried in a shared communication area is an ordinary part
 *       of the request and response bodies in this module, echoed by the client, so it binds through the
 *       standard body path. A bespoke resolver would hide that contract inside framework plumbing.</li>
 *   <li><strong>Validator replacement.</strong> Declined. Field validation on the request types is
 *       declarative and is performed by the auto-configured validator. Supplying another one would
 *       replace it, and the request types depend on the standard one being in force.</li>
 *   <li><strong>Error rendering: exception resolvers, the framework's own advice base class, and the
 *       standard problem-detail response format.</strong> Declined, all three. The error body is owned by
 *       {@code com.carddemo.api.GlobalExceptionHandler} together with
 *       {@code com.carddemo.api.dto.ErrorResponse}, whose per-field states distinguish a value that was
 *       absent from one that was present and rejected. That two-state distinction is inherited from the
 *       legacy screens, which changed a field's display attribute when its validation flag was not
 *       satisfied and additionally marked the field when the flag was specifically blank, and only on
 *       re-submission. A framework-wide error format flattens those two states into one, and an
 *       exception resolver or advice base class registered here would compete with the handler for the
 *       same responses. {@code application.yml} is deliberately silent on the problem-detail switch for
 *       the same reason, and this class does not enable from Java what configuration declined to
 *       enable.</li>
 * </ul>
 *
 * <h2>Bean semantics</h2>
 * A container-managed singleton, collected by the web auto-configuration along with every other
 * configurer bean and consulted during web-tier initialisation. It declares no factory method, so it is
 * registered in lite mode; that is stated explicitly on the annotation rather than left to the default
 * for two reasons. A full-mode configuration class is subclassed at runtime so that factory-method calls
 * can be intercepted, which this class has no need of and which would additionally forbid it from being
 * {@code final}. And keeping it unproxied leaves the module's runtime proxy and reflection surface
 * untouched, which the low-level-code audit measures and reports.
 *
 * <p>This class sits at the base of the dependency graph. It has no field, no collaborator and no
 * dependency on any other type in this package, so it can never participate in an initialisation cycle
 * with the web infrastructure it configures.</p>
 *
 * <h2>Thread safety</h2>
 * Stateless and immutable. There is no field, no static state, no setter and no lazily populated value,
 * so the singleton is safe for unsynchronised concurrent use. Every method it exposes is an inherited
 * default that this class does not override, and each of those defaults either does nothing or reports
 * that it has no contribution to make.
 */
@Configuration(proxyBeanMethods = false)
public final class WebMvcConfig implements WebMvcConfigurer {

    /**
     * Creates the configurer singleton.
     *
     * <p>The constructor is declared explicitly, rather than left implicit, so that the absence of
     * collaborators is a visible and reviewable property of the class: a reader can see at a glance that
     * nothing is injected and therefore that nothing can be reconfigured from elsewhere. It takes no
     * parameter and performs no work, because this class contributes no override and consequently needs
     * no state to contribute one from.</p>
     */
    public WebMvcConfig() {
        // Nothing to inject and nothing to initialise. Every method of the implemented interface is
        // inherited as its own no-op default, which is precisely the intended configuration: the
        // auto-configured web tier is left exactly as it stands.
    }
}
