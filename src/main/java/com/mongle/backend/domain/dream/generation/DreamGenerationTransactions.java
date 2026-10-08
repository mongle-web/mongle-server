package com.mongle.backend.domain.dream.generation;

import com.mongle.backend.domain.dream.analysis.StoredAnalysisRepository;
import com.mongle.backend.domain.dream.entity.Dream;
import com.mongle.backend.domain.dream.entity.DreamRecordStatus;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.dream.repository.DreamRepository;
import com.mongle.backend.domain.dream.story.DreamStoryRepository;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DreamGenerationTransactions {
    private final DreamGenerationJobRepository jobs;
    private final UserRepository users;
    private final DreamRepository dreams;
    private final StoredAnalysisRepository analyses;
    private final DreamStoryRepository stories;
    private final Clock authClock;

    public record Candidate(Long id, Long userId) {}

    public record Claim(
            Long id,
            Long userId,
            Long dreamId,
            long sourceRevision,
            DreamGenerationJob.Stage stage,
            String token,
            boolean recovering) {}

    public record Progress(GenerationStatus status, String failureCode, Instant leaseUntil) {}

    public List<Candidate> candidates() {
        return jobs.due(
                        authClock.instant(),
                        DreamGenerationJob.Status.QUEUED,
                        DreamGenerationJob.Status.PROCESSING,
                        PageRequest.of(0, 32))
                .stream()
                .map(job -> new Candidate(job.getId(), job.getUserId()))
                .toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Claim> claim(Candidate candidate) {
        // 모든 꿈 작업과 동일하게 사용자 → 작업 순서로 잠근다. 외부 호출 전 반환한다.
        if (users.findByIdForUpdate(candidate.userId()).isEmpty()) {
            return Optional.empty();
        }
        var job = jobs.locked(candidate.id()).orElse(null);
        var now = authClock.instant();
        if (job == null || !job.due(now)) {
            return Optional.empty();
        }
        if (jobs.userBusy(
                job.getUserId(), job.getId(), now, DreamGenerationJob.Status.PROCESSING)) {
            boolean recovering =
                    job.isRecovering() || job.getStatus() == DreamGenerationJob.Status.PROCESSING;
            job.queue(now.plusSeconds(2), recovering);
            return Optional.empty();
        }
        var dream = dreams.findByIdAndUserId(job.getDreamId(), job.getUserId()).orElse(null);
        if (dream == null
                || dream.getSourceRevision() != job.getSourceRevision()
                || dream.getRecordStatus() != DreamRecordStatus.COMPLETED) {
            job.fail(dream == null ? "SOURCE_DELETED" : "SOURCE_CHANGED");
            return Optional.empty();
        }
        job.claim(now);
        return Optional.of(
                new Claim(
                        job.getId(), job.getUserId(), job.getDreamId(), job.getSourceRevision(),
                        job.getStage(), job.getClaimToken(), job.isRecovering()));
    }

    public Progress progress(Claim claim) {
        var analysis =
                analyses.findBySourceDreamIdAndUserId(claim.dreamId(), claim.userId()).orElse(null);
        if (analysis == null) {
            return null;
        }
        if (analysis.getObservedRevision() != claim.sourceRevision()) {
            return new Progress(GenerationStatus.FAILED, "SOURCE_CHANGED", null);
        }
        if (claim.stage() == DreamGenerationJob.Stage.ANALYSIS) {
            return new Progress(
                    analysis.getStatus(), analysis.getFailureCode(), analysis.getLeaseUntil());
        }
        var story = stories.findByAnalysisIdAndUserId(analysis.getId(), claim.userId()).orElse(null);
        if (story == null) {
            return null;
        }
        if (story.getSourceRevision() != claim.sourceRevision()) {
            return new Progress(GenerationStatus.FAILED, "SOURCE_CHANGED", null);
        }
        return new Progress(story.getStatus(), story.getFailureCode(), story.getLeaseUntil());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void result(Claim claim, GenerationStatus status, String code) {
        users.findByIdForUpdate(claim.userId());
        var job = jobs.locked(claim.id()).orElse(null);
        if (job == null || !job.accepts(claim.token())) {
            return;
        }
        var dream = dreams.findByIdAndUserId(claim.dreamId(), claim.userId()).orElse(null);
        if (dream == null || dream.getSourceRevision() != claim.sourceRevision()) {
            job.fail(dream == null ? "SOURCE_DELETED" : "SOURCE_CHANGED");
            return;
        }
        if (status == GenerationStatus.COMPLETED) {
            job.succeed(authClock.instant());
        } else if (status == GenerationStatus.PROCESSING) {
            job.queue(authClock.instant().plusSeconds(2), true);
        } else {
            job.fail(code == null ? "CALL_FAILED" : code);
        }
    }

    public DreamGenerationResponse get(Long userId, Long dreamId) {
        var dream = owned(userId, dreamId);
        return response(job(userId, dreamId), dream);
    }

    @Transactional
    public DreamGenerationResponse retry(Long userId, Long dreamId, Long revision) {
        var user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(DreamErrorCode.NOT_FOUND));
        if (!user.isOnboardingCompleted()) {
            throw new BusinessException(UserErrorCode.ONBOARDING_REQUIRED);
        }
        var dream = owned(userId, dreamId);
        dream.checkRevision(revision);
        var job = job(userId, dreamId);
        if (job.getSourceRevision() != dream.getSourceRevision()) {
            throw new BusinessException(DreamErrorCode.VERSION_CONFLICT);
        }
        if (job.getStatus() == DreamGenerationJob.Status.FAILED) {
            job.queue(authClock.instant(), false);
        }
        return response(job, dream);
    }

    private DreamGenerationResponse response(DreamGenerationJob job, Dream dream) {
        var analysis =
                analyses.findBySourceDreamIdAndUserId(dream.getId(), job.getUserId()).orElse(null);
        var story = analysis == null ? null
                : stories.findByAnalysisIdAndUserId(analysis.getId(), job.getUserId()).orElse(null);
        return new DreamGenerationResponse(
                job.getDreamId(), job.getSourceRevision(),
                job.getSourceRevision() != dream.getSourceRevision(), job.getStage(), job.getStatus(),
                job.getFailureCode(), analysis == null ? null : analysis.getId(),
                story == null ? null : story.getId());
    }

    private Dream owned(Long userId, Long dreamId) {
        return dreams.findByIdAndUserId(dreamId, userId)
                .orElseThrow(() -> new BusinessException(DreamErrorCode.NOT_FOUND));
    }

    private DreamGenerationJob job(Long userId, Long dreamId) {
        return jobs.findByDreamIdAndUserId(dreamId, userId)
                .orElseThrow(() -> new BusinessException(DreamErrorCode.NOT_FOUND));
    }
}
