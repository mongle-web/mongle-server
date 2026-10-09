package com.mongle.backend.domain.archive.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/** 한 달의 기록 표시 정보. 꿈 카드와 이미지 정보는 기존 Archive API에서 필요할 때 조회한다. */
public record DreamCalendarResponse(
        @Schema(description = "조회한 월. YYYY-MM 형식", example = "2026-09") String month,
        @Schema(description = "해당 월에 완성한 꿈을 기록한 날짜 수", example = "4") int recordedDayCount,
        @Schema(description = "완성한 꿈이 있는 날짜를 오름차순으로 반환. 기록이 없으면 빈 배열")
        List<LocalDate> recordedDates) {

    public DreamCalendarResponse {
        // 조회 결과를 불변 복사해 응답 생성 후 날짜 목록과 집계가 변경되지 않도록 한다.
        recordedDates = List.copyOf(recordedDates);
    }
}
