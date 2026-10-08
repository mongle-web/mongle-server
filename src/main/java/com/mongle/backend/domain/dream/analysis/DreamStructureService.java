package com.mongle.backend.domain.dream.analysis;

import com.mongle.backend.domain.dream.gateway.DreamGenerationResources;
import com.mongle.backend.global.error.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

@Service
@RequiredArgsConstructor
public class DreamStructureService {
    private final AnalysisTransactions transactions;
    private final StructureGenerator generator;
    private final StructureValidator validator;
    private final DreamGenerationResources resources;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CompletableFuture<AnalysisResponse> analyze(Long userId, Long dreamId, Long revision) {
        var reserved = transactions.begin(userId, dreamId, revision, generator.available());
        if (reserved.input() == null) return CompletableFuture.completedFuture(reserved.response());
        try {
            return resources.execute(
                    () -> generator.generate(reserved.input()),
                    (content, failure) -> complete(reserved.input(), content, failure));
        } catch (RejectedExecutionException ex) {
            // 受付スレッドで失敗状態だけ記録する。外部呼び出しも待機も行わない。
            return CompletableFuture.completedFuture(
                    transactions.fail(reserved.input(), "CALL_FAILED"));
        }
    }

    private AnalysisResponse complete(
            StructureGenerator.Input input, String content, Throwable failure) {
        StructureResult output;
        try {
            if (failure != null) throw new CompletionException(failure);
            output = validator.parse(content);
        } catch (RuntimeException ex) {
            String code =
                    ex instanceof BusinessException b
                                    && b.getErrorCode() == AnalysisErrorCode.INVALID_OUTPUT
                            ? "INVALID_OUTPUT"
                            : "CALL_FAILED";
            return transactions.fail(input, code);
        }
        // 저장 실패도 별도 트랜잭션에서 상태를 기록한다. 결과 전체는 finish 트랜잭션에서 롤백된다.
        try {
            return transactions.finish(input, output);
        } catch (RuntimeException ex) {
            try {
                transactions.fail(input, "PERSISTENCE_FAILED");
            } catch (RuntimeException recoveryFailure) {
                ex.addSuppressed(recoveryFailure);
            }
            throw new BusinessException(AnalysisErrorCode.CALL_FAILED, ex);
        }
    }
}
