package com.mongle.backend.domain.dream.interpretation;

import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InterpretationTransactions {
    private final UserRepository users;
    private final RecentInterpretationRepository interpretations;
    private final InterpretationTargets targets;
    private final Clock authClock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InterpretationResponse get(Long userId) {
        lock(userId);
        var row = interpretations.findByUserId(userId).orElse(null);
        if (row != null) sanitize(userId, row);
        return response(row, targets.current(userId));
    }

    /** DreamService와 동일한 사용자 잠금 안에서 호출한다. 결과/진행 중 출처를 함께 정리한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void dreamDeleted(Long userId, Long dreamId) {
        interpretations.findByUserId(userId).ifPresent(row -> {
            boolean resultAffected = targets.decodeSources(row.getResultSources()).stream()
                    .anyMatch(source -> source.dreamId().equals(dreamId));
            boolean attemptAffected = targets.decodeIds(row.getAttemptDreamIds()).contains(dreamId);
            if (resultAffected) row.discardResult();
            if (attemptAffected || (resultAffected && row.getStatus() != GenerationStatus.PROCESSING))
                row.fail("SOURCE_DELETED");
        });
    }

    private void sanitize(Long userId, RecentInterpretation row) {
        if (row.getBody() != null && !targets.allPresent(userId, targets.decodeSources(row.getResultSources()))) {
            row.discardResult();
            if (row.getStatus() != GenerationStatus.PROCESSING) row.fail("SOURCE_DELETED");
        }
        if (row.getStatus() == GenerationStatus.PROCESSING && !row.active(authClock.instant()))
            row.fail("ATTEMPT_EXPIRED");
    }

    private InterpretationResponse response(RecentInterpretation row, InterpretationTargets.Target target) {
        boolean empty = target.empty();
        var state = empty ? InterpretationResponse.State.EMPTY : row == null
                ? InterpretationResponse.State.NOT_GENERATED : row.getStatus() == GenerationStatus.PENDING
                ? InterpretationResponse.State.NOT_GENERATED
                : InterpretationResponse.State.valueOf(row.getStatus().name());
        return new InterpretationResponse(state, row == null || empty ? null : row.getFailureCode(),
                row == null || empty ? null : row.getBody(), row == null || empty ? null : row.getGeneratedAt(),
                row == null || empty ? null : row.getPromptVersion(),
                !empty && row != null && row.isEverCompleted() && !row.matches(target.fingerprint()),
                target.limitedEvidence(), target.from(), target.to(), target.sources().size(),
                row == null || empty ? List.of() : targets.decodeSources(row.getResultSources()));
    }

    private User lock(Long userId) {
        var user = users.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        if (!user.isOnboardingCompleted()) throw new BusinessException(UserErrorCode.ONBOARDING_REQUIRED);
        return user;
    }
}
