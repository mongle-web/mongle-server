package com.mongle.backend.domain.world.folder;

import com.mongle.backend.domain.world.summary.WorldSummaryResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 기존 폴더 필드는 유지하며 목록 카드에 필요한 공개 완성본 정보를 더한다. */
public record WorldFolderCardResponse(
        Long worldId, String name, long selectedDreamCount, long revision,
        LocalDateTime createdAt, LocalDateTime updatedAt,
        @Schema(description = "현재 공개 완성본의 서로 다른 꿈 수. 선택 수나 summary.sourceCount와 별개")
        long composedDreamCount,
        @Schema(description = "공개 완성본에서 가장 오래된 꿈. 현재 제목과 꿈 날짜이며 완성본 출처가 없으면 null")
        @Nullable RepresentativeDream representativeDream,
        @Schema(description = "기존 세계관 요약의 저장 결과와 상태. 목록 조회는 새 생성을 접수하지 않음")
        WorldSummaryResponse summary) {

    public record RepresentativeDream(Long dreamId, @Nullable String title, LocalDate dreamedAt) {}

    static WorldFolderCardResponse from(WorldFolder folder, long count,
                                        RepresentativeDream representative, WorldSummaryResponse summary) {
        return new WorldFolderCardResponse(folder.getId(), folder.getName(), folder.getSelectedDreamCount(),
                folder.getRevision(), folder.getCreatedAt(), folder.getUpdatedAt(), count, representative, summary);
    }
}
