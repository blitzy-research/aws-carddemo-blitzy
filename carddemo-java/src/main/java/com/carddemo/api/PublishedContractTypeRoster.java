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

import com.carddemo.api.dto.AccountUpdateRequest;
import com.carddemo.api.dto.AccountUpdateResponse;
import com.carddemo.api.dto.AccountViewResponse;
import com.carddemo.api.dto.BatchJobExecutionResponse;
import com.carddemo.api.dto.BatchJobLaunchRequest;
import com.carddemo.api.dto.BatchJobLaunchResponse;
import com.carddemo.api.dto.BillPaymentRequest;
import com.carddemo.api.dto.BillPaymentResponse;
import com.carddemo.api.dto.CardDetailRequest;
import com.carddemo.api.dto.CardDetailResponse;
import com.carddemo.api.dto.CardListRequest;
import com.carddemo.api.dto.CardListResponse;
import com.carddemo.api.dto.CardUpdateRequest;
import com.carddemo.api.dto.CardUpdateResponse;
import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.api.dto.FieldErrorDecorator;
import com.carddemo.api.dto.MenuResponse;
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.api.dto.PageMetadata;
import com.carddemo.api.dto.ReportRequest;
import com.carddemo.api.dto.ReportResponse;
import com.carddemo.api.dto.ScreenWorkArea;
import com.carddemo.api.dto.SignOnRequest;
import com.carddemo.api.dto.SignOnResponse;
import com.carddemo.api.dto.StatementSummary;
import com.carddemo.api.dto.TransactionAddRequest;
import com.carddemo.api.dto.TransactionAddResponse;
import com.carddemo.api.dto.TransactionListRequest;
import com.carddemo.api.dto.TransactionListResponse;
import com.carddemo.api.dto.TransactionViewResponse;
import com.carddemo.api.dto.UserRequest;
import com.carddemo.api.dto.UserResponse;
import com.carddemo.util.ContractTypeRoster;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The roster of every request and response family the published interface description must describe.
 *
 * <p>This is the boundary half of {@link ContractTypeRoster}. The families are transport types this
 * package declares, so this package is where the roster belongs; the configuration that assembles the
 * document reads it through the neutral contract and therefore imports nothing from here.
 *
 * <p><strong>Why the roster is explicit rather than discovered.</strong> A schema reaches the document only
 * if some scanned controller operation happens to reference the type, so an operation-driven document would
 * publish only the subset any signature happens to mention and would silently omit the rest - including
 * every echoed carrier and every state a client has to be able to read. Naming every family here makes the
 * published contract complete and makes its completeness assertable, which is what lets a test fail when a
 * family is added and forgotten.
 *
 * <p>The number of families is deliberately not spelled out in this description. It was, once, and the
 * numeral outlived the list: families were added below and the sentence above them was not revised, and
 * nothing failed, because prose is not compiled. The membership is asserted instead -
 * {@code OpenApiConfigTest} builds an independent list from the contract package's own files, requires the
 * roster to contain all of it, and requires the roster to hold exactly that many entries plus the one
 * nested shape named below - so a family added and forgotten fails a test rather than contradicting a
 * sentence.
 *
 * <p><strong>The nested shapes are deliberately absent from this list, and are still published.</strong>
 * Each entry is resolved transitively by the caller, so a family's nested rows, states and cursor shapes
 * are derived from the components that reference them rather than restated here. Listing a nested type
 * explicitly would create a second place for it to be declared and a second place for it to drift. Every
 * nested type in the package carries a distinct simple name, so no two schemas contend for one key.
 *
 * <p><strong>Nothing here is hand-written.</strong> The entries are types, not schemas: every property,
 * width, format and access mode in the published document is derived from the annotations on the type
 * itself. This is the opposite of maintaining a schema by hand beside the code it describes, and it is why
 * the derived document cannot disagree with the contracts it documents.
 *
 * <p>Stateless and deeply immutable: the roster is built once with the immutable list factory and held in a
 * {@code private static final} field, so the singleton is safe for unsynchronised concurrent use and the
 * published list cannot be modified by a caller.
 *
 * @since 1.0.0
 */
@Component
public final class PublishedContractTypeRoster implements ContractTypeRoster {

    /**
     * Every published family, in the order a reader of the document would look for them: alphabetical.
     *
     * <p>One entry per family, plus exactly one more: {@code PageMetadata.PageCursorRequest}, the nested
     * cursor shape that no family references by property. That is why this shape is named explicitly while
     * every other nested row, state and cursor is derived transitively from the component that references
     * it. The count itself is this list's own size and is stated nowhere else.
     */
    private static final List<Class<?>> PUBLISHED_CONTRACT_TYPES = List.of(
            AccountUpdateRequest.class,
            AccountUpdateResponse.class,
            AccountViewResponse.class,
            BatchJobExecutionResponse.class,
            BatchJobLaunchRequest.class,
            BatchJobLaunchResponse.class,
            BillPaymentRequest.class,
            BillPaymentResponse.class,
            CardDetailRequest.class,
            CardDetailResponse.class,
            CardListRequest.class,
            CardListResponse.class,
            CardUpdateRequest.class,
            CardUpdateResponse.class,
            ErrorResponse.class,
            FieldErrorDecorator.class,
            MenuResponse.class,
            NavigationContext.class,
            PageMetadata.class,
            PageMetadata.PageCursorRequest.class,
            ReportRequest.class,
            ReportResponse.class,
            ScreenWorkArea.class,
            SignOnRequest.class,
            SignOnResponse.class,
            StatementSummary.class,
            TransactionAddRequest.class,
            TransactionAddResponse.class,
            TransactionListRequest.class,
            TransactionListResponse.class,
            TransactionViewResponse.class,
            UserRequest.class,
            UserResponse.class);

    /**
     * Creates the roster. Declared explicitly, with no parameters, because it has no collaborators and a
     * consumer has an unambiguous single constructor to inject through.
     */
    public PublishedContractTypeRoster() {
    }

    /**
     * {@inheritDoc}
     *
     * <p>The list is unmodifiable and is the same instance on every call, which is safe precisely because
     * a class literal cannot be mutated.
     */
    @Override
    public List<Class<?>> publishedContractTypes() {
        return PUBLISHED_CONTRACT_TYPES;
    }
}
