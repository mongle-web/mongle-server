package com.mongle.backend.domain.credit;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;

@Schema(description = "출석 보상 수령 결과. 재요청은 기존 지급량을 반환하며 추가 지급하지 않습니다.")
public record AttendanceClaim(
        LocalDate date,
        @Schema(description = "해당 날짜에 실제 지급된 수량. 재요청에도 동일하며 추가 지급량이 아닙니다.") long amount,
        boolean alreadyClaimed,
        @Schema(description = "현재 잔액. 과거 최초 수령 시점의 잔액이 아닙니다.") CreditBalance balance,
        @Schema(description = "다음 날짜 보상이 열리는 한국 시간 자정의 UTC 시각") Instant nextClaimAt) {}
