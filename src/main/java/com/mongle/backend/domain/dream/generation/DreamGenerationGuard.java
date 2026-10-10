package com.mongle.backend.domain.dream.generation;

import com.mongle.backend.domain.dream.analysis.StoredAnalysisRepository;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.dream.story.DreamStoryRepository;
import com.mongle.backend.global.error.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.time.Clock;

/** 사용자 행 잠금 획득 후 호출한다. 예약부터 완료/실패까지 같은 꿈의 변경을 막는다. */
@Component
@RequiredArgsConstructor
public class DreamGenerationGuard {
    private final DreamGenerationJobRepository jobs;
    private final StoredAnalysisRepository analyses;
    private final DreamStoryRepository stories;
    private final Clock authClock;

    public void requireMutable(Long userId, Long dreamId) {
        var job = jobs.findByDreamIdAndUserId(dreamId, userId).orElse(null);
        if (job != null && job.isRegeneration() && job.unfinished()) {
            throw new BusinessException(DreamErrorCode.GENERATION_IN_PROGRESS);
        }
        var analysis = analyses.findBySourceDreamIdAndUserId(dreamId, userId).orElse(null);
        if (analysis != null) {
            var story = stories.findByAnalysisIdAndUserId(analysis.getId(), userId).orElse(null);
            if ((analysis.isRegenerating() && analysis.active(authClock.instant()))
                    || (story != null
                            && (analysis.isRegenerating() || story.getResultJson() != null)
                            && story.active(authClock.instant()))) {
                throw new BusinessException(DreamErrorCode.GENERATION_IN_PROGRESS);
            }
        }
    }
}
