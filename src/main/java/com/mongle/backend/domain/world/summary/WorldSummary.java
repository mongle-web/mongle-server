package com.mongle.backend.domain.world.summary;

import com.mongle.backend.global.common.BaseEntity;
import com.mongle.backend.global.common.GenerationStatus;

import jakarta.persistence.*;

import lombok.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.*;
import java.util.*;

@Getter
@Entity
@Table(
        name = "world_summaries",
        indexes = @Index(name = "idx_world_summary_work", columnList = "status,world_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorldSummary extends BaseEntity {
    @Id
    @Column(name = "world_id")
    private Long worldId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private GenerationStatus status;

    @Column(name = "requested_revision", nullable = false)
    private long requestedRevision;

    @Column(name = "attempt_id", length = 36)
    private String attemptId;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "attempt_fingerprint", length = 64)
    private String attemptFingerprint;

    @Column(name = "attempt_settings", columnDefinition = "TEXT")
    private String attemptSettings;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "result_revision")
    private Long resultRevision;

    @Column(name = "result_fingerprint", length = 64)
    private String resultFingerprint;

    @Column(name = "result_settings", columnDefinition = "TEXT")
    private String resultSettings;

    @Column(name = "prompt_version", length = 50)
    private String promptVersion;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "generated_at")
    private Instant generatedAt;

    @Version private long version;

    static WorldSummary create(Long user, Long world) {
        var s = new WorldSummary();
        s.userId = user;
        s.worldId = world;
        return s;
    }

    void queue(long revision) {
        requestedRevision = revision;
        status = GenerationStatus.PENDING;
        attemptId = UUID.randomUUID().toString();
        leaseUntil = null;
        attemptFingerprint = null;
        attemptSettings = null;
        failureCode = null;
    }

    void claim(String fingerprint, String settings, Instant now) {
        status = GenerationStatus.PROCESSING;
        attemptFingerprint = fingerprint;
        attemptSettings = settings;
        leaseUntil = now.plusSeconds(120);
    }

    boolean active(Instant now) {
        return status == GenerationStatus.PROCESSING
                && leaseUntil != null
                && leaseUntil.isAfter(now);
    }

    boolean accepts(String attempt) {
        return status == GenerationStatus.PROCESSING && Objects.equals(attemptId, attempt);
    }

    boolean matches(long revision, String settings) {
        return resultJson != null
                && Objects.equals(resultRevision, revision)
                && Objects.equals(resultSettings, settings)
                && Objects.equals(promptVersion, WorldSummaryPrompt.VERSION);
    }

    void complete(String json, Instant now) {
        resultJson = json;
        resultRevision = requestedRevision;
        resultFingerprint = attemptFingerprint;
        resultSettings = attemptSettings;
        promptVersion = WorldSummaryPrompt.VERSION;
        generatedAt = now;
        status = GenerationStatus.COMPLETED;
        leaseUntil = null;
        failureCode = null;
    }

    void fail(String code) {
        status = GenerationStatus.FAILED;
        failureCode = code;
        leaseUntil = null;
    }

    void discardResult() {
        resultJson = null;
        resultRevision = null;
        resultFingerprint = null;
        resultSettings = null;
        promptVersion = null;
        generatedAt = null;
    }
}
