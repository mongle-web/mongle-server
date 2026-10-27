package com.mongle.backend.domain.world.image;

import jakarta.persistence.*;
import lombok.*;

/** 생성 당시 식별자만 보존한다. 삭제된 꿈의 원문·이야기 본문을 복제하지 않는다. */
@Getter @Entity
@Table(name = "world_image_sources", indexes = @Index(name = "idx_world_image_source_dream", columnList = "user_id,dream_id,image_id"),
        uniqueConstraints = @UniqueConstraint(name = "uk_world_image_source", columnNames = {"image_id", "candidate", "dream_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorldImageSource {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "image_id", nullable = false, updatable = false) private Long imageId;
    @Column(name = "user_id", nullable = false, updatable = false) private Long userId;
    @Column(name = "dream_id", nullable = false, updatable = false) private Long dreamId;
    @Column(name = "story_version_id", nullable = false, updatable = false) private Long storyVersionId;
    @Column(name = "source_revision", nullable = false, updatable = false) private long sourceRevision;
    @Column(name = "bridge_id", updatable = false) private Long bridgeId;
    @Column(nullable = false) private boolean candidate;
    public static WorldImageSource create(WorldImage image, WorldImageSources.Reference ref) {
        var s = new WorldImageSource(); s.imageId = image.getId(); s.userId = image.getUserId();
        s.dreamId = ref.dreamId(); s.storyVersionId = ref.storyVersionId();
        s.sourceRevision = ref.sourceRevision(); s.bridgeId = ref.bridgeId(); s.candidate = true; return s;
    }
}
