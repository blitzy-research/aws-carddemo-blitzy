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
package com.carddemo.service;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit specification for {@link PostingRecordTransactionBoundary}, the per-record unit of work of the
 * posting run.
 *
 * <p>The reason this collaborator exists at all is asserted here rather than described: the posting
 * service reproduces the legacy mainline loop in one of its own methods, so a transactional annotation
 * on that service could never apply to the records the loop drives - a call from a method of a class to
 * another method of the same instance reaches the target directly and consults no proxy. Holding the
 * boundary on a separate bean is what makes the loop's call a proxied one, and the assertions below pin
 * both halves of that: the annotation is here, and it is nowhere on the service.
 *
 * @since 1.0.0
 */
@DisplayName("PostingRecordTransactionBoundary - one record, one unit of work, no bypass")
class PostingRecordTransactionBoundaryTest {

    @Test
    @DisplayName("execute is a REQUIRED transaction on a separate registered bean that is not final, so "
            + "a class-based proxy can be created for it")
    void executeOwnsThePerRecordTransaction() throws NoSuchMethodException {
        final Method execute =
                PostingRecordTransactionBoundary.class.getMethod("execute", Supplier.class);
        final Transactional transactional = execute.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation())
                .as("a record is the unit of atomicity, not of isolation: with no caller transaction "
                        + "REQUIRED opens one per record, and with one it joins rather than committing "
                        + "independently inside it")
                .isEqualTo(Propagation.REQUIRED);
        assertThat(PostingRecordTransactionBoundary.class.getAnnotation(Service.class))
                .as("a bean, so the framework proxies it")
                .isNotNull();
        assertThat(Modifier.isFinal(PostingRecordTransactionBoundary.class.getModifiers())).isFalse();
        assertThat(Modifier.isFinal(execute.getModifiers())).isFalse();
    }

    @Test
    @DisplayName("the callback result and its failure cross the boundary unchanged, and an absent "
            + "callback is refused")
    void delegatesToTheCompleteRecordOperation() {
        final PostingRecordTransactionBoundary boundary = new PostingRecordTransactionBoundary();
        final IllegalStateException failure = new IllegalStateException("record failed");

        assertThat(boundary.<String>execute(() -> "posted")).isEqualTo("posted");
        assertThatThrownBy(() -> boundary.execute(() -> {
            throw failure;
        }))
                .as("a failure leaves the method, which is what lets the framework roll the record back")
                .isSameAs(failure);
        assertThatNullPointerException()
                .isThrownBy(() -> boundary.execute(null))
                .withMessage("operation must not be null");
    }

    @Test
    @DisplayName("the boundary declares nothing but execute, so it cannot grow into a whole-run "
            + "transaction")
    void theBoundaryDeclaresOnlyTheOneEntryPoint() {
        assertThat(Arrays.stream(PostingRecordTransactionBoundary.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName))
                .containsExactly("execute");
        assertThat(PostingRecordTransactionBoundary.class.getDeclaredFields())
                .as("no state, so nothing one record leaves behind can reach the next")
                .isEmpty();
    }

    @Test
    @DisplayName("the posting service declares no transactional method of its own, so the boundary above "
            + "is the only per-record unit of work and a self-invoked one cannot exist")
    void thePostingServiceHoldsNoTransactionalMethodOfItsOwn() {
        assertThat(TransactionPostingService.class.getAnnotation(Transactional.class))
                .as("nor a class-level one, which would make the whole run one transaction")
                .isNull();
        assertThat(Arrays.stream(TransactionPostingService.class.getDeclaredMethods())
                .filter(method -> method.getAnnotation(Transactional.class) != null)
                .map(Method::getName))
                .as("a transactional method here would be bypassed by the mainline loop's own call")
                .isEmpty();
    }
}
