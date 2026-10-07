package com.mongle.backend.domain.dream.dto;

import com.mongle.backend.domain.dream.entity.*;
import com.mongle.backend.global.common.GenerationStatus;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record DreamResponse(
        Long dreamId,
        LocalDate dreamedAt,
        String originalText,
        String title,
        List<DreamEmotion> emotions,
        DreamRecordStatus recordStatus,
        GenerationStatus analysisStatus,
        boolean edited,
        @Schema(description = "수정 충돌 검사용 버전. 다음 수정·분석·서사화 요청의 revision에 전달") long revision,
        @Schema(description = "AI 입력 버전. 원문·최초 감정 선택 변경 시 증가, 제목만 변경하면 유지") long sourceRevision,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
    public static DreamResponse from(Dream dream) {
        return new DreamResponse(
                dream.getId(),
                dream.getDreamedAt(),
                dream.getOriginalText(),
                dream.getTitle(),
                dream.getEmotions().stream().sorted().toList(),
                dream.getRecordStatus(),
                dream.getAnalysisStatus(),
                dream.isEdited(),
                dream.getRevision(),
                dream.getSourceRevision(),
                dream.getCreatedAt(),
                dream.getUpdatedAt());
    }
}
