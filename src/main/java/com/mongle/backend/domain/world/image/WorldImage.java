package com.mongle.backend.domain.world.image;

import com.mongle.backend.domain.dream.image.ImagePayload;
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
@Table(name = "world_images", uniqueConstraints = @UniqueConstraint(name = "uk_world_image_world", columnNames = "world_id"),
        indexes = {@Index(name = "idx_world_image_expiry", columnList = "status,lease_until,id"),
                @Index(name = "idx_world_image_key", columnList = "storage_key")})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorldImage extends BaseEntity {
    public static final String PROMPT_VERSION = "image-v1-world";
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false, updatable = false) private Long userId;
    @Column(name = "world_id", nullable = false, updatable = false) private Long worldId;
    @Column(name = "composition_revision", nullable = false) private long compositionRevision;
    @Column(nullable = false, length = 50) private String style;
    @Column(length = 50) private String mood;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30) private GenerationStatus status;
    @Column(name = "attempt_id", nullable = false, length = 36) private String attemptId;
    @Column(name = "credit_reservation_id") private Long creditReservationId;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "failure_code", length = 50) private String failureCode;
    @Column(name = "storage_key", length = 255) private String storageKey;
    @Column(name = "content_type", length = 30) private String contentType;
    private Integer width;
    private Integer height;
    @Column(name = "result_composition_revision") private Long resultCompositionRevision;
    @Column(name = "result_source_deleted", nullable = false) private boolean resultSourceDeleted;
    @Column(name = "result_style", length = 50) private String resultStyle;
    @Column(name = "result_mood", length = 50) private String resultMood;
    @Version private long version;

    public static WorldImage create(Long user, Long world) {
        var image = new WorldImage(); image.userId = user; image.worldId = world; return image;
    }
    public void start(long revision, String style, String mood, Instant now) {
        compositionRevision = revision; this.style = style; this.mood = mood;
        status = GenerationStatus.PROCESSING; attemptId = UUID.randomUUID().toString();
        leaseUntil = now.plusSeconds(120); failureCode = null;
    }
    public void reserve(Long id) { creditReservationId = id; }
    public boolean active(Instant now) { return status == GenerationStatus.PROCESSING && leaseUntil != null && leaseUntil.isAfter(now); }
    public boolean accepts(String attempt) { return status == GenerationStatus.PROCESSING && Objects.equals(attemptId, attempt); }
    public void finish(String key, ImagePayload payload) {
        storageKey = key; contentType = payload.contentType(); width = payload.width(); height = payload.height();
        resultCompositionRevision = compositionRevision; resultStyle = style; resultMood = mood;
        resultSourceDeleted = false; status = GenerationStatus.COMPLETED; leaseUntil = null; failureCode = null;
    }
    public void sourceDeleted() { resultSourceDeleted = true; }
    public void fail(String code) { status = GenerationStatus.FAILED; failureCode = code; leaseUntil = null; }
}
