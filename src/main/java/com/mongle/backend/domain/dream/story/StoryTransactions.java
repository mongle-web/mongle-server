package com.mongle.backend.domain.dream.story;

import com.mongle.backend.domain.dream.analysis.AnalysisResultCodec;
import com.mongle.backend.domain.dream.analysis.AnalysisTransactions;
import com.mongle.backend.domain.dream.analysis.StoredAnalysisRepository;
import com.mongle.backend.domain.dream.entity.DreamRecordStatus;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.dream.gateway.DreamGenerationSettings;
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
    private final StoryResultVersionRepository versions;
    private final StoryValidator validator;
    private final Clock authClock;
    private final AnalysisResultCodec analysisCodec;
    private final DreamGenerationSettings settings;

    public record Reservation(StoryResponse response, StoryGenerator.Input input) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation begin(Long userId, Long dreamId, StoryRequest request, boolean available) {
        return reserve(userId, dreamId, request, null, available);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation beginForSource(
            Long userId, Long dreamId, long sourceRevision, boolean available) {
        return reserve(userId, dreamId, null, sourceRevision, available);
    }

    private Reservation reserve(
            Long userId,
            Long dreamId,
            StoryRequest request,
            Long expectedSource,
            boolean available) {
        lock(userId);

        var dream =
                dreams.findByIdAndUserId(dreamId, userId)
                        .orElseThrow(() -> new BusinessException(DreamErrorCode.NOT_FOUND));
        if (expectedSource != null) {
            if (dream.getSourceRevision() != expectedSource) {
                throw new BusinessException(DreamErrorCode.VERSION_CONFLICT);
            }
            request = new StoryRequest(dream.getRevision(), false, null);
        }
        dream.checkRevision(request.revision());

        if (dream.getRecordStatus() != DreamRecordStatus.COMPLETED) {
            throw new BusinessException(DreamErrorCode.INVALID_STATE);
        }

        var analysis =
                analyses.findBySourceDreamIdAndUserId(dreamId, userId)
                        .orElseThrow(() -> new BusinessException(StoryErrorCode.ANALYSIS_REQUIRED));

        if (expectedSource == null && analysis.isRegenerating()) {
            throw new BusinessException(DreamErrorCode.GENERATION_IN_PROGRESS);
        }

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
                && prior.get().getSourceRevision() == dream.getSourceRevision()
                && analysis.getPendingResultJson() == null
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

        var context = analysisTransactions.generationContext(analysis);
        var story = prior.orElseGet(() -> DreamStory.create(analysis));
        story.start(dream.getSourceRevision(), now, Duration.ofMinutes(2));
        story.recordSettings(settings.capture());
        if (analysis.getPendingResultJson() != null) {
            dream.changeAnalysisStatus(GenerationStatus.PROCESSING);
        }
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
            markPendingFailure(story);
        } else if (!story.active(authClock.instant())) {
            story.fail("ATTEMPT_EXPIRED");
            markPendingFailure(story);
        } else {
            var encoded = validator.encode(result);
            // 성공 결과와 버전을 같은 트랜잭션에서 확정한다. 실패/늦은 응답은 버전을 만들지 않는다.
            versions.saveAndFlush(
                    StoryResultVersion.capture(
                            story,
                            encoded,
                            input,
                            analysisCodec.encode(
                                    analysisTransactions.generationContext(story.getAnalysis()))));
            story.finish(encoded);
            analysisTransactions.publishPending(story.getAnalysis());
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
        markPendingFailure(story);
        stories.flush();

        return response(story);
    }

    private void markPendingFailure(DreamStory story) {
        var analysis = story.getAnalysis();
        if (analysis.getPendingResultJson() != null && analysis.getDream() != null) {
            // 분석 임시 결과는 서사만 재시도할 수 있도록 보존한다.
            analysis.getDream().changeAnalysisStatus(GenerationStatus.FAILED);
        }
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

    public StoryVersionPage versions(Long userId, Long storyId, Long before, int limit) {
        var story = owned(userId, storyId);
        if (limit < 1 || limit > 50 || (before != null && before <= 0)) {
            throw new BusinessException(StoryErrorCode.INVALID_VERSION_PAGE);
        }
        var rows =
                versions.findPage(
                        storyId,
                        before,
                        org.springframework.data.domain.PageRequest.of(0, limit + 1));
        boolean more = rows.size() > limit;
        var items =
                rows.stream()
                        .limit(limit)
                        .map(
                                v ->
                                        new StoryVersionPage.Item(
                                                v.getId(),
                                                v.getSourceRevision(),
                                                v.getPromptVersion(),
                                                v.getCreatedAt(),
                                                changed(story, v.getSourceRevision()),
                                                "legacy".equals(v.getGenerationKey())))
                        .toList();
        return new StoryVersionPage(items, more ? items.getLast().versionId() : null);
    }

    public StoryVersionResponse version(Long userId, Long storyId, Long versionId) {
        var story = owned(userId, storyId);
        var version =
                versions.findByIdAndStoryId(versionId, storyId)
                        .orElseThrow(() -> new BusinessException(StoryErrorCode.NOT_FOUND));
        var analysis = story.getAnalysis();
        var dream = analysis.getDream();
        return new StoryVersionResponse(
                version.getId(),
                storyId,
                analysis.getId(),
                dream == null ? null : dream.getId(),
                analysis.getDreamedAt(),
                version.getSourceRevision(),
                version.getPromptVersion(),
                version.getCreatedAt(),
                dream == null,
                changed(story, version.getSourceRevision()),
                "legacy".equals(version.getGenerationKey()),
                validator.decode(version.getResultJson()).sections(),
                version.getSourceText(),
                version.getSourceEmotions() == null
                        ? List.of()
                        : java.util.Arrays.stream(version.getSourceEmotions().split(","))
                                .filter(s -> !s.isEmpty())
                                .map(com.mongle.backend.domain.dream.entity.DreamEmotion::valueOf)
                                .toList(),
                version.getAnalysisPromptVersion(),
                version.getAnalysisJson() == null
                        ? null
                        : analysisCodec.decode(version.getAnalysisJson()),
                version.getSourceText() != null,
                settings.decode(version.getAnalysisSettings()),
                settings.decode(version.getStorySettings()));
    }

    private boolean changed(DreamStory story, long sourceRevision) {
        var dream = story.getAnalysis().getDream();
        return dream != null && dream.getSourceRevision() != sourceRevision;
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
                sections,
                versions
                        .findLatestId(
                                story.getId(), org.springframework.data.domain.PageRequest.of(0, 1))
                        .stream()
                        .findFirst()
                        .orElse(null));
    }
}
