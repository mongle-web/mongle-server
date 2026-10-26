package com.mongle.backend.domain.world.folder;

import com.mongle.backend.domain.dream.entity.Dream;
import com.mongle.backend.global.common.BaseCreatedEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "world_selections",
        uniqueConstraints = @UniqueConstraint(name = "uk_world_selection_dream", columnNames = {"world_id", "dream_id"}),
        indexes = {
            @Index(name = "idx_world_selection_date", columnList = "world_id,dreamed_at,dream_id"),
            @Index(name = "idx_world_selection_dream", columnList = "dream_id")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorldSelection extends BaseCreatedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "world_id", nullable = false)
    private WorldFolder folder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dream_id", nullable = false)
    private Dream dream;

    // Dream.dreamedAt은 불변이다. 선택 순서와 무관한 인덱스 기반 페이지 경계로 사용한다.
    @Column(name = "dreamed_at", nullable = false, updatable = false)
    private LocalDate dreamedAt;

    public static WorldSelection create(WorldFolder folder, Dream dream) {
        var selection = new WorldSelection();
        selection.folder = folder;
        selection.dream = dream;
        selection.dreamedAt = dream.getDreamedAt();
        return selection;
    }
}
