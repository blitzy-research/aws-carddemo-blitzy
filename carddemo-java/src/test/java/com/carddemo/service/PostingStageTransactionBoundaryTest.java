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
 * Unit specification for {@link PostingStageTransactionBoundary}, the unit of work of one I/O act of the
 * posting run.
 *
 * <p>Two properties are asserted here rather than described, and each of them is a property the module
 * previously had wrong in the opposite direction.
 *
 * <p>The first is the <strong>propagation</strong>. The legacy posting member stores into three files at
 * its lines 440 to 442, all three defined {@code RECOVERY(NONE)} with {@code JOURNAL(NO)}, and the member
 * holds no rollback site anywhere: a store that completed stayed completed whatever the store after it did.
 * The step that drives the run is chunk-oriented, so a transaction is already open by the time a record
 * reaches the posting cascade, and {@code REQUIRED} would join it - making the three stores one atomic unit
 * and discarding two of them whenever the third failed. {@link Propagation#REQUIRES_NEW} suspends the
 * caller's transaction and gives the act a unit of its own, which is the only propagation under which a
 * completed store survives a later failure.
 *
 * <p>The second is <strong>where the boundary lives</strong>. The posting service reproduces the legacy
 * mainline loop and the posting cascade in its own methods, so a transactional annotation on that service
 * could never apply to the acts those methods drive: a call from one method of a class to another method of
 * the same instance reaches the target directly and consults no proxy. Holding the boundary on a separate
 * bean is what makes every one of those calls a proxied one, and the assertions below pin both halves of
 * that - the annotation is here, and it is nowhere on the service.
 *
 * @since 1.0.0
 */
@DisplayName("PostingStageTransactionBoundary - one I/O act, one unit of work, no shared fate")
class PostingStageTransactionBoundaryTest {

    @Test
    @DisplayName("execute is a REQUIRES_NEW transaction on a separate registered bean that is not final, "
            + "so a class-based proxy can be created for it")
    void executeOwnsThePerStageTransaction() throws NoSuchMethodException {
        final Method execute =
                PostingStageTransactionBoundary.class.getMethod("execute", Supplier.class);
        final Transactional transactional = execute.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation())
                .as("the source's three stores are independent, and the chunk-oriented step has already "
                        + "opened a transaction by the time a record arrives: REQUIRED would join it and "
                        + "make the three atomic, which the member is not")
                .isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(PostingStageTransactionBoundary.class.getAnnotation(Service.class))
                .as("a bean, so the framework proxies it")
                .isNotNull();
        assertThat(Modifier.isFinal(PostingStageTransactionBoundary.class.getModifiers())).isFalse();
        assertThat(Modifier.isFinal(execute.getModifiers())).isFalse();
    }

    @Test
    @DisplayName("the callback result and its failure cross the boundary unchanged, and an absent "
            + "callback is refused")
    void delegatesToTheOneAct() {
        final PostingStageTransactionBoundary boundary = new PostingStageTransactionBoundary();
        final IllegalStateException failure = new IllegalStateException("stage failed");

        assertThat(boundary.<String>execute(() -> "stored")).isEqualTo("stored");
        assertThatThrownBy(() -> boundary.execute(() -> {
            throw failure;
        }))
                .as("a failure leaves the method, which is what lets the framework roll THAT act back - "
                        + "and only that one, the acts before it having already committed")
                .isSameAs(failure);
        assertThatNullPointerException()
                .isThrownBy(() -> boundary.execute(null))
                .withMessage("operation must not be null");
    }

    @Test
    @DisplayName("the boundary declares nothing but execute, so it cannot grow into a per-record or a "
            + "whole-run transaction")
    void theBoundaryDeclaresOnlyTheOneEntryPoint() {
        assertThat(Arrays.stream(PostingStageTransactionBoundary.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName))
                .containsExactly("execute");
        assertThat(PostingStageTransactionBoundary.class.getDeclaredFields())
                .as("no state, so nothing one act leaves behind can reach the next")
                .isEmpty();
    }

    @Test
    @DisplayName("the posting service declares no transactional method of its own, so the boundary above "
            + "is the only unit of work in the cascade and a self-invoked one cannot exist")
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
