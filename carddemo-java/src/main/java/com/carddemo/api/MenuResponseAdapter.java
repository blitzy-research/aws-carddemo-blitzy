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
package com.carddemo.api;

import com.carddemo.api.dto.MenuResponse;
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.domain.enums.UserType;
import com.carddemo.service.MenuService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Maps the menu service's own turn result onto the menu transport contract.
 *
 * <p>The service used to return the transport record directly, which made it depend upward on this
 * package and inverted the specification's layering rule. It now returns a result expressed in types it
 * owns, and this adapter is the single place that result becomes a response. Keeping the mapping here
 * rather than in a controller means it is testable without a servlet and that every controller reaching
 * the menu produces an identical payload.
 *
 * <p><strong>The two menus stay two shapes.</strong> The service's result names which menu it describes
 * and this adapter chooses the corresponding factory, which is what supplies each menu's own
 * transaction name and program name. One row type serves both because both legacy catalogs reduce to a
 * number and a label at the point of presentation, and the adapter widens that pair into whichever row
 * record the chosen factory takes.
 *
 * <p><strong>Identity on the returned state is the authenticated identity.</strong> The navigation
 * record this adapter publishes is built by {@link ConversationStateAdapter}, so the routing change the
 * service made is merged onto the record the client echoed and the identity members are reconciled
 * against the authenticated principal rather than echoed back.
 *
 * <p>Stateless apart from the injected adapter, holding no mutable field, so the singleton is safe for
 * unsynchronised concurrent use.
 *
 * @since 1.0.0
 */
@Component
public final class MenuResponseAdapter {

    /** The single conversion point for the navigation record, injected rather than reimplemented. */
    private final ConversationStateAdapter conversationStateAdapter;

    /**
     * @param conversationStateAdapter the boundary conversion for the navigation record
     */
    public MenuResponseAdapter(final ConversationStateAdapter conversationStateAdapter) {
        this.conversationStateAdapter = Objects.requireNonNull(conversationStateAdapter,
                "conversationStateAdapter must not be null");
    }

    /**
     * Converts one menu turn result into the response the client receives.
     *
     * @param screen the service's turn result, never {@code null}
     * @param inboundContext the navigation record the client echoed, which may be {@code null}
     * @param authenticatedUserId the identifier of the authenticated principal, possibly {@code null}
     * @param authenticatedUserType the type of the authenticated principal, possibly {@code null}
     * @return the transport response, never {@code null}
     * @throws NullPointerException if {@code screen} is {@code null}
     */
    public MenuResponse toResponse(final MenuService.MenuScreen screen,
                                   final NavigationContext inboundContext,
                                   final String authenticatedUserId,
                                   final UserType authenticatedUserType) {
        Objects.requireNonNull(screen, "screen must not be null");
        final NavigationContext outboundContext = conversationStateAdapter.toNavigationContext(
                inboundContext, screen.conversationState(), authenticatedUserId,
                authenticatedUserType);
        final MenuResponse.MessageSeverity severity = severityOf(screen.severity());

        if (screen.adminMenu()) {
            return MenuResponse.forAdminMenu(screen.title01(), screen.title02(),
                    screen.currentDate(), screen.currentTime(), adminRows(screen.rows()),
                    screen.echoedOption(), screen.message(), severity, screen.errorFlag(),
                    screen.focusScreenFieldId(), screen.nextRoute(), outboundContext);
        }
        return MenuResponse.forUserMenu(screen.title01(), screen.title02(),
                screen.currentDate(), screen.currentTime(), userRows(screen.rows()),
                screen.echoedOption(), screen.message(), severity, screen.errorFlag(),
                screen.focusScreenFieldId(), screen.nextRoute(), outboundContext);
    }

    /**
     * Widens the service's rows into the transport's user-menu row record.
     *
     * @param rows the service rows, never {@code null}
     * @return the transport rows in the same order, never {@code null}
     */
    private static List<MenuResponse.UserMenuOption> userRows(final List<MenuService.MenuRow> rows) {
        final List<MenuResponse.UserMenuOption> mapped = new ArrayList<>(rows.size());
        for (final MenuService.MenuRow row : rows) {
            mapped.add(new MenuResponse.UserMenuOption(row.number(), row.label()));
        }
        return mapped;
    }

    /**
     * Widens the service's rows into the transport's administrator-menu row record.
     *
     * @param rows the service rows, never {@code null}
     * @return the transport rows in the same order, never {@code null}
     */
    private static List<MenuResponse.AdminMenuOption> adminRows(final List<MenuService.MenuRow> rows) {
        final List<MenuResponse.AdminMenuOption> mapped = new ArrayList<>(rows.size());
        for (final MenuService.MenuRow row : rows) {
            mapped.add(new MenuResponse.AdminMenuOption(row.number(), row.label()));
        }
        return mapped;
    }

    /**
     * Maps the service's severity onto the transport's, preserving absence.
     *
     * <p>Absence is preserved rather than defaulted because a turn that reports nothing carries no
     * message and therefore no severity, and inventing one would put a severity on a blank message
     * field.
     *
     * @param severity the service severity, which may be {@code null}
     * @return the transport severity, or {@code null} when there is no message
     */
    private static MenuResponse.MessageSeverity severityOf(
            final MenuService.MessageSeverity severity) {
        if (severity == null) {
            return null;
        }
        return switch (severity) {
            case INFORMATIONAL -> MenuResponse.MessageSeverity.INFORMATIONAL;
            case ERROR -> MenuResponse.MessageSeverity.ERROR;
        };
    }
}
