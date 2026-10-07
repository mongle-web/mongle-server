package com.mongle.backend.domain.dream.entity;

import com.mongle.backend.global.common.BaseEntity;
import com.mongle.backend.domain.dream.analysis.DreamAnalysis;
import com.mongle.backend.domain.user.entity.User;
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
@Table(
        name = "dream_entities",
        uniqueConstraints =
                @jakarta.persistence.UniqueConstraint(
                        name = "uk_analysis_entity_name",
                        columnNames = {"analysis_id", "entity_type", "normalized_name"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DreamEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "analysis_id")
    private DreamAnalysis analysis;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    // 원문 삭제 후에도 분석 결과는 남기므로 꿈 연결은 해제할 수 있다.
    @JoinColumn(name = "dream_id")
    private Dream dream;

    // 꿈이 삭제된 뒤에도 분석 결과의 소유자를 확인할 수 있어야 한다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "entity_type", nullable = false, length = 30)
    private DreamEntityType entityType;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "normalized_name", length = 255)
    private String normalizedName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder(access = AccessLevel.PRIVATE)
    private DreamEntity(Dream dream, DreamEntityType entityType, String name, String description) {
        this.dream = Objects.requireNonNull(dream, "꿈 기록이 필요합니다.");
        this.user = dream.getUser();
        this.entityType = Objects.requireNonNull(entityType, "꿈 요소 유형이 필요합니다.");
        this.name = Objects.requireNonNull(name, "꿈 요소 이름이 필요합니다.");
        this.normalizedName = DreamElementNames.normalize(name);
        this.description = description;
    }

    public static DreamEntity create(
            Dream dream, DreamEntityType entityType, String name, String description) {
        return builder()
                .dream(dream)
                .entityType(entityType)
                .name(name)
                .description(description)
                .build();
    }

    public static DreamEntity create(
            DreamAnalysis analysis, DreamEntityType type, String name, String description) {
        DreamEntity entity =
                create(Objects.requireNonNull(analysis.getDream()), type, name, description);
        entity.analysis = analysis;
        return entity;
    }
}
