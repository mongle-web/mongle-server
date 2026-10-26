package com.mongle.backend.domain.world.composition;

import com.mongle.backend.global.common.BaseCreatedEntity;

import jakarta.persistence.*;

import lombok.*;

@Getter
@Entity
@Table(
        name = "world_composition_entries",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_composition_position",
                        columnNames = {"world_id", "candidate", "position"}),
        indexes = @Index(name = "idx_composition_dream", columnList = "dream_id,world_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorldCompositionEntry extends BaseCreatedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "world_id", nullable = false)
    private Long worldId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private boolean candidate;

    @Column(nullable = false)
    private long position;

    @Column(name = "dream_id", nullable = false)
    private Long dreamId;

    @Column(name = "story_version_id", nullable = false)
    private Long storyVersionId;

    @Column(name = "bridge_id")
    private Long bridgeId;

    @Column(name = "needs_bridge", nullable = false)
    private boolean needsBridge;

    static WorldCompositionEntry create(
            Long world, Long user, long position, Long dream, Long version) {
        var e = new WorldCompositionEntry();
        e.worldId = world;
        e.userId = user;
        e.position = position;
        e.dreamId = dream;
        e.storyVersionId = version;
        e.candidate = true;
        e.needsBridge = position > 0;
        return e;
    }

    void waiting(Long bridge) {
        bridgeId = bridge;
    }

    void resolved(Long bridge) {
        bridgeId = bridge;
        needsBridge = false;
    }

    void publish() {
        candidate = false;
    }

    void gap() {
        bridgeId = null;
        needsBridge = false;
    }

    WorldCompositionEntry copy() {
        var e = create(worldId, userId, position, dreamId, storyVersionId);
        e.bridgeId = bridgeId;
        e.needsBridge = false;
        return e;
    }

    void replace(Long version, boolean hasPrevious) {
        storyVersionId = version;
        bridgeId = null;
        needsBridge = hasPrevious;
    }

    void reconnect() {
        bridgeId = null;
        needsBridge = true;
    }
}
