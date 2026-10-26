package com.mongle.backend.domain.dream.generation;

import com.mongle.backend.domain.dream.analysis.StoredAnalysisRepository;
import com.mongle.backend.domain.dream.dto.DreamMutationStatus;
import com.mongle.backend.domain.dream.dto.DreamMutationStatus.BlockingTask;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.dream.image.DreamImageRepository;
import com.mongle.backend.domain.dream.story.DreamStoryRepository;
import com.mongle.backend.domain.world.bridge.BridgeResultRepository;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;

/** 변경/예약 시에는 사용자 행 잠금 안에서 검사한다. 조회는 변경을 보장하는 잠금이 아니다. */
@Component
@RequiredArgsConstructor
public class DreamGenerationGuard {
    private final DreamGenerationJobRepository jobs;
    private final StoredAnalysisRepository analyses;
    private final DreamStoryRepository stories;
    private final DreamImageRepository images;
    private final BridgeResultRepository bridges;
    private final Clock authClock;
    private final com.mongle.backend.domain.world.composition.WorldCompositionRepository compositions;

    public DreamMutationStatus status(Long userId, Long dreamId) {
        var now = authClock.instant();
        var reasons = new ArrayList<BlockingTask>();
        if (narrativeBusy(userId, dreamId, now, false)) reasons.add(BlockingTask.ANALYSIS_STORY);
        if (images.existsByUserIdAndAnalysis_Dream_IdAndStatusAndLeaseUntilAfter(
                userId, dreamId, GenerationStatus.PROCESSING, now)) reasons.add(BlockingTask.IMAGE);
        if (bridges.hasActiveForDream(userId, dreamId, GenerationStatus.PROCESSING, now))
            reasons.add(BlockingTask.WORLD_BRIDGE);
        if (worldBusy(userId,dreamId,now)) reasons.add(BlockingTask.WORLD_COMPOSITION);
        return new DreamMutationStatus(reasons.isEmpty(), reasons);
    }

    public void requireMutable(Long userId, Long dreamId) {
        reject(!status(userId, dreamId).mutable());
    }

    /** 분석·서사 생성이 이미지/브리지의 입력을 교체하지 않도록 신규 작업에만 적용한다. */
    public void requireNoDerivedWork(Long userId, Long dreamId) {
        var now = authClock.instant();
        reject(images.existsByUserIdAndAnalysis_Dream_IdAndStatusAndLeaseUntilAfter(
                        userId, dreamId, GenerationStatus.PROCESSING, now)
                || bridges.hasActiveForDream(userId, dreamId, GenerationStatus.PROCESSING, now)
                || worldBusy(userId,dreamId,now));
    }

    /** 기존 결과/진행 요청 재사용을 먼저 처리한 뒤 신규 파생 작업 예약 직전에 호출한다. */
    public void requireNarrativeAvailable(Long userId, Long dreamId) {
        reject(narrativeBusy(userId, dreamId, authClock.instant(), true));
    }

    private boolean narrativeBusy(Long userId, Long dreamId, Instant now, boolean includeInitial) {
        var job = jobs.findByDreamIdAndUserId(dreamId, userId).orElse(null);
        if (job != null && job.isRegeneration() && job.unfinished()) return true;
        var analysis = analyses.findBySourceDreamIdAndUserId(dreamId, userId).orElse(null);
        if (analysis == null) return false;
        var story = stories.findByAnalysisIdAndUserId(analysis.getId(), userId).orElse(null);
        return ((includeInitial || analysis.isRegenerating()) && analysis.active(now))
                || (story != null
                        && (includeInitial || analysis.isRegenerating() || story.getResultJson() != null)
                        && story.active(now));
    }

    private boolean worldBusy(Long user,Long dream,Instant now) {
        return compositions.hasActiveSource(user,dream,java.util.List.of(GenerationStatus.PENDING,GenerationStatus.PROCESSING),now);
    }

    private static void reject(boolean busy) {
        if (busy) throw new BusinessException(DreamErrorCode.GENERATION_IN_PROGRESS);
    }
}
