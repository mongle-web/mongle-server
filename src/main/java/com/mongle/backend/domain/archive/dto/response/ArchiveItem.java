package com.mongle.backend.domain.archive.dto.response;

import com.mongle.backend.global.common.GenerationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.List;

/** 목록 카드와 상세의 공통 정보. 원문·생성 본문·스토리지 키는 카드에 포함하지 않는다. */
public record ArchiveItem(
        Long dreamId, LocalDate dreamedAt, @Nullable String title, List<String> displayKeywords,
        @Schema(description = "실제 수정 이력이 있으면 true. 목록·상세에서 수정됨 표시") boolean edited,
        @Schema(description = "기존 꿈 수정·삭제 API에 전달할 최신 버전") long revision,
        long sourceRevision,
        @Schema(description = "아직 분석을 요청하지 않았으면 null") @Nullable Analysis analysis,
        @Schema(description = "아직 이야기 생성을 요청하지 않았으면 null") @Nullable Story story,
        @Schema(description = "아직 이미지 생성을 요청하지 않았으면 null") @Nullable Image image,
        @Nullable com.mongle.backend.domain.dream.generation.DreamGenerationResponse generation) {

    public ArchiveItem {
        displayKeywords = List.copyOf(displayKeywords);
    }

    /** 분석 ID로 기존 장면·요소 조회 API에 접근한다. 키워드는 기존 생성값을 유지한다. */
    public record Analysis(Long analysisId, GenerationStatus status, @Nullable String failureCode,
                           boolean sourceChanged, @Nullable Long resultRevision) {}

    /** 재생성 실패·진행 중에도 이전 성공 결과가 있으면 이를 별도로 표시한다. */
    public record Story(Long storyId, long storyVersion, GenerationStatus status,
                        @Nullable String failureCode, boolean hasResult, boolean hasPreviousResult,
                        @Nullable Long resultRevision, boolean sourceChanged) {}

    /**
     * hasResult이면 imageId로 기존 임시 URL API를 호출한다. 서명 URL은 DB/Archive 응답에 보관하지 않는다.
     * sourceChanged는 원문·감정 변경, storyChanged는 이미지의 출처 이야기 변경을 뜻한다.
     */
    public record Image(Long imageId, long imageVersion, GenerationStatus status,
                        @Nullable String failureCode, boolean hasResult, boolean hasPreviousResult,
                        @Nullable Long resultRevision, boolean sourceChanged, boolean storyChanged,
                        @Nullable String contentType, @Nullable Integer width, @Nullable Integer height) {}
}
