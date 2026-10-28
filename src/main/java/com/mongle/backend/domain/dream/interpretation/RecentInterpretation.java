package com.mongle.backend.domain.dream.interpretation;

import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.global.common.BaseEntity;
import com.mongle.backend.global.common.GenerationStatus;

import jakarta.persistence.*;

import lombok.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.*;
import java.util.Objects;
import java.util.UUID;

/** 회원별 최신 완성본 한 개와 진행 중 시도를 분리한다. 입력 원문/분석 본문은 복제하지 않는다. */
@Getter
@Entity
@Table(
        name = "recent_interpretations",
        uniqueConstraints =
                @UniqueConstraint(name = "uk_recent_interpretation_user", columnNames = "user_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecentInterpretation extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private GenerationStatus status;

    @Column(name = "ever_completed", nullable = false)
    private boolean everCompleted;

    @Column(name = "attempt_id", length = 36)
    private String attemptId;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "target_fingerprint", length = 64)
    private String targetFingerprint;

    @Column(name = "attempt_dream_ids", columnDefinition = "TEXT")
    private String attemptDreamIds;

    @Column(name = "generation_settings", columnDefinition = "TEXT")
    private String generationSettings;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(columnDefinition = "TEXT")
    private String body;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "generated_at")
    private Instant generatedAt;

    @Column(name = "result_fingerprint", length = 64)
    private String resultFingerprint;

    @Column(name = "result_sources", columnDefinition = "TEXT")
    private String resultSources;

    @Column(name = "prompt_version", length = 50)
    private String promptVersion;

    @Version private long version;

    public static RecentInterpretation create(User user) {
        var row = new RecentInterpretation();
        row.user = user;
        row.status = GenerationStatus.PENDING;
        return row;
    }

    public void start(String fingerprint, String dreamIds, String settings, Instant now) {
        status = GenerationStatus.PROCESSING;
        attemptId = UUID.randomUUID().toString();
        leaseUntil = now.plus(Duration.ofMinutes(2));
        targetFingerprint = fingerprint;
        attemptDreamIds = dreamIds;
        generationSettings = settings;
        failureCode = null;
    }

    public boolean active(Instant now) {
        return status == GenerationStatus.PROCESSING && leaseUntil.isAfter(now);
    }

    public boolean accepts(String attempt) {
        return status == GenerationStatus.PROCESSING && Objects.equals(attemptId, attempt);
    }

    public boolean matches(String fingerprint) {
        return body != null && Objects.equals(resultFingerprint, fingerprint);
    }

    public void finish(String text, String sources, Instant now) {
        body = text;
        resultSources = sources;
        resultFingerprint = targetFingerprint;
        promptVersion = InterpretationPrompt.VERSION;
        generatedAt = now;
        everCompleted = true;
        status = GenerationStatus.COMPLETED;
        clearAttempt();
        failureCode = null;
    }

    public void fail(String code) {
        status = GenerationStatus.FAILED;
        failureCode = code;
        clearAttempt();
    }

    /** 삭제한 꿈의 표현을 제거한다. 완료 이력은 남겨 다음 진입의 자동 재생성을 막는다. */
    public void discardResult() {
        body = null;
        resultSources = null;
        resultFingerprint = null;
        promptVersion = null;
        generatedAt = null;
    }

    private void clearAttempt() {
        leaseUntil = null;
        attemptDreamIds = null;
    }
}
