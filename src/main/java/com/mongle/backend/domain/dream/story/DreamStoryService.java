package com.mongle.backend.domain.dream.story;

import com.mongle.backend.domain.dream.gateway.DreamGenerationResources;
import com.mongle.backend.global.error.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

@Service
@RequiredArgsConstructor
public class DreamStoryService {

    private final StoryTransactions transactions;
    private final StoryGenerator generator;
    private final StoryValidator validator;
    private final DreamGenerationResources resources;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CompletableFuture<StoryResponse> generate(
            Long userId, Long dreamId, StoryRequest request) {
        var reservation = transactions.begin(userId, dreamId, request, generator.available());

        if (reservation.input() == null) {
            return CompletableFuture.completedFuture(reservation.response());
        }

        var input = reservation.input();
        try {
            return resources.execute(
                    () -> generator.generate(input),
                    (content, failure) -> complete(input, content, failure));
        } catch (RejectedExecutionException ex) {
            return CompletableFuture.completedFuture(transactions.fail(input, "CALL_FAILED"));
        }
    }

    private StoryResponse complete(StoryGenerator.Input input, String content, Throwable failure) {
        StoryResult result;

        try {
            if (failure != null) throw new CompletionException(failure);
            result = validator.parse(content, input.scenes());
        } catch (RuntimeException ex) {
            String code =
                    ex instanceof BusinessException business
                                    && business.getErrorCode() == StoryErrorCode.INVALID_OUTPUT
                            ? "INVALID_OUTPUT"
                            : "CALL_FAILED";

            return transactions.fail(input, code);
        }

        try {
            return transactions.finish(input, result);
        } catch (RuntimeException ex) {
            // finish 전체 롤백 후 실패 상태만 별도 트랜잭션으로 기록한다.
            try {
                transactions.fail(input, "PERSISTENCE_FAILED");
            } catch (RuntimeException recoveryFailure) {
                ex.addSuppressed(recoveryFailure);
            }
            throw new BusinessException(StoryErrorCode.CALL_FAILED, ex);
        }
    }
}
