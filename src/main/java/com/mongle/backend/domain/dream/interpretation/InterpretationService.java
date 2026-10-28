package com.mongle.backend.domain.dream.interpretation;

import com.mongle.backend.domain.dream.gateway.DreamGenerationResources;
import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.concurrent.*;

@Service @RequiredArgsConstructor
public class InterpretationService {
    private final InterpretationTransactions transactions;
    private final InterpretationGenerator generator;
    private final InterpretationValidator validator;
    private final DreamGenerationResources resources;
    @Value("${mongle.analysis.interpretation.enabled:false}") private boolean enabled;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CompletableFuture<InterpretationResponse> ensure(Long userId) {
        return launch(transactions.begin(userId, enabled && generator.available()));
    }

    private CompletableFuture<InterpretationResponse> launch(InterpretationTransactions.Reservation reservation) {
        if (reservation.input() == null) return CompletableFuture.completedFuture(reservation.response());
        var input = reservation.input();
        try {
            return resources.execute(() -> generator.generate(input), (content, failure) -> complete(input, content, failure));
        } catch (RejectedExecutionException ex) {
            return CompletableFuture.completedFuture(transactions.fail(input, "CALL_FAILED"));
        }
    }

    private InterpretationResponse complete(InterpretationGenerator.Input input, String content, Throwable failure) {
        String body;
        try {
            if (failure != null) return transactions.fail(input, "CALL_FAILED");
            body = validator.parse(content);
        } catch (BusinessException ex) { return transactions.fail(input, "INVALID_OUTPUT"); }
        try { return transactions.finish(input, body); }
        catch (RuntimeException ex) {
            // 실패한 트랜잭션은 재사용하지 않고 별도 트랜잭션으로 실패를 기록한다.
            transactions.fail(input, "PERSISTENCE_FAILED");
            throw new BusinessException(InterpretationErrorCode.CALL_FAILED);
        }
    }
}
