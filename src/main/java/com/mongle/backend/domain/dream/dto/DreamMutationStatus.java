package com.mongle.backend.domain.dream.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "꿈 수정·삭제 허용 여부의 조회 시점 스냅샷. 실제 변경 요청에서도 서버가 재검사합니다.")
public record DreamMutationStatus(boolean mutable, List<BlockingTask> blockingTasks) {
    public enum BlockingTask { ANALYSIS_STORY, IMAGE, WORLD_BRIDGE }

    public DreamMutationStatus {
        blockingTasks = List.copyOf(blockingTasks);
    }
}
