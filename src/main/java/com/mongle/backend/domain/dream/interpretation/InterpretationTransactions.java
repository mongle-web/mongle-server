package com.mongle.backend.domain.dream.interpretation;

import com.mongle.backend.domain.dream.gateway.DreamGenerationSettings;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.domain.user.repository.UserRepository;
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
    private final DreamGenerationSettings settings;

    public record Reservation(
            InterpretationResponse response, InterpretationGenerator.Input input) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InterpretationResponse get(Long userId) {
        lock(userId);
        var row = interpretations.findByUserId(userId).orElse(null);
        if (row != null) sanitize(userId, row);
        return response(row, targets.current(userId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation begin(Long userId, boolean available, boolean refresh) {
        var user = lock(userId);
        var row = interpretations.findByUserId(userId).orElse(null);
        if (row != null) sanitize(userId, row);
        var target = targets.current(userId);
        if (target.empty()
                || (row != null
                        && (row.active(authClock.instant())
                                || row.matches(target.fingerprint())
                                || (!refresh && row.isEverCompleted()))))
            return new Reservation(response(row, target), null);
        if (!available) throw new BusinessException(InterpretationErrorCode.UNAVAILABLE);
        if (target.evidenceJson().length() > 120000)
            throw new BusinessException(InterpretationErrorCode.CONTEXT_TOO_LARGE);
        if (row == null) row = RecentInterpretation.create(user);
        row.start(
                target.fingerprint(),
                targets.encodeIds(target.dreamIds()),
                settings.capture(),
                authClock.instant());
        interpretations.saveAndFlush(row);
        return new Reservation(
                response(row, target),
                new InterpretationGenerator.Input(userId, row.getAttemptId(), target));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InterpretationResponse finish(InterpretationGenerator.Input input, String body) {
        lock(input.userId());
        var row = interpretations.findByUserId(input.userId()).orElseThrow();
        sanitize(input.userId(), row);
        var target = targets.current(input.userId());
        if (row.accepts(input.attemptId())) {
            if (!row.active(authClock.instant())) row.fail("ATTEMPT_EXPIRED");
            else if (!target.fingerprint().equals(input.target().fingerprint()))
                row.fail("SOURCE_CHANGED");
            else
                row.finish(
                        body, targets.encodeSources(input.target().sources()), authClock.instant());
        }
        return response(row, target);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InterpretationResponse fail(InterpretationGenerator.Input input, String code) {
        lock(input.userId());
        var row = interpretations.findByUserId(input.userId()).orElseThrow();
        sanitize(input.userId(), row);
        if (row.accepts(input.attemptId())) row.fail(code);
        return response(row, targets.current(input.userId()));
    }

    /** DreamService와 동일한 사용자 잠금 안에서 호출한다. 결과/진행 중 출처를 함께 정리한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void dreamDeleted(Long userId, Long dreamId) {
        interpretations
                .findByUserId(userId)
                .ifPresent(
                        row -> {
                            boolean resultAffected =
                                    targets.decodeSources(row.getResultSources()).stream()
                                            .anyMatch(source -> source.dreamId().equals(dreamId));
                            boolean attemptAffected =
                                    targets.decodeIds(row.getAttemptDreamIds()).contains(dreamId);
                            if (resultAffected) row.discardResult();
                            if (attemptAffected
                                    || (resultAffected
                                            && row.getStatus() != GenerationStatus.PROCESSING))
                                row.fail("SOURCE_DELETED");
                        });
    }

    private void sanitize(Long userId, RecentInterpretation row) {
        if (row.getBody() != null
                && !targets.allPresent(userId, targets.decodeSources(row.getResultSources()))) {
            row.discardResult();
            if (row.getStatus() != GenerationStatus.PROCESSING) row.fail("SOURCE_DELETED");
        }
        if (row.getStatus() == GenerationStatus.PROCESSING && !row.active(authClock.instant()))
            row.fail("ATTEMPT_EXPIRED");
    }

    private InterpretationResponse response(
            RecentInterpretation row, InterpretationTargets.Target target) {
        boolean empty = target.empty();
        var state = InterpretationResponse.State.NOT_GENERATED;
        if (empty) state = InterpretationResponse.State.EMPTY;
        else if (row != null && row.getStatus() != GenerationStatus.PENDING)
            state = InterpretationResponse.State.valueOf(row.getStatus().name());
        boolean hasBody = !empty && row != null && row.getBody() != null;
        var sources =
                hasBody
                        ? targets.decodeSources(row.getResultSources())
                        : List.<InterpretationTargets.Source>of();
        return new InterpretationResponse(
                state,
                row == null || empty ? null : row.getFailureCode(),
                hasBody ? row.getBody() : null,
                hasBody ? row.getGeneratedAt() : null,
                hasBody ? row.getPromptVersion() : null,
                !empty
                        && row != null
                        && row.isEverCompleted()
                        && !row.matches(target.fingerprint()),
                hasBody ? sources.size() < 3 : target.limitedEvidence(),
                target.from(),
                target.to(),
                target.sources().size(),
                sources);
    }

    private User lock(Long userId) {
        var user =
                users.findByIdForUpdate(userId)
                        .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        if (!user.isOnboardingCompleted())
            throw new BusinessException(UserErrorCode.ONBOARDING_REQUIRED);
        return user;
    }
}
