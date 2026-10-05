package com.mongle.backend.domain.dream.story;

import com.mongle.backend.global.error.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DreamStoryService {

    private final StoryTransactions transactions;
    private final StoryGenerator generator;
    private final StoryValidator validator;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public StoryResponse generate(Long userId, Long dreamId, StoryRequest request) {
        var reservation = transactions.begin(userId, dreamId, request, generator.available());

        if (reservation.input() == null) {
            return reservation.response();
        }

        var input = reservation.input();
        StoryResult result;

        try {
            result = validator.parse(generator.generate(input), input.scenes());
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
            transactions.fail(input, "PERSISTENCE_FAILED");
            throw new BusinessException(StoryErrorCode.CALL_FAILED);
        }
    }
}
