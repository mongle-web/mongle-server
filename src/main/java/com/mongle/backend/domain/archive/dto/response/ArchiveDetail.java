package com.mongle.backend.domain.archive.dto.response;

import com.mongle.backend.domain.dream.entity.DreamEmotion;
import java.time.LocalDateTime;
import java.util.List;

/** 나의 꿈 기록 화면. 본문은 AI 이야기 대신 사용자가 작성한 원문이며 감정은 기존 7종이다. */
public record ArchiveDetail(ArchiveItem dream, String originalText, List<DreamEmotion> emotions,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {
    public ArchiveDetail {
        emotions = List.copyOf(emotions);
    }
}
