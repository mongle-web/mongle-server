package com.mongle.backend.domain.ai.gateway.support;

import com.mongle.backend.domain.ai.gateway.AiGateway;
import com.mongle.backend.domain.ai.error.AiGatewayException;
import com.mongle.backend.domain.ai.dto.request.AiGenerationRequest;
import com.mongle.backend.domain.ai.dto.response.AiGenerationResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;

/**
 * 도메인 서비스 테스트에서 사용할, 순서대로 응답을 반환하는 가짜 Gateway.
 *
 * <p>테스트 클래스패스에만 존재하며 Spring Bean으로 등록하지 않는다. API 키, 네트워크,
 * DB와 무관하게 호출 내용을 확인하고 성공/실패를 재현할 수 있다. 각 테스트에서 새 객체를
 * 생성한다. 준비한 응답이 소진되면 테스트 설정 오류를 알리고 임의의 성공을 반환하지 않는다.</p>
 */
public final class StubAiGateway implements AiGateway {

    private final Deque<Supplier<AiGenerationResult>> outcomes = new ArrayDeque<>();
    private final List<AiGenerationRequest> receivedRequests = new ArrayList<>();

    public StubAiGateway enqueueResult(AiGenerationResult result) {
        Objects.requireNonNull(result, "테스트 응답은 필수입니다.");
        outcomes.addLast(() -> result);
        return this;
    }

    public StubAiGateway enqueueFailure(AiGatewayException failure) {
        Objects.requireNonNull(failure, "테스트 예외는 필수입니다.");
        outcomes.addLast(() -> {
            throw failure;
        });
        return this;
    }

    @Override
    public CompletableFuture<AiGenerationResult> generate(AiGenerationRequest request) {
        Objects.requireNonNull(request, "생성 요청은 필수입니다.");
        Supplier<AiGenerationResult> outcome = outcomes.pollFirst();
        if (outcome == null) {
            throw new IllegalStateException("테스트용 Gateway에 준비된 응답이나 예외가 없습니다.");
        }
        receivedRequests.add(request);
        try {
            return CompletableFuture.completedFuture(outcome.get());
        } catch (AiGatewayException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** 호출 목록도 불변 복사본으로 반환하여 테스트 단언이 스텁 내부를 바꾸지 않도록 한다. */
    public List<AiGenerationRequest> receivedRequests() {
        return List.copyOf(receivedRequests);
    }
}
