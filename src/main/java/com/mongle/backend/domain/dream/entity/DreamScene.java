package com.mongle.backend.domain.dream.entity;

import com.mongle.backend.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.ColumnDefault;

import java.util.Objects;

@Getter
@Entity
@Table(name = "dream_scenes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DreamScene extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dream_id", nullable = false)
    private Dream dream;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "is_disconnected_from_previous", nullable = false)
    @ColumnDefault("false")
    private boolean disconnectedFromPrevious;

    @Builder
    private DreamScene(Dream dream, int sequenceNo, String content, boolean disconnectedFromPrevious) {
        this.dream = Objects.requireNonNull(dream, "dream must not be null");
        this.sequenceNo = sequenceNo;
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.disconnectedFromPrevious = disconnectedFromPrevious;
    }
}
