package com.mongle.backend.domain.dream.image;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

class ImageGenerationResourcesTest {
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
