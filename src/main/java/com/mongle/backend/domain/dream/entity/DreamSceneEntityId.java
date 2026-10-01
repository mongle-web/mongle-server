package com.mongle.backend.domain.dream.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DreamSceneEntityId implements Serializable {

    @Column(name = "dream_scene_id", nullable = false)
    private Long dreamSceneId;

    @Column(name = "dream_entity_id", nullable = false)
    private Long dreamEntityId;
}
