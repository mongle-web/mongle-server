package com.mongle.backend.domain.dream.story;

import com.mongle.backend.domain.dream.analysis.DreamAnalysis;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.global.common.BaseEntity;
import com.mongle.backend.global.common.GenerationStatus;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(
        name = "dream_stories",
        uniqueConstraints =
                @UniqueConstraint(name = "uk_story_analysis", columnNames = "analysis_id"),
        indexes = @Index(name = "idx_story_user_status", columnList = "user_id,status"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DreamStory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analysis_id", nullable = false, updatable = false)
    private DreamAnalysis analysis;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "source_revision", nullable = false)
    private long sourceRevision;

    @Column(name = "prompt_version", nullable = false, length = 50)
    private String promptVersion;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private GenerationStatus status;

    @Column(name = "attempt_id", nullable = false, length = 36)
    private String attemptId;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "result_revision")
    private Long resultRevision;

    @Column(name = "result_prompt_version", length = 50)
    private String resultPromptVersion;

    @Column(name = "generation_settings", columnDefinition = "TEXT")
    private String generationSettings;

    public void recordSettings(String settings) {
        generationSettings = settings;
    }

    @Version private long version;

    public static DreamStory create(DreamAnalysis analysis) {
        var story = new DreamStory();
        story.analysis = analysis;
        story.user = analysis.getUser();

        return story;
    }

    public void start(long revision, Instant now, Duration lease) {
        sourceRevision = revision;
        promptVersion = StoryPrompt.VERSION;
        status = GenerationStatus.PROCESSING;
        attemptId = UUID.randomUUID().toString();
        leaseUntil = now.plus(lease);
        failureCode = null;
        // 재생성 실패 시에도 이전 성공 결과를 조회할 수 있도록 유지한다.
    }

    public boolean accepts(String attempt) {
        return status == GenerationStatus.PROCESSING && attemptId.equals(attempt);
    }

    public boolean active(Instant now) {
        return (status == GenerationStatus.PROCESSING
                && leaseUntil != null
                && leaseUntil.isAfter(now));
    }

    public void finish(String encoded) {
        resultJson = encoded;
        resultRevision = sourceRevision;
        resultPromptVersion = promptVersion;
        status = GenerationStatus.COMPLETED;
        leaseUntil = null;
        failureCode = null;
    }

    public void fail(String code) {
        status = GenerationStatus.FAILED;
        failureCode = code;
        leaseUntil = null;
    }
}
