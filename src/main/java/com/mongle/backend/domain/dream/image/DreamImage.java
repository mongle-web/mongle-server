package com.mongle.backend.domain.dream.image;

import com.mongle.backend.domain.dream.analysis.DreamAnalysis;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.global.common.BaseEntity;
import com.mongle.backend.global.common.GenerationStatus;

import jakarta.persistence.*;

import lombok.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.*;
import java.util.UUID;

@Getter
@Entity
@Table(
        name = "dream_images",
        uniqueConstraints =
                @UniqueConstraint(name = "uk_image_analysis", columnNames = "analysis_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DreamImage extends BaseEntity {
    public static final String PROMPT_VERSION = "image-v1-story";

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

    @Column(name = "story_hash", nullable = false, length = 64)
    private String storyHash;

    @Column(nullable = false, length = 50)
    private String style;

    @Column(length = 50)
    private String mood;

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

    @Column(name = "storage_key", length = 255)
    private String storageKey;

    @Column(name = "content_type", length = 30)
    private String contentType;

    private Integer width;
    private Integer height;

    @Column(name = "result_revision")
    private Long resultRevision;

    @Column(name = "result_story_hash", length = 64)
    private String resultStoryHash;

    @Column(name = "result_style", length = 50)
    private String resultStyle;

    @Column(name = "result_mood", length = 50)
    private String resultMood;

    @Version private long version;

    public static DreamImage create(DreamAnalysis analysis) {
        var image = new DreamImage();
        image.analysis = analysis;
        image.user = analysis.getUser();
        return image;
    }

    public void start(long revision, String hash, String style, String mood, Instant now) {
        sourceRevision = revision;
        storyHash = hash;
        this.style = style;
        this.mood = mood;
        status = GenerationStatus.PROCESSING;
        attemptId = UUID.randomUUID().toString();
        leaseUntil = now.plus(Duration.ofMinutes(2));
        failureCode = null;
        // 이전 성공 파일은 새 결과 저장이 끝날 때까지 유지한다.
    }

    public boolean accepts(String attempt) {
        return status == GenerationStatus.PROCESSING && attemptId.equals(attempt);
    }

    public boolean active(Instant now) {
        return status == GenerationStatus.PROCESSING
                && leaseUntil != null
                && leaseUntil.isAfter(now);
    }

    public void finish(String key, ImagePayload payload) {
        storageKey = key;
        contentType = payload.contentType();
        width = payload.width();
        height = payload.height();
        resultRevision = sourceRevision;
        resultStoryHash = storyHash;
        resultStyle = style;
        resultMood = mood;
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
