package com.mongle.backend.domain.dream.generation;

import com.mongle.backend.domain.dream.entity.Dream;
import com.mongle.backend.global.common.BaseEntity;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 원문을 복제하지 않는 영속 작업. 외부 호출의 성공 여부가 불명확하면 자동 재호출하지 않는다. */
@Getter
@Entity
@Table(
        name = "dream_generation_jobs",
        uniqueConstraints =
                @UniqueConstraint(name = "uk_generation_job_dream", columnNames = "dream_id"),
        indexes = {
            @Index(name = "idx_generation_job_due", columnList = "status,next_run_at,id"),
            @Index(name = "idx_generation_job_user", columnList = "user_id,status,lease_until")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DreamGenerationJob extends BaseEntity {
    public enum Stage { ANALYSIS, STORY }
    public enum Status { QUEUED, PROCESSING, COMPLETED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 꿈 삭제 시 같은 트랜잭션에서 작업도 삭제한다. 늦은 콜백은 토큰과 행 존재 여부를 확인한다.
    @Column(name = "dream_id", nullable = false, updatable = false)
    private Long dreamId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "source_revision", nullable = false, updatable = false)
    private long sourceRevision;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Stage stage;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "claim_token", length = 36)
    private String claimToken;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "lease_until")
    private Instant leaseUntil;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    @Column(nullable = false)
    private boolean recovering;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Version private long version;

    public static DreamGenerationJob create(Dream dream, Instant now) {
        var job = new DreamGenerationJob();
        job.dreamId = dream.getId();
        job.userId = dream.getUser().getId();
        job.sourceRevision = dream.getSourceRevision();
        job.stage = Stage.ANALYSIS;
        job.queue(now, false);
        return job;
    }

    public boolean due(Instant now) {
        return (status == Status.QUEUED && !nextRunAt.isAfter(now))
                || (status == Status.PROCESSING && !leaseUntil.isAfter(now));
    }

    public void claim(Instant now) {
        recovering = recovering || status == Status.PROCESSING;
        status = Status.PROCESSING;
        claimToken = UUID.randomUUID().toString();
        leaseUntil = now.plus(Duration.ofMinutes(3));
    }

    public boolean accepts(String token) {
        return status == Status.PROCESSING && Objects.equals(claimToken, token);
    }

    public void queue(Instant now, boolean recovery) {
        status = Status.QUEUED;
        nextRunAt = now;
        recovering = recovery;
        claimToken = null;
        leaseUntil = null;
        failureCode = null;
    }

    public void succeed(Instant now) {
        if (stage == Stage.ANALYSIS) {
            stage = Stage.STORY;
            queue(now, false);
        } else {
            status = Status.COMPLETED;
            claimToken = null;
            leaseUntil = null;
            failureCode = null;
        }
    }

    public void fail(String code) {
        status = Status.FAILED;
        failureCode = code;
        claimToken = null;
        leaseUntil = null;
    }
}
