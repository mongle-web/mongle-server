package com.mongle.backend.domain.dream.image;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class ImageGenerationResourcesTest {
    @Test
    void sixteenCallsCanWaitWithoutUsingCompletionThreads() {
        try (var resources = new ImageGenerationResources(16)) {
            var sources = new ArrayList<CompletableFuture<byte[]>>();
            var results = new ArrayList<CompletableFuture<String>>();
            var completions = new AtomicInteger();
            try {
                for (int i = 0; i < 16; i++) {
                    var source = new CompletableFuture<byte[]>();
                    sources.add(source);
                    results.add(resources.reserve().execute(() -> source, (bytes, failure) -> {
                        completions.incrementAndGet();
                        return Thread.currentThread().getName();
                    }));
                }
                assertThat(completions).hasValue(0);
                assertThat(results).allMatch(result -> !result.isDone());
                assertThatThrownBy(resources::reserve)
                        .isInstanceOf(RejectedExecutionException.class);
            } finally {
                sources.forEach(source -> source.complete(new byte[] {1}));
            }
            results.forEach(result -> assertThat(await(result)).startsWith("image-result-"));
            assertThat(completions).hasValue(16);
            var permits = new ArrayList<ImageGenerationResources.Permit>();
            try {
                for (int i = 0; i < 16; i++) permits.add(resources.reserve());
                assertThatThrownBy(resources::reserve)
                        .isInstanceOf(RejectedExecutionException.class);
            } finally {
                permits.forEach(ImageGenerationResources.Permit::close);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4})
    void completedProvidersUseConfiguredCompletionThreads(int resultThreads) throws Exception {
        try (var resources = new ImageGenerationResources(16, resultThreads)) {
            var entered = new CountDownLatch(resultThreads);
            var release = new CountDownLatch(1);
            var active = new AtomicInteger();
            var maximum = new AtomicInteger();
            var results = new ArrayList<CompletableFuture<String>>();
            try {
                for (int i = 0; i < 16; i++) {
                    results.add(resources.reserve().execute(
                            () -> CompletableFuture.completedFuture(new byte[] {1}),
                            (bytes, failure) -> {
                                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                                entered.countDown();
                                try {
                                    if (!release.await(5, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("완료 작업 해제 시간 초과");
                                    }
                                    return Thread.currentThread().getName();
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(ex);
                                } finally {
                                    active.decrementAndGet();
                                }
                            }));
                }
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(active).hasValue(resultThreads);
                assertThatThrownBy(resources::reserve)
                        .isInstanceOf(RejectedExecutionException.class);
            } finally {
                release.countDown();
            }
            results.forEach(result -> assertThat(await(result)).startsWith("image-result-"));
            assertThat(maximum).hasValue(resultThreads);
            assertThat(active).hasValue(0);
        }
    }

    @Test
    void externalWaitDoesNotUseCompletionThreadAndCancellationKeepsInternalWork() {
        try (var resources = new ImageGenerationResources(1)) {
            var source = new CompletableFuture<byte[]>();
            var stored = new CompletableFuture<String>();
            var calls = new AtomicInteger();
            var result = resources.reserve().execute(() -> source, (bytes, failure) -> {
                calls.incrementAndGet();
                stored.complete(Thread.currentThread().getName());
                return "stored";
            });
            assertThat(result).isNotDone();
            assertThat(calls).hasValue(0);
            assertThatThrownBy(resources::reserve).isInstanceOf(RejectedExecutionException.class);
            result.cancel(true);
            assertThatThrownBy(resources::reserve).isInstanceOf(RejectedExecutionException.class);
            source.complete(new byte[] {1});
            assertThat(await(stored)).startsWith("image-result-");
            // 취소한 공개 Future와 달리 내부 저장은 정확히 한 번 실행된다.
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void failedCompletionReleasesSlotForNextCall() {
        try (var resources = new ImageGenerationResources(1)) {
            var result = resources.reserve().execute(
                    () -> CompletableFuture.completedFuture(new byte[] {1}),
                    (bytes, failure) -> { throw new IllegalStateException("storage"); });
            assertThatThrownBy(() -> await(result)).hasMessage("storage");
            CompletableFuture<Byte> next = resources.reserve().execute(
                    () -> CompletableFuture.completedFuture(new byte[] {2}),
                    (bytes, failure) -> bytes[0]);
            byte value = await(next);
            assertThat(value).isEqualTo((byte) 2);
        }
    }

    @Test
    void rollbackAndRepeatedCloseReleaseExactlyOneSlot() {
        try (var resources = new ImageGenerationResources(1)) {
            var rolledBack = resources.reserve();
            rolledBack.close();
            rolledBack.close();
            var next = resources.reserve();
            try {
                assertThatThrownBy(resources::reserve).isInstanceOf(RejectedExecutionException.class);
            } finally {
                next.close();
            }
        }
    }

    @Test
    void synchronousProviderFailureIsHandledOnCompletionThread() {
        try (var resources = new ImageGenerationResources(1)) {
            var provider = new IllegalStateException("provider");
            var result = resources.reserve().execute(
                    () -> { throw provider; },
                    (bytes, failure) -> {
                        assertThat(failure).isSameAs(provider);
                        return Thread.currentThread().getName();
                    });
            assertThat(await(result)).startsWith("image-result-");
        }
    }
}
