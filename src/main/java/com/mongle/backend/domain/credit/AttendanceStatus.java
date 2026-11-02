package com.mongle.backend.domain.credit;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;

@Schema(description = "한국 시간 기준 오늘의 출석 보상 상태. 조회로 지급하지 않습니다.")
public record AttendanceStatus(
        @Schema(description = "한국 시간 기준 오늘 날짜") LocalDate date,
        @Schema(description = "오늘 보상 수령 여부") boolean claimed,
        @Schema(description = "미수령이면 현재 설정량, 수령했으면 실제 지급량") long rewardAmount,
        @Schema(description = "오늘 미수령이며 전체 크레딧·출석 기능이 모두 활성화된 경우 true")
                boolean claimable,
        @Schema(description = "다음 날짜 보상이 열리는 한국 시간 자정의 UTC 시각. 현재 수령 가능 여부와 별개입니다.")
                Instant nextClaimAt) {}
