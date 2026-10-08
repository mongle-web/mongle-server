package com.mongle.backend.domain.dream.gateway;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.ai.dto.request.*;
import com.mongle.backend.domain.ai.dto.response.*;
import com.mongle.backend.domain.ai.entity.AiTaskType;
import com.mongle.backend.domain.ai.error.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

class DreamAiGatewayTest {
    @Test
    void pendingCallReturnsImmediatelyAndHoldsSlotUntilCompletion() {
        var pending = new CompletableFuture<AiGenerationResult>();
        var calls = new AtomicInteger();
        var gateway =
                new DreamAiGateway(
                        request -> {
                            calls.incrementAndGet();
                            return pending;
                        },
                        true,
                        1);
        var first = gateway.generate(request());
        assertThat(first).isNotDone();
        assertThatThrownBy(() -> gateway.generate(request()))
                .isInstanceOfSatisfying(
                        AiGatewayException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(AiGatewayErrorCode.RATE_LIMITED));
        assertThat(calls).hasValue(1);
        pending.complete(result());
        assertThat(DreamAiTestAwait.await(first)).isEqualTo("결과");
        assertThat(DreamAiTestAwait.await(gateway.generate(request()))).isEqualTo("결과");
    }

    @Test
    void asyncFailureAlsoReleasesSlot() {
        var pending = new CompletableFuture<AiGenerationResult>();
        var calls = new AtomicInteger();
        var gateway =
                new DreamAiGateway(
                        request ->
                                calls.incrementAndGet() == 1
                                        ? pending
                                        : CompletableFuture.completedFuture(result()),
                        true,
                        1);
        var first = gateway.generate(request());
        pending.completeExceptionally(new IllegalStateException("통신 실패"));
        assertThatThrownBy(() -> DreamAiTestAwait.await(first))
                .isInstanceOf(IllegalStateException.class);
        assertThat(DreamAiTestAwait.await(gateway.generate(request()))).isEqualTo("결과");
    }

    @Test
    void synchronousFailureAlsoReleasesSlot() {
        var calls = new AtomicInteger();
        var gateway =
                new DreamAiGateway(
                        request -> {
                            if (calls.incrementAndGet() == 1) throw new IllegalStateException();
                            return CompletableFuture.completedFuture(result());
                        },
                        true,
                        1);
        assertThatThrownBy(() -> gateway.generate(request()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(DreamAiTestAwait.await(gateway.generate(request()))).isEqualTo("결과");
    }

    @Test
    void missingKeyDoesNotCallProvider() {
        var gateway =
                new DreamAiGateway(
                        request -> {
                            throw new AssertionError("외부 호출 금지");
                        },
                        false,
                        1);
        assertThat(gateway.available()).isFalse();
        assertThatThrownBy(() -> gateway.generate(request()))
                .isInstanceOfSatisfying(
                        AiGatewayException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(AiGatewayErrorCode.AUTHENTICATION_FAILED));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 65})
    void invalidLimitIsRejected(int limit) {
        assertThatThrownBy(() -> new DreamAiProperties(limit))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new DreamAiGateway(
                                        request -> CompletableFuture.completedFuture(result()),
                                        true,
                                        limit))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AiGenerationRequest request() {
        return new AiGenerationRequest(
                1,
                AiTaskType.DREAM_STRUCTURE,
                "test-v1",
                List.of(new AiMessage(AiMessage.Role.USER, "입력")),
                null);
    }

    private AiGenerationResult result() {
        return new AiGenerationResult(
                "결과", null, AiTokenUsage.unknown(), AiFinishReason.unknown(), null, 0);
    }
}
