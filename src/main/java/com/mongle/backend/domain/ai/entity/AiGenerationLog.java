package com.mongle.backend.domain.ai.entity;

import com.mongle.backend.domain.ai.dto.logging.AiGenerationAttempt;
import com.mongle.backend.domain.ai.dto.logging.AiGenerationCost;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.global.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Objects;

/** 시도별 사용량·성공/실패·가격 근거를 저장한다. 사용자 원문이나 생성 결과는 보관하지 않는다. */
@Getter
@Entity
@Table(name = "ai_generation_logs",
        uniqueConstraints = @UniqueConstraint(name = "uk_ai_generation_logs_call_attempt", columnNames = {"call_id", "attempt_no"}),
        indexes = @Index(name = "idx_ai_generation_logs_user_created", columnList = "user_id, created_at"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
public class AiGenerationLog extends BaseCreatedEntity {

    // DB 행 식별자. 논리 호출 ID와 다르다.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 로그를 소유한 사용자. 생성 요청에서 받은 PK로 연결한다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 꿈 구조화 등 호출 목적을 문자열로 저장한다.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "task_type", nullable = false, length = 50)
    private AiTaskType taskType;

    // 최초 요청과 재시도가 공유하는 UUID. 기존 행에는 없다.
    @Column(name = "call_id", length = 36)
    private String callId;

    // 동일 callId 안에서 1부터 증가하는 시도 번호.
    @Column(name = "attempt_no")
    private Integer attemptNo;

    // 외부 통신 제공자 이름.
    @Column(length = 30)
    private String provider;

    // 외부 요청에 사용한 설정 모델명.
    @Column(name = "requested_model", length = 100)
    private String requestedModel;

    // 응답에 보고된 모델명. 누락이면 요청 모델로 대체하지 않는다.
    @Column(name = "model_name", length = 100)
    private String modelName;

    // 도메인에서 관리하는 프롬프트 버전. 프롬프트 본문은 저장하지 않는다.
    @Column(name = "prompt_version", length = 255)
    private String promptVersion;

    // 제공자의 x-request-id 헤더.
    @Column(name = "request_id", length = 255)
    private String requestId;

    // 수신한 HTTP 상태. 완성된 응답이 없으면 null.
    @Column(name = "http_status")
    private Integer httpStatus;

    // 실제 통신 여부. 기존 기록의 여부는 알 수 없어 nullable이다.
    @Column(name = "http_attempted")
    private Boolean httpAttempted;

    // 전체 입력 사용량. 모르는 수량은 null로 보존한다.
    @Column(name = "input_tokens")
    private Integer inputTokens;

    // 추론 토큰을 포함하는 출력 사용량.
    @Column(name = "output_tokens")
    private Integer outputTokens;

    // 입력 중 캐시가 적용된 부분. 기본 0을 사용하지 않는다.
    @Column(name = "cached_input_tokens")
    private Integer cachedInputTokens;

    // 제공자가 보고한 전체 수량. 임의 합산하여 채우지 않는다.
    @Column(name = "total_tokens")
    private Integer totalTokens;

    // 출력 중 추론에 쓰인 부분. 출력 비용에 다시 더하지 않는다.
    @Column(name = "reasoning_tokens")
    private Integer reasoningTokens;

    // 제공자가 보고한 종료 사유 원문.
    @Column(name = "finish_reason", length = 100)
    private String finishReason;

    // 실패를 분류한 내부 AI 오류 코드. 외부 오류 본문은 저장하지 않는다.
    @Column(name = "error_code", length = 50)
    private String errorCode;

    // 기존 컬럼명을 유지한 USD 추정 비용. 제공자 청구서의 확정 금액이 아니다.
    @Column(name = "actual_cost", precision = 20, scale = 8)
    private BigDecimal actualCost;

    // 계산 완료/사용량 누락/단가 누락/기존 기록을 구분한다.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "cost_status", nullable = false, length = 30)
    @ColumnDefault("'LEGACY'")
    private AiCostStatus costStatus;

    // 단가와 비용의 통화. 이번 구현은 USD만 계산한다.
    @Column(name = "cost_currency", nullable = false, length = 3)
    @ColumnDefault("'USD'")
    private String costCurrency;

    // 가격표에서 정확히 일치한 응답 모델명.
    @Column(name = "pricing_model", length = 100)
    private String pricingModel;

    // 가격표 변경 후에도 과거 계산 근거를 추적할 버전.
    @Column(name = "pricing_version", length = 100)
    private String pricingVersion;

    // 계산 시점의 일반 입력 100만 토큰당 단가.
    @Column(name = "input_price", precision = 12, scale = 8)
    private BigDecimal inputPrice;

    // 계산 시점의 출력 100만 토큰당 단가.
    @Column(name = "output_price", precision = 12, scale = 8)
    private BigDecimal outputPrice;

    // 계산 시점의 캐시 입력 100만 토큰당 단가.
    @Column(name = "cached_input_price", precision = 12, scale = 8)
    private BigDecimal cachedInputPrice;

    // 기존 비교 기준 데이터 보존용. 새 로그에서는 임의로 채우지 않는다.
    @Column(name = "baseline_model", length = 100)
    private String baselineModel;

    // 기존 비교 비용 보존용. 비용 절감 비교는 이번 범위에 포함하지 않는다.
    @Column(name = "baseline_cost", precision = 12, scale = 8)
    private BigDecimal baselineCost;

    // 이번 시도의 통신·응답 변환 시간(ms). 전체 요청 시간과 다르다.
    @Column(name = "latency_ms")
    private Long latencyMs;

    // 공통 Gateway가 결과를 반환할 수 있었는지 기록한다.
    @Column(nullable = false)
    private boolean success;

    /** 새 시도의 저장 진입점. 외부에는 builder를 노출하지 않는다. */
    public static AiGenerationLog create(User user, AiGenerationAttempt attempt, AiGenerationCost cost) {
        Objects.requireNonNull(user, "사용자는 필수입니다.");
        Objects.requireNonNull(attempt, "호출 시도는 필수입니다.");
        Objects.requireNonNull(cost, "비용 계산 결과는 필수입니다.");
        // DTO에서 관측한 값만 옮긴다. 알려지지 않은 모델·수량·비용을 채워 넣지 않는다.
        return builder().user(user).taskType(attempt.taskType())
                .callId(attempt.callId()).attemptNo(attempt.attemptNo()).provider(attempt.provider())
                .requestedModel(attempt.requestedModel()).modelName(attempt.modelName()).promptVersion(attempt.promptVersion())
                .requestId(attempt.requestId()).httpStatus(attempt.httpStatus()).httpAttempted(attempt.httpAttempted())
                .inputTokens(attempt.usage().inputTokens()).outputTokens(attempt.usage().outputTokens())
                .cachedInputTokens(attempt.usage().cachedInputTokens()).totalTokens(attempt.usage().totalTokens())
                .reasoningTokens(attempt.usage().reasoningTokens()).finishReason(attempt.finishReason()).errorCode(attempt.errorCode())
                .latencyMs(attempt.latencyMs()).success(attempt.success())
                .actualCost(cost.amountUsd()).costStatus(cost.status()).costCurrency("USD")
                .pricingModel(cost.pricingModel()).pricingVersion(cost.pricingVersion())
                .inputPrice(cost.inputPrice()).outputPrice(cost.outputPrice()).cachedInputPrice(cost.cachedInputPrice()).build();
    }

    /** 기존 도메인 초기화 코드의 생성 계약을 유지한다. 관측하지 않았던 새 정보는 null로 둔다. */
    public static AiGenerationLog create(User user, AiTaskType taskType, String modelName, String promptVersion,
                                         int inputTokens, int outputTokens, int cachedInputTokens, BigDecimal actualCost,
                                         String baselineModel, BigDecimal baselineCost, Long latencyMs, boolean success) {
        return builder().user(Objects.requireNonNull(user, "사용자는 필수입니다."))
                .taskType(Objects.requireNonNull(taskType, "작업 종류는 필수입니다."))
                .modelName(Objects.requireNonNull(modelName, "모델명은 필수입니다.")).promptVersion(promptVersion)
                .inputTokens(inputTokens).outputTokens(outputTokens).cachedInputTokens(cachedInputTokens)
                .actualCost(Objects.requireNonNull(actualCost, "기존 비용은 필수입니다."))
                .baselineModel(Objects.requireNonNull(baselineModel, "비교 기준 모델명은 필수입니다."))
                .baselineCost(Objects.requireNonNull(baselineCost, "비교 기준 비용은 필수입니다."))
                .latencyMs(latencyMs).success(success).costStatus(AiCostStatus.LEGACY).costCurrency("USD").build();
    }
}
