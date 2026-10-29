package com.mongle.backend.domain.world.summary;

import com.mongle.backend.domain.dream.gateway.DreamGenerationSettings;
import com.mongle.backend.domain.world.composition.WorldComposition;
import com.mongle.backend.global.common.GenerationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;

/** 상세와 목록이 같은 결과·변경 안내를 반환하도록 공유하는 읽기 전용 변환이다. */
@Component
@RequiredArgsConstructor
public class WorldSummaryResponses {
    private final DreamGenerationSettings settings;
    private final Clock authClock;
    private final JsonMapper json = JsonMapper.builder().build();

    WorldSummaryResponse from(Long world, WorldComposition composition, WorldSummary summary,
                              WorldSummaryQueries.Stats stats, boolean sourcesMissing) {
        long revision = composition == null ? 0 : composition.getCompositionRevision();
        var result = summary == null || summary.getResultJson() == null || sourcesMissing
                ? null : json.readValue(summary.getResultJson(), WorldSummaryContent.class);
        boolean empty = composition != null && composition.isHasResult() && stats.count() == 0;
        boolean expired = summary != null && summary.getStatus() == GenerationStatus.PROCESSING
                && !summary.active(authClock.instant());
        var status = empty ? WorldSummaryResponse.Status.EMPTY
                : summary == null ? WorldSummaryResponse.Status.NOT_GENERATED
                : expired ? WorldSummaryResponse.Status.FAILED
                : WorldSummaryResponse.Status.valueOf(summary.getStatus().name());
        boolean refresh = !empty && composition != null && composition.isHasResult()
                && (result == null || !summary.matches(revision, settings.capture())
                        || stats.sourceChanged() || stats.count() != stats.validCount());
        return new WorldSummaryResponse(world, revision,
                summary == null || sourcesMissing ? null : summary.getResultRevision(),
                summary == null ? 0 : summary.getVersion(), status,
                expired ? "ATTEMPT_EXPIRED" : summary == null ? null : summary.getFailureCode(),
                result == null ? null : result.generatedTitle(),
                result == null ? List.of() : result.commonKeywords(),
                result == null ? List.of() : result.recurringEmotions(),
                result == null ? 0 : result.sourceCount(), result == null || result.limitedEvidence(),
                stats.sourceChanged(), refresh,
                result != null && (status != WorldSummaryResponse.Status.COMPLETED || refresh),
                summary == null || sourcesMissing ? null : summary.getGeneratedAt(),
                summary == null || sourcesMissing ? null : summary.getPromptVersion());
    }
}
