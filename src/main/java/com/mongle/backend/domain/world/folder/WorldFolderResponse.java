package com.mongle.backend.domain.world.folder;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record WorldFolderResponse(Long worldId, String name,
        @Schema(description = "현재 선택 수. 완성 세계관에 반영된 꿈의 개수와는 별개입니다.") long selectedDreamCount,
        @Schema(description = "이름·선택 목록이 실제 변경될 때 증가합니다.") long revision,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static WorldFolderResponse from(WorldFolder folder) {
        return new WorldFolderResponse(folder.getId(), folder.getName(), folder.getSelectedDreamCount(),
                folder.getRevision(), folder.getCreatedAt(), folder.getUpdatedAt());
    }
}
