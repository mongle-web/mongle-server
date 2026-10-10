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
        @Schema(description = "분석에서 생성한 순서 있는 표시 키워드. 이미지 불필요, 기존 분석은 빈 목록")
                List<String> displayKeywords,
        @Schema(description = "완료 분석의 입력과 현재 원문·감정이 다르면 true. 기존 키워드는 유지")
                boolean analysisSourceChanged,
        List<DreamEmotion> emotions,
        DreamRecordStatus recordStatus,
        GenerationStatus analysisStatus,
        boolean edited,
        @Schema(description = "수정 충돌 검사용 버전. 다음 수정·분석·서사화 요청의 revision에 전달") long revision,
        @Schema(description = "AI 입력 버전. 원문·감정이 실제로 변경되면 증가, 제목만 변경하면 유지") long sourceRevision,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long analysisResultRevision,
        com.mongle.backend.domain.dream.generation.DreamGenerationResponse generation) {

    public DreamResponse withGeneration(Long resultRevision,
            com.mongle.backend.domain.dream.generation.DreamGenerationResponse generation) {
        return new DreamResponse(dreamId, dreamedAt, originalText, title, displayKeywords,
                analysisSourceChanged, emotions, recordStatus, analysisStatus, edited, revision,
                sourceRevision, createdAt, updatedAt, resultRevision, generation);
    }
    public static DreamResponse from(Dream dream) {
        return from(dream, List.of(), false);
    }

    public static DreamResponse from(Dream dream, List<String> keywords, boolean sourceChanged) {
        return new DreamResponse(
                dream.getId(),
                dream.getDreamedAt(),
                dream.getOriginalText(),
                dream.getTitle(),
                List.copyOf(keywords),
                sourceChanged,
                dream.getEmotions().stream().sorted().toList(),
                dream.getRecordStatus(),
                dream.getAnalysisStatus(),
                dream.isEdited(),
                dream.getRevision(),
                dream.getSourceRevision(),
                dream.getCreatedAt(),
                dream.getUpdatedAt(), null, null);
    }
}
