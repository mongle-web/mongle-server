package com.mongle.backend.domain.dream.story;

import com.mongle.backend.domain.dream.analysis.AnalysisTransactions;
import com.mongle.backend.domain.dream.analysis.StoredAnalysisRepository;
import com.mongle.backend.domain.dream.entity.DreamRecordStatus;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.dream.repository.DreamRepository;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoryTransactions {
    private final UserRepository users;
    private final DreamRepository dreams;
    private final StoredAnalysisRepository analyses;
    private final AnalysisTransactions analysisTransactions;
    private final DreamStoryRepository stories;
    private final StoryValidator validator;
    private final Clock authClock;

    public record Reservation(StoryResponse response, StoryGenerator.Input input) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation begin(Long userId, Long dreamId, StoryRequest request, boolean available) {
        lock(userId);

        var dream =
                dreams.findByIdAndUserId(dreamId, userId)
                        .orElseThrow(() -> new BusinessException(DreamErrorCode.NOT_FOUND));
        dream.checkRevision(request.revision());

        if (dream.getRecordStatus() != DreamRecordStatus.COMPLETED) {
            throw new BusinessException(DreamErrorCode.INVALID_STATE);
        }

        var analysis =
                analyses.findBySourceDreamIdAndUserId(dreamId, userId)
                        .orElseThrow(() -> new BusinessException(StoryErrorCode.ANALYSIS_REQUIRED));

        if (analysis.getStatus() != GenerationStatus.COMPLETED) {
            throw new BusinessException(StoryErrorCode.ANALYSIS_REQUIRED);
        }

        if (analysis.getObservedRevision() != dream.getSourceRevision()) {
            throw new BusinessException(StoryErrorCode.ANALYSIS_STALE);
        }

        var prior = stories.findByAnalysisIdAndUserId(analysis.getId(), userId);
        var now = authClock.instant();

        if (prior.isPresent() && prior.get().active(now)) {
            return new Reservation(response(prior.get()), null);
        }

        if (prior.isPresent()
                && prior.get().getStatus() == GenerationStatus.COMPLETED
                && !request.regenerate()) {
            return new Reservation(response(prior.get()), null);
        }

        if (request.regenerate()
                && (prior.isEmpty()
                        || request.storyVersion() == null
                        || request.storyVersion() != prior.get().getVersion())) {
            // 완료된 재생성 요청의 재전송은 새 AI 호출을 만들지 않는다.
            throw new BusinessException(StoryErrorCode.VERSION_CONFLICT);
        }

        if (!available) {
            throw new BusinessException(StoryErrorCode.UNAVAILABLE);
        }

        var context = analysisTransactions.response(analysis);
        var story = prior.orElseGet(() -> DreamStory.create(analysis));
        story.start(dream.getSourceRevision(), now, Duration.ofMinutes(2));
        stories.saveAndFlush(story);

        var input =
                new StoryGenerator.Input(
                        userId,
                        story.getId(),
                        analysis.getId(),
                        story.getAttemptId(),
                        story.getSourceRevision(),
                        dream.getOriginalText(),
                        dream.getEmotions(),
                        context.scenes(),
                        context.elements());

        return new Reservation(response(story), input);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StoryResponse finish(StoryGenerator.Input input, StoryResult result) {
        lock(input.userId());

        var story = owned(input.userId(), input.storyId());

        if (!story.accepts(input.attemptId())) {
            return response(story);
        }

        String failure = sourceFailure(story);

        if (failure != null) {
            story.fail(failure);
        } else if (!story.active(authClock.instant())) {
            story.fail("ATTEMPT_EXPIRED");
        } else {
            story.finish(validator.encode(result));
        }

        stories.flush();

        return response(story);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StoryResponse fail(StoryGenerator.Input input, String code) {
        lock(input.userId());

        var story = owned(input.userId(), input.storyId());

        if (!story.accepts(input.attemptId())) {
            return response(story);
        }

        String failure = sourceFailure(story);
        story.fail(failure == null ? code : failure);
        stories.flush();

        return response(story);
    }

    public StoryResponse get(Long userId, Long storyId) {
        return response(owned(userId, storyId));
    }

    public StoryResponse latest(Long userId, Long dreamId) {
        dreams.findByIdAndUserId(dreamId, userId)
                .orElseThrow(() -> new BusinessException(DreamErrorCode.NOT_FOUND));

        var analysis =
                analyses.findBySourceDreamIdAndUserId(dreamId, userId)
                        .orElseThrow(() -> new BusinessException(StoryErrorCode.NOT_FOUND));
        var story =
                stories.findByAnalysisIdAndUserId(analysis.getId(), userId)
                        .orElseThrow(() -> new BusinessException(StoryErrorCode.NOT_FOUND));

        return response(story);
    }

    private DreamStory owned(Long userId, Long storyId) {
        return stories.findByIdAndUserId(storyId, userId)
                .orElseThrow(() -> new BusinessException(StoryErrorCode.NOT_FOUND));
    }

    private void lock(Long userId) {
        var user =
                users.findByIdForUpdate(userId)
                        .orElseThrow(() -> new BusinessException(DreamErrorCode.NOT_FOUND));

        if (!user.isOnboardingCompleted()) {
            throw new BusinessException(UserErrorCode.ONBOARDING_REQUIRED);
        }
    }

    private String sourceFailure(DreamStory story) {
        var dream = story.getAnalysis().getDream();

        if (dream == null) {
            return "SOURCE_DELETED";
        }

        if (dream.getSourceRevision() != story.getSourceRevision()) {
            return "SOURCE_CHANGED";
        }

        return null;
    }

    private StoryResponse response(DreamStory story) {
        var analysis = story.getAnalysis();
        var dream = analysis.getDream();
        var sections =
                story.getResultJson() == null
                        ? List.<StoryResult.Section>of()
                        : validator.decode(story.getResultJson()).sections();
        long visibleRevision =
                story.getResultRevision() == null
                        ? story.getSourceRevision()
                        : story.getResultRevision();

        return new StoryResponse(
                story.getId(),
                analysis.getId(),
                dream == null ? null : dream.getId(),
                analysis.getDreamedAt(),
                story.getSourceRevision(),
                story.getVersion(),
                story.getStatus(),
                story.getFailureCode(),
                dream == null,
                dream != null && dream.getSourceRevision() != visibleRevision,
                story.getResultJson() != null && story.getStatus() != GenerationStatus.COMPLETED,
                story.getResultRevision(),
                story.getResultPromptVersion(),
                sections);
    }
}
