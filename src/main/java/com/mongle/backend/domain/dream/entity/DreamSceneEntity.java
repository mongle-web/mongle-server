package com.mongle.backend.domain.dream.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

@Getter
@Entity
@Table(name = "dream_scene_entities")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DreamSceneEntity {

    @EmbeddedId
    private DreamSceneEntityId id;

    @MapsId("dreamSceneId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dream_scene_id", nullable = false)
    private DreamScene dreamScene;

    @MapsId("dreamEntityId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dream_entity_id", nullable = false)
    private DreamEntity dreamEntity;

    @Builder(access = AccessLevel.PRIVATE)
    private DreamSceneEntity(DreamScene dreamScene, DreamEntity dreamEntity) {
        this.dreamScene = Objects.requireNonNull(dreamScene, "scene must not be null");
        this.dreamEntity = Objects.requireNonNull(dreamEntity, "entity must not be null");
        Dream sceneDream = dreamScene.getDream();
        Dream entityDream = dreamEntity.getDream();
        if (sceneDream != entityDream && (sceneDream.getId() == null
                || !sceneDream.getId().equals(entityDream.getId()))) {
            throw new IllegalArgumentException("장면과 꿈 요소는 같은 꿈에 속해야 합니다.");
        }

        this.id = new DreamSceneEntityId(dreamScene.getId(), dreamEntity.getId());
    }

    public static DreamSceneEntity link(DreamScene scene, DreamEntity entity) {
        return builder()
                .dreamScene(scene)
                .dreamEntity(entity)
                .build();
    }
}
