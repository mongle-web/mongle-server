package com.mongle.backend.domain.dream.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
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

    public static DreamSceneEntity link(DreamScene scene, DreamEntity entity) {
        Objects.requireNonNull(scene, "scene must not be null");
        Objects.requireNonNull(entity, "entity must not be null");
        Dream sceneDream = scene.getDream();
        Dream entityDream = entity.getDream();
        if (sceneDream != entityDream && (sceneDream.getId() == null
                || !sceneDream.getId().equals(entityDream.getId()))) {
            throw new IllegalArgumentException("장면과 꿈 요소는 같은 꿈에 속해야 합니다.");
        }

        DreamSceneEntity link = new DreamSceneEntity();
        link.id = new DreamSceneEntityId(scene.getId(), entity.getId());
        link.dreamScene = scene;
        link.dreamEntity = entity;
        return link;
    }
}
