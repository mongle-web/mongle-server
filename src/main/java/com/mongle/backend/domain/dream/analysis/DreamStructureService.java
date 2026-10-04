package com.mongle.backend.domain.dream.analysis;

import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class DreamStructureService {
    private final AnalysisTransactions transactions;
    private final StructureGenerator generator;
    private final StructureValidator validator;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AnalysisResponse analyze(Long userId, Long dreamId, Long revision) {
        var reserved = transactions.begin(userId, dreamId, revision, generator.available());
        if (reserved.input() == null) return reserved.response();
        StructureResult output;
        try {
            output = validator.parse(generator.generate(reserved.input()));
        } catch (RuntimeException ex) {
            String code =
                    ex instanceof BusinessException b
                                    && b.getErrorCode() == AnalysisErrorCode.INVALID_OUTPUT
                            ? "INVALID_OUTPUT"
                            : "CALL_FAILED";
            return transactions.fail(reserved.input(), code);
        }
        // 저장 실패도 별도 트랜잭션에서 상태를 기록한다. 결과 전체는 finish 트랜잭션에서 롤백된다.
        try {
            return transactions.finish(reserved.input(), output);
        } catch (RuntimeException ex) {
            transactions.fail(reserved.input(), "PERSISTENCE_FAILED");
            throw new BusinessException(AnalysisErrorCode.CALL_FAILED);
        }
    }
}
