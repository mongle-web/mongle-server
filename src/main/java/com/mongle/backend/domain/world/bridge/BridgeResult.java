package com.mongle.backend.domain.world.bridge;

import com.mongle.backend.domain.dream.entity.Dream;
import com.mongle.backend.domain.dream.story.StoryResultVersion;
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
import java.util.Objects;
import java.util.UUID;

/** 성공 후에는 본문과 출처가 바뀌지 않는다. 세계관은 COMPLETED인 이 행의 ID만 참조한다. */
@Getter
@Entity
@Table(name = "world_bridge_results", uniqueConstraints = @UniqueConstraint(
        name = "uk_bridge_inputs", columnNames = {"user_id", "before_version_id", "after_version_id", "prompt_version", "settings_hash"}),
        indexes = {@Index(name = "idx_bridge_before_dream", columnList = "before_dream_id"),
                @Index(name = "idx_bridge_after_dream", columnList = "after_dream_id")})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BridgeResult extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "before_dream_id", nullable = false, updatable = false)
    private Dream beforeDream;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "after_dream_id", nullable = false, updatable = false)
    private Dream afterDream;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "before_version_id", nullable = false, updatable = false)
    private StoryResultVersion beforeVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "after_version_id", nullable = false, updatable = false)
    private StoryResultVersion afterVersion;

    @Column(name = "prompt_version", nullable = false, updatable = false, length = 50)
    private String promptVersion;

    @Column(name = "settings_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String settingsJson;

    @Column(name = "settings_hash", nullable = false, updatable = false, length = 64)
    private String settingsHash;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30) private GenerationStatus status;

    @Column(name = "attempt_id", nullable = false, length = 36) private String attemptId;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "failure_code", length = 50) private String failureCode;
    @Column(columnDefinition = "TEXT") private String content;
    @Version private long version;

    static BridgeResult create(User user, StoryResultVersion before, StoryResultVersion after,
            String settings, String hash, Instant now) {
        var row = new BridgeResult();
        row.user = user;
        row.beforeVersion = before;
        row.afterVersion = after;
        row.beforeDream = before.getStory().getAnalysis().getDream();
        row.afterDream = after.getStory().getAnalysis().getDream();
        row.promptVersion = BridgePrompt.VERSION;
        row.settingsJson = settings;
        row.settingsHash = hash;
        row.start(now);
        return row;
    }

    void start(Instant now) {
        if (status == GenerationStatus.COMPLETED) throw new IllegalStateException("성공 연결 결과는 변경할 수 없습니다.");
        status = GenerationStatus.PROCESSING;
        attemptId = UUID.randomUUID().toString();
        leaseUntil = now.plus(Duration.ofMinutes(2));
        failureCode = null;
    }

    boolean accepts(String attempt) {
        return status == GenerationStatus.PROCESSING && Objects.equals(attemptId, attempt);
    }

    boolean active(Instant now) {
        return status == GenerationStatus.PROCESSING && leaseUntil != null && leaseUntil.isAfter(now);
    }

    void succeed(String value) {
        content = value;
        status = GenerationStatus.COMPLETED;
        leaseUntil = null;
        failureCode = null;
    }

    void fail(String code) {
        status = GenerationStatus.FAILED;
        failureCode = code;
        leaseUntil = null;
    }
}
