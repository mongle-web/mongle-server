package com.mongle.backend.domain.dream.entity;

import com.mongle.backend.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;

@Getter
@Entity
@Table(name = "dream_entities")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DreamEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dream_id", nullable = false)
    private Dream dream;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "entity_type", nullable = false, length = 30)
    private DreamEntityType entityType;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder(access = AccessLevel.PRIVATE)
    private DreamEntity(Dream dream, DreamEntityType entityType, String name, String description) {
        this.dream = Objects.requireNonNull(dream, "dream must not be null");
        this.entityType = Objects.requireNonNull(entityType, "entityType must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.description = description;
    }

    public static DreamEntity create(Dream dream, DreamEntityType entityType, String name, String description) {
        return builder()
                .dream(dream)
                .entityType(entityType)
                .name(name)
                .description(description)
                .build();
    }
}
