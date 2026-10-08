package com.mongle.backend.domain.dream.gateway;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class DreamGenerationResourcesTest {
    @Test
    void waitingUsesNoWorkerAndPersistenceDoesNotRunOnGatewayThread() throws Exception {
        try (var resources = new DreamGenerationResources(1)) {
            var pending = new CompletableFuture<String>();
            var name = new AtomicReference<String>();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var result =
                    resources.execute(
                            () -> pending,
                            (content, failure) -> {
                                name.set(Thread.currentThread().getName());
                                entered.countDown();
                                try {
                                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                                } catch (InterruptedException ex) {
                                    throw new AssertionError(ex);
                                }
                                return content;
                            });
            assertThat(result).isNotDone();
            assertThat(name).hasNullValue();
            pending.complete("성공");
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(name.get()).startsWith("dream-result-");
                assertThatThrownBy(
                                () -> resources.execute(() -> pending, (value, failure) -> value))
                        .isInstanceOf(RejectedExecutionException.class);
            } finally {
                release.countDown();
            }
            assertThat(DreamAiTestAwait.await(result)).isEqualTo("성공");
        }
    }

    @Test
    void cancelledHttpResultStillSaves() {
        try (var resources = new DreamGenerationResources(1)) {
            var pending = new CompletableFuture<String>();
            var saved = new CompletableFuture<String>();
            var result =
                    resources.execute(
                            () -> pending,
                            (content, failure) -> {
                                saved.complete(content);
                                return content;
                            });
            result.cancel(false);
            pending.complete("저장");
            assertThat(DreamAiTestAwait.await(saved)).isEqualTo("저장");
        }
    }

    @Test
    void exceptionalCompletionReleasesCapacity() {
        try (var resources = new DreamGenerationResources(1)) {
            var result =
                    resources.execute(
                            () -> CompletableFuture.failedFuture(new IllegalStateException()),
                            (content, failure) -> {
                                throw new IllegalArgumentException("저장 실패");
                            });
            assertThatThrownBy(() -> DreamAiTestAwait.await(result))
                    .isInstanceOf(IllegalArgumentException.class);
            var retried =
                    resources.execute(
                            () -> CompletableFuture.completedFuture("재시도"),
                            (content, failure) -> content);
            assertThat(DreamAiTestAwait.await(retried)).isEqualTo("재시도");
        }
    }
}
