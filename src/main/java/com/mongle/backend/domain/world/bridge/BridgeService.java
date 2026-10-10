package com.mongle.backend.domain.world.bridge;

import com.mongle.backend.domain.dream.gateway.DreamGenerationResources;
import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

/** 세계관 오케스트레이션에서 명시적 생성·반영 요청 때 호출한다. 독립 공개 API는 제공하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class BridgeService {
    private final BridgeTransactions transactions;
    private final BridgeGenerator generator;
    private final BridgeValidator validator;
    private final DreamGenerationResources resources;

    public CompletableFuture<BridgeResponse> generate(Long userId, BridgeRequest request) {
        return launch(transactions.begin(userId, request, generator.available()));
    }

    public CompletableFuture<BridgeResponse> retry(Long userId, Long bridgeId, Long jobVersion) {
        return launch(transactions.retry(userId, bridgeId, jobVersion, generator.available()));
    }

    public BridgeResponse get(Long userId, Long bridgeId) { return transactions.get(userId, bridgeId); }

    private CompletableFuture<BridgeResponse> launch(BridgeTransactions.Reservation reservation) {
        if (reservation.input() == null) return CompletableFuture.completedFuture(reservation.response());
        var input = reservation.input();
        try {
            // 분석·서사와 같은 제한된 완료 풀을 사용한다. 외부 대기용 스레드나 무제한 큐는 만들지 않는다.
            return resources.execute(() -> generator.generate(input), (content, failure) -> complete(input, content, failure));
        } catch (RejectedExecutionException ex) {
            return CompletableFuture.completedFuture(transactions.fail(input, "CAPACITY_EXCEEDED"));
        }
    }

    private BridgeResponse complete(BridgeGenerator.Input input, String raw, Throwable failure) {
        String content;
        try {
            if (failure != null) throw new CompletionException(failure);
            content = validator.parse(raw);
        } catch (RuntimeException ex) {
            String code = ex instanceof BusinessException business && business.getErrorCode() == BridgeErrorCode.INVALID_OUTPUT
                    ? "INVALID_OUTPUT" : "CALL_FAILED";
            return transactions.fail(input, code);
        }
        try {
            return transactions.finish(input, content);
        } catch (BusinessException ex) {
            // 꿈 삭제로 결과 행도 제거됐다면 새 행을 만들거나 삭제한 본문을 보존하지 않는다.
            if (ex.getErrorCode() == BridgeErrorCode.NOT_FOUND) throw ex;
            return persistenceFailure(input, ex);
        } catch (RuntimeException ex) {
            return persistenceFailure(input, ex);
        }
    }

    private BridgeResponse persistenceFailure(BridgeGenerator.Input input, RuntimeException failure) {
        try { transactions.fail(input, "PERSISTENCE_FAILED"); }
        catch (RuntimeException recovery) { failure.addSuppressed(recovery); }
        throw new BusinessException(BridgeErrorCode.CALL_FAILED, failure);
    }
}
