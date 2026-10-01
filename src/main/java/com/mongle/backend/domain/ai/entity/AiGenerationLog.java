package com.mongle.backend.domain.ai.entity;

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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Objects;

@Getter
@Entity
@Table(name = "ai_generation_logs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiGenerationLog extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "task_type", nullable = false, length = 50)
    private AiTaskType taskType;

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    @Column(name = "prompt_version", length = 50)
    private String promptVersion;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "cached_input_tokens", nullable = false)
    @ColumnDefault("0")
    private int cachedInputTokens;

    @Column(name = "actual_cost", nullable = false, precision = 12, scale = 8)
    private BigDecimal actualCost;

    @Column(name = "baseline_model", nullable = false, length = 100)
    private String baselineModel;

    @Column(name = "baseline_cost", nullable = false, precision = 12, scale = 8)
    private BigDecimal baselineCost;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(nullable = false)
    private boolean success;

    @Builder(access = AccessLevel.PRIVATE)
    private AiGenerationLog(User user, AiTaskType taskType, String modelName, String promptVersion,
                            int inputTokens, int outputTokens, int cachedInputTokens, BigDecimal actualCost,
                            String baselineModel, BigDecimal baselineCost, Long latencyMs, boolean success) {
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.taskType = Objects.requireNonNull(taskType, "taskType must not be null");
        this.modelName = Objects.requireNonNull(modelName, "modelName must not be null");
        this.promptVersion = promptVersion;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cachedInputTokens = cachedInputTokens;
        this.actualCost = Objects.requireNonNull(actualCost, "actualCost must not be null");
        this.baselineModel = Objects.requireNonNull(baselineModel, "baselineModel must not be null");
        this.baselineCost = Objects.requireNonNull(baselineCost, "baselineCost must not be null");
        this.latencyMs = latencyMs;
        this.success = success;
    }

    public static AiGenerationLog create(User user, AiTaskType taskType, String modelName, String promptVersion,
                                         int inputTokens, int outputTokens, int cachedInputTokens, BigDecimal actualCost,
                                         String baselineModel, BigDecimal baselineCost, Long latencyMs, boolean success) {
        return builder()
                .user(user)
                .taskType(taskType)
                .modelName(modelName)
                .promptVersion(promptVersion)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .cachedInputTokens(cachedInputTokens)
                .actualCost(actualCost)
                .baselineModel(baselineModel)
                .baselineCost(baselineCost)
                .latencyMs(latencyMs)
                .success(success)
                .build();
    }
}
